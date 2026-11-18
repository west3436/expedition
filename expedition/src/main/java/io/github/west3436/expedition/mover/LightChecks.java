package io.github.west3436.expedition.mover;

import io.github.west3436.expedition.mixin.LevelLightEngineAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * The light checks of a move's block changes, gathered over a tick and handed to each level's light
 * engine as one task per chunk, rather than one task per block (#69). The light engine queues a
 * check of a block as a task of its own, with half a dozen objects for each, which for a large ship
 * was the bulk of what the mover allocated. A batch is the positions alone.
 *
 * <p>The light engine runs a batch on its own thread, as it runs a single check: each position goes
 * to the block and sky light engines, which work out the light there once the task's turn comes.
 * The mover hands its batches over at the end of each tick it works in ({@link #flush}), so the
 * light catches up as it would have.
 *
 * <p>The batch goes in through {@code checkBlock}, which queues the batch in place of its own task
 * ({@code ThreadedLevelLightEngineMixin}). If a mod has replaced the light engine, so that nothing
 * takes the batch, each position is checked on its own, as vanilla does.
 *
 * <p>Thread: server thread only.
 */
public final class LightChecks {
    /** The batch being handed over, and the thread handing it, for {@link #handOver}. */
    @Nullable
    private static Runnable handing;

    @Nullable
    private static Thread hander;

    /** How many batches the light engines have taken. */
    private static long taken;

    private final Map<ServerLevel, Long2ObjectOpenHashMap<LongArrayList>> pending = new IdentityHashMap<>(2);

    @Nullable
    private ServerLevel lastLevel;

    private long lastChunk;

    @Nullable
    private LongArrayList last;

    /** Light checks for one move. */
    LightChecks() {}

    /**
     * Notes a position whose light may have changed, to be checked at the next {@link #flush}.
     *
     * @param level its level
     * @param pos the position; only its coordinates are kept
     */
    void check(ServerLevel level, BlockPos pos) {
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongArrayList list = last;
        if (list == null || level != lastLevel || chunk != lastChunk) {
            list = pending.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>())
                    .computeIfAbsent(chunk, c -> new LongArrayList());
            lastLevel = level;
            lastChunk = chunk;
            last = list;
        }
        list.add(pos.asLong());
    }

    /** Hands every position noted so far to its level's light engine, one task per chunk. */
    void flush() {
        for (Map.Entry<ServerLevel, Long2ObjectOpenHashMap<LongArrayList>> level : pending.entrySet()) {
            ThreadedLevelLightEngine engine = level.getKey().getChunkSource().getLightEngine();
            for (Long2ObjectMap.Entry<LongArrayList> chunk : level.getValue().long2ObjectEntrySet()) {
                LongArrayList list = chunk.getValue();
                if (!list.isEmpty()) {
                    long[] positions = list.toLongArray();
                    list.clear();
                    hand(engine, positions);
                }
            }
        }
    }

    private static void hand(ThreadedLevelLightEngine engine, long[] positions) {
        LevelLightEngineAccessor engines = (LevelLightEngineAccessor) engine;
        Runnable batch = () -> {
            LightEngine<?, ?> block = engines.expedition$blockEngine();
            LightEngine<?, ?> sky = engines.expedition$skyEngine();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (long p : positions) {
                pos.set(p);
                if (block != null) {
                    block.checkBlock(pos);
                }
                if (sky != null) {
                    sky.checkBlock(pos);
                }
            }
        };
        boolean taken;
        handing = batch;
        hander = Thread.currentThread();
        try {
            engine.checkBlock(BlockPos.of(positions[0]));
        } finally {
            taken = handing == null;
            handing = null;
            hander = null;
        }
        if (!taken) {
            // Another light engine is in place: the first position was checked its way; so is each
            // of the rest.
            for (int i = 1; i < positions.length; i++) {
                engine.checkBlock(BlockPos.of(positions[i]));
            }
        }
    }

    /**
     * For {@code ThreadedLevelLightEngineMixin}: the task {@code checkBlock} is to queue. While {@link
     * #flush} hands a batch over on this thread, it is the batch, once; otherwise it is {@code task}.
     *
     * @param task the task {@code checkBlock} made, for its one position
     * @return the task to queue
     */
    public static Runnable handOver(Runnable task) {
        Runnable batch = handing;
        if (batch == null || hander != Thread.currentThread()) {
            return task;
        }
        handing = null;
        taken++;
        return batch;
    }

    /**
     * How many batches the light engines have taken since the game started, for tests: none means
     * each check went on its own, as when a mod has replaced the light engine.
     *
     * @return the count
     */
    public static long batchesTaken() {
        return taken;
    }
}
