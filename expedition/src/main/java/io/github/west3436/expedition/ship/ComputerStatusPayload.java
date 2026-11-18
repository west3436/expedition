package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.client.ShuttleComputerScreen;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A Shuttle Computer's status, sent to a player: to open its window, or to update a window already
 * open after a change.
 *
 * @param pos where the computer is
 * @param open whether to open the window, rather than only update one open on this computer
 * @param status what the window shows
 * @param notice a message to show in the window, such as the result of fitting the box
 */
public record ComputerStatusPayload(BlockPos pos, boolean open, ShipStatus status, Optional<Component> notice)
        implements CustomPacketPayload {
    /** The payload's type. */
    public static final Type<ComputerStatusPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "computer_status"));

    /** How the payload goes over the network. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ComputerStatusPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    ComputerStatusPayload::pos,
                    ByteBufCodecs.BOOL,
                    ComputerStatusPayload::open,
                    ShipStatus.STREAM_CODEC,
                    ComputerStatusPayload::status,
                    ComponentSerialization.TRUSTED_OPTIONAL_STREAM_CODEC,
                    ComputerStatusPayload::notice,
                    ComputerStatusPayload::new);

    @Override
    public Type<ComputerStatusPayload> type() {
        return TYPE;
    }

    /**
     * Registers this payload and {@link ComputerActionPayload}.
     *
     * @param event the mod-bus event
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(TYPE, STREAM_CODEC, (payload, context) -> handleOnClient(payload, context))
                .playToServer(
                        ComputerActionPayload.TYPE,
                        ComputerActionPayload.STREAM_CODEC,
                        ComputerStatusPayload::handleOnServer);
    }

    private static void handleOnClient(ComputerStatusPayload payload, IPayloadContext context) {
        // Only ever called on a client, so the screen's class never loads on a dedicated server.
        ShuttleComputerScreen.accept(payload);
    }

    private static void handleOnServer(ComputerActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
            ShipComputers.handle(player, payload);
        }
    }
}
