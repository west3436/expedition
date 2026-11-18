package io.github.west3436.expedition.flight;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Where a pilot asks a ship to go: a planet, a column of its surface, and whether to land there or
 * stay in orbit above it, at a cruise speed of the pilot's or as fast as the drives allow.
 *
 * @param planet the planet's id
 * @param x the column's X on the planet's surface, where the middle of the ship's box goes
 * @param z the column's Z
 * @param orbit true to stay in Space above the column, false to land on the ground there
 * @param cruiseSpeed the fastest to go, in blocks per second; 0 or less, or not finite, for as fast
 *     as the drives allow
 */
public record FlightOrder(ResourceLocation planet, int x, int z, boolean orbit, double cruiseSpeed) {
    /** How an order goes over the network. */
    public static final StreamCodec<ByteBuf, FlightOrder> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC,
            FlightOrder::planet,
            ByteBufCodecs.VAR_INT,
            FlightOrder::x,
            ByteBufCodecs.VAR_INT,
            FlightOrder::z,
            ByteBufCodecs.BOOL,
            FlightOrder::orbit,
            ByteBufCodecs.DOUBLE,
            FlightOrder::cruiseSpeed,
            FlightOrder::new);

    /**
     * Checks the planet.
     *
     * @throws NullPointerException if it is null
     */
    public FlightOrder {
        Objects.requireNonNull(planet, "planet");
    }

    /**
     * A landing as fast as the drives allow.
     *
     * @param planet the planet's id
     * @param x the column's X
     * @param z the column's Z
     * @return the order
     */
    public static FlightOrder land(ResourceLocation planet, int x, int z) {
        return new FlightOrder(planet, x, z, false, 0);
    }

    /**
     * Orbit above a column, as fast as the drives allow.
     *
     * @param planet the planet's id
     * @param x the column's X
     * @param z the column's Z
     * @return the order
     */
    public static FlightOrder orbit(ResourceLocation planet, int x, int z) {
        return new FlightOrder(planet, x, z, true, 0);
    }

    /**
     * Whether the pilot chose a speed.
     *
     * @return true if {@link #cruiseSpeed} is above 0 and finite
     */
    public boolean hasCruiseSpeed() {
        return cruiseSpeed > 0 && Double.isFinite(cruiseSpeed);
    }
}
