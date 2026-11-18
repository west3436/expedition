package io.github.west3436.expedition.portal;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Whether Nether and End portals take things in (#57), from the server settings' {@code portals}
 * section. Both do by default, as in vanilla.
 *
 * <p>With one turned off, its portal still lights and still draws its effect, but going through it
 * into that dimension does nothing: {@code PortalDestinationMixin} gives the portal no destination,
 * so no exit portal is built and nothing is moved, and a player is told why on the action bar. Every
 * entity is stopped alike, players in any game mode and operators included; there is no bypass.
 *
 * <p>Only going in is stopped:
 *
 * <ul>
 *   <li>A Nether portal in the Nether always leads out, to the Overworld.
 *   <li>An End portal in the End always leads out: the exit portal after the dragon, and the
 *       credits and the return to the Overworld it starts.
 *   <li>End gateways move things within the End, and are left alone.
 * </ul>
 *
 * <p>A Nether portal in any dimension but the Nether leads into the Nether, so it is stopped from
 * Space or a planet as from the Overworld; the same goes for End portals and the End.
 *
 * <p>Thread: server thread.
 */
public final class PortalRules {
    /** The action-bar message a player gets at a Nether portal that is turned off. */
    public static final String NETHER_OFF = "expedition.portal.nether_off";

    /** The action-bar message a player gets at an End portal that is turned off. */
    public static final String END_OFF = "expedition.portal.end_off";

    /**
     * Which portals take things in, read at each trip.
     *
     * @param nether whether Nether portals lead into the Nether
     * @param end whether End portals lead into the End
     */
    public record Settings(BooleanSupplier nether, BooleanSupplier end) {
        /** Both portals on: vanilla. */
        public static final Settings VANILLA = of(true, true);

        /**
         * Checks the suppliers.
         *
         * @throws NullPointerException if one is null
         */
        public Settings {
            Objects.requireNonNull(nether, "nether");
            Objects.requireNonNull(end, "end");
        }

        /**
         * Fixed settings.
         *
         * @param nether whether Nether portals lead into the Nether
         * @param end whether End portals lead into the End
         * @return the settings
         */
        public static Settings of(boolean nether, boolean end) {
            return new Settings(() -> nether, () -> end);
        }
    }

    private static volatile Settings settings = Settings.VANILLA;

    private PortalRules() {}

    /**
     * The settings portals follow.
     *
     * @return the settings; {@link Settings#VANILLA} until the mod sets its own
     */
    public static Settings settings() {
        return settings;
    }

    /**
     * Sets the settings portals follow. The mod sets the ones read from its server settings as it
     * loads; a test that sets others puts the old ones back when it is done.
     *
     * @param settings the settings
     */
    public static void setSettings(Settings settings) {
        PortalRules.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Whether a portal refuses to take an entity in, telling a player why if it does.
     *
     * @param portal the portal's block
     * @param from the level the entity is in
     * @param entity the entity at the portal
     * @return true if the portal must do nothing
     */
    public static boolean refuses(Block portal, ServerLevel from, Entity entity) {
        String message;
        if (portal == Blocks.NETHER_PORTAL) {
            if (leads(from, Level.NETHER, settings.nether())) {
                return false;
            }
            message = NETHER_OFF;
        } else if (portal == Blocks.END_PORTAL) {
            if (leads(from, Level.END, settings.end())) {
                return false;
            }
            message = END_OFF;
        } else {
            return false;
        }
        if (entity instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable(message), true);
        }
        return true;
    }

    /** Whether a portal into {@code into} works here: it leads out of it, or it is on. */
    private static boolean leads(ServerLevel from, ResourceKey<Level> into, BooleanSupplier on) {
        return from.dimension() == into || on.getAsBoolean();
    }
}
