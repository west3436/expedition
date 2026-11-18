package io.github.west3436.expedition.mover;

import net.minecraft.network.chat.Component;

/**
 * The stable codes a refused move gives: in {@link MoveResult#refusal()}, in {@link
 * DryRun#refusal()} and in each {@link Obstacle#code()}. Programs compare these strings; they never
 * change. Each has a message for people in the mod's {@code en_us.json}, under {@link
 * #translationKey}.
 *
 * <p>The mover's own checks are asked in this order, and the first that refuses names the move's
 * refusal: {@link #TOO_LARGE}, {@link #HOP_TOO_LARGE}, {@link #BUSY}, {@link #UNLOADED}, {@link
 * #IMMOVABLE}, {@link #BLOCKED}. The checks in {@link MoveSettings#checks()} come after them, with codes of their own.
 */
public final class MoveRefusal {
    /**
     * Something is in the way at the destination: a block that is neither air nor replaceable (see
     * {@link ShipSurvey#isSolid}), or a living entity, a vehicle or a hanging entity the move doesn't
     * carry. Grass, snow layers, water and the like are written over, without drops.
     */
    public static final String BLOCKED = "blocked";

    /** A chunk at either end, or a block beyond, isn't loaded, or the entities of a box's chunk aren't. */
    public static final String UNLOADED = "unloaded";

    /** The box holds a block in the {@code expedition:immovable} tag ({@link ExpeditionTags#IMMOVABLE}). */
    public static final String IMMOVABLE = "immovable";

    /** The box is over the server's ceiling, as {@link ShipMover#ceiling()} judges it. */
    public static final String TOO_LARGE = "too_large";

    /**
     * The move is a short hop, whose destination overlaps its own box, and the box is over the
     * server's ceiling for one, as {@link ShipCeiling#hopExceeds} judges it. The mover carries a short
     * hop whole in one tick, so a large one would stall the server; the same box may still move
     * further, clear of itself.
     */
    public static final String HOP_TOO_LARGE = "hop_too_large";

    /** Either box overlaps a move in progress, at either end. */
    public static final String BUSY = "busy";

    /** The relocation hand-over threw instead of beginning. */
    public static final String HANDOVER_FAILED = "handover_failed";

    /** The server stopped while the move waited for its hand-over. */
    public static final String SERVER_STOPPING = "server_stopping";

    /**
     * ZAS³ couldn't carry the cabin air: a zone in the box is paused, because part of it is in an
     * unloaded chunk, or the dimension's simulation is paused ({@link ZasRelocationHandover}).
     */
    public static final String ATMOS_PAUSED = "atmos_paused";

    /** ZAS³ couldn't carry the cabin air: a zone in the box is already being moved, by another mover. */
    public static final String ATMOS_BUSY = "atmos_busy";

    /**
     * ZAS³ couldn't carry the cabin air: the zones in the box have more cells than it allows in one
     * move ({@code [atmos] relocation_max_cells} in {@code zas-server.toml}).
     */
    public static final String ATMOS_TOO_LARGE = "atmos_too_large";

    /** ZAS³ couldn't carry the cabin air: a chunk the box or a zone in it spans isn't loaded. */
    public static final String ATMOS_UNLOADED = "atmos_unloaded";

    /** ZAS³ couldn't carry the cabin air: its ticket timed out before the move began. */
    public static final String ATMOS_TIMED_OUT = "atmos_timed_out";

    /**
     * ZAS³ couldn't carry the cabin air: its ticket was over before the move began, as when the
     * dimension's simulation was rebuilt in between.
     */
    public static final String ATMOS_ENDED = "atmos_ended";

    /** What every code's translation key starts with. */
    public static final String KEY_PREFIX = "expedition.move.refusal.";

    private MoveRefusal() {}

    /**
     * The translation key of a code's message, such as {@code expedition.move.refusal.blocked}. The
     * codes above all have one in {@code en_us.json}; a code from a check of another mod's has one
     * only if that mod gives it.
     *
     * @param code a refusal code
     * @return its translation key
     */
    public static String translationKey(String code) {
        return KEY_PREFIX + code;
    }

    /**
     * A code's message, for a player or an operator: the translation, or the code itself if it has
     * none.
     *
     * @param code a refusal code
     * @return the message
     */
    public static Component message(String code) {
        return Component.translatableWithFallback(translationKey(code), code);
    }
}
