package io.github.west3436.expedition.flight;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What flights keep across restarts, in the Overworld's {@code data/expedition_flights.dat}: the
 * transit trips that are flights (#29), with their pilots, so a flight whose landing is refused
 * goes into orbit instead even if the server restarted while it was in transit. The trip itself,
 * with its arrival, is transit's to keep ({@code expedition_transit.dat}).
 */
final class FlightData extends SavedData {
    /** The file's name. */
    static final String NAME = "expedition_flights";

    /**
     * One flight in transit.
     *
     * @param pilot who launched it, if a player did
     * @param fallbacks how many arrivals in orbit have been scheduled since its landing was refused
     */
    record Entry(Optional<UUID> pilot, int fallbacks) {}

    /** The flights, by their transit trip's id. */
    final Map<UUID, Entry> flights = new LinkedHashMap<>();

    FlightData() {}

    static SavedData.Factory<FlightData> factory() {
        return new SavedData.Factory<>(FlightData::new, FlightData::load, null);
    }

    static FlightData load(CompoundTag tag, HolderLookup.Provider registries) {
        FlightData data = new FlightData();
        for (Tag t : tag.getList("flights", Tag.TAG_COMPOUND)) {
            CompoundTag f = (CompoundTag) t;
            if (f.hasUUID("trip")) {
                Optional<UUID> pilot = f.hasUUID("pilot") ? Optional.of(f.getUUID("pilot")) : Optional.empty();
                data.flights.put(f.getUUID("trip"), new Entry(pilot, f.getInt("fallbacks")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> e : flights.entrySet()) {
            CompoundTag f = new CompoundTag();
            f.putUUID("trip", e.getKey());
            e.getValue().pilot().ifPresent(p -> f.putUUID("pilot", p));
            f.putInt("fallbacks", e.getValue().fallbacks());
            list.add(f);
        }
        tag.put("flights", list);
        return tag;
    }
}
