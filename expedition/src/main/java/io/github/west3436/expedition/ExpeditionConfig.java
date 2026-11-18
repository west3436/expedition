package io.github.west3436.expedition;

import io.github.west3436.expedition.mover.ShipCeiling;
import io.github.west3436.expedition.mover.ShipMover;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Expedition's server settings, in {@code serverconfig/expedition-server.toml} of each world. They
 * apply on the next move after the file is reloaded, with no restart.
 *
 * <p>The {@code ships} section is the server's ceiling on a ship's box ({@link ShipMover#ceiling()}).
 * It is a technical limit, not a game rule: ship size has no gameplay cap, since thrust against mass
 * limits a ship, but the mover, ZAS³'s relocation hand-over and the transit slots all need an upper
 * bound. The defaults come from the measurements in #8, on a 4-vCPU runner with a 3 GiB heap:
 *
 * <ul>
 *   <li>{@link #MAX_VOLUME}, 131,072 blocks (64 × 64 × 32): a full box at it takes about 30 ticks
 *       a move, 3 s a trip, and allocates about 200 MiB a move. 262,144 (64³) works, at about 6 s a
 *       trip and 400 MiB a move, which brought 50–200 ms garbage-collection pauses on a small heap.
 *   <li>{@link #MAX_LONGEST_SIDE}, 64 blocks: the largest side measured. It keeps a box within 6 × 6
 *       chunk columns and sets the transit slots' spacing. Going past it needs new measurements.
 *   <li>{@link #MAX_HOP_VOLUME}, 4,096 blocks (16³): a short hop, whose destination overlaps its own
 *       box, is carried whole in one tick, whatever the tick budget. A full 16³ box costs that tick
 *       about 6–10 ms; a full box at the volume ceiling would cost about 150 ms.
 * </ul>
 */
public final class ExpeditionConfig {
    private static final String KEY = ExpeditionMod.MOD_ID + ".configuration.";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment(
                        "The server's ceiling on a ship's box. This is a technical limit, not a game rule: it keeps one",
                        "move from stalling the server. A move over it is refused as too_large, before anything is read.",
                        "Raising it makes the largest moves longer and heavier on memory; see each value.")
                .translation(KEY + "ships")
                .push("ships");
    }

    /** The most blocks a ship's box may hold, counting air. */
    public static final ModConfigSpec.IntValue MAX_VOLUME = BUILDER.comment(
                    "The most blocks a ship's box may hold, air included. The default, 131072, is 64 x 64 x 32.",
                    "A full box at the default takes about 1.5 s a move, 3 s a trip, and allocates about 200 MiB",
                    "a move. 262144 (64 x 64 x 64) works, but takes about 6 s a trip, allocates about 400 MiB a",
                    "move and can bring 50-200 ms lag spikes from garbage collection on a small heap.")
            .translation(KEY + "maxVolume")
            .defineInRange("maxVolume", 131_072, 1, Integer.MAX_VALUE);

    /** The most blocks a ship's box may be along any axis. */
    public static final ModConfigSpec.IntValue MAX_LONGEST_SIDE = BUILDER.comment(
                    "The most blocks a ship's box may be along any one axis. 64 is the largest size that has been",
                    "measured; a longer side spans more chunks, and needs wider spacing between transit slots.")
            .translation(KEY + "maxLongestSide")
            .defineInRange("maxLongestSide", 64, 1, Integer.MAX_VALUE);

    /** The most blocks a short hop's box may hold: a move whose destination overlaps its own box. */
    public static final ModConfigSpec.IntValue MAX_HOP_VOLUME = BUILDER.comment(
                    "The most blocks a ship's box may hold for a short hop: a move whose destination overlaps the",
                    "box itself. Such a hop is carried whole in one server tick, so it is refused as hop_too_large",
                    "over this, though the same ship may still move further. A full 16 x 16 x 16 box (4096) costs",
                    "that tick about 6-10 ms; every 4096 blocks more adds about as much.")
            .translation(KEY + "maxOverlappingHopVolume")
            .defineInRange("maxOverlappingHopVolume", 4_096, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    /** The server settings' spec, registered as the mod's {@code SERVER} config. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The ceiling on a ship's box made from these settings, read at every move. */
    public static final ShipCeiling CEILING =
            ShipCeiling.of(() -> value(MAX_VOLUME), () -> value(MAX_LONGEST_SIDE), () -> value(MAX_HOP_VOLUME));

    private ExpeditionConfig() {}

    /** A setting's value, or its default while no world's settings are loaded. */
    private static int value(ModConfigSpec.IntValue setting) {
        return SPEC.isLoaded() ? setting.getAsInt() : setting.getDefault();
    }
}
