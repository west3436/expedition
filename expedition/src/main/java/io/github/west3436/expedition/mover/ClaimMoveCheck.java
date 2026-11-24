package io.github.west3436.expedition.mover;

import io.github.west3436.expedition.api.space.Transit;
import io.github.west3436.ss13.claim.ClaimCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Refuses a move that a player started if it touches land the player may not build on, at either
 * end, with {@link MoveRefusal#CLAIMED} (#31). Lifting the ship's blocks out of its box is taken as
 * breaking them ({@link ClaimCheck.Action#BREAK}), and setting them down at the destination as
 * placing them ({@link ClaimCheck.Action#PLACE}).
 *
 * <p>The question "may this player build here" is SS13 Framework's {@link ClaimCheck}: the server's
 * own protection, other mods' claims through NeoForge's block events, the positions it samples in
 * each chunk, and the operator bypass. This check asks it at both ends of the move, and never in
 * the transit dimension, which is the mod's own, a slot per ship.
 *
 * <p>A move with no player, such as one from the console, has no claim check at all ({@link
 * MoveSettings#startedBy}).
 *
 * <p>Thread: server thread only.
 */
public final class ClaimMoveCheck implements MoveCheck {
    private final ClaimCheck claims;

    /**
     * A check for a move a player started, by the server's own protection and other mods' events.
     *
     * @param player who started the move
     */
    public ClaimMoveCheck(ServerPlayer player) {
        this(new ClaimCheck(player));
    }

    /**
     * A check for a move, asking the framework's claim check given, as GameTests do with the
     * server's protection replaced, since their server protects no spawn.
     *
     * @param claims the claim check for who started the move
     */
    public ClaimMoveCheck(ClaimCheck claims) {
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    /**
     * Who started the move.
     *
     * @return the player
     */
    public ServerPlayer player() {
        return claims.player();
    }

    @Override
    public Optional<String> check(MoveRequest request) {
        boolean refused = (!Transit.is(request.from())
                        && !claims.mayBuild(request.from(), request.box(), ClaimCheck.Action.BREAK))
                || (!Transit.is(request.to())
                        && !claims.mayBuild(request.to(), request.destination(), ClaimCheck.Action.PLACE));
        return refused ? Optional.of(MoveRefusal.CLAIMED) : Optional.empty();
    }

    /** Each chunk at either end where the player may not build, as {@link Obstacle.InChunk}. */
    @Override
    public List<Obstacle> obstacles(MoveRequest request) {
        List<Obstacle> found = new ArrayList<>();
        if (!Transit.is(request.from())) {
            for (ChunkPos chunk : claims.refusedChunks(request.from(), request.box(), ClaimCheck.Action.BREAK)) {
                found.add(
                        new Obstacle.InChunk(MoveRefusal.CLAIMED, request.from().dimension(), chunk));
            }
        }
        if (!Transit.is(request.to())) {
            for (ChunkPos chunk : claims.refusedChunks(request.to(), request.destination(), ClaimCheck.Action.PLACE)) {
                found.add(new Obstacle.InChunk(MoveRefusal.CLAIMED, request.to().dimension(), chunk));
            }
        }
        return found;
    }
}
