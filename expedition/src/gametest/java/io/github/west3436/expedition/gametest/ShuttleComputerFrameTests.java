package io.github.west3436.expedition.gametest;

import com.mojang.authlib.GameProfile;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShipStatus;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import io.github.west3436.ss13.api.construction.InstalledParts;
import io.github.west3436.ss13.api.construction.MachineBoard;
import io.github.west3436.ss13.api.construction.MachineBoards;
import io.github.west3436.ss13.construction.ComputerFrameBlock;
import io.github.west3436.ss13.construction.FrameBlock;
import io.github.west3436.ss13.construction.FrameBlockEntity;
import io.github.west3436.ss13.construction.FrameStage;
import io.github.west3436.ss13.registry.FrameworkBlocks;
import io.github.west3436.ss13.registry.FrameworkComponents;
import io.github.west3436.ss13.registry.FrameworkItems;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Shuttle Computer built from SS13 Framework's computer frame and its board (#50's Phase B gate,
 * "a Shuttle Computer is built from a frame and a board"), played through as a player would: the
 * framework's tools and parts used on the frame through the server's own use path. The plain recipe
 * still makes one, which holds its board too.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShuttleComputerFrameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);

    private ShuttleComputerFrameTests() {}

    /** The board builds the Shuttle Computer in a computer frame, and nothing in a machine frame. */
    @GameTest(template = "empty")
    public static void the_board_builds_a_shuttle_computer(GameTestHelper h) {
        Optional<MachineBoard> board = MachineBoards.of(board());
        check(board.isPresent(), "the Shuttle Computer Board isn't a board");
        check(
                board.get().equals(MachineBoard.computer(ExpeditionBlocks.SHUTTLE_COMPUTER.getId())),
                "the board builds " + board.get());
        check(
                MachineBoards.boardFor(ExpeditionBlocks.SHUTTLE_COMPUTER.getId())
                        .equals(Optional.of(ExpeditionBlocks.SHUTTLE_COMPUTER_BOARD.getId())),
                "the Shuttle Computer's board isn't found from the computer");
        h.succeed();
    }

    /**
     * The gate: a computer frame, anchored with a wrench, takes the board, a screwdriver, cable and a
     * console screen, and a screwdriver makes it a working Shuttle Computer facing the frame's way and
     * holding its board. A screwdriver takes the screen off again, leaving the frame with the board.
     */
    @GameTest(template = "empty")
    public static void a_shuttle_computer_is_built_from_a_frame_and_a_board(GameTestHelper h) {
        h.setBlock(
                AT,
                FrameworkBlocks.COMPUTER_FRAME.get().defaultBlockState().setValue(FrameBlock.FACING, Direction.SOUTH));
        FakePlayer player = builder(h);

        use(h, player, FrameworkItems.WRENCH.toStack());
        check(h.getBlockState(AT).getValue(FrameBlock.ANCHORED), "the wrench didn't anchor the frame");
        use(h, player, board());
        assertStage(h, FrameStage.BOARD, "the frame didn't take the Shuttle Computer Board");
        use(h, player, FrameworkItems.SCREWDRIVER.toStack());
        assertStage(h, FrameStage.SECURED, "the screwdriver didn't secure the board");
        use(h, player, FrameworkItems.CABLE_COIL.toStack());
        assertStage(h, FrameStage.WIRED, "the cable didn't wire the frame");
        use(h, player, FrameworkItems.CONSOLE_SCREEN.toStack());
        assertStage(h, FrameStage.SCREEN, "the frame didn't take its screen");
        use(h, player, FrameworkItems.SCREWDRIVER.toStack());

        h.assertBlockState(
                AT,
                s -> s.is(ExpeditionBlocks.SHUTTLE_COMPUTER.get())
                        && s.getValue(HorizontalDirectionalBlock.FACING) == Direction.SOUTH,
                () -> "the frame didn't become a Shuttle Computer facing south, but " + h.getBlockState(AT));
        ShuttleComputerBlockEntity computer = computer(h);
        check(computer.installedParts().board().is(ExpeditionBlocks.SHUTTLE_COMPUTER_BOARD.get()), "no board");
        check(computer.installedParts().parts().isEmpty(), "the computer holds parts");
        check(!computer.panelOpen(), "the computer's panel is open");
        // A working computer: its ship, alone, is the computer, which weighs 1.
        ShipStatus status = ShipComputers.status(h.getLevel(), computer);
        check(status.mass() == 1, "the built computer's ship weighs " + status.mass());
        // No one placed it, so the first player to use it owns it, and may launch it.
        check(computer.owner().isEmpty(), "a built computer has an owner before anyone uses it");
        use(h, player, ItemStack.EMPTY);
        check(computer.owner().equals(Optional.of(player.getUUID())), "the first user doesn't own the computer");
        FakePlayer other = builder(h);
        use(h, other, ItemStack.EMPTY);
        check(computer.owner().equals(Optional.of(player.getUUID())), "a second user took the computer");

        use(h, player, FrameworkItems.CROWBAR.toStack());
        h.assertBlockState(
                AT, s -> s.is(ExpeditionBlocks.SHUTTLE_COMPUTER.get()), () -> "a crowbar opened the computer");
        use(h, player, FrameworkItems.SCREWDRIVER.toStack());
        assertStage(h, FrameStage.SCREEN, "the screwdriver didn't take the screen off");
        check(
                frame(h).contents().board().is(ExpeditionBlocks.SHUTTLE_COMPUTER_BOARD.get()),
                "the frame lost the board");
        use(h, player, FrameworkItems.SCREWDRIVER.toStack());
        h.assertBlockState(
                AT, s -> s.is(ExpeditionBlocks.SHUTTLE_COMPUTER.get()), () -> "the frame didn't close again");
        h.succeed();
    }

    /** A board for something else is refused, and the frame stays empty. */
    @GameTest(template = "empty")
    public static void a_blank_board_builds_nothing(GameTestHelper h) {
        h.setBlock(AT, FrameworkBlocks.COMPUTER_FRAME.get());
        FakePlayer player = builder(h);
        use(h, player, FrameworkItems.WRENCH.toStack());
        use(h, player, FrameworkItems.CIRCUIT_BOARD.toStack());
        assertStage(h, FrameStage.EMPTY, "the frame took a blank circuit board");
        h.succeed();
    }

    /**
     * The plain recipe's computer holds its board from when it is placed, keeps it through a save, and
     * mined, drops a plain Shuttle Computer that stacks with crafted ones.
     */
    @GameTest(template = "empty")
    public static void a_crafted_computer_holds_its_board(GameTestHelper h) {
        FakePlayer player = builder(h);
        h.setBlock(AT.below(), Blocks.STONE);
        h.setBlock(AT, Blocks.AIR);
        BlockPos below = h.absolutePos(AT.below());
        ItemStack item = new ItemStack(ExpeditionBlocks.SHUTTLE_COMPUTER_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        var result = player.gameMode.useItemOn(
                player,
                h.getLevel(),
                item,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
        h.assertBlockState(
                AT,
                s -> s.is(ExpeditionBlocks.SHUTTLE_COMPUTER.get()),
                () -> "placing gave " + result + " and left " + h.getBlockState(AT));
        ShuttleComputerBlockEntity computer = computer(h);
        check(computer.owner().equals(Optional.of(player.getUUID())), "the placer isn't the owner");
        check(
                computer.installedParts().board().is(ExpeditionBlocks.SHUTTLE_COMPUTER_BOARD.get()),
                "a placed computer has no board");

        CompoundTag saved = computer.saveWithoutMetadata(h.getLevel().registryAccess());
        ShuttleComputerBlockEntity copy =
                new ShuttleComputerBlockEntity(computer.getBlockPos(), computer.getBlockState());
        copy.loadWithComponents(saved, h.getLevel().registryAccess());
        check(copy.installedParts().equals(computer.installedParts()), "the board wasn't saved");

        h.getLevel().destroyBlock(h.absolutePos(AT), true, player);
        List<ItemEntity> drops =
                h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(AT)).inflate(1.5));
        ItemEntity mined = drops.stream()
                .filter(e -> e.getItem().is(ExpeditionBlocks.SHUTTLE_COMPUTER_ITEM.get()))
                .findFirst()
                .orElseThrow(() -> new GameTestAssertException("the mined computer dropped " + drops));
        check(
                !mined.getItem().has(FrameworkComponents.INSTALLED_PARTS.get()),
                "a crafted computer's item carries its board, so it won't stack with crafted ones");
        check(
                drops.stream().noneMatch(e -> e.getItem().is(ExpeditionBlocks.SHUTTLE_COMPUTER_BOARD.get())),
                "the board dropped beside the computer's item");
        h.succeed();
    }

    /** A computer saved before it had a board loads with none, and counts as holding its own. */
    @GameTest(template = "empty")
    public static void a_computer_saved_before_boards_loads_with_none(GameTestHelper h) {
        h.setBlock(AT, ExpeditionBlocks.SHUTTLE_COMPUTER.get());
        ShuttleComputerBlockEntity computer = computer(h);
        computer.installParts(new InstalledParts(board(), List.of()));
        CompoundTag saved = computer.saveWithoutMetadata(h.getLevel().registryAccess());
        check(saved.contains("Parts"), "the computer doesn't save its board under Parts");
        saved.remove("Parts");
        ShuttleComputerBlockEntity copy =
                new ShuttleComputerBlockEntity(computer.getBlockPos(), computer.getBlockState());
        copy.loadWithComponents(saved, h.getLevel().registryAccess());
        check(copy.installedParts().equals(InstalledParts.EMPTY), "an old computer has " + copy.installedParts());
        h.succeed();
    }

    // Helpers

    private static ItemStack board() {
        return new ItemStack(ExpeditionBlocks.SHUTTLE_COMPUTER_BOARD.get());
    }

    private static FakePlayer builder(GameTestHelper h) {
        FakePlayer player =
                FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "expedition_builder"));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        return player;
    }

    /** Uses an item on the frame or computer through the server's own use path, as a click does. */
    private static void use(GameTestHelper h, FakePlayer player, ItemStack stack) {
        BlockPos at = h.absolutePos(AT);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.gameMode.useItemOn(
                player,
                h.getLevel(),
                stack,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
    }

    private static ShuttleComputerBlockEntity computer(GameTestHelper h) {
        if (!(h.getBlockEntity(AT) instanceof ShuttleComputerBlockEntity computer)) {
            throw new GameTestAssertException("no Shuttle Computer at " + AT + ", but " + h.getBlockState(AT));
        }
        return computer;
    }

    private static FrameBlockEntity frame(GameTestHelper h) {
        if (!(h.getBlockEntity(AT) instanceof FrameBlockEntity frame)) {
            throw new GameTestAssertException("no frame at " + AT + ", but " + h.getBlockState(AT));
        }
        return frame;
    }

    private static void assertStage(GameTestHelper h, FrameStage expected, String message) {
        BlockState state = h.getBlockState(AT);
        check(
                state.hasProperty(ComputerFrameBlock.STAGE) && state.getValue(ComputerFrameBlock.STAGE) == expected,
                message + " (" + state + ")");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
