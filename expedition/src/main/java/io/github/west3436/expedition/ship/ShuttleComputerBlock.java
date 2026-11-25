package io.github.west3436.expedition.ship;

import com.mojang.serialization.MapCodec;
import io.github.west3436.zas.api.mutation.ZonePins;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Shuttle Computer: a ship's brain. It holds the ship's name, owner and box, and its window sets
 * the box and shows what the ship is. Its screen faces the player who places it, who owns it; one no
 * player placed, such as one built from a computer frame, is owned by the first player to use it.
 */
public final class ShuttleComputerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    /** The block's codec. */
    public static final MapCodec<ShuttleComputerBlock> CODEC = simpleCodec(ShuttleComputerBlock::new);

    /**
     * A Shuttle Computer.
     *
     * @param properties its properties
     */
    public ShuttleComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShuttleComputerBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // The item's name, if it has one, became the ship's as the block entity took the item's
        // components.
        if (!level.isClientSide
                && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof ShuttleComputerBlockEntity computer) {
            computer.setOwner(player.getUUID(), player.getGameProfile().getName());
        }
    }

    /**
     * Takes the ship's cabin pins off when the computer is broken or replaced, so its cabin can go
     * back to the open air as any room does. A ship's move doesn't come here: the mover carries the
     * computer without removing it.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof ShuttleComputerBlockEntity computer) {
            ZonePins.unpin(server, computer.airOwner());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ShuttleComputerBlockEntity computer) {
            // A computer no one placed, such as one SS13 Framework built from a computer frame, is
            // the first user's.
            if (computer.owner().isEmpty()) {
                computer.setOwner(player.getUUID(), player.getGameProfile().getName());
            }
            ShipComputers.open(serverPlayer, server, computer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
