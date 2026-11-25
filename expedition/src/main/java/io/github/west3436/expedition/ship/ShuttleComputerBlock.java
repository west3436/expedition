package io.github.west3436.expedition.ship;

import com.mojang.serialization.MapCodec;
import io.github.west3436.ss13.api.console.ConsoleBlock;
import io.github.west3436.zas.api.mutation.ZonePins;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Shuttle Computer: a ship's brain. It holds the ship's name, owner and box, and its window sets
 * the box and shows what the ship is. Its screen faces the player who places it, who owns it; one no
 * player placed, such as one built from a computer frame, is owned by the first player to use it.
 *
 * <p>It is a console 2 blocks wide, 2 tall and 1 deep, like ZAS³'s Central Atmospherics Controller
 * and SS13 Framework's computer frame it is built from ({@link ConsoleBlock}): it grows up and to the
 * right of the block placed, as the placer sees it, breaking any block breaks it whole, and its lower
 * left block holds the {@link ShuttleComputerBlockEntity}. Using any of its blocks opens its window.
 */
public final class ShuttleComputerBlock extends ConsoleBlock {
    /** The block's codec. */
    public static final MapCodec<ShuttleComputerBlock> CODEC = simpleCodec(ShuttleComputerBlock::new);

    /**
     * A Shuttle Computer.
     *
     * @param properties its properties
     */
    public ShuttleComputerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected @Nullable BlockEntity newConsoleEntity(BlockPos pos, BlockState state) {
        return new ShuttleComputerBlockEntity(pos, state);
    }

    /** The placing player owns it. The item's name, if it has one, is already the ship's. */
    @Override
    protected void consolePlaced(
            Level level, BlockPos origin, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide
                && placer instanceof Player player
                && level.getBlockEntity(origin) instanceof ShuttleComputerBlockEntity computer) {
            computer.setOwner(player.getUUID(), player.getGameProfile().getName());
        }
    }

    /**
     * Takes the ship's cabin pins off when the computer is broken or replaced, so its cabin can go
     * back to the open air as any room does. A ship's move doesn't come here: the mover carries the
     * computer without removing it. Only the lower left block has the block entity, so only its
     * removal does this.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())
                && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof ShuttleComputerBlockEntity computer) {
            ZonePins.unpin(server, computer.airOwner());
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected InteractionResult useConsole(
            BlockState state, Level level, BlockPos origin, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(origin) instanceof ShuttleComputerBlockEntity computer) {
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
