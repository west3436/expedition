package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.ss13.api.construction.InstalledParts;
import io.github.west3436.ss13.api.construction.PartHolder;
import io.github.west3436.ss13.api.label.Labelled;
import io.github.west3436.ss13.api.label.Labels;
import io.github.west3436.ss13.device.DeviceBlockEntity;
import io.github.west3436.ss13.device.DeviceData;
import io.github.west3436.zas.api.mutation.SourceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A Shuttle Computer's state: its ship's name, owner and box. It is saved with the block, and moves
 * with it when the ship moves, so the box, being relative to the computer, moves too.
 *
 * <p>It is a device on SS13 Framework's base ({@link DeviceBlockEntity}), which versions its save and
 * keeps its label. The ship's name is the computer's label ({@link Labelled}): saved under {@code
 * Label}, taken from the name of the item that placed it, given back as an item's name, set with a
 * named name tag, and shown as the block's name, as Jade does. Its device step does nothing yet.
 *
 * <p>Its ship has an id of its own ({@link #shipId()}), saved with it, which stays the same when the
 * ship is renamed or moves: its box is an SS13 Framework area with that id ({@link ShipAreas}). A
 * computer saved before it had one is given one when it loads.
 *
 * <p>It is built from SS13 Framework's computer frame with its board, {@code
 * expedition:shuttle_computer_board} ({@link PartHolder}), and keeps that board ({@link
 * #installedParts()}), saved under {@code Parts}: a screwdriver takes its screen off, leaving the frame
 * with the board in it. A computer has no maintenance panel, so {@link #panelOpen()} is always false.
 * One crafted by its plain recipe holds the board as well, from when it is placed.
 *
 * <p>Its save format, by version: 1, from before the device base, kept the name under {@code name}
 * ({@link DeviceData#FIRST_FORMAT}); 2 keeps it as the label.
 */
public final class ShuttleComputerBlockEntity extends DeviceBlockEntity implements Labelled, PartHolder {
    /**
     * The steps that bring an older save forward: version 0, with no version, is read as version 1,
     * and version 1's {@code name} becomes version 2's {@code Label}.
     */
    private static final List<Consumer<CompoundTag>> FORMAT = List.of(DeviceData.FIRST_FORMAT.get(0), tag -> {
        String name = tag.getString("name");
        tag.remove("name");
        if (!name.isEmpty() && !tag.contains(LABEL_KEY)) {
            tag.putString(LABEL_KEY, name);
        }
    });

    private static final String SHIP_ID_KEY = "ship_id";

    private static final String PARTS_KEY = "Parts";

    private UUID shipId = UUID.randomUUID();

    /** Whether the ship's id was made at load, as its save had none, so the chunk must save it. */
    private boolean shipIdMade;

    /** The name the ship's area was last said to have, to tell when a label changes it. */
    private String areaName = "";

    private Optional<UUID> owner = Optional.empty();
    private String ownerName = "";
    private ShipBox box = ShipBox.NONE;
    private InstalledParts parts = InstalledParts.EMPTY;

    /**
     * A computer's block entity.
     *
     * @param pos where it is
     * @param state its block
     */
    public ShuttleComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ExpeditionBlocks.SHUTTLE_COMPUTER_ENTITY.get(), pos, state);
    }

    /**
     * The ship's id: its area's ({@link ShipAreas#areaId}). It stays the same when the ship is renamed
     * or moves. A copy of the computer, such as a creative player picks, gets an id of its own.
     *
     * @return the id
     */
    public UUID shipId() {
        return shipId;
    }

    /**
     * Who pins the ship's cabin air in ZAS³: {@code expedition:ship/<ship id>}. A flight pins every
     * enclosed room of the ship under it as it leaves ({@link
     * io.github.west3436.expedition.mover.ZasRelocationHandover#keeping}), so the cabin's zone goes
     * with the ship and stays, even when its air matches the open air where it lands. The pins come
     * off when the computer is broken.
     *
     * @return the owner
     */
    public SourceId airOwner() {
        return airOwner(shipId);
    }

    /**
     * The owner of a ship's cabin pins ({@link #airOwner()}).
     *
     * @param shipId the ship's id
     * @return the owner
     */
    public static SourceId airOwner(UUID shipId) {
        return SourceId.of(ExpeditionMod.MOD_ID, "ship/" + shipId);
    }

    /**
     * The ship's name: the computer's label.
     *
     * @return the name, empty if it has none
     */
    public String name() {
        return label();
    }

    /**
     * Names the ship: sets the computer's label to what {@link Labels#clean} makes of the name, at
     * most {@link ShipStatus#MAX_NAME} characters, trimmed, with no formatting codes.
     *
     * @param name the name; blank clears it
     */
    public void setName(String name) {
        setLabel(name);
    }

    /**
     * The player who placed the computer.
     *
     * @return their id, or empty if unknown
     */
    public Optional<UUID> owner() {
        return owner;
    }

    /**
     * The owner's name when they placed it.
     *
     * @return the name, empty if unknown
     */
    public String ownerName() {
        return ownerName;
    }

    /**
     * Sets the owner.
     *
     * @param id their id
     * @param name their name
     */
    public void setOwner(UUID id, String name) {
        this.owner = Optional.of(id);
        this.ownerName = name;
        setChanged();
    }

    /**
     * The ship's box, as distances from the computer.
     *
     * @return the box
     */
    public ShipBox box() {
        return box;
    }

    /**
     * Sets the ship's box.
     *
     * @param box the box
     */
    public void setBox(ShipBox box) {
        BoundingBox before = worldBox();
        this.box = box;
        setChanged();
        BoundingBox after = worldBox();
        if (!after.equals(before)) {
            ShipAreas.changed(this, before);
            ShipAreas.changed(this, after);
        }
    }

    /**
     * The board in the computer, which a computer frame put in, or placing it did.
     *
     * @return the board, {@link InstalledParts#EMPTY} if none has been installed
     */
    @Override
    public InstalledParts installedParts() {
        return parts;
    }

    /**
     * Replaces the board in the computer, as SS13 Framework builds or opens it.
     *
     * @param parts the board, and no parts: a computer takes none
     */
    @Override
    public void installParts(InstalledParts parts) {
        this.parts = parts;
        setChanged();
    }

    /**
     * A computer has no maintenance panel.
     *
     * @return false
     */
    @Override
    public boolean panelOpen() {
        return false;
    }

    /**
     * Does nothing: a computer has no maintenance panel to open.
     *
     * @param open ignored
     */
    @Override
    public void setPanelOpen(boolean open) {}

    /** Also says the ship's area was renamed, when a label changed its name. */
    @Override
    public void setChanged() {
        super.setChanged();
        if (!areaName.equals(name())) {
            areaName = name();
            ShipAreas.changed(this, worldBox());
        }
    }

    /**
     * The way the computer's screen faces.
     *
     * @return a horizontal direction
     */
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(HorizontalDirectionalBlock.FACING)
                ? state.getValue(HorizontalDirectionalBlock.FACING)
                : Direction.NORTH;
    }

    /**
     * The ship's box in the world.
     *
     * @return the box
     */
    public BoundingBox worldBox() {
        return box.toWorld(getBlockPos(), facing());
    }

    // Tracked as soon as it joins a level, rather than at onLoad, which NeoForge defers to the next
    // tick for a block entity a block placement makes.
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide) {
            ShipComputers.track(this);
            areaName = name();
            ShipAreas.changed(this, worldBox());
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            ShipComputers.track(this);
            ShipAreas.changed(this, worldBox());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ShipComputers.untrack(this);
        ShipAreas.changed(this, worldBox());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (shipIdMade && level != null && !level.isClientSide) {
            shipIdMade = false;
            setChanged();
        }
    }

    /** A copy of the computer as an item, as a creative player picks, makes a ship of its own. */
    @Override
    @SuppressWarnings("deprecation")
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove(SHIP_ID_KEY);
    }

    @Override
    protected List<Consumer<CompoundTag>> formatSteps() {
        return FORMAT;
    }

    /** Nothing yet: the computer's work is done when a pilot asks for it. */
    @Override
    protected void deviceTick(ServerLevel level) {}

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID(SHIP_ID_KEY, shipId);
        owner.ifPresent(id -> tag.putUUID("owner", id));
        tag.putString("owner_name", ownerName);
        tag.put("box", box.save());
        if (!parts.isEmpty()) {
            tag.put(PARTS_KEY, parts.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID(SHIP_ID_KEY)) {
            shipId = tag.getUUID(SHIP_ID_KEY);
        } else {
            shipIdMade = true;
        }
        owner = tag.hasUUID("owner") ? Optional.of(tag.getUUID("owner")) : Optional.empty();
        ownerName = tag.getString("owner_name");
        box = ShipBox.load(tag.getCompound("box"));
        parts = InstalledParts.load(tag.get(PARTS_KEY), registries);
    }
}
