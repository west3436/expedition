package io.github.west3436.expedition.mover;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One thing that makes a move refused, as a {@link DryRun} lists it: a block, an entity, a chunk, or
 * a limit of the server's that the ship is over, or the move as a whole. Each carries its {@link MoveRefusal} code; a program switches on the record
 * to say where.
 */
public sealed interface Obstacle {
    /**
     * Why this refuses the move.
     *
     * @return a stable code, such as {@link MoveRefusal#BLOCKED}
     */
    String code();

    /**
     * A block: one in the destination that is in the way ({@link MoveRefusal#BLOCKED}), or one in the
     * box that can't be moved ({@link MoveRefusal#IMMOVABLE}).
     *
     * @param code why
     * @param level the level it is in
     * @param pos where
     * @param state what it is
     */
    record AtBlock(String code, ResourceKey<Level> level, BlockPos pos, BlockState state) implements Obstacle {
        /**
         * Checks the arguments and makes the position immutable.
         *
         * @throws NullPointerException if an argument is null
         */
        public AtBlock {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(state, "state");
            pos = pos.immutable();
        }
    }

    /**
     * An entity in the destination that the move doesn't carry ({@link MoveRefusal#BLOCKED}).
     *
     * @param code why
     * @param level the level it is in
     * @param uuid its id
     * @param type its type
     * @param position where it stood when the dry run looked
     */
    record OfEntity(String code, ResourceKey<Level> level, UUID uuid, EntityType<?> type, Vec3 position)
            implements Obstacle {
        /**
         * Checks the arguments.
         *
         * @throws NullPointerException if an argument is null
         */
        public OfEntity {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * A chunk that isn't loaded, or whose entities aren't ({@link MoveRefusal#UNLOADED}), or where
     * the player who started the move may not build ({@link MoveRefusal#CLAIMED}).
     *
     * @param code why
     * @param level the level it is in
     * @param chunk which
     */
    record InChunk(String code, ResourceKey<Level> level, ChunkPos chunk) implements Obstacle {
        /**
         * Checks the arguments.
         *
         * @throws NullPointerException if an argument is null
         */
        public InChunk {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    /**
     * A limit of the server's {@link ShipCeiling} that the ship's box is over ({@link
     * MoveRefusal#TOO_LARGE} or {@link MoveRefusal#HOP_TOO_LARGE}), with how large the box is and
     * what the limit allows, so a pilot can tell which limit it broke and by how much. A box over
     * both {@link #VOLUME} and {@link #LONGEST_SIDE} gives one of each.
     *
     * @param code why
     * @param limit which limit: {@link #VOLUME}, {@link #LONGEST_SIDE}, {@link #HOP_VOLUME}, or
     *     another ceiling's own
     * @param size the box's measure for that limit, in blocks
     * @param max the most the limit allows, in blocks
     */
    record OverLimit(String code, String limit, long size, long max) implements Obstacle {
        /** The most blocks a box may hold ({@code ships.maxVolume}). */
        public static final String VOLUME = "volume";

        /** The most blocks a box may be along any axis ({@code ships.maxLongestSide}). */
        public static final String LONGEST_SIDE = "longest_side";

        /** The most blocks a short hop's box may hold ({@code ships.maxOverlappingHopVolume}). */
        public static final String HOP_VOLUME = "hop_volume";

        /** What every limit's translation key starts with. */
        public static final String KEY_PREFIX = "expedition.move.limit.";

        /**
         * Checks the arguments.
         *
         * @throws NullPointerException if the code or the limit is null
         */
        public OverLimit {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(limit, "limit");
        }

        /**
         * How far over the limit the box is.
         *
         * @return the size less the limit, in blocks
         */
        public long over() {
            return size - max;
        }

        /**
         * The message for a player or an operator, such as "The ship's box holds 140,000 blocks; this
         * server allows 131,072 (8,928 over).", under {@code expedition.move.limit.<limit>}. Its
         * arguments are the size, the limit and how far over, each written with a thousands
         * separator. A limit with no translation falls back to its name and the numbers.
         *
         * @return the message
         */
        public Component message() {
            String size = number(size());
            String max = number(max());
            String over = number(over());
            return Component.translatableWithFallback(
                    KEY_PREFIX + limit, limit + ": " + size + " > " + max, size, max, over);
        }

        /**
         * A count as the messages write it, with a thousands separator.
         *
         * @param n the count
         * @return it, such as {@code 131,072}
         */
        public static String number(long n) {
            return String.format(Locale.ROOT, "%,d", n);
        }
    }

    /**
     * The move as a whole: it is {@link MoveRefusal#TOO_LARGE} or {@link MoveRefusal#HOP_TOO_LARGE} by
     * a ceiling that can't say by how much, {@link MoveRefusal#BUSY} or {@link MoveRefusal#OUTSIDE_BORDER}, or one of {@link MoveSettings#checks()} refused it.
     *
     * @param code why
     */
    record OfMove(String code) implements Obstacle {
        /**
         * Checks the code.
         *
         * @throws NullPointerException if it is null
         */
        public OfMove {
            Objects.requireNonNull(code, "code");
        }
    }
}
