package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.ExpeditionMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What a player asks of a Shuttle Computer from its window. The server answers with a {@link
 * ComputerStatusPayload}.
 *
 * @param pos where the computer is
 * @param action what to do
 * @param name the ship's new name, for {@link Action#RENAME}; else empty
 * @param box the ship's new box, for {@link Action#SET_BOX}; else any
 */
public record ComputerActionPayload(BlockPos pos, Action action, String name, ShipBox box)
        implements CustomPacketPayload {
    /** The payload's type. */
    public static final Type<ComputerActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "computer_action"));

    /** How the payload goes over the network. */
    public static final StreamCodec<ByteBuf, ComputerActionPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            ComputerActionPayload::pos,
            ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal),
            ComputerActionPayload::action,
            ByteBufCodecs.stringUtf8(ShipStatus.MAX_NAME),
            ComputerActionPayload::name,
            ShipBox.STREAM_CODEC,
            ComputerActionPayload::box,
            ComputerActionPayload::new);

    /** What a player may ask. */
    public enum Action {
        /** Send the status again. */
        REFRESH,
        /** Name the ship. */
        RENAME,
        /** Set the box. */
        SET_BOX,
        /** Fit the box to the blocks connected to the computer. */
        FIT
    }

    /**
     * An action with no arguments.
     *
     * @param pos where the computer is
     * @param action what to do
     * @return the payload
     */
    public static ComputerActionPayload of(BlockPos pos, Action action) {
        return new ComputerActionPayload(pos, action, "", ShipBox.NONE);
    }

    @Override
    public Type<ComputerActionPayload> type() {
        return TYPE;
    }
}
