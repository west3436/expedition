package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.ss13.api.label.Labelled;
import io.github.west3436.ss13.api.label.Labels;
import io.github.west3436.ss13.device.DeviceBlockEntity;
import io.github.west3436.ss13.device.DeviceData;
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
 * <p>Its save format, by version: 1, from before the device base, kept the name under {@code name}
 * ({@link DeviceData#FIRST_FORMAT}); 2 keeps it as the label.
 */
public final class ShuttleComputerBlockEntity extends DeviceBlockEntity implements Labelled {
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

    private Optional<UUID> owner = Optional.empty();
    private String ownerName = "";
    private ShipBox box = ShipBox.NONE;

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
        this.box = box;
        setChanged();
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
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            ShipComputers.track(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ShipComputers.untrack(this);
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
        owner.ifPresent(id -> tag.putUUID("owner", id));
        tag.putString("owner_name", ownerName);
        tag.put("box", box.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? Optional.of(tag.getUUID("owner")) : Optional.empty();
        ownerName = tag.getString("owner_name");
        box = ShipBox.load(tag.getCompound("box"));
    }
}
