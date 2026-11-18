package io.github.west3436.expedition.mover;

import io.github.west3436.expedition.api.ExpeditionApi;
import io.github.west3436.zas.api.mutation.SourceId;
import io.github.west3436.zas.api.relocation.Destination;
import io.github.west3436.zas.api.relocation.RelocationOutcome;
import io.github.west3436.zas.api.relocation.RelocationResult;
import io.github.west3436.zas.api.relocation.RelocationTicket;
import io.github.west3436.zas.api.relocation.Relocations;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The hand-over to ZAS³'s relocation (API-065, ZAS³ 1.2.0's {@link Relocations}), which carries the
 * cabin air of a moved box along: every zone wholly inside the box arrives with the same moles of
 * each gas, the same temperature and the same pins, and nothing breaches or vents on the way. It is
 * the hand-over {@link MoveSettings#DEFAULT} uses.
 *
 * <p>Each step maps one-to-one onto ZAS³'s: {@link #begin} is {@link Relocations#begin}, {@link
 * Ticket#complete} is {@link Relocations#complete} with {@link Destination#offset}, and {@link
 * Ticket#cancel} is {@link Relocations#cancel}. ZAS³ applies a begin at its next atmos tick, so a
 * move waits up to about a second before its first block moves.
 *
 * <p><b>Refusal codes.</b> A begin ZAS³ refuses refuses the move whole, with nothing changed. The
 * code is {@code atmos_} and the outcome's name in lower case, each one in {@link MoveRefusal} with a
 * message in {@code en_us.json}:
 *
 * <ul>
 *   <li>{@link MoveRefusal#ATMOS_PAUSED atmos_paused}: a zone in the box is paused, because part of
 *       it is in an unloaded chunk, or the dimension's simulation is paused;
 *   <li>{@link MoveRefusal#ATMOS_BUSY atmos_busy}: a zone in the box is already being moved, by
 *       another mover;
 *   <li>{@link MoveRefusal#ATMOS_TOO_LARGE atmos_too_large}: the zones in the box have more cells
 *       than ZAS³ allows in one move ({@code [atmos] relocation_max_cells}, 102,400 by default);
 *   <li>{@link MoveRefusal#ATMOS_UNLOADED atmos_unloaded}: a chunk the box or a zone in it spans
 *       isn't loaded;
 *   <li>{@link MoveRefusal#ATMOS_TIMED_OUT atmos_timed_out}, {@link MoveRefusal#ATMOS_ENDED
 *       atmos_ended}: the ticket was over before it began, as when the simulation was rebuilt in
 *       between.
 * </ul>
 *
 * <p>{@code BEGUN} lets the move go ahead, and so does {@code ABSENT}, which ZAS³'s API answers when
 * ZAS³ itself isn't running: there is no air to carry. An outcome a later ZAS³ adds gets a code by
 * the same rule. A begin that fails outright is the mover's {@link MoveRefusal#HANDOVER_FAILED}.
 *
 * <p>ZAS³ cancels a ticket that isn't completed within {@code [atmos] relocation_timeout_seconds}
 * (30 by default). A move longer than that loses its cabin air to the ordinary block changes, and is
 * logged.
 *
 * <p>Thread: server thread only.
 */
public final class ZasRelocationHandover implements RelocationHandover {
    /** Who moves the blocks, as ZAS³'s {@code ZoneRelocatedEvent} and {@code BlocksMovedEvent} name it. */
    public static final SourceId MOVER = SourceId.of(ExpeditionApi.MOD_ID, "ship_mover");

    /** The hand-over. It keeps nothing between moves. */
    public static final ZasRelocationHandover INSTANCE = new ZasRelocationHandover();

    /** The prefix of every refusal code this hand-over gives. */
    public static final String REFUSAL_PREFIX = "atmos_";

    private ZasRelocationHandover() {}

    /**
     * Begins ZAS³'s relocation of the zones in {@code box}.
     *
     * @param from the level the blocks leave
     * @param box the blocks that will move
     * @return the ticket
     */
    @Override
    public ZasTicket begin(ServerLevel from, BoundingBox box) {
        return new ZasTicket(Relocations.begin(from, box, MOVER));
    }

    /**
     * The refusal code for the outcome of a begin.
     *
     * @param outcome what ZAS³'s begin answered
     * @return empty for {@code BEGUN} and {@code ABSENT}, which let the move go ahead; otherwise {@code
     *     atmos_} and the outcome's name in lower case
     */
    public static Optional<String> refusal(RelocationOutcome outcome) {
        return switch (outcome) {
            case BEGUN, ABSENT -> Optional.empty();
            case PAUSED -> Optional.of(MoveRefusal.ATMOS_PAUSED);
            case BUSY -> Optional.of(MoveRefusal.ATMOS_BUSY);
            case TOO_LARGE -> Optional.of(MoveRefusal.ATMOS_TOO_LARGE);
            case UNLOADED -> Optional.of(MoveRefusal.ATMOS_UNLOADED);
            case TIMED_OUT -> Optional.of(MoveRefusal.ATMOS_TIMED_OUT);
            case ENDED -> Optional.of(MoveRefusal.ATMOS_ENDED);
            // Never a begin's answer; and whatever a later ZAS³ adds.
            default -> Optional.of(REFUSAL_PREFIX + outcome.name().toLowerCase(Locale.ROOT));
        };
    }

    /** One of ZAS³'s relocation tickets, seen as the mover's. */
    public static final class ZasTicket implements Ticket {
        private final RelocationTicket ticket;
        private final CompletableFuture<Optional<String>> begun;
        private final CompletableFuture<RelocationResult> ended = new CompletableFuture<>();

        private ZasTicket(RelocationTicket ticket) {
            this.ticket = ticket;
            this.begun = ticket.begun().thenApply(result -> refusal(result.outcome()));
        }

        /**
         * ZAS³'s ticket.
         *
         * @return the ticket
         */
        public RelocationTicket ticket() {
            return ticket;
        }

        @Override
        public CompletableFuture<Optional<String>> begun() {
            return begun;
        }

        /**
         * How ZAS³ ended the move: once {@link #complete} or {@link #cancel} is called, it completes on
         * the server thread with what ZAS³'s step answered, {@code MOVED} once every zone has arrived,
         * or {@code CANCELLED} once they carry on where they were.
         *
         * @return the future, the same one every time
         */
        public CompletableFuture<RelocationResult> ended() {
            return ended;
        }

        @Override
        public void complete(ServerLevel to, Vec3i offset) {
            Relocations.complete(ticket, Destination.offset(to, offset)).whenComplete((result, error) -> {
                if (error == null && result.outcome() == RelocationOutcome.UNLOADED) {
                    // The mover holds both ends loaded, so this shouldn't happen. The blocks have
                    // moved: an open ticket would only hold the zones paused until it timed out.
                    ShipMover.LOGGER.warn(
                            "ZAS³ couldn't carry the air of {}: a chunk isn't loaded; it is released", ticket);
                    Relocations.cancel(ticket);
                } else if (error == null && result.outcome() != RelocationOutcome.MOVED) {
                    ShipMover.LOGGER.warn("ZAS³ didn't carry the air of {}: {}", ticket, result.outcome());
                } else if (error != null) {
                    ShipMover.LOGGER.warn("ZAS³ failed to carry the air of {}", ticket, error);
                }
                finish(result, error);
            });
        }

        @Override
        public void cancel() {
            Relocations.cancel(ticket).whenComplete(this::finish);
        }

        private void finish(RelocationResult result, Throwable error) {
            if (error != null) {
                ended.completeExceptionally(error);
            } else {
                ended.complete(result);
            }
        }

        @Override
        public String toString() {
            return "ZasTicket[" + ticket + "]";
        }
    }
}
