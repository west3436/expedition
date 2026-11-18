package io.github.west3436.expedition.flight;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.ship.ComputerStatusPayload;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A pilot's flight controls, from a Shuttle Computer's window: plot a flight, or launch it. The
 * server answers with a {@link ComputerStatusPayload} whose notice says what the plot found, or
 * that the ship is launching.
 *
 * @param pos where the computer is
 * @param launch true to launch, false only to plot
 * @param order where to go
 */
public record ComputerFlightPayload(BlockPos pos, boolean launch, FlightOrder order) implements CustomPacketPayload {
    /** The payload's type. */
    public static final Type<ComputerFlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "computer_flight"));

    /** How the payload goes over the network. */
    public static final StreamCodec<ByteBuf, ComputerFlightPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            ComputerFlightPayload::pos,
            ByteBufCodecs.BOOL,
            ComputerFlightPayload::launch,
            FlightOrder.STREAM_CODEC,
            ComputerFlightPayload::order,
            ComputerFlightPayload::new);

    /** How close a pilot must be to the computer, in blocks, as for its other buttons. */
    private static final double REACH = 8.0;

    @Override
    public Type<ComputerFlightPayload> type() {
        return TYPE;
    }

    /**
     * Registers the payload.
     *
     * @param event the mod-bus event
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, ComputerFlightPayload::handle);
    }

    private static void handle(ComputerFlightPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            handle(player, payload);
        }
    }

    /**
     * Plots or launches what a player asked, then sends them the computer's status with what came
     * of it. A player too far from the computer, or in another level, is ignored.
     *
     * @param player the player
     * @param payload what they asked
     */
    static void handle(ServerPlayer player, ComputerFlightPayload payload) {
        if (!(player.level() instanceof ServerLevel level)
                || !level.isLoaded(payload.pos())
                || !(level.getBlockEntity(payload.pos()) instanceof ShuttleComputerBlockEntity computer)
                || player.distanceToSqr(payload.pos().getCenter()) > REACH * REACH) {
            return;
        }
        Component planet = Flights.name(payload.order().planet());
        Component notice;
        if (payload.launch()) {
            Flight flight = Flights.launch(level, computer, payload.order(), player);
            // A refused launch's plan holds why, the owner check's and the countdown's included.
            notice = flight.result().isDone()
                    ? flight.plan().message(planet)
                    : Component.translatable("expedition.flight.launching", planet);
        } else {
            notice = Flights.plan(level, computer, payload.order()).message(planet);
        }
        PacketDistributor.sendToPlayer(
                player,
                new ComputerStatusPayload(
                        payload.pos(), false, ShipComputers.status(level, computer), Optional.of(notice)));
    }
}
