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
 * Makes sure every enclosed room in a box is a ZAS³ zone before the box moves, by pinning it.
 *
 * <p>ZAS³'s relocation carries zones, and only zones. But ZAS³ dissolves a sealed room whose air
 * matches its dimension's open air after a minute (CORE-052), and a cabin of plain Overworld air
 * does. Such a cabin had no zone by the time its ship took off, so it arrived empty: vacuum on the
 * Moon, and "open air" again at home. A pin forms a zone in an enclosed region that has none,
 * holding the air it has, and keeps it from dissolving (API-050).
 *
 * <p>The regions are found here as the box's positions with no collision shape, joined across
 * faces: a rough picture of ZAS³'s cells, but one seed per region is all a pin needs. A region ZAS³
 * sees as open to the outside forms nothing when pinned, and two seeds ZAS³ sees as one room pin it
 * once.
 *
 * <p>Thread: server thread only.
 */
public final class CabinAir {
    private CabinAir() {}

    /**
     * One position in each region of empty positions inside a box.
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
            if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                empty.set(i);
            }
        }
        List<BlockPos> seeds = new ArrayList<>();
        int[] queue = new int[size];
        for (int start = empty.nextSetBit(0); start >= 0; start = empty.nextSetBit(start + 1)) {
            seeds.add(
                    new BlockPos(box.minX() + start % sx, box.minY() + start / sx / sz, box.minZ() + start / sx % sz));
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
                        continue;
                    }
                    int n = nx + sx * (nz + sz * ny);
                    if (empty.get(n)) {
                        empty.clear(n);
                        queue[tail++] = n;
                    }
                }
            }
        }
        return seeds;
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
        List<CompletableFuture<PinResult>> pins = new ArrayList<>();
        for (BlockPos seed : seeds(level, box)) {
            pins.add(ZonePins.pin(level, Vec3.atCenterOf(seed), owner).exceptionally(error -> {
                ShipMover.LOGGER.warn("ZAS³ couldn't pin the air at {} for {}", seed, owner, error);
                return null;
            }));
        }
        return CompletableFuture.allOf(pins.toArray(CompletableFuture[]::new));
    }
}
