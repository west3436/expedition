package io.github.west3436.expedition.mover;

import io.github.west3436.zas.api.mutation.PinResult;
import io.github.west3436.zas.api.mutation.SourceId;
import io.github.west3436.zas.api.mutation.ZonePins;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Makes sure every enclosed room in a box is a ZAS³ zone, by pinning it: before the box moves, and
 * for as long as a ship has a Shuttle Computer with a box.
 *
 * <p>ZAS³'s relocation carries zones, and only zones. But ZAS³ dissolves a sealed room whose air
 * matches its dimension's open air after a minute (CORE-052), and a cabin of plain Overworld air
 * does. Such a cabin had no zone by the time its ship took off, so it arrived empty: vacuum on the
 * Moon, and "open air" again at home. A pin forms a zone in an enclosed region that has none,
 * holding the air it has, and keeps it from dissolving (API-050).
 *
 * <p>The regions are found here as the box's positions with no collision shape, joined across
 * faces: a rough picture of ZAS³'s cells, but one seed per region is all a pin needs. A region ZAS³
 * sees as open to the outside forms nothing when pinned ({@code PinOutcome.OPEN}, which is no
 * error), and two seeds ZAS³ sees as one room pin it once. A region that runs on out of the box is
 * left out: its zone wouldn't be carried, and it isn't the box's to keep, such as the open air
 * around a ship or a neighbour's room the box cuts through.
 *
 * <p>A Shuttle Computer pins its ship's cabin this way under the ship's owner from when it has a
 * box, and again every few seconds ({@code ShuttleComputerBlockEntity}), so a room sealed later
 * becomes a zone too.
 *
 * <p>Thread: server thread only.
 */
public final class CabinAir {
    private CabinAir() {}

    /**
     * One position in each region of empty positions wholly inside a box. A region that reaches the
     * box's side where the position just outside is empty too, or isn't loaded, runs on out of the
     * box, and is left out.
     *
     * @param level the level
     * @param box the box
     * @return a position in each region, in no particular order
     */
    public static List<BlockPos> seeds(ServerLevel level, BoundingBox box) {
        int sx = box.getXSpan();
        int sy = box.getYSpan();
        int sz = box.getZSpan();
        int size = sx * sy * sz;
        BitSet empty = new BitSet(size);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < size; i++) {
            at.set(box.minX() + i % sx, box.minY() + i / sx / sz, box.minZ() + i / sx % sz);
            if (empty(level, at)) {
                empty.set(i);
            }
        }
        List<BlockPos> seeds = new ArrayList<>();
        int[] queue = new int[size];
        for (int start = empty.nextSetBit(0); start >= 0; start = empty.nextSetBit(start + 1)) {
            boolean inside = true;
            empty.clear(start);
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            while (head < tail) {
                int i = queue[head++];
                int x = i % sx;
                int z = i / sx % sz;
                int y = i / sx / sz;
                for (Direction d : Direction.values()) {
                    int nx = x + d.getStepX();
                    int ny = y + d.getStepY();
                    int nz = z + d.getStepZ();
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
                        if (inside) {
                            at.set(box.minX() + nx, box.minY() + ny, box.minZ() + nz);
                            inside = level.isLoaded(at) && !empty(level, at);
                        }
                        continue;
                    }
                    int n = nx + sx * (nz + sz * ny);
                    if (empty.get(n)) {
                        empty.clear(n);
                        queue[tail++] = n;
                    }
                }
            }
            if (inside) {
                seeds.add(new BlockPos(
                        box.minX() + start % sx, box.minY() + start / sx / sz, box.minZ() + start / sx % sz));
            }
        }
        return seeds;
    }

    /** Whether a position has no collision shape: air, or a block gas would pass. */
    private static boolean empty(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Pins every enclosed region of a box under an owner ({@link ZonePins#pin}).
     *
     * @param level the level
     * @param box the box
     * @param owner who holds the pins
     * @return a future completing on the server thread once every pin has been applied; a pin that
     *     fails is logged and doesn't fail it
     */
    public static CompletableFuture<Void> pin(ServerLevel level, BoundingBox box, SourceId owner) {
        return pin(level, box, owner, false);
    }

    /**
     * Pins every enclosed region of a box under an owner ({@link ZonePins#pin}). A region that is
     * open, or can't be told now, pins nothing ({@code OPEN}, {@code UNRESOLVED}), which isn't an
     * error: pin it again once it is sealed or loaded.
     *
     * @param level the level
     * @param box the box
     * @param owner who holds the pins
     * @param retried true if the caller pins again before long, as a Shuttle Computer does: a pin
     *     that fails is then logged at debug level only, so a fault doesn't fill the log
     * @return a future completing on the server thread once every pin has been applied; a pin that
     *     fails is logged and doesn't fail it
     */
    public static CompletableFuture<Void> pin(ServerLevel level, BoundingBox box, SourceId owner, boolean retried) {
        List<CompletableFuture<PinResult>> pins = new ArrayList<>();
        for (BlockPos seed : seeds(level, box)) {
            pins.add(ZonePins.pin(level, Vec3.atCenterOf(seed), owner).exceptionally(error -> {
                if (retried) {
                    ShipMover.LOGGER.debug("ZAS³ couldn't pin the air at {} for {}", seed, owner, error);
                } else {
                    ShipMover.LOGGER.warn("ZAS³ couldn't pin the air at {} for {}", seed, owner, error);
                }
                return null;
            }));
        }
        return CompletableFuture.allOf(pins.toArray(CompletableFuture[]::new));
    }
}
