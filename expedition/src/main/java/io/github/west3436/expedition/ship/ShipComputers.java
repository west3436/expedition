package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.expedition.drive.Drive;
import io.github.west3436.expedition.drive.DriveSource;
import io.github.west3436.expedition.drive.FuelSystem;
import io.github.west3436.expedition.drive.Plot;
import io.github.west3436.expedition.drive.ShipDrives;
import io.github.west3436.expedition.drive.ThrustModel;
import io.github.west3436.expedition.drive.Trip;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.ShipMover;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What a Shuttle Computer does: everything its window's buttons ask, and what later flight controls
 * (#29) and the Shuttle Interface's Lua calls will ask, so there is one path to test. Every method
 * runs on the server thread.
 *
 * <ul>
 *   <li>{@link #status}: what the window shows: name, owner, box, mass, thrust, thrust-to-weight
 *       here, drives and fuel, and warnings for natural terrain, an overlapping ship and a box over
 *       the server's ceiling.
 *   <li>{@link #setBox}, {@link #rename} and {@link #fit}: change the ship.
 *   <li>{@link #plot}: the thrust model's answer for a trip, refused first if another ship's box
 *       overlaps this one or the box is over the ceiling. Flight (#29) starts from here.
 *   <li>{@link #burn}: takes a departure's fuel from the ship's supplies, once the plot is
 *       feasible.
 * </ul>
 */
public final class ShipComputers {
    /**
     * {@code expedition:terrain}: natural ground. A ship's box may hold it, and it counts as mass,
     * but the window warns about it, and fitting the box to the ship's blocks never grows into it.
     */
    public static final TagKey<Block> TERRAIN =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "terrain"));

    /** A refusal: another ship's box overlaps this one, so neither may move. */
    public static final String OVERLAP = "ship_overlap";

    /** How far from the computer fitting the box looks, in blocks, on each axis. */
    public static final int FIT_REACH = 128;

    /** How many connected blocks fitting the box looks at before giving up. */
    public static final int FIT_LIMIT = 262_144;

    /** How close a player must be to use a computer, in blocks. */
    private static final double REACH = 8.0;

    /** The computers loaded in each server level. */
    private static final Map<Level, Set<ShuttleComputerBlockEntity>> LOADED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ShipComputers() {}

    static void track(ShuttleComputerBlockEntity computer) {
        LOADED.computeIfAbsent(
                        computer.getLevel(),
                        l -> Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>())))
                .add(computer);
    }

    static void untrack(ShuttleComputerBlockEntity computer) {
        Set<ShuttleComputerBlockEntity> set = LOADED.get(computer.getLevel());
        if (set != null) {
            set.remove(computer);
        }
    }

    /**
     * The computers loaded in a level.
     *
     * @param level the level
     * @return a copy of them, in no order
     */
    public static List<ShuttleComputerBlockEntity> loaded(Level level) {
        Set<ShuttleComputerBlockEntity> set = LOADED.get(level);
        if (set == null) {
            return List.of();
        }
        synchronized (set) {
            return List.copyOf(set);
        }
    }

    /**
     * The loaded computer whose ship's box holds a position, for its area ({@link ShipAreas}). Where
     * boxes overlap, which no ship that moves allows, the ship whose id sorts first. Quick, copying
     * nothing and loading nothing.
     *
     * @param level the level
     * @param pos the position
     * @return the computer, if a box holds it
     */
    static Optional<ShuttleComputerBlockEntity> holding(Level level, BlockPos pos) {
        Set<ShuttleComputerBlockEntity> set = LOADED.get(level);
        if (set == null) {
            return Optional.empty();
        }
        ShuttleComputerBlockEntity found = null;
        synchronized (set) {
            for (ShuttleComputerBlockEntity computer : set) {
                if (!computer.isRemoved()
                        && computer.worldBox().isInside(pos)
                        && (found == null || computer.shipId().compareTo(found.shipId()) < 0)) {
                    found = computer;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * A loaded computer whose ship's box overlaps this one's. Two such ships can't both move: a ship
     * may not carry another's computer, nor part of its box.
     *
     * @param computer the computer
     * @return another computer whose box overlaps, if any
     */
    public static Optional<ShuttleComputerBlockEntity> overlapping(ShuttleComputerBlockEntity computer) {
        BoundingBox box = computer.worldBox();
        for (ShuttleComputerBlockEntity other : loaded(computer.getLevel())) {
            if (other != computer
                    && !other.isRemoved()
                    && other.getLevel() == computer.getLevel()
                    && other.worldBox().intersects(box)) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    /**
     * What one walk of a box finds.
     *
     * @param mass the box's mass
     * @param terrain how many blocks of natural terrain it holds
     * @param drives the drives that would fire
     * @param positions where those drives are, by fuel
     * @param fuel how much of each fuel they can draw on, from its {@link FuelSystem}
     */
    private record Survey(
            double mass,
            int terrain,
            List<Drive> drives,
            Map<String, List<BlockPos>> positions,
            Map<String, Double> fuel) {}

    /** Walks a box once: its mass, its natural terrain, and its drives; then measures their fuel. */
    private static Survey survey(ServerLevel level, BoundingBox box) {
        double mass = 0;
        int terrain = 0;
        List<Drive> drives = new ArrayList<>();
        Map<String, List<BlockPos>> positions = new TreeMap<>();
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            mass += BlockWeights.weight(state);
            if (state.is(TERRAIN)) {
                terrain++;
            }
            if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof DriveSource source) {
                BlockPos at = pos.immutable();
                source.drive(level, at).ifPresent(drive -> {
                    drives.add(drive);
                    positions
                            .computeIfAbsent(drive.fuel(), k -> new ArrayList<>())
                            .add(at);
                });
            }
        }
        Map<String, Double> fuel = new TreeMap<>();
        positions.forEach(
                (kind, at) -> fuel.put(kind, FuelSystem.Registry.get(kind).measure(level, at)));
        return new Survey(mass, terrain, drives, positions, fuel);
    }

    /**
     * What a computer's window shows. It walks the whole box, unless the box is over the server's
     * ceiling, when the mass is unknown ({@code NaN}) and there are no drives.
     *
     * @param level the computer's level
     * @param computer the computer
     * @return the status
     */
    public static ShipStatus status(ServerLevel level, ShuttleComputerBlockEntity computer) {
        BoundingBox world = computer.worldBox();
        boolean tooLarge = ShipMover.ceiling().exceeds(world);
        Survey survey = tooLarge ? new Survey(Double.NaN, 0, List.of(), Map.of(), Map.of()) : survey(level, world);
        Optional<String> overlap = overlapping(computer).map(ShipComputers::label);
        return new ShipStatus(
                computer.name(),
                computer.ownerName(),
                computer.box(),
                world,
                survey.mass,
                survey.terrain,
                ThrustModel.thrust(survey.drives),
                survey.drives.size(),
                survey.fuel,
                PlanetGravity.of(level),
                overlap,
                tooLarge);
    }

    /** A ship's name, or its computer's position if it has none. */
    private static String label(ShuttleComputerBlockEntity computer) {
        if (!computer.name().isEmpty()) {
            return computer.name();
        }
        BlockPos p = computer.getBlockPos();
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    /**
     * Plots a trip for a computer's ship: the thrust model's answer ({@link ThrustModel#plot}), from
     * the ship's mass, drives and fuel. Before the model, a ship whose box is over the server's
     * ceiling is refused as {@link MoveRefusal#TOO_LARGE}, with no numbers; and a ship whose box
     * overlaps another's is refused as {@link #OVERLAP}, with the model's numbers.
     *
     * @param level the computer's level
     * @param computer the computer
     * @param trip the trip
     * @return the plot
     */
    public static Plot plot(ServerLevel level, ShuttleComputerBlockEntity computer, Trip trip) {
        BoundingBox world = computer.worldBox();
        if (ShipMover.ceiling().exceeds(world)) {
            return new Plot(Optional.of(MoveRefusal.TOO_LARGE), 0, 0, 0, 0, Double.POSITIVE_INFINITY, Map.of());
        }
        Survey survey = survey(level, world);
        Plot plot = ThrustModel.plot(new ShipDrives(survey.mass, survey.drives, survey.fuel), trip);
        if (overlapping(computer).isPresent()) {
            return new Plot(
                    Optional.of(OVERLAP),
                    plot.liftOffRatio(),
                    plot.landingRatio(),
                    plot.cruiseSpeed(),
                    plot.acceleration(),
                    plot.time(),
                    plot.fuel());
        }
        return plot;
    }

    /**
     * Burns a departure's fuel: each fuel's {@link FuelSystem} takes what the plot says from the
     * ship's supplies, all of it now, and lets the drives' exhaust out. The trip gate (#29) calls it
     * once a plot is feasible, as the ship leaves.
     *
     * @param level the computer's level
     * @param computer the computer
     * @param plot the departure's plot, from {@link #plot}
     * @throws IllegalArgumentException if the plot was refused
     */
    public static void burn(ServerLevel level, ShuttleComputerBlockEntity computer, Plot plot) {
        if (!plot.feasible()) {
            throw new IllegalArgumentException("a refused plot burns nothing: " + plot.refusal());
        }
        Survey survey = survey(level, computer.worldBox());
        plot.fuel().forEach((kind, amount) -> {
            List<BlockPos> at = survey.positions.getOrDefault(kind, List.of());
            double burned = Math.min(amount, survey.fuel.getOrDefault(kind, 0.0));
            if (!at.isEmpty() && burned > 0) {
                FuelSystem.Registry.get(kind).burn(level, at, burned);
            }
        });
    }

    /**
     * Sets a computer's box.
     *
     * @param computer the computer
     * @param box the box
     */
    public static void setBox(ShuttleComputerBlockEntity computer, ShipBox box) {
        computer.setBox(box);
    }

    /**
     * Names a computer's ship.
     *
     * @param computer the computer
     * @param name the name, cut to {@link ShipStatus#MAX_NAME} characters
     */
    public static void rename(ShuttleComputerBlockEntity computer, String name) {
        computer.setName(name);
    }

    /**
     * Whether fitting the box grows through a block: anything solid that isn't air, a fluid, a plant
     * or snow layer a placed block would replace, or natural terrain ({@link #TERRAIN}).
     *
     * @param state the block
     * @return true if it is part of a ship
     */
    public static boolean connects(BlockState state) {
        return !state.isAir()
                && !(state.getBlock() instanceof LiquidBlock)
                && !state.is(BlockTags.REPLACEABLE)
                && !state.is(TERRAIN);
    }

    /**
     * Grows or shrinks a computer's box to the blocks connected to it, face to face, through blocks
     * that {@link #connects connect}, as far as {@link #FIT_REACH} from the computer. Unloaded
     * chunks stop it. The box is left as it was if the connected blocks run past the reach, past
     * {@link #FIT_LIMIT} blocks, or past the server's ceiling on a ship's box.
     *
     * @param level the computer's level
     * @param computer the computer
     * @return how many connected blocks the box was fitted to, or empty if it was left as it was
     */
    public static Optional<Integer> fit(ServerLevel level, ShuttleComputerBlockEntity computer) {
        BlockPos origin = computer.getBlockPos();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(origin.asLong());
        queue.enqueue(origin.asLong());
        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        int count = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            BlockPos pos = BlockPos.of(queue.dequeueLong());
            count++;
            if (count > FIT_LIMIT) {
                return Optional.empty();
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            for (Direction direction : Direction.values()) {
                next.setWithOffset(pos, direction);
                long key = next.asLong();
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                if (level.isOutsideBuildHeight(next) || !level.isLoaded(next) || !connects(level.getBlockState(next))) {
                    continue;
                }
                if (Math.abs(next.getX() - origin.getX()) > FIT_REACH
                        || Math.abs(next.getY() - origin.getY()) > FIT_REACH
                        || Math.abs(next.getZ() - origin.getZ()) > FIT_REACH) {
                    // The ship runs on past the reach.
                    return Optional.empty();
                }
                queue.enqueue(key);
            }
        }
        BoundingBox box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        if (ShipMover.ceiling().exceeds(box)) {
            return Optional.empty();
        }
        computer.setBox(ShipBox.fromWorld(box, origin, computer.facing()));
        return Optional.of(count);
    }

    // ---- The window ---------------------------------------------------------------------

    /**
     * Opens a computer's window for a player.
     *
     * @param player the player
     * @param level the computer's level
     * @param computer the computer
     */
    public static void open(ServerPlayer player, ServerLevel level, ShuttleComputerBlockEntity computer) {
        PacketDistributor.sendToPlayer(
                player,
                new ComputerStatusPayload(computer.getBlockPos(), true, status(level, computer), Optional.empty()));
    }

    /**
     * Does what a player asked of a computer from its window, then sends them its status again. A
     * player too far from the computer, or in another level, is ignored.
     *
     * @param player the player
     * @param action what they asked
     */
    static void handle(ServerPlayer player, ComputerActionPayload action) {
        if (!(player.level() instanceof ServerLevel level)
                || !level.isLoaded(action.pos())
                || !(level.getBlockEntity(action.pos()) instanceof ShuttleComputerBlockEntity computer)
                || player.distanceToSqr(action.pos().getCenter()) > REACH * REACH) {
            return;
        }
        Optional<Component> notice = Optional.empty();
        switch (action.action()) {
            case RENAME -> rename(computer, action.name());
            case SET_BOX -> setBox(computer, action.box());
            case FIT ->
                notice = Optional.of(fit(level, computer)
                        .map(count -> Component.translatable("expedition.shuttle_computer.fit.done", count))
                        .orElseGet(() -> Component.translatable("expedition.shuttle_computer.fit.too_large")));
            case REFRESH -> {}
        }
        PacketDistributor.sendToPlayer(
                player, new ComputerStatusPayload(action.pos(), false, status(level, computer), notice));
    }
}
