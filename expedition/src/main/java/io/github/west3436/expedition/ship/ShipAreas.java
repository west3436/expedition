package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.ss13.api.area.Area;
import io.github.west3436.ss13.api.area.Areas;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Each ship's box as an SS13 Framework area: a {@link Areas#REGION} that the rooms drawn inside it
 * win over, so a device in a ship's room is in that room, and a device anywhere else in the box is in
 * the ship. A mod that wants the ship a room is on finds it under the room in {@link Areas#claims}.
 *
 * <p>A ship's area has the id {@code expedition:ship/<ship id>} ({@link #areaId}), which stays the
 * same when the ship is renamed or moves, as the id is saved with its Shuttle Computer ({@link
 * ShuttleComputerBlockEntity#shipId()}). Its name is the ship's, or {@value #UNNAMED} for a ship
 * with none.
 *
 * <p>The answer comes from the computers loaded in each level ({@link ShipComputers}), on the server
 * only; on a client no position is in a ship's area. The computer says when the area at some blocks
 * may have changed ({@link Areas#changed}): when it is placed, loaded, removed or unloaded, which is
 * also how a move carries the area, and when its box or name changes.
 */
public final class ShipAreas {
    /** {@code expedition:ships}: the id the provider is registered under. */
    public static final ResourceLocation PROVIDER =
            ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "ships");

    /** The name of the area of a ship that has none. */
    public static final String UNNAMED = "Unnamed ship";

    private ShipAreas() {}

    /** Registers the provider. Called once, from the mod's constructor. */
    public static void register() {
        Areas.register(PROVIDER, Areas.REGION, ShipAreas::at);
    }

    /**
     * The id of a ship's area.
     *
     * @param shipId the ship's id
     * @return {@code expedition:ship/<ship id>}
     */
    public static ResourceLocation areaId(UUID shipId) {
        return ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "ship/" + shipId);
    }

    /**
     * A ship's area.
     *
     * @param computer the ship's computer
     * @return the area
     */
    public static Area area(ShuttleComputerBlockEntity computer) {
        String name = computer.name();
        return new Area(areaId(computer.shipId()), name.isBlank() ? UNNAMED : name);
    }

    /** The provider's answer: the area of the ship whose box holds the position. */
    private static Optional<Area> at(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return Optional.empty();
        }
        return ShipComputers.holding(level, pos).map(ShipAreas::area);
    }

    /** Says the area at some of a computer's blocks may have changed, if it is in a server level. */
    static void changed(ShuttleComputerBlockEntity computer, BoundingBox bounds) {
        if (computer.getLevel() instanceof ServerLevel level) {
            Areas.changed(level, bounds);
        }
    }
}
