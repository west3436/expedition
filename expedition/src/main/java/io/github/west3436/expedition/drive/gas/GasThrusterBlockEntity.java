package io.github.west3436.expedition.drive.gas;

import io.github.west3436.expedition.drive.Drive;
import io.github.west3436.expedition.drive.DriveSource;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.ss13.device.DeviceBlockEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A gas thruster's block entity: what makes it a {@link DriveSource}. It holds nothing; its fuel is
 * in the tanks beside it, measured and burned for the whole ship by {@link GasThrusters}.
 *
 * <p>It is a device on SS13 Framework's base ({@link DeviceBlockEntity}), which versions its save
 * (format 1, which is what it always saved: nothing) and can report how hard it works, for its
 * nozzle's look and sound while it fires. Its device step does nothing yet.
 */
public final class GasThrusterBlockEntity extends DeviceBlockEntity implements DriveSource {
    /**
     * A thruster's block entity.
     *
     * @param pos where it is
     * @param state its block
     */
    public GasThrusterBlockEntity(BlockPos pos, BlockState state) {
        super(ExpeditionBlocks.GAS_THRUSTER_ENTITY.get(), pos, state);
    }

    /** Nothing yet: a thruster burns when its ship moves, not on a schedule of its own. */
    @Override
    protected void deviceTick(ServerLevel level) {}

    @Override
    public Optional<Drive> drive(ServerLevel level, BlockPos pos) {
        return GasThrusters.nozzleOpen(level, pos) ? Optional.of(GasThrusters.drive()) : Optional.empty();
    }
}
