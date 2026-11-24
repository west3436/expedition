package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.drive.ThrustModel;
import io.github.west3436.ss13.api.label.Labels;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What a Shuttle Computer's window shows of its ship, worked out on the server: a snapshot, sent
 * when the window opens and after each change.
 *
 * @param name the ship's name; empty if it has none
 * @param owner the name of the player who placed the computer; empty if unknown
 * @param box the box, as distances from the computer
 * @param world the box in the world
 * @param mass the mass of the blocks in the box ({@link ShipMass})
 * @param terrain how many blocks of natural terrain the box holds ({@link ShipComputers#TERRAIN})
 * @param thrust the thrust of the drives in the box whose nozzles are open
 * @param drives how many such drives there are
 * @param fuel how much of each fuel those drives can draw on
 * @param gravity the gravity where the ship is, a multiple of the Overworld's
 * @param overlap the name or position of another ship whose box overlaps this one, if any
 * @param tooLarge whether the box is over the server's ceiling on a ship's box
 */
public record ShipStatus(
        String name,
        String owner,
        ShipBox box,
        BoundingBox world,
        double mass,
        int terrain,
        double thrust,
        int drives,
        Map<String, Double> fuel,
        double gravity,
        Optional<String> overlap,
        boolean tooLarge) {
    /** The longest name a ship may have: a label's ({@link Labels#MAX_LENGTH}), as the name is the computer's. */
    public static final int MAX_NAME = Labels.MAX_LENGTH;

    /** How a status goes over the network. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ShipStatus> STREAM_CODEC =
            StreamCodec.of(ShipStatus::write, ShipStatus::read);

    /** Copies the map. */
    public ShipStatus {
        fuel = Map.copyOf(fuel);
    }

    /**
     * Thrust-to-weight where the ship is.
     *
     * @return the ratio, positive infinity with no weight
     */
    public double ratio() {
        return ThrustModel.ratio(thrust, mass, gravity);
    }

    /**
     * The fuel, sorted by kind.
     *
     * @return the fuel
     */
    public Map<String, Double> sortedFuel() {
        return new TreeMap<>(fuel);
    }

    private static void write(RegistryFriendlyByteBuf buf, ShipStatus s) {
        buf.writeUtf(s.name, MAX_NAME);
        buf.writeUtf(s.owner, 64);
        ShipBox.STREAM_CODEC.encode(buf, s.box);
        buf.writeBlockPos(new BlockPos(s.world.minX(), s.world.minY(), s.world.minZ()));
        buf.writeBlockPos(new BlockPos(s.world.maxX(), s.world.maxY(), s.world.maxZ()));
        buf.writeDouble(s.mass);
        buf.writeVarInt(s.terrain);
        buf.writeDouble(s.thrust);
        buf.writeVarInt(s.drives);
        buf.writeVarInt(s.fuel.size());
        s.fuel.forEach((kind, amount) -> {
            buf.writeUtf(kind, 256);
            buf.writeDouble(amount);
        });
        buf.writeDouble(s.gravity);
        buf.writeOptional(s.overlap, (b, o) -> b.writeUtf(o, 256));
        buf.writeBoolean(s.tooLarge);
    }

    private static ShipStatus read(RegistryFriendlyByteBuf buf) {
        String name = buf.readUtf(MAX_NAME);
        String owner = buf.readUtf(64);
        ShipBox box = ShipBox.STREAM_CODEC.decode(buf);
        BoundingBox world = BoundingBox.fromCorners(buf.readBlockPos(), buf.readBlockPos());
        double mass = buf.readDouble();
        int terrain = buf.readVarInt();
        double thrust = buf.readDouble();
        int drives = buf.readVarInt();
        int kinds = buf.readVarInt();
        Map<String, Double> fuel = new TreeMap<>();
        for (int i = 0; i < kinds; i++) {
            fuel.put(buf.readUtf(256), buf.readDouble());
        }
        double gravity = buf.readDouble();
        Optional<String> overlap = buf.readOptional(b -> b.readUtf(256));
        boolean tooLarge = buf.readBoolean();
        return new ShipStatus(name, owner, box, world, mass, terrain, thrust, drives, fuel, gravity, overlap, tooLarge);
    }
}
