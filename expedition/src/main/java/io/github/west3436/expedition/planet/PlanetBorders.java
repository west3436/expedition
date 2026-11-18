package io.github.west3436.expedition.planet;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.ExpeditionConfig;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.mixin.DelegateBorderChangeListenerAccessor;
import io.github.west3436.expedition.mixin.WorldBorderAccessor;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Bounded planets (#56): a world border of each planet's own at its square's edge, and empty chunks
 * past it. Off by default; {@code [planets.border]} in {@code expedition-server.toml} turns it on.
 *
 * <p>Vanilla gives every level a border of its own but makes each copy the Overworld's, and sends
 * every player the Overworld's. A bounded planet's border is detached from the Overworld's: it is
 * centred on {@code surface.center}, {@code space.size} blocks a side ({@link PlanetBounds}), and
 * its changes go to the players in its dimension only. Players get their own level's border on
 * login, respawn and dimension change ({@code PlayerListMixin}), and the Overworld's changes reach
 * only the players whose level still copies it ({@code PlayerListBorderListenerMixin}). If the
 * Overworld is itself bounded, every other level is detached, and an unbounded one gets vanilla's
 * default border.
 *
 * <p>The packets carry a border's centre as the Overworld stores it: a level whose {@code
 * coordinate_scale} isn't 1 reports its centre divided by the scale, and the client divides it
 * again, so a packet is built from a copy holding the stored centre ({@link #borderToSend}).
 *
 * <p>Chunks lying wholly past the border plus {@code voidMargin} are left empty, whatever the
 * dimension's generator: {@code ChunkStatusTasksMixin} asks {@link #isVoid} at each generation
 * step. Chunks already generated stay as they are.
 *
 * <p>The borders are applied when the server has started and again whenever the config file is
 * reloaded; nothing about them is saved, except that the Overworld's border is in {@code level.dat}
 * as vanilla keeps it. A {@code /reload} that changes a planet's square takes effect at the next
 * start or config reload, and {@link PlanetLoader#checkWorld} says so.
 */
public final class PlanetBorders {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The mod id of DimWorldBorder, which also sets per-dimension borders. */
    private static final String DIM_WORLD_BORDER = "dimworldborder";

    /**
     * The border settings.
     *
     * @param enabled whether planets are bounded
     * @param voidMargin how many blocks of terrain generate past a border; -1 for everywhere
     * @param exclude the dimensions left unbounded
     */
    public record Settings(boolean enabled, int voidMargin, Set<ResourceLocation> exclude) {
        /** No planet bounded: the default. */
        public static final Settings OFF = new Settings(false, 200, Set.of());

        /** Copies the exclusions. */
        public Settings {
            exclude = Set.copyOf(exclude);
        }

        /**
         * Whether a planet's dimension is bounded.
         *
         * @param dimension the dimension
         * @return true if it gets a border and a void
         */
        public boolean bounds(ResourceKey<Level> dimension) {
            return enabled && !exclude.contains(dimension.location());
        }

        /** {@return the settings in {@code expedition-server.toml}, or {@link #OFF} while none is loaded} */
        public static Settings fromConfig() {
            if (!ExpeditionConfig.SPEC.isLoaded()) {
                return OFF;
            }
            Set<ResourceLocation> exclude = new HashSet<>();
            for (String id : ExpeditionConfig.BORDER_EXCLUDE.get()) {
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location != null) {
                    exclude.add(location);
                }
            }
            return new Settings(
                    ExpeditionConfig.BORDER_ENABLED.getAsBoolean(), ExpeditionConfig.VOID_MARGIN.getAsInt(), exclude);
        }
    }

    /** The settings from the config file: where they come from outside tests. */
    public static final Supplier<Settings> CONFIG = Settings::fromConfig;

    private static volatile Supplier<Settings> settings = CONFIG;

    /** Each level's bounds, worked out when first asked and kept until the borders are applied again. */
    private static final Map<ResourceKey<Level>, Optional<PlanetBounds>> BOUNDS = new ConcurrentHashMap<>();

    /** The levels whose border Expedition has detached from the Overworld's. */
    private static final Set<ResourceKey<Level>> DETACHED = ConcurrentHashMap.newKeySet();

    /** The listener sending each detached level's border changes to its players. */
    private static final Map<ResourceKey<Level>, BorderChangeListener> LISTENERS = new ConcurrentHashMap<>();

    private PlanetBorders() {}

    /**
     * Listens for the config reloading and the server stopping.
     *
     * @param modBus the mod's event bus
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(PlanetBorders::onConfigReloading);
        gameBus.addListener(PlanetBorders::onServerStopped);
    }

    /**
     * Sets where the settings come from, for tests; {@link #CONFIG} otherwise. Set it before the
     * server starts.
     *
     * @param source the settings
     */
    public static void setSettings(Supplier<Settings> source) {
        settings = source;
    }

    /** {@return the settings in force} */
    public static Settings settings() {
        return settings.get();
    }

    /**
     * The bounds the settings give a planet now.
     *
     * @param planet the planet
     * @return its bounds, or empty if it isn't bounded
     */
    public static Optional<PlanetBounds> wanted(Planet planet) {
        Settings now = settings();
        return now.bounds(planet.dimension())
                ? Optional.of(PlanetBounds.of(planet.surface(), planet.space().size(), now.voidMargin()))
                : Optional.empty();
    }

    /**
     * A level's bounds, worked out from its planet and the settings the first time it is asked.
     *
     * @param dimension the level
     * @return its bounds, or empty if it isn't a bounded planet
     */
    public static Optional<PlanetBounds> bounds(ResourceKey<Level> dimension) {
        return BOUNDS.computeIfAbsent(
                dimension, d -> Planets.forDimension(d, LogicalSide.SERVER).flatMap(PlanetBorders::wanted));
    }

    /**
     * The bounds a level has now, without working them out.
     *
     * @param dimension the level
     * @return its bounds, or empty if it has none or they haven't been worked out
     */
    public static Optional<PlanetBounds> applied(ResourceKey<Level> dimension) {
        Optional<PlanetBounds> bounds = BOUNDS.get(dimension);
        return bounds == null ? Optional.empty() : bounds;
    }

    /**
     * Whether a chunk is to be left empty: it lies wholly past its bounded planet's border and void
     * margin. Asked from worldgen threads.
     *
     * @param level the chunk's level
     * @param pos the chunk
     * @return true if no terrain, structures or features generate in it
     */
    public static boolean isVoid(ServerLevel level, ChunkPos pos) {
        Optional<PlanetBounds> bounds = bounds(level.dimension());
        return bounds.isPresent() && bounds.get().isVoid(pos.x, pos.z);
    }

    /**
     * Whether a level's border is Expedition's, detached from the Overworld's.
     *
     * @param dimension the level
     * @return true if it is
     */
    public static boolean isDetached(ResourceKey<Level> dimension) {
        return !DETACHED.isEmpty() && DETACHED.contains(dimension);
    }

    /** {@return whether any level's border is detached from the Overworld's} */
    public static boolean anyDetached() {
        return !DETACHED.isEmpty();
    }

    /**
     * Whether a player sees the Overworld's border: they are in the Overworld or a level that still
     * copies it.
     *
     * @param player the player
     * @return true if the Overworld's border changes are theirs
     */
    public static boolean followsOverworld(ServerPlayer player) {
        return !isDetached(player.level().dimension());
    }

    /**
     * The border a player in a level is sent: the level's own, with its centre in the Overworld's
     * units, if it is detached; the Overworld's otherwise, as vanilla sends.
     *
     * @param level the player's level
     * @return the border to build the packet from
     */
    public static WorldBorder borderToSend(ServerLevel level) {
        if (!isDetached(level.dimension())) {
            return level.getServer().overworld().getWorldBorder();
        }
        WorldBorder own = level.getWorldBorder();
        double scale = level.dimensionType().coordinateScale();
        if (scale == 1.0) {
            return own;
        }
        // The level's border reports its centre divided by the scale, and the client's divides
        // again, so the packet is built from a copy holding the centre as stored.
        WorldBorder copy = new WorldBorder();
        copy.setAbsoluteMaxSize(own.getAbsoluteMaxSize());
        copy.setCenter(own.getCenterX() * scale, own.getCenterZ() * scale);
        if (own.getLerpRemainingTime() > 0) {
            copy.lerpSizeBetween(own.getSize(), own.getLerpTarget(), own.getLerpRemainingTime());
        } else {
            copy.setSize(own.getSize());
        }
        copy.setWarningBlocks(own.getWarningBlocks());
        copy.setWarningTime(own.getWarningTime());
        copy.setDamagePerBlock(own.getDamagePerBlock());
        copy.setDamageSafeZone(own.getDamageSafeZone());
        return copy;
    }

    /**
     * Applies the borders: works out every level's bounds again, detaches and sets each bounded
     * planet's border, puts back any that is no longer bounded, and sends each player their
     * border. Server thread only.
     *
     * @param server the server
     */
    public static void apply(MinecraftServer server) {
        BOUNDS.clear();
        ServerLevel overworld = server.overworld();
        WorldBorder main = overworld.getWorldBorder();
        Optional<PlanetBounds> overworldBounds = bounds(Level.OVERWORLD);
        if (overworldBounds.isEmpty()) {
            resetOverworld(main);
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level == overworld) {
                continue;
            }
            Optional<PlanetBounds> own = bounds(level.dimension());
            if (own.isPresent() || overworldBounds.isPresent()) {
                detach(level, main);
                if (own.isPresent()) {
                    set(level, own.get());
                } else {
                    level.getWorldBorder().applySettings(WorldBorder.DEFAULT_SETTINGS);
                }
            } else if (DETACHED.contains(level.dimension())) {
                reattach(level, main);
            }
        }
        overworldBounds.ifPresent(bounds -> set(overworld, bounds));
        if (settings().enabled() && ModList.get() != null && ModList.get().isLoaded(DIM_WORLD_BORDER)) {
            LOGGER.warn("DimWorldBorder is installed too. Expedition sets the borders of bounded planets;"
                    + " leave those dimensions out of DimWorldBorder's config, or turn [planets.border] off.");
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundInitializeBorderPacket(borderToSend(player.serverLevel())));
        }
    }

    /** Sets a level's border to its bounds. */
    private static void set(ServerLevel level, PlanetBounds bounds) {
        WorldBorder border = level.getWorldBorder();
        double scale = level.dimensionType().coordinateScale();
        // A scaled level's border divides the stored centre by the scale, as vanilla's Nether does.
        border.setCenter(bounds.centerX() * scale, bounds.centerZ() * scale);
        border.setSize(bounds.size());
        String cutoff = bounds.voidMargin() < 0
                ? "terrain everywhere"
                : "chunks more than " + bounds.voidMargin() + " blocks past it left empty";
        LOGGER.info(
                "Planet {}: world border centred on ({}, {}), {} blocks a side; {}",
                level.dimension().location(),
                bounds.centerX(),
                bounds.centerZ(),
                bounds.size(),
                cutoff);
    }

    /**
     * Puts the Overworld's border back to vanilla's default centre and size if it is still where a
     * bounded Overworld's was: the border is saved in {@code level.dat}, so it outlasts the setting.
     * Any other border is the operator's.
     */
    private static void resetOverworld(WorldBorder main) {
        Optional<PlanetBounds> square = Planets.forDimension(Level.OVERWORLD, LogicalSide.SERVER)
                .map(planet -> PlanetBounds.of(planet.surface(), planet.space().size(), -1));
        if (square.isPresent()
                && main.getCenterX() == square.get().centerX()
                && main.getCenterZ() == square.get().centerZ()
                && main.getSize() == square.get().size()) {
            main.setCenter(WorldBorder.DEFAULT_SETTINGS.getCenterX(), WorldBorder.DEFAULT_SETTINGS.getCenterZ());
            main.setSize(WorldBorder.DEFAULT_SETTINGS.getSize());
            LOGGER.info("The Overworld is no longer bounded: its world border is back to the default");
        }
    }

    /** Stops a level's border copying the Overworld's, and sends its changes to its own players. */
    private static void detach(ServerLevel level, WorldBorder main) {
        WorldBorder own = level.getWorldBorder();
        ((WorldBorderAccessor) main)
                .expedition$listeners()
                .removeIf(listener -> listener instanceof BorderChangeListener.DelegateBorderChangeListener delegate
                        && ((DelegateBorderChangeListenerAccessor) delegate).expedition$worldBorder() == own);
        DETACHED.add(level.dimension());
        LISTENERS.computeIfAbsent(level.dimension(), dimension -> {
            BorderChangeListener listener = new LevelBorderListener(level);
            own.addListener(listener);
            return listener;
        });
    }

    /** Makes a level's border copy the Overworld's again, as vanilla has it. */
    private static void reattach(ServerLevel level, WorldBorder main) {
        WorldBorder own = level.getWorldBorder();
        BorderChangeListener listener = LISTENERS.remove(level.dimension());
        if (listener != null) {
            own.removeListener(listener);
        }
        DETACHED.remove(level.dimension());
        main.addListener(new BorderChangeListener.DelegateBorderChangeListener(own));
        own.applySettings(main.createSettings());
        LOGGER.info(
                "Planet {} is no longer bounded: its world border follows the Overworld's again",
                level.dimension().location());
    }

    /**
     * How many of a level's region files lie wholly past its bounds' cutoff: chunks generated there
     * before the planet was bounded, which stay as they are.
     *
     * @param server the server
     * @param dimension the level
     * @param bounds its bounds
     * @return the count; 0 if the level has no region files
     */
    public static long regionsPastCutoff(MinecraftServer server, ResourceKey<Level> dimension, PlanetBounds bounds) {
        Path regions = DimensionType.getStorageFolder(dimension, server.getWorldPath(LevelResource.ROOT))
                .resolve("region");
        if (!Files.isDirectory(regions)) {
            return 0;
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regions, "r.*.mca")) {
            for (Path file : files) {
                names.add(file.getFileName().toString());
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't list the region files of {}", dimension.location(), e);
        }
        return bounds.voidRegions(names);
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ExpeditionConfig.SPEC) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> apply(server));
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        BOUNDS.clear();
        DETACHED.clear();
        LISTENERS.clear();
    }

    /** Sends a detached level's border changes to the players in that level. */
    private static final class LevelBorderListener implements BorderChangeListener {
        private final ServerLevel level;

        LevelBorderListener(ServerLevel level) {
            this.level = level;
        }

        private void send(Packet<?> packet) {
            level.getServer().getPlayerList().broadcastAll(packet, level.dimension());
        }

        @Override
        public void onBorderSizeSet(WorldBorder border, double size) {
            send(new ClientboundSetBorderSizePacket(border));
        }

        @Override
        public void onBorderSizeLerping(WorldBorder border, double from, double to, long time) {
            send(new ClientboundSetBorderLerpSizePacket(border));
        }

        @Override
        public void onBorderCenterSet(WorldBorder border, double x, double z) {
            send(new ClientboundSetBorderCenterPacket(borderToSend(level)));
        }

        @Override
        public void onBorderSetWarningTime(WorldBorder border, int time) {
            send(new ClientboundSetBorderWarningDelayPacket(border));
        }

        @Override
        public void onBorderSetWarningBlocks(WorldBorder border, int blocks) {
            send(new ClientboundSetBorderWarningDistancePacket(border));
        }

        @Override
        public void onBorderSetDamagePerBlock(WorldBorder border, double damage) {}

        @Override
        public void onBorderSetDamageSafeZOne(WorldBorder border, double safeZone) {}
    }
}
