package io.github.west3436.expedition.drive.gas;

import io.github.west3436.expedition.drive.Drive;
import io.github.west3436.expedition.drive.DriveSource;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A gas thruster's block entity: what makes it a {@link DriveSource}. It holds nothing; its fuel is
 * in the tanks beside it, measured and burned for the whole ship by {@link GasThrusters}.
 */
public final class GasThrusterBlockEntity extends BlockEntity implements DriveSource {
    /**
     * A thruster's block entity.
     *
     * @param pos where it is
     * @param state its block
     */
    public GasThrusterBlockEntity(BlockPos pos, BlockState state) {
        super(ExpeditionBlocks.GAS_THRUSTER_ENTITY.get(), pos, state);
    }

    @Override
    public Optional<Drive> drive(ServerLevel level, BlockPos pos) {
        return GasThrusters.nozzleOpen(level, pos) ? Optional.of(GasThrusters.drive()) : Optional.empty();
    }
}
