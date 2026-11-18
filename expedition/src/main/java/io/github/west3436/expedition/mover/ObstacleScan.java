package io.github.west3436.expedition.mover;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * The mover's own checks, and the caller's after them, in the order {@link MoveRefusal} gives. One
 * scan serves both a move, which wants only the first obstacle, and a dry run, which wants them all.
 *
 * <p>It never loads a chunk: a chunk that isn't loaded is an obstacle, and what is in it isn't read.
 * Blocks are read section by section, and a section whose palette can't hold a match is skipped, so
 * an empty destination costs little.
 *
 * <p>Thread: server thread only.
 */
final class ObstacleScan {
    private final MoveRequest request;
    private final boolean all;
    private final List<Obstacle> found = new ArrayList<>();

    private ObstacleScan(MoveRequest request, boolean all) {
        this.request = request;
        this.all = all;
    }

    /**
     * Finds what would refuse a move.
     *
     * @param request the move
     * @param settings its settings, whose checks are asked after the mover's own
     * @param jobs the moves in progress
     * @param self the move being checked again, if it is one of {@code jobs}; else null
     * @param all true to find every obstacle, false to stop at the first
     * @return the obstacles, in order; at most one unless {@code all}
     */
    static List<Obstacle> find(
            MoveRequest request, MoveSettings settings, List<MoveJob> jobs, MoveJob self, boolean all) {
        ObstacleScan scan = new ObstacleScan(request, all);
        scan.run(settings, jobs, self);
        return List.copyOf(scan.found);
    }

    private void run(MoveSettings settings, List<MoveJob> jobs, MoveJob self) {
        ServerLevel from = request.from();
        ServerLevel to = request.to();
        BoundingBox box = request.box();
        BoundingBox destination = request.destination();

        ShipCeiling ceiling = ShipMover.ceiling();
        boolean tooLarge = ceiling.exceeds(box);
        if (tooLarge && !add(new Obstacle.OfMove(MoveRefusal.TOO_LARGE))) {
            return;
        }
        if (!tooLarge && request.overlaps() && ceiling.hopExceeds(box)) {
            if (!add(new Obstacle.OfMove(MoveRefusal.HOP_TOO_LARGE))) {
                return;
            }
        }
        for (MoveJob job : jobs) {
            if (job != self && (job.overlaps(from, box) || job.overlaps(to, destination))) {
                if (!add(new Obstacle.OfMove(MoveRefusal.BUSY))) {
                    return;
                }
                break;
            }
        }
        if (!bothEndsLoaded()) {
            return;
        }
        // A box over the ceiling may be any size at all, so it isn't read block by block.
        if (!tooLarge) {
            if (!immovable(from, box) || !blocked(to, destination)) {
                return;
            }
        }
        for (MoveCheck check : settings.checks()) {
            List<Obstacle> refusals = all
                    ? check.obstacles(request)
                    : check.check(request)
                            .map(c -> List.<Obstacle>of(new Obstacle.OfMove(c)))
                            .orElse(List.of());
            for (Obstacle obstacle : refusals) {
                if (!add(obstacle)) {
                    return;
                }
            }
        }
    }

    /**
     * Notes an obstacle.
     *
     * @return true to look for more
     */
    private boolean add(Obstacle obstacle) {
        found.add(obstacle);
        return all;
    }

    /** The chunks at both ends, a block beyond, and the entities of the boxes' own chunks. */
    private boolean bothEndsLoaded() {
        Set<String> seen = new HashSet<>();
        return loaded(request.from(), request.box(), seen) && loaded(request.to(), request.destination(), seen);
    }

    private boolean loaded(ServerLevel level, BoundingBox box, Set<String> seen) {
        for (ChunkPos pos : ShipMover.chunks(box)) {
            if (!level.hasChunk(pos.x, pos.z) && seen.add(key(level, pos))) {
                if (!add(new Obstacle.InChunk(MoveRefusal.UNLOADED, level.dimension(), pos))) {
                    return false;
                }
            }
        }
        // A chunk's entities load after its blocks; a move before then would leave them behind, and
        // a check before then would miss what stands in the way.
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (level.hasChunk(x, z) && !level.areEntitiesLoaded(pos.toLong()) && seen.add(key(level, pos))) {
                    if (!add(new Obstacle.InChunk(MoveRefusal.UNLOADED, level.dimension(), pos))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String key(ServerLevel level, ChunkPos pos) {
        return level.dimension().location() + " " + pos.toLong();
    }

    /** Every block of the box in the immovable tag. */
    private boolean immovable(ServerLevel from, BoundingBox box) {
        boolean tagged = BuiltInRegistries.BLOCK
                .getTag(ExpeditionTags.IMMOVABLE)
                .map(set -> set.size() > 0)
                .orElse(false);
        if (!tagged) {
            return true;
        }
        return blocks(from, box, s -> s.is(ExpeditionTags.IMMOVABLE), null, MoveRefusal.IMMOVABLE);
    }

    /**
     * Everything in the way at the destination: each block that isn't air or replaceable, outside
     * the box itself for a short hop, then each entity there the move doesn't carry.
     */
    private boolean blocked(ServerLevel to, BoundingBox destination) {
        BoundingBox own = request.sameLevel() ? request.box() : null;
        if (!blocks(to, destination, ShipSurvey::isSolid, own, MoveRefusal.BLOCKED)) {
            return false;
        }
        List<Entity> inTheWay = to.getEntities((Entity) null, AABB.of(destination), this::inTheWay);
        for (Entity entity : inTheWay) {
            if (!add(new Obstacle.OfEntity(
                    MoveRefusal.BLOCKED, to.dimension(), entity.getUUID(), entity.getType(), entity.position()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an entity at the destination is in the way: a living one (players, mobs, armour
     * stands), a vehicle or a hanging one (paintings, item frames) that the move doesn't carry.
     * Items, experience orbs, projectiles, markers and the like aren't.
     */
    private boolean inTheWay(Entity entity) {
        if (entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        if (!(entity instanceof LivingEntity || entity instanceof VehicleEntity || entity instanceof HangingEntity)) {
            return false;
        }
        Entity root = entity.getRootVehicle();
        boolean carried = request.sameLevel()
                && root.level() == request.from()
                && request.box().isInside(root.blockPosition());
        return !carried;
    }

    /**
     * Every block of a box, in its loaded chunks, that matches, outside {@code skip} if it is given.
     *
     * @return true to look for more
     */
    private boolean blocks(
            ServerLevel level, BoundingBox box, Predicate<BlockState> match, BoundingBox skip, String code) {
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue; // already an obstacle of its own
                }
                LevelChunkSection[] sections = chunk.getSections();
                int x0 = Math.max(box.minX(), cx << 4);
                int x1 = Math.min(box.maxX(), (cx << 4) + 15);
                int z0 = Math.max(box.minZ(), cz << 4);
                int z1 = Math.min(box.maxZ(), (cz << 4) + 15);
                for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length || !sections[index].maybeHas(match)) {
                        continue;
                    }
                    LevelChunkSection section = sections[index];
                    int y0 = Math.max(box.minY(), sy << 4);
                    int y1 = Math.min(box.maxY(), (sy << 4) + 15);
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (match.test(state) && (skip == null || !skip.isInside(x, y, z))) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    if (!add(new Obstacle.AtBlock(code, level.dimension(), pos, state))) {
                                        return false;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
}
