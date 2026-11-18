package io.github.west3436.expedition.mover;

import io.github.west3436.expedition.api.space.Transit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Refuses a move that a player started if it touches land the player may not build on, at either
 * end, with {@link MoveRefusal#CLAIMED} (#31). Lifting the ship's blocks out of its box is taken as
 * breaking them, and setting them down at the destination as placing them.
 *
 * <p>For each chunk the box touches at either end, the check looks at a few positions of the part
 * of the box in that chunk: its centre, a corner in each of the chunk's four columns at alternating
 * heights, and the position nearest the level's spawn. At each one it asks:
 *
 * <ul>
 *   <li>the server's own protection ({@link Protection#VANILLA}: {@link ServerLevel#mayInteract},
 *       which is spawn protection on a dedicated server, and the world border); and
 *   <li>other mods: it posts NeoForge's {@link BlockEvent.BreakEvent} for the player at the source and
 *       {@link BlockEvent.EntityPlaceEvent} at the destination, which claim mods cancel where the
 *       player may not build. No claim mod is needed or named.
 * </ul>
 *
 * <p>Neither end is looked at in the transit dimension, which is the mod's own, a slot per ship.
 *
 * <p>The events are asked, not acted on: nothing is broken or placed, though a mod that listens may
 * tell the player it refused. A claim smaller than a chunk, between the positions looked at, can be
 * missed.
 *
 * <p>An operator ({@link net.minecraft.server.players.PlayerList#isOp}) is never refused, as they
 * may build anywhere; whether they are one is asked each time the check is. A move with no player,
 * such as one from the console, has no claim check at all ({@link MoveSettings#startedBy}).
 *
 * <p>Thread: server thread only.
 */
public final class ClaimCheck implements MoveCheck {
    /** Whether a player may build at a position, by the server's own rules. */
    @FunctionalInterface
    public interface Protection {
        /** The server's own: {@link ServerLevel#mayInteract}, spawn protection and the world border. */
        Protection VANILLA = (level, pos, player) -> level.mayInteract(player, pos);

        /**
         * Asks whether a player may build at a position.
         *
         * @param level the level
         * @param pos the position
         * @param player the player
         * @return true if they may
         */
        boolean mayBuild(ServerLevel level, BlockPos pos, ServerPlayer player);
    }

    private final ServerPlayer player;
    private final Protection protection;

    /**
     * A check for a move a player started, by the server's own protection and other mods' events.
     *
     * @param player who started the move
     */
    public ClaimCheck(ServerPlayer player) {
        this(player, Protection.VANILLA);
    }

    /**
     * A check for a move a player started, with the server's protection replaced, as GameTests do,
     * since their server protects no spawn. Other mods' events are still asked.
     *
     * @param player who started the move
     * @param protection the server's protection
     */
    public ClaimCheck(ServerPlayer player, Protection protection) {
        this.player = Objects.requireNonNull(player, "player");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    /**
     * Who started the move.
     *
     * @return the player
     */
    public ServerPlayer player() {
        return player;
    }

    @Override
    public Optional<String> check(MoveRequest request) {
        return find(request, false).isEmpty() ? Optional.empty() : Optional.of(MoveRefusal.CLAIMED);
    }

    /** Each chunk at either end where the player may not build, as {@link Obstacle.InChunk}. */
    @Override
    public List<Obstacle> obstacles(MoveRequest request) {
        return find(request, true);
    }

    private List<Obstacle> find(MoveRequest request, boolean all) {
        List<Obstacle> found = new ArrayList<>();
        if (player.server.getPlayerList().isOp(player.getGameProfile())) {
            return found;
        }
        // The transit dimension is the mod's own, a slot per ship: no one claims land there.
        boolean more = Transit.is(request.from()) || scan(request.from(), request.box(), false, all, found);
        if ((more || all) && !Transit.is(request.to())) {
            scan(request.to(), request.destination(), true, all, found);
        }
        return found;
    }

    /**
     * Looks at each chunk a box touches.
     *
     * @return true to look further
     */
    private boolean scan(ServerLevel level, BoundingBox box, boolean placing, boolean all, List<Obstacle> found) {
        BlockPos spawn = level.getSharedSpawnPos();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                for (BlockPos pos : samples(box, cx, cz, spawn)) {
                    if (!mayBuild(level, pos, placing)) {
                        found.add(new Obstacle.InChunk(MoveRefusal.CLAIMED, level.dimension(), new ChunkPos(cx, cz)));
                        if (!all) {
                            return false;
                        }
                        break;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The positions looked at in the part of a box in one chunk.
     *
     * @param box the box
     * @param cx the chunk's x
     * @param cz the chunk's z
     * @param spawn the level's spawn
     * @return the positions, each once
     */
    static Set<BlockPos> samples(BoundingBox box, int cx, int cz, BlockPos spawn) {
        int x0 = Math.max(box.minX(), cx << 4);
        int x1 = Math.min(box.maxX(), (cx << 4) + 15);
        int z0 = Math.max(box.minZ(), cz << 4);
        int z1 = Math.min(box.maxZ(), (cz << 4) + 15);
        int y0 = box.minY();
        int y1 = box.maxY();
        int midY = Math.floorDiv(y0 + y1, 2);
        Set<BlockPos> samples = new LinkedHashSet<>();
        samples.add(new BlockPos(Math.floorDiv(x0 + x1, 2), midY, Math.floorDiv(z0 + z1, 2)));
        samples.add(new BlockPos(x0, y0, z0));
        samples.add(new BlockPos(x1, y0, z1));
        samples.add(new BlockPos(x0, y1, z1));
        samples.add(new BlockPos(x1, y1, z0));
        samples.add(new BlockPos(Mth.clamp(spawn.getX(), x0, x1), midY, Mth.clamp(spawn.getZ(), z0, z1)));
        return samples;
    }

    private boolean mayBuild(ServerLevel level, BlockPos pos, boolean placing) {
        if (!protection.mayBuild(level, pos, player)) {
            return false;
        }
        if (placing) {
            // Placed against what is below it, as a block set down on the ground would be.
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
            return !NeoForge.EVENT_BUS
                    .post(new BlockEvent.EntityPlaceEvent(snapshot, level.getBlockState(pos.below()), player))
                    .isCanceled();
        }
        return !NeoForge.EVENT_BUS
                .post(new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player))
                .isCanceled();
    }
}
