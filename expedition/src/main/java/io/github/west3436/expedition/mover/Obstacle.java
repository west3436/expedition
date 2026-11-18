package io.github.west3436.expedition.mover;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One thing that makes a move refused, as a {@link DryRun} lists it: a block, an entity, a chunk, or
 * the move as a whole. Each carries its {@link MoveRefusal} code; a program switches on the record
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
     * A chunk that isn't loaded, or whose entities aren't ({@link MoveRefusal#UNLOADED}).
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
     * The move as a whole: it is {@link MoveRefusal#TOO_LARGE}, {@link MoveRefusal#HOP_TOO_LARGE},
     * {@link MoveRefusal#BUSY} or {@link MoveRefusal#OUTSIDE_BORDER}, or one of {@link MoveSettings#checks()} refused it.
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
