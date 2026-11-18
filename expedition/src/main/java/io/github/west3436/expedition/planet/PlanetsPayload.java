package io.github.west3436.expedition.planet;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Every planet the server loaded, sent to a client when it joins and to every client after a
 * {@code /reload}. Each planet travels as its planet file ({@link PlanetFormat#write}), so the
 * client reads the same format the server does.
 *
 * @param planets the planets
 */
public record PlanetsPayload(List<Planet> planets) implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The payload's type. */
    public static final Type<PlanetsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "planets"));

    /** The most characters one planet's file may take on the wire. */
    private static final int MAX_FILE = 1 << 18;

    /** How the payload goes over the network. */
    public static final StreamCodec<FriendlyByteBuf, PlanetsPayload> STREAM_CODEC =
            StreamCodec.of(PlanetsPayload::write, PlanetsPayload::read);

    /**
     * Copies the list.
     */
    public PlanetsPayload {
        planets = List.copyOf(planets);
    }

    @Override
    public Type<PlanetsPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, PlanetsPayload payload) {
        buf.writeVarInt(payload.planets.size());
        for (Planet planet : payload.planets) {
            buf.writeResourceLocation(planet.id());
            buf.writeUtf(PlanetFormat.write(planet).toString(), MAX_FILE);
        }
    }

    private static PlanetsPayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Planet> planets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            String json = buf.readUtf(MAX_FILE);
            // The server checked the file against its registries already.
            PlanetFormat.Read read =
                    PlanetFormat.read(id, "the server's planet " + id, JsonParser.parseString(json), References.ANY);
            read.planet()
                    .ifPresentOrElse(
                            planets::add,
                            () -> read.problems()
                                    .forEach(p ->
                                            LOGGER.error("The server sent a planet this client can't read: {}", p)));
        }
        return new PlanetsPayload(planets);
    }

    /**
     * Registers the payload.
     *
     * @param event the mod-bus event
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, PlanetsPayload::handle);
    }

    private static void handle(PlanetsPayload payload, IPayloadContext context) {
        PlanetRegistry.setClient(payload.planets());
    }
}
