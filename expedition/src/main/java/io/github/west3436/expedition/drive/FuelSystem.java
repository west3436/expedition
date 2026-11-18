package io.github.west3436.expedition.drive;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How one kind of fuel ({@link Drive#fuel()}) is measured and burned across a ship: the drives of
 * that fuel may share their supplies, such as two thrusters beside one tank, so the fuel is a matter
 * for the ship as a whole, not for each drive. A kind with no system registered measures 0.
 *
 * <p>Thread: server thread.
 */
public interface FuelSystem {
    /**
     * How much of this fuel a ship's drives can draw on, each supply counted once.
     *
     * @param level the ship's level
     * @param drives where the drives of this fuel that would fire are
     * @return the amount, in the fuel's units, at least 0
     */
    double measure(ServerLevel level, List<BlockPos> drives);

    /**
     * Burns fuel for a departure: takes it from the supplies, and lets the drives' exhaust out.
     *
     * @param level the ship's level
     * @param drives where the drives of this fuel that fire are
     * @param amount how much to burn, at most what {@link #measure} gave
     */
    void burn(ServerLevel level, List<BlockPos> drives, double amount);

    /** The fuel systems, by fuel. */
    final class Registry {
        private static final Map<String, FuelSystem> SYSTEMS = new ConcurrentHashMap<>();

        private Registry() {}

        /**
         * Registers a fuel's system, replacing any before it.
         *
         * @param fuel the fuel
         * @param system its system
         */
        public static void register(String fuel, FuelSystem system) {
            SYSTEMS.put(Objects.requireNonNull(fuel, "fuel"), Objects.requireNonNull(system, "system"));
        }

        /**
         * A fuel's system.
         *
         * @param fuel the fuel
         * @return its system, or one that measures 0 and burns nothing if none is registered
         */
        public static FuelSystem get(String fuel) {
            return SYSTEMS.getOrDefault(fuel, NONE);
        }

        private static final FuelSystem NONE = new FuelSystem() {
            @Override
            public double measure(ServerLevel level, List<BlockPos> drives) {
                return 0;
            }

            @Override
            public void burn(ServerLevel level, List<BlockPos> drives, double amount) {}
        };
    }
}
