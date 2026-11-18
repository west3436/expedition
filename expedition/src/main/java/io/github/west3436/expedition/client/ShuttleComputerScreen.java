package io.github.west3436.expedition.client;

import io.github.west3436.expedition.ship.ComputerActionPayload;
import io.github.west3436.expedition.ship.ComputerStatusPayload;
import io.github.west3436.expedition.ship.ShipBox;
import io.github.west3436.expedition.ship.ShipStatus;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Shuttle Computer's window: the ship's name, owner and box, with buttons to change them, and what
 * the ship is: its mass, thrust, thrust-to-weight here, drives and fuel. It sits at the left of the
 * screen, so the pilot sees the outline of the box in the world ({@link ShipOutline}) while it is
 * open.
 */
public final class ShuttleComputerScreen extends Screen {
    private static final int PANEL_WIDTH = 210;
    private static final int MARGIN = 8;
    private static final int LINE = 11;
    private static final int TEXT = 0xE0E0E0;
    private static final int DIM = 0xA0A0A0;
    private static final int WARNING = 0xFFCC55;
    private static final int PROBLEM = 0xFF6655;

    /** The labels of the box's six sides, in {@link ShipBox#distances()} order. */
    private static final String[] SIDES = {"front", "back", "left", "right", "up", "down"};

    private final BlockPos pos;
    private ShipStatus status;
    private Optional<Component> notice;
    private EditBox nameField;
    private int boxTop;
    private int readoutTop;

    private ShuttleComputerScreen(BlockPos pos, ShipStatus status) {
        super(Component.translatable("block.expedition.shuttle_computer"));
        this.pos = pos;
        this.status = status;
        this.notice = Optional.empty();
    }

    /**
     * Opens or updates the window from the server's status.
     *
     * @param payload the status
     */
    public static void accept(ComputerStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new ShuttleComputerScreen(payload.pos(), payload.status()));
        } else if (minecraft.screen instanceof ShuttleComputerScreen screen && screen.pos.equals(payload.pos())) {
            screen.status = payload.status();
            if (payload.notice().isPresent()) {
                screen.notice = payload.notice();
            }
        }
    }

    /**
     * The ship's box in the world, as the server last sent it.
     *
     * @return the box
     */
    BoundingBox outline() {
        return status.world();
    }

    @Override
    protected void init() {
        int x = MARGIN;
        int y = MARGIN + LINE + 4;
        nameField = new EditBox(font, x, y, 140, 16, Component.translatable("expedition.shuttle_computer.name"));
        nameField.setMaxLength(ShipStatus.MAX_NAME);
        nameField.setValue(status.name());
        nameField.setHint(
                Component.translatable("expedition.shuttle_computer.unnamed").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameField);
        addRenderableWidget(Button.builder(
                        Component.translatable("expedition.shuttle_computer.rename"),
                        b -> send(new ComputerActionPayload(
                                pos, ComputerActionPayload.Action.RENAME, nameField.getValue(), ShipBox.NONE)))
                .bounds(x + 144, y - 1, PANEL_WIDTH - 2 * MARGIN - 144, 18)
                .build());

        boxTop = y + 20 + LINE + LINE;
        for (int i = 0; i < 6; i++) {
            int side = i;
            int rowY = boxTop + i * 14;
            addRenderableWidget(Button.builder(Component.literal("-"), b -> step(side, -1))
                    .bounds(x + 110, rowY - 2, 20, 13)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> step(side, 1))
                    .bounds(x + 132, rowY - 2, 20, 13)
                    .build());
        }
        int fitY = boxTop + 6 * 14 + 2;
        addRenderableWidget(Button.builder(
                        Component.translatable("expedition.shuttle_computer.fit"),
                        b -> send(ComputerActionPayload.of(pos, ComputerActionPayload.Action.FIT)))
                .bounds(x, fitY, PANEL_WIDTH - 2 * MARGIN, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("expedition.shuttle_computer.fit.tooltip")))
                .build());
        readoutTop = fitY + 24;
    }

    /** Moves one side of the box out (+) or in (-), five at a time with Shift. */
    private void step(int side, int sign) {
        int[] d = status.box().distances();
        d[side] += sign * (hasShiftDown() ? 5 : 1);
        send(new ComputerActionPayload(pos, ComputerActionPayload.Action.SET_BOX, "", ShipBox.clamped(d)));
    }

    private static void send(ComputerActionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public void tick() {
        var player = Minecraft.getInstance().player;
        if (player == null || player.distanceToSqr(pos.getCenter()) > 64) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Only the panel: the world stays visible, with the box's outline.
        graphics.fill(0, 0, PANEL_WIDTH, height, 0xC0101418);
        graphics.fill(PANEL_WIDTH, 0, PANEL_WIDTH + 1, height, 0xFF3A4450);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x = MARGIN;
        graphics.drawString(font, title, x, MARGIN, 0xFFFFFF);

        int y = MARGIN + LINE + 4 + 20;
        Component owner = status.owner().isEmpty()
                ? Component.translatable("expedition.shuttle_computer.owner.unknown")
                : Component.literal(status.owner());
        graphics.drawString(font, Component.translatable("expedition.shuttle_computer.owner", owner), x, y, DIM);

        BoundingBox w = status.world();
        graphics.drawString(
                font,
                Component.translatable("expedition.shuttle_computer.box", w.getXSpan(), w.getYSpan(), w.getZSpan()),
                x,
                boxTop - LINE - 2,
                TEXT);
        int[] d = status.box().distances();
        for (int i = 0; i < 6; i++) {
            int rowY = boxTop + i * 14;
            graphics.drawString(
                    font, Component.translatable("expedition.shuttle_computer.side." + SIDES[i]), x + 4, rowY, DIM);
            graphics.drawString(font, String.valueOf(d[i]), x + 80, rowY, TEXT);
        }

        y = readoutTop;
        y = line(graphics, y, "mass", Double.isNaN(status.mass()) ? "-" : number(status.mass()), TEXT);
        y = line(graphics, y, "thrust", number(status.thrust()), TEXT);
        double ratio = status.ratio();
        String ratioText =
                Double.isNaN(ratio) ? "-" : Double.isInfinite(ratio) ? "∞" : String.format(Locale.ROOT, "%.2f", ratio);
        y = line(
                graphics,
                y,
                "ratio",
                ratioText + " (" + String.format(Locale.ROOT, "%.2f", status.gravity()) + " g)",
                ratio > 1 ? TEXT : WARNING);
        y = line(graphics, y, "drives", String.valueOf(status.drives()), TEXT);
        if (status.fuel().isEmpty()) {
            y = line(graphics, y, "fuel", "-", TEXT);
        } else {
            for (Map.Entry<String, Double> e : status.sortedFuel().entrySet()) {
                y = line(graphics, y, "fuel", e.getKey() + ": " + number(e.getValue()), TEXT);
            }
        }

        y += 4;
        if (status.tooLarge()) {
            y = warning(graphics, y, Component.translatable("expedition.move.refusal.too_large"), PROBLEM);
        }
        if (status.overlap().isPresent()) {
            y = warning(
                    graphics,
                    y,
                    Component.translatable(
                            "expedition.shuttle_computer.overlap",
                            status.overlap().get()),
                    PROBLEM);
        }
        if (status.terrain() > 0) {
            y = warning(
                    graphics,
                    y,
                    Component.translatable("expedition.shuttle_computer.terrain", status.terrain()),
                    WARNING);
        }
        if (notice.isPresent()) {
            warning(graphics, y + 2, notice.get(), DIM);
        }
    }

    private int line(GuiGraphics graphics, int y, String key, String value, int color) {
        graphics.drawString(font, Component.translatable("expedition.shuttle_computer." + key), MARGIN, y, DIM);
        graphics.drawString(font, value, MARGIN + 110, y, color);
        return y + LINE;
    }

    private int warning(GuiGraphics graphics, int y, Component text, int color) {
        graphics.drawWordWrap(font, text, MARGIN, y, PANEL_WIDTH - 2 * MARGIN, color);
        return y + font.split(text, PANEL_WIDTH - 2 * MARGIN).size() * 9 + 2;
    }

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
