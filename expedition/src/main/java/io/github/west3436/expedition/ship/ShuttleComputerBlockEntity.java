package io.github.west3436.expedition.ship;

import io.github.west3436.expedition.registry.ExpeditionBlocks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A Shuttle Computer's state: its ship's name, owner and box. It is saved with the block, and moves
 * with it when the ship moves, so the box, being relative to the computer, moves too.
 */
public final class ShuttleComputerBlockEntity extends BlockEntity {
    private String name = "";
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
     * The ship's name.
     *
     * @return the name, empty if it has none
     */
    public String name() {
        return name;
    }

    /**
     * Names the ship.
     *
     * @param name the name, cut to {@link ShipStatus#MAX_NAME} characters and trimmed
     */
    public void setName(String name) {
        String trimmed = name.strip();
        this.name = trimmed.length() > ShipStatus.MAX_NAME ? trimmed.substring(0, ShipStatus.MAX_NAME) : trimmed;
        setChanged();
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
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!name.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        Component custom = input.get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            setName(custom.getString());
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("name");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("name", name);
        owner.ifPresent(id -> tag.putUUID("owner", id));
        tag.putString("owner_name", ownerName);
        tag.put("box", box.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        name = tag.getString("name");
        owner = tag.hasUUID("owner") ? Optional.of(tag.getUUID("owner")) : Optional.empty();
        ownerName = tag.getString("owner_name");
        box = ShipBox.load(tag.getCompound("box"));
    }
}
