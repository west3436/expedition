package io.github.west3436.expedition.mover;

import io.github.west3436.expedition.mixin.ChunkAccessAccessor;
import io.github.west3436.expedition.mixin.HeightmapAccessor;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Block changes that run none of a block's own behaviour, as a chunk saving at one end and loading
 * at the other would see them. A block taken away isn't removed (no {@code onRemove}, so a chest
 * doesn't spill and a door doesn't take its other half with it), and a block set down isn't placed
 * (no {@code onPlace}, so sand doesn't fall, TNT doesn't prime and a piston doesn't push). No
 * neighbour hears of the change, and nothing drops.
 *
 * <p>What the world itself keeps about a block still follows it: the chunk's heightmaps, the light
 * engine, points of interest such as beds and job sites, NeoForge's capability caches, the chunk's
 * saved flag and the clients watching it. A block entity leaves as a chunk unloading lets it go ({@code onChunkUnloaded}, then
 * {@code setRemoved}) and arrives as a chunk loading builds it (from its saved state, then
 * registered and its ticker and {@code onLoad} set up), so other mods' block entities see a move as
 * an unload and a load, which they already survive.
 *
 * <p>Neighbours are told once a whole move is done, by {@link #settle}.
 */
public final class QuietBlocks {
    /** The heightmaps a loaded chunk keeps up to date with each block change, as LevelChunk.setBlockState does. */
    private static final Heightmap.Types[] LIVE_HEIGHTMAPS = {
        Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.WORLD_SURFACE
    };

    /** Every side, for {@link #settle(ServerLevel, BlockPos, int)}. */
    static final int ALL_SIDES = (1 << 6) - 1;

    /** The order {@code updateNeighborsAt} tells a block's neighbours in (NeighborUpdater.UPDATE_ORDER). */
    private static final Direction[] NEIGHBOUR_ORDER = {
        Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH
    };

    /** The order {@code updateNeighbourShapes} fits a block's neighbours in (BlockBehaviour.UPDATE_SHAPE_ORDER). */
    private static final Direction[] SHAPE_ORDER = {
        Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP
    };

    private QuietBlocks() {}

    /**
     * Sets a block without running its behaviour. Any block entity at {@code pos} must already be
     * gone: take it first with {@link #takeBlockEntity}, or drop it with {@link
     * LevelChunk#removeBlockEntity}.
     *
     * <p>Thread: server thread only.
     *
     * @param level the level
     * @param chunk the loaded chunk that holds {@code pos}
     * @param pos the position
     * @param state the block to set
     * @return the block that was there
     */
    public static BlockState set(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state) {
        return set(level, chunk, pos, state, null);
    }

    /**
     * {@link #set(ServerLevel, LevelChunk, BlockPos, BlockState)}, with the light check gathered into
     * a move's batch instead of queued on its own.
     *
     * @param lights the move's light checks, or null to queue the check at once
     */
    static BlockState set(
            ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state, @Nullable LightChecks lights) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        boolean wasEmpty = section.hasOnlyAir();
        if (wasEmpty && state.isAir()) {
            return state;
        }
        int x = pos.getX() & 15;
        int y = pos.getY();
        int z = pos.getZ() & 15;
        BlockState old = section.setBlockState(x, y & 15, z, state);
        if (old == state) {
            return old;
        }
        // The heightmaps LevelChunk.setBlockState keeps, looked up rather than iterated, which would
        // make an entry for each.
        Map<Heightmap.Types, Heightmap> heightmaps = ((ChunkAccessAccessor) chunk).expedition$heightmaps();
        for (Heightmap.Types type : LIVE_HEIGHTMAPS) {
            Heightmap heightmap = heightmaps.get(type);
            if (heightmap == null) {
                continue;
            }
            Predicate<BlockState> counts = type.isOpaque();
            if (!counts.test(state) && heightmap.getFirstAvailable(x, z) - 1 == y) {
                lower(chunk, heightmap, counts, x, y, z);
            } else {
                heightmap.update(x, y, z, state);
            }
        }
        boolean nowEmpty = section.hasOnlyAir();
        if (wasEmpty != nowEmpty) {
            level.getChunkSource().getLightEngine().updateSectionStatus(pos, nowEmpty);
        }
        if (LightEngine.hasDifferentLightProperties(chunk, pos, old, state)) {
            chunk.getSkyLightSources().update(chunk, x, y, z);
            if (lights != null) {
                lights.check(level, pos);
            } else {
                level.getChunkSource().getLightEngine().checkBlock(pos);
            }
        }
        chunk.setUnsaved(true);
        level.getChunkSource().blockChanged(pos);
        // Both copy the position if they keep it.
        level.onBlockStateChange(pos, old, state);
        // A block entity's capabilities are invalidated as it leaves and arrives; a plain block's
        // aren't, so a neighbour that cached one (a pipe, say) is told here.
        if (!old.hasBlockEntity() && !state.hasBlockEntity()) {
            level.invalidateCapabilities(pos);
        }
        return old;
    }

    /**
     * Lowers a heightmap's column whose top block was just taken away, to the next block below that
     * counts, as {@link Heightmap#update} does, without making a position for each search: a move
     * takes a ship's columns away from the top, one block at a time. Sections of nothing but air,
     * which never counts, are passed over whole.
     */
    private static void lower(
            LevelChunk chunk, Heightmap heightmap, Predicate<BlockState> counts, int x, int y, int z) {
        LevelChunkSection[] sections = chunk.getSections();
        int min = chunk.getMinBuildHeight();
        for (int j = y - 1; j >= min; j--) {
            LevelChunkSection section = sections[chunk.getSectionIndex(j)];
            if (section.hasOnlyAir()) {
                j = (j >> 4) << 4; // the section's lowest y; the loop goes on below it
                continue;
            }
            if (counts.test(section.getBlockState(x, j & 15, z))) {
                ((HeightmapAccessor) heightmap).expedition$setHeight(x, z, j + 1);
                return;
            }
        }
        ((HeightmapAccessor) heightmap).expedition$setHeight(x, z, min);
    }

    /**
     * Takes a block entity out of the level as an unloading chunk does, keeping its saved state.
     *
     * <p>Thread: server thread only.
     *
     * @param level the level
     * @param chunk the loaded chunk that holds {@code pos}
     * @param pos the position
     * @return its full saved state, or null if there was no block entity there; a block entity that
     *     throws while saving is still taken away, logged, and given as an empty state
     */
    @Nullable
    public static CompoundTag takeBlockEntity(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        BlockEntity blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (blockEntity == null) {
            return null;
        }
        CompoundTag tag;
        try {
            tag = blockEntity.saveWithFullMetadata(level.registryAccess());
        } catch (RuntimeException e) {
            // As a chunk save does: the block entity won't persist, and the mod is to blame.
            ShipMover.LOGGER.error(
                    "Block entity {} at {} threw while saving for a move; it arrives empty",
                    blockEntity.getClass().getName(),
                    pos,
                    e);
            tag = new CompoundTag();
        }
        blockEntity.onChunkUnloaded();
        chunk.removeBlockEntity(pos);
        return tag;
    }

    /**
     * Builds a block entity for a block just set with {@link #set}, as a loading chunk does: from its
     * saved state if there is one, otherwise new. Its position in the saved state is rewritten to
     * {@code pos}.
     *
     * <p>Thread: server thread only.
     *
     * @param level the level
     * @param chunk the loaded chunk that holds {@code pos}
     * @param pos the position
     * @param state the block there
     * @param tag the saved state from {@link #takeBlockEntity}, or null
     * @return the block entity, or null if the block has none
     */
    @Nullable
    public static BlockEntity putBlockEntity(
            ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state, @Nullable CompoundTag tag) {
        if (!state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = null;
        if (tag != null && tag.contains("id")) {
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            blockEntity = BlockEntity.loadStatic(pos.immutable(), state, tag, level.registryAccess());
        }
        if (blockEntity == null && state.getBlock() instanceof EntityBlock block) {
            blockEntity = block.newBlockEntity(pos.immutable(), state);
        }
        if (blockEntity != null) {
            chunk.addAndRegisterBlockEntity(blockEntity);
        }
        return blockEntity;
    }

    /**
     * Carries a position's scheduled ticks, its block's and its fluid's, to where the position goes,
     * so that a repeater mid-pulse or flowing water carries on there.
     *
     * <p>Thread: server thread only.
     *
     * @param from the level the block leaves
     * @param pos its position there
     * @param state the block
     * @param to the level it arrives in
     * @param offset how far it goes
     */
    public static void moveTicks(ServerLevel from, BlockPos pos, BlockState state, ServerLevel to, Vec3i offset) {
        BoundingBox one = null;
        Block block = state.getBlock();
        if (from.getBlockTicks().hasScheduledTick(pos, block)) {
            one = new BoundingBox(pos);
            moveTicks(from.getBlockTicks(), to.getBlockTicks(), one, offset);
        }
        Fluid fluid = state.getFluidState().getType();
        if (fluid != Fluids.EMPTY && from.getFluidTicks().hasScheduledTick(pos, fluid)) {
            moveTicks(from.getFluidTicks(), to.getFluidTicks(), one != null ? one : new BoundingBox(pos), offset);
        }
    }

    private static <T> void moveTicks(LevelTicks<T> from, LevelTicks<T> to, BoundingBox one, Vec3i offset) {
        // copyAreaFrom collects the ticks before it schedules their copies, so it is safe within one
        // level too; the clear then takes only the ticks at the old position.
        to.copyAreaFrom(from, one, offset);
        from.clearArea(one);
    }

    /**
     * Tells a position's neighbours that it changed, once a move is done, as {@code setBlock} with
     * {@code UPDATE_ALL} would have: the neighbours hear of it ({@code neighborChanged}), those whose
     * shape depends on it fit it, such as a fence joining or a torch on it falling, and the block
     * there fits its neighbours in turn. Only a position on a face of a moved box needs this: inside
     * the box, every block still has the neighbours it had.
     *
     * <p>Thread: server thread only.
     *
     * @param level the level
     * @param pos a position on a face of a box that moved away or arrived
     */
    public static void settle(ServerLevel level, BlockPos pos) {
        settle(level, pos, ALL_SIDES);
    }

    /**
     * {@link #settle(ServerLevel, BlockPos)}, telling only the neighbours on the given sides: those
     * outside the box whose face the position is on. Inside a box that moved away, every neighbour
     * is air, which hears nothing; inside one that arrived, every neighbour came along with the
     * block and already fits it. A short hop, whose boxes overlap, is covered too: a neighbour pair
     * that changed has one side on a face of one of the two boxes, with the other outside it.
     *
     * @param level the level
     * @param pos a position on a face of a box that moved away or arrived
     * @param sides the sides to tell, a bit {@code 1 << direction.ordinal()} for each
     */
    static void settle(ServerLevel level, BlockPos pos, int sides) {
        BlockPos at = pos.immutable();
        BlockState now = level.getBlockState(at);
        // Air keeps its shape whatever its neighbours: the whole of a box that moved away.
        BlockState fitted = now.isAir() ? now : Block.updateFromNeighbourShapes(now, level, at);
        if (fitted != now) {
            Block.updateOrDestroy(now, fitted, level, at, Block.UPDATE_ALL);
            now = level.getBlockState(at);
        }
        if (sides == ALL_SIDES) {
            level.updateNeighborsAt(at, now.getBlock());
            now.updateNeighbourShapes(level, at, Block.UPDATE_ALL);
        } else {
            tellSides(level, at, now, sides);
        }
        now.updateIndirectNeighbourShapes(level, at, Block.UPDATE_ALL);
        if (now.hasAnalogOutputSignal()) {
            level.updateNeighbourForOutputSignal(at, now.getBlock());
        }
    }

    /**
     * As {@code updateNeighborsAt} and {@code updateNeighbourShapes} do, in their orders, for some
     * sides only. NeoForge's neighbour event hears of those sides, and may cancel the updates or drop
     * a side, as it may for {@code updateNeighborsAtExceptFromFacing}.
     */
    private static void tellSides(ServerLevel level, BlockPos at, BlockState now, int sides) {
        EnumSet<Direction> told = EnumSet.noneOf(Direction.class);
        for (Direction side : NEIGHBOUR_ORDER) {
            if ((sides & 1 << side.ordinal()) != 0) {
                told.add(side);
            }
        }
        if (told.isEmpty()) {
            return;
        }
        if (!EventHooks.onNeighborNotify(level, at, now, told, false).isCanceled()) {
            Block block = now.getBlock();
            for (Direction side : NEIGHBOUR_ORDER) {
                if (told.contains(side)) {
                    level.neighborChanged(at.relative(side), block, at);
                }
            }
        }
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction side : SHAPE_ORDER) {
            if ((sides & 1 << side.ordinal()) != 0) {
                level.neighborShapeChanged(
                        side.getOpposite(), now, next.setWithOffset(at, side), at, Block.UPDATE_ALL, 512);
            }
        }
    }

    /**
     * The sides of a position that face out of a box: those whose neighbour is outside it.
     *
     * @param box the box
     * @param x the position's x, inside the box
     * @param y its y
     * @param z its z
     * @return a bit {@code 1 << direction.ordinal()} for each side; 0 inside the box, away from its faces
     */
    static int sidesOut(BoundingBox box, int x, int y, int z) {
        int sides = 0;
        if (x == box.minX()) {
            sides |= 1 << Direction.WEST.ordinal();
        }
        if (x == box.maxX()) {
            sides |= 1 << Direction.EAST.ordinal();
        }
        if (y == box.minY()) {
            sides |= 1 << Direction.DOWN.ordinal();
        }
        if (y == box.maxY()) {
            sides |= 1 << Direction.UP.ordinal();
        }
        if (z == box.minZ()) {
            sides |= 1 << Direction.NORTH.ordinal();
        }
        if (z == box.maxZ()) {
            sides |= 1 << Direction.SOUTH.ordinal();
        }
        return sides;
    }
}
