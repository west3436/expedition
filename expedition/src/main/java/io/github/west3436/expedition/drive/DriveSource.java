package io.github.west3436.expedition.drive;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A block entity that is a ship's drive: a thruster. The Shuttle Computer asks every one in its
 * ship's box for its {@link Drive}; the {@link FuelSystem} of the drive's fuel measures and burns
 * what the drives can draw on, and the thrust model ({@link ThrustModel}) does the rest.
 *
 * <p>Thread: server thread.
 */
public interface DriveSource {
    /**
     * The drive this block adds to its ship now: its numbers, if the space behind its nozzle is open.
     * A drive adds its thrust whichever way it faces.
     *
     * @param level the level it is in
     * @param pos where it is
     * @return the drive, or empty if it adds none now
     */
    Optional<Drive> drive(ServerLevel level, BlockPos pos);
}
