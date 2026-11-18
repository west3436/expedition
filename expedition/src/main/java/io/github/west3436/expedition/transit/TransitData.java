package io.github.west3436.expedition.transit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What the transit dimension keeps across restarts, in the Overworld's {@code
 * data/expedition_transit.dat}: every trip in transit, and the players to put back aboard their ship
 * when they next log in, because it arrived while they were away.
 *
 * <p>A trip still departing isn't saved: if the server stops before its ship is in the slot, the
 * mover finishes or refuses the move as it stops, and a refused departure frees its slot.
 */
public final class TransitData extends SavedData {
    /** The file's name. */
    public static final String NAME = "expedition_transit";

    /**
     * Where a player who was aboard goes when they next log in: the ship's destination, moved by
     * the arrival's offset from where they are in the slot.
     *
     * @param to the level the ship arrived in
     * @param offset how far the arrival moved the ship
     */
    record Return(ResourceKey<Level> to, Vec3i offset) {}

    final Map<UUID, TransitTrip> trips = new LinkedHashMap<>();
    final Map<UUID, Return> returns = new LinkedHashMap<>();

    TransitData() {}

    /**
     * The factory the data storage reads and makes it with.
     *
     * @return the factory
     */
    static SavedData.Factory<TransitData> factory() {
        return new SavedData.Factory<>(TransitData::new, TransitData::load, null);
    }

    static TransitData load(CompoundTag tag, HolderLookup.Provider registries) {
        TransitData data = new TransitData();
        for (Tag t : tag.getList("trips", Tag.TAG_COMPOUND)) {
            TransitTrip trip = TransitTrip.load((CompoundTag) t);
            if (trip != null) {
                data.trips.put(trip.id(), trip);
            }
        }
        for (Tag t : tag.getList("returns", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) t;
            ResourceLocation to = ResourceLocation.tryParse(r.getString("to"));
            int[] o = r.getIntArray("offset");
            if (r.hasUUID("player") && to != null && o.length == 3) {
                data.returns.put(
                        r.getUUID("player"),
                        new Return(ResourceKey.create(Registries.DIMENSION, to), new Vec3i(o[0], o[1], o[2])));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag trips = new ListTag();
        for (TransitTrip trip : this.trips.values()) {
            if (trip.status != TransitTrip.Status.DEPARTING && trip.status != TransitTrip.Status.ARRIVED) {
                trips.add(trip.save());
            }
        }
        tag.put("trips", trips);
        ListTag returns = new ListTag();
        for (Map.Entry<UUID, Return> e : this.returns.entrySet()) {
            CompoundTag r = new CompoundTag();
            r.putUUID("player", e.getKey());
            r.putString("to", e.getValue().to().location().toString());
            Vec3i o = e.getValue().offset();
            r.putIntArray("offset", new int[] {o.getX(), o.getY(), o.getZ()});
            returns.add(r);
        }
        tag.put("returns", returns);
        return tag;
    }
}
