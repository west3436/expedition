package io.github.west3436.expedition.transit;

import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * One ship's trip through the transit dimension, from {@link Transits#depart} until it arrives.
 *
 * <p>A trip holds its slot, the box the ship fills in it, its route, and who is aboard: the players
 * the departure carried into the slot. Once it {@linkplain #inTransit() is in transit} it is saved
 * with the world, so it survives a restart, and it arrives at the time {@link Transits#arrive}
 * gives whether anyone is aboard or not.
 *
 * <p>Thread: server thread.
 */
public final class TransitTrip {
    /** Where a trip is going, and when it gets there. */
    record Arrival(ResourceKey<Level> to, BlockPos origin, long at) {}

    enum Status {
        /** Its slot is taken and the departure move hasn't finished; never saved. */
        DEPARTING,
        /** The ship is in its slot. */
        IN_TRANSIT,
        /** The arrival move is under way. */
        ARRIVING,
        /** The ship has left the slot, which is free again. */
        ARRIVED
    }

    private final UUID id;
    private final int slot;
    private final int pitch;
    private final BoundingBox box;
    private final TransitRoute route;
    private final Set<UUID> aboard = new LinkedHashSet<>();

    Status status;

    @Nullable
    Arrival arrival;

    /** The departure's result; complete once the ship is in its slot, or refused. */
    final CompletableFuture<MoveResult> departure = new CompletableFuture<>();

    /** The arrival's result, for the arrival scheduled now; replaced when a new one is scheduled. */
    CompletableFuture<MoveResult> arrived = new CompletableFuture<>();

    /** The game time before which a refused move isn't tried again. */
    long retryAt;

    /** Whether the trip holds chunks loaded now. */
    boolean holding;

    /** A chunk a trip holds loaded. */
    record Held(ResourceKey<Level> level, ChunkPos chunk) {}

    /** The chunks the trip holds loaded now. */
    final List<Held> held = new ArrayList<>();

    /** The server whose chunks it holds. */
    @Nullable
    MinecraftServer server;

    /** How many times the move now due has been refused for something that passes. */
    int attempts;

    /** The move under way, if any. */
    @Nullable
    MoveJob job;

    /** Where the box is while departing; null once it is in transit. */
    @Nullable
    ServerLevel from;

    /** The box while departing, where it is; null once it is in transit. */
    @Nullable
    BoundingBox fromBox;

    TransitTrip(UUID id, int slot, int pitch, BoundingBox box, TransitRoute route, Status status) {
        this.id = id;
        this.slot = slot;
        this.pitch = pitch;
        this.box = box;
        this.route = route;
        this.status = status;
    }

    /**
     * The trip's id, unique on the server.
     *
     * @return the id
     */
    public UUID id() {
        return id;
    }

    /**
     * The slot the ship has in the transit dimension.
     *
     * @return the slot's index, from 0
     */
    public int slot() {
        return slot;
    }

    /**
     * The grid's pitch when the slot was taken.
     *
     * @return blocks from one slot to the next
     */
    public int pitch() {
        return pitch;
    }

    /**
     * The slot's origin.
     *
     * @return its lowest corner's X and Z, at {@link TransitSlots#FLOOR_Y}
     */
    public BlockPos slotOrigin() {
        return TransitSlots.origin(slot, pitch);
    }

    /**
     * The box the ship fills in its slot, in the transit dimension.
     *
     * @return the box
     */
    public BoundingBox box() {
        return box;
    }

    /**
     * The route the ship flies.
     *
     * @return the route
     */
    public TransitRoute route() {
        return route;
    }

    /**
     * The players aboard: those the departure carried, less any who have since stepped outside.
     *
     * @return their ids, unmodifiable
     */
    public Set<UUID> aboard() {
        return Collections.unmodifiableSet(aboard);
    }

    Set<UUID> aboardMutable() {
        return aboard;
    }

    /**
     * How the departure went: it completes once the ship is in its slot, or once the move into it is
     * refused, in which case the slot is free again and the ship never left.
     *
     * @return the future, the same one every time
     */
    public CompletableFuture<MoveResult> departure() {
        return departure;
    }

    /**
     * Whether the ship is in its slot now.
     *
     * @return true from the end of the departure until the arrival move starts
     */
    public boolean inTransit() {
        return status == Status.IN_TRANSIT;
    }

    /**
     * Whether the trip is over: the ship has left its slot for its destination.
     *
     * @return true once it arrived
     */
    public boolean hasArrived() {
        return status == Status.ARRIVED;
    }

    /**
     * When the trip arrives, if an arrival is scheduled.
     *
     * @return the game time, or empty
     */
    public Optional<Long> arrivesAt() {
        return arrival == null ? Optional.empty() : Optional.of(arrival.at());
    }

    /**
     * Where the trip is going, if an arrival is scheduled.
     *
     * @return the destination's dimension, or empty
     */
    public Optional<ResourceKey<Level>> destination() {
        return arrival == null ? Optional.empty() : Optional.of(arrival.to());
    }

    // ---- Saving ---------------------------------------------------------------------------

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putInt("slot", slot);
        tag.putInt("pitch", pitch);
        tag.putIntArray("box", new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
        CompoundTag r = new CompoundTag();
        r.putDouble("from_x", route.fromX());
        r.putDouble("from_z", route.fromZ());
        r.putDouble("to_x", route.toX());
        r.putDouble("to_z", route.toZ());
        r.putLong("departs", route.departs());
        r.putLong("arrives", route.arrives());
        tag.put("route", r);
        ListTag players = new ListTag();
        for (UUID player : aboard) {
            players.add(NbtUtils.createUUID(player));
        }
        tag.put("aboard", players);
        if (arrival != null) {
            CompoundTag a = new CompoundTag();
            a.putString("to", arrival.to().location().toString());
            a.put("origin", NbtUtils.writeBlockPos(arrival.origin()));
            a.putLong("at", arrival.at());
            tag.put("arrival", a);
        }
        return tag;
    }

    @Nullable
    static TransitTrip load(CompoundTag tag) {
        int[] b = tag.getIntArray("box");
        if (b.length != 6 || !tag.hasUUID("id")) {
            return null;
        }
        CompoundTag r = tag.getCompound("route");
        TransitTrip trip = new TransitTrip(
                tag.getUUID("id"),
                tag.getInt("slot"),
                tag.getInt("pitch"),
                new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]),
                new TransitRoute(
                        r.getDouble("from_x"),
                        r.getDouble("from_z"),
                        r.getDouble("to_x"),
                        r.getDouble("to_z"),
                        r.getLong("departs"),
                        Math.max(r.getLong("departs"), r.getLong("arrives"))),
                Status.IN_TRANSIT);
        for (Tag player : tag.getList("aboard", Tag.TAG_INT_ARRAY)) {
            trip.aboard.add(NbtUtils.loadUUID(player));
        }
        if (tag.contains("arrival", Tag.TAG_COMPOUND)) {
            CompoundTag a = tag.getCompound("arrival");
            ResourceLocation to = ResourceLocation.tryParse(a.getString("to"));
            BlockPos origin = NbtUtils.readBlockPos(a, "origin").orElse(null);
            if (to != null && origin != null) {
                trip.arrival = new Arrival(ResourceKey.create(Registries.DIMENSION, to), origin, a.getLong("at"));
            }
        }
        return trip;
    }

    @Override
    public String toString() {
        return "TransitTrip[" + id + ", slot " + slot + ", " + status + ", " + box + "]";
    }
}
