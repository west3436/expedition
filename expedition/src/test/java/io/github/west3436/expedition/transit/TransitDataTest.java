package io.github.west3436.expedition.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** What the transit dimension saves: trips in transit with who is aboard, and pending returns. */
class TransitDataTest {
    private static final ResourceKey<Level> MOON =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("expedition", "moon"));

    @Test
    void tripsInTransitAndReturnsSurviveARestart() {
        TransitData data = new TransitData();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        TransitTrip flying = trip(3, TransitTrip.Status.IN_TRANSIT);
        flying.aboardMutable().add(alice);
        flying.aboardMutable().add(bob);
        flying.arrival = new TransitTrip.Arrival(MOON, new BlockPos(10, 70, -20), 12_345L);
        TransitTrip waiting = trip(4, TransitTrip.Status.IN_TRANSIT);
        TransitTrip departing = trip(5, TransitTrip.Status.DEPARTING);
        TransitTrip arrived = trip(6, TransitTrip.Status.ARRIVED);
        for (TransitTrip t : List.of(flying, waiting, departing, arrived)) {
            data.trips.put(t.id(), t);
        }
        UUID carol = UUID.randomUUID();
        data.returns.put(carol, new TransitData.Return(MOON, new Vec3i(-5120, 64, 7)));

        TransitData back = TransitData.load(data.save(new CompoundTag(), null), null);

        assertEquals(List.of(flying.id(), waiting.id()), List.copyOf(back.trips.keySet()), "only trips in transit");
        TransitTrip f = back.trips.get(flying.id());
        assertEquals(3, f.slot());
        assertEquals(flying.pitch(), f.pitch());
        assertEquals(flying.box(), f.box());
        assertEquals(flying.route(), f.route());
        assertEquals(List.of(alice, bob), List.copyOf(f.aboard()));
        assertTrue(f.inTransit());
        assertEquals(Optional.of(12_345L), f.arrivesAt());
        assertEquals(Optional.of(MOON), f.destination());
        assertEquals(flying.arrival, f.arrival);
        TransitTrip w = back.trips.get(waiting.id());
        assertTrue(w.aboard().isEmpty());
        assertEquals(Optional.empty(), w.arrivesAt());
        assertEquals(1, back.returns.size());
        assertEquals(new TransitData.Return(MOON, new Vec3i(-5120, 64, 7)), back.returns.get(carol));
    }

    @Test
    void aBrokenTripIsDropped() {
        CompoundTag tag = new CompoundTag();
        ListTag trips = new ListTag();
        CompoundTag broken = trip(0, TransitTrip.Status.IN_TRANSIT).save();
        broken.putIntArray("box", new int[] {1, 2, 3});
        trips.add(broken);
        tag.put("trips", trips);
        assertTrue(TransitData.load(tag, null).trips.isEmpty());
    }

    private static TransitTrip trip(int slot, TransitTrip.Status status) {
        int pitch = TransitSlots.pitch(64);
        BlockPos o = TransitSlots.origin(slot, pitch);
        return new TransitTrip(
                UUID.randomUUID(),
                slot,
                pitch,
                new BoundingBox(o.getX(), 0, o.getZ(), o.getX() + 4, 3, o.getZ() + 4),
                new TransitRoute(0, 0, 0, -7120, 100, 2500),
                status);
    }
}
