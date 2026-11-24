package io.github.west3436.expedition.client;

import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.flight.ComputerFlightPayload;
import io.github.west3436.expedition.flight.FlightOrder;
import io.github.west3436.expedition.flight.Flights;
import io.github.west3436.expedition.ship.ComputerActionPayload;
import io.github.west3436.expedition.ship.ComputerStatusPayload;
import io.github.west3436.expedition.ship.ShipBox;
import io.github.west3436.expedition.ship.ShipStatus;
import io.github.west3436.ss13.client.tgui.DeviceParts;
import io.github.west3436.ss13.client.tgui.TguiWindow;
import io.github.west3436.ss13.client.tgui.Ui;
import io.github.west3436.ss13.client.tgui.Ui.Face;
import io.github.west3436.ss13.client.tgui.Ui.Tone;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * A Shuttle Computer's window, in SS13 Framework's TGUI look ({@link TguiWindow}), as ZAS³'s machine
 * windows are. It has three tabs:
 *
 * <ul>
 *   <li><b>Ship:</b> the ship's name, with a button to rename it, its owner, and what the ship is: its
 *       mass, thrust, thrust-to-weight here, drives and fuel.
 *   <li><b>Box:</b> how far the box reaches from the computer on each of its six sides, a step at a
 *       time, and a button that fits it to the blocks joined to the computer.
 *   <li><b>Flight:</b> the flight controls (#29): a destination planet, a column of its surface,
 *       landing or orbit, and a cruise speed. Plot shows what the flight would take, or why it can't
 *       go, and Launch flies it. Only the owner or an operator may launch; the server says so to
 *       anyone else.
 * </ul>
 *
 * <p>Under every tab are the box's problems, if it has any, and the server's last word, such as what
 * a plot found. The world behind the window stays clear, so the pilot sees the outline of the box
 * ({@link ShipOutline}) while it is open.
 */
public final class ShuttleComputerScreen extends TguiWindow {
    private static final int WIDTH = 240;
    private static final int LABEL_WIDTH = 58;
    private static final int TITLED = 14;
    private static final int SHIP = 0;
    private static final int BOX = 1;

    /** The labels of the box's six sides, in {@link ShipBox#distances()} order. */
    private static final String[] SIDES = {"front", "back", "left", "right", "up", "down"};

    private static final Pattern NUMBER = Pattern.compile("-?[0-9]*(\\.[0-9]*)?");
    private static final int NUMBER_LENGTH = 12;

    private static final List<Component> TABS = List.of(text("tab.ship"), text("tab.box"), text("flight"));
    private static final List<Component> ARRIVALS = List.of(text("flight.ground"), text("flight.orbit"));

    private final BlockPos pos;
    private ShipStatus status;
    private Optional<Component> notice = Optional.empty();
    private int tab;

    /** The height of what was drawn last frame, below the title bar and above the padding. */
    private int contentHeight = 160;

    private final TguiField nameField = new TguiField(ShipStatus.MAX_NAME, s -> true);
    private final TguiField xField = numberField();
    private final TguiField zField = numberField();
    private final TguiField speedField = numberField();
    private final List<TguiField> fields = List.of(nameField, xField, zField, speedField);

    /** The fields drawn last frame, which are the ones a click can reach. */
    private final List<TguiField> shown = new ArrayList<>();

    private List<Planet> planets = List.of();
    private int planetIndex;
    private boolean orbit;

    private ShuttleComputerScreen(BlockPos pos, ShipStatus status) {
        super(Component.translatable("block.expedition.shuttle_computer"), WIDTH);
        this.pos = pos;
        this.status = status;
        nameField.setValue(status.name());
        loadPlanets();
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
            if (!screen.nameField.typing()) {
                screen.nameField.setValue(payload.status().name());
            }
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

    private static Component text(String key, Object... args) {
        return Component.translatable("expedition.shuttle_computer." + key, args);
    }

    private static TguiField numberField() {
        return new TguiField(NUMBER_LENGTH, s -> NUMBER.matcher(s).matches());
    }

    @Override
    protected void init() {
        super.init();
        loadPlanets();
    }

    /** The planets a flight may go to, keeping the one picked if it's still there. */
    private void loadPlanets() {
        List<Planet> before = planets;
        planets = Flights.destinations(LogicalSide.CLIENT);
        if (planetIndex < before.size()) {
            int again = planets.indexOf(before.get(planetIndex));
            if (again >= 0) {
                planetIndex = again;
                return;
            }
        }
        pickPlanet(0);
    }

    /** Picks a planet, and the centre of its surface as the column to land at. */
    private void pickPlanet(int index) {
        planetIndex = index;
        if (planetIndex < planets.size()) {
            Planet planet = planets.get(planetIndex);
            xField.setValue(String.valueOf(planet.surface().centerX()));
            zField.setValue(String.valueOf(planet.surface().centerZ()));
        } else {
            xField.setValue("");
            zField.setValue("");
        }
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected int windowHeight() {
        return Ui.TITLE_HEIGHT + 2 * Ui.PAD + contentHeight;
    }

    @Override
    protected void draw(Ui ui, int x, int y, int w, int h) {
        shown.clear();
        int top = y;
        y += ui.tabs(x, y, w, TABS, tab, this::selectTab) + Ui.PAD;
        y = switch (tab) {
            case SHIP -> ship(ui, x, y, w);
            case BOX -> box(ui, x, y, w);
            default -> flight(ui, x, y, w);
        };
        y = notices(ui, x, y, w);
        contentHeight = y - top;
    }

    private void selectTab(int selected) {
        tab = selected;
        for (TguiField field : fields) {
            field.setTyping(false);
        }
        nameField.setValue(status.name());
    }

    /** The ship tab: name, owner and readings. Returns where it ends. */
    private int ship(Ui ui, int x, int y, int w) {
        int labelX = x + Ui.PAD;
        int valueX = labelX + LABEL_WIDTH;
        int right = x + w - Ui.PAD;

        int height = 2 * Ui.BUTTON_ROW + 3;
        int row = ui.section(x, y, w, height, null) + 2;
        DeviceParts.label(ui, text("name"), labelX, row, Ui.BUTTON_HEIGHT);
        Component rename = text("rename");
        int renameWidth = ui.buttonWidth(rename);
        field(ui, nameField, valueX, row, right - renameWidth - 2 - valueX, text("unnamed"));
        ui.button(right - renameWidth, row, renameWidth, Ui.BUTTON_HEIGHT, rename, Tone.DEFAULT, this::rename);
        row += Ui.BUTTON_ROW;
        DeviceParts.label(ui, text("owner"), labelX, row, Ui.BUTTON_HEIGHT);
        if (status.owner().isEmpty()) {
            ui.line(text("owner.unknown"), valueX, row, Ui.BUTTON_HEIGHT, Ui.DIM, Face.BODY);
        } else {
            Component owner = ui.fit(Component.literal(status.owner()), Face.BODY, right - valueX);
            ui.line(owner, valueX, row, Ui.BUTTON_HEIGHT, Ui.WHITE, Face.BODY);
        }
        y += height + Ui.PAD;

        Map<String, Double> fuel = status.sortedFuel();
        int lines = 4 + Math.max(1, fuel.size());
        height = TITLED + lines * Ui.ROW + 2;
        row = ui.section(x, y, w, height, text("readings")) + 1;
        String mass = Double.isNaN(status.mass()) ? "-" : number(status.mass());
        row = reading(ui, labelX, right, row, "mass", mass, Ui.WHITE);
        row = reading(ui, labelX, right, row, "thrust", number(status.thrust()), Ui.WHITE);
        double ratio = status.ratio();
        String ratioText =
                Double.isNaN(ratio) ? "-" : Double.isInfinite(ratio) ? "∞" : String.format(Locale.ROOT, "%.2f", ratio);
        String gravity = String.format(Locale.ROOT, "%.2f", status.gravity());
        row = reading(
                ui, labelX, right, row, "ratio", ratioText + " (" + gravity + " g)", ratio > 1 ? Ui.WHITE : Ui.AVERAGE);
        row = reading(ui, labelX, right, row, "drives", String.valueOf(status.drives()), Ui.WHITE);
        if (fuel.isEmpty()) {
            reading(ui, labelX, right, row, "fuel", "-", Ui.WHITE);
        } else {
            for (Map.Entry<String, Double> e : fuel.entrySet()) {
                row = reading(ui, labelX, right, row, "fuel", e.getKey() + ": " + number(e.getValue()), Ui.WHITE);
            }
        }
        return y + height;
    }

    private static int reading(Ui ui, int labelX, int right, int row, String key, String value, int colour) {
        DeviceParts.label(ui, text(key), labelX, row, Ui.ROW);
        ui.lineRight(Component.literal(value), right, row, Ui.ROW, colour, Face.BODY);
        return row + Ui.ROW;
    }

    /** The box tab: a stepper for each side, and the button that fits the box. Returns where it ends. */
    private int box(Ui ui, int x, int y, int w) {
        int labelX = x + Ui.PAD;
        int valueX = labelX + LABEL_WIDTH;
        int valueW = w - 2 * Ui.PAD - LABEL_WIDTH;
        BoundingBox world = status.world();
        Component title = text("box", world.getXSpan(), world.getYSpan(), world.getZSpan());
        int height = TITLED + 7 * Ui.BUTTON_ROW + 3;
        int row = ui.section(x, y, w, height, title) + 2;
        String unit = text("blocks").getString();
        int[] distances = status.box().distances();
        for (int i = 0; i < SIDES.length; i++) {
            int side = i;
            DeviceParts.label(ui, text("side." + SIDES[i]), labelX, row, Ui.BUTTON_HEIGHT);
            ui.stepper(
                    valueX,
                    row,
                    valueW,
                    Component.literal(String.valueOf(distances[i])),
                    Ui.WHITE,
                    DeviceParts.SMALL_STEPS,
                    unit,
                    step -> step(side, DeviceParts.SMALL_STEPS[step]),
                    null,
                    null);
            row += Ui.BUTTON_ROW;
        }
        int fitWidth = w - 2 * Ui.PAD;
        ui.button(
                labelX,
                row,
                fitWidth,
                Ui.BUTTON_HEIGHT,
                text("fit"),
                Tone.DEFAULT,
                () -> send(ComputerActionPayload.of(pos, ComputerActionPayload.Action.FIT)));
        ui.tooltip(labelX, row, fitWidth, Ui.BUTTON_HEIGHT, text("fit.tooltip"));
        return y + height;
    }

    /** The flight tab. Returns where it ends. */
    private int flight(Ui ui, int x, int y, int w) {
        int labelX = x + Ui.PAD;
        int valueX = labelX + LABEL_WIDTH;
        int right = x + w - Ui.PAD;
        int valueW = right - valueX;
        int height = TITLED + 6 * Ui.BUTTON_ROW + 3;
        int row = ui.section(x, y, w, height, text("flight.plan")) + 2;

        DeviceParts.label(ui, text("flight.destination"), labelX, row, Ui.BUTTON_HEIGHT);
        Component next = Component.literal("▶");
        int arrow = ui.buttonWidth(next);
        boolean choose = planets.size() > 1;
        ui.button(
                valueX,
                row,
                arrow,
                Ui.BUTTON_HEIGHT,
                Component.literal("◀"),
                Tone.DEFAULT,
                choose ? () -> pickPlanet(Math.floorMod(planetIndex - 1, planets.size())) : null);
        ui.button(
                right - arrow,
                row,
                arrow,
                Ui.BUTTON_HEIGHT,
                next,
                Tone.DEFAULT,
                choose ? () -> pickPlanet((planetIndex + 1) % planets.size()) : null);
        Component planet = planets.isEmpty()
                ? text("flight.no_planets")
                : Flights.name(planets.get(planetIndex).id());
        int nameX = valueX + arrow + 2;
        int nameW = valueW - 2 * arrow - 4;
        ui.lineCentred(
                ui.fit(planet, Face.BOLD, nameW),
                nameX,
                nameW,
                row,
                Ui.BUTTON_HEIGHT,
                planets.isEmpty() ? Ui.DIM : Ui.WHITE,
                Face.BOLD);
        row += Ui.BUTTON_ROW;

        DeviceParts.label(ui, text("flight.x"), labelX, row, Ui.BUTTON_HEIGHT);
        field(ui, xField, valueX, row, valueW, Component.empty());
        row += Ui.BUTTON_ROW;
        DeviceParts.label(ui, text("flight.z"), labelX, row, Ui.BUTTON_HEIGHT);
        field(ui, zField, valueX, row, valueW, Component.empty());
        row += Ui.BUTTON_ROW;

        DeviceParts.label(ui, text("flight.arrival"), labelX, row, Ui.BUTTON_HEIGHT);
        ui.choice(valueX, row, ARRIVALS, orbit ? 1 : 0, -1, i -> orbit = i == 1);
        row += Ui.BUTTON_ROW;

        DeviceParts.label(ui, text("flight.speed"), labelX, row, Ui.BUTTON_HEIGHT);
        field(ui, speedField, valueX, row, valueW, text("flight.speed.full"));
        row += Ui.BUTTON_ROW;

        int half = (w - 2 * Ui.PAD - 2) / 2;
        boolean canFly = !planets.isEmpty();
        ui.button(
                labelX,
                row,
                half,
                Ui.BUTTON_HEIGHT,
                text("flight.plot"),
                Tone.DEFAULT,
                canFly ? () -> sendFlight(false) : null);
        ui.button(
                right - half,
                row,
                half,
                Ui.BUTTON_HEIGHT,
                text("flight.launch"),
                Tone.CAUTION,
                canFly ? () -> sendFlight(true) : null);
        ui.tooltip(right - half, row, half, Ui.BUTTON_HEIGHT, text("flight.launch.tooltip"));
        return y + height;
    }

    /** The box's problems and the server's last word, under every tab. Returns where they end. */
    private int notices(Ui ui, int x, int y, int w) {
        if (status.tooLarge()) {
            Component tooLarge = ui.alert("⚠", Component.translatable("expedition.move.refusal.too_large"));
            y += Ui.PAD + ui.notice(x, y + Ui.PAD, w, Ui.NOTICE_DANGER, Ui.WHITE, tooLarge);
        }
        if (status.overlap().isPresent()) {
            Component overlap = ui.alert("⚠", text("overlap", status.overlap().get()));
            y += Ui.PAD + ui.notice(x, y + Ui.PAD, w, Ui.NOTICE_DANGER, Ui.WHITE, overlap);
        }
        if (status.terrain() > 0) {
            y += Ui.PAD + ui.notice(x, y + Ui.PAD, w, Ui.NOTICE, Ui.BLACK, text("terrain", status.terrain()));
        }
        if (notice.isPresent()) {
            y += Ui.PAD + ui.notice(x, y + Ui.PAD, w, Ui.NOTICE_INFO, Ui.WHITE, notice.get());
        }
        return y;
    }

    private void field(Ui ui, TguiField field, int x, int y, int w, Component placeholder) {
        field.draw(ui, x, y, w, Ui.BUTTON_HEIGHT, placeholder);
        shown.add(field);
    }

    /** The world stays clear behind the window, so the box's outline shows. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    // ---- Input ------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        TguiField clicked = null;
        for (TguiField field : shown) {
            if (field.over(mouseX, mouseY)) {
                clicked = field;
            }
        }
        for (TguiField field : fields) {
            if (field != clicked && field.typing()) {
                field.setTyping(false);
            }
        }
        if (clicked != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!clicked.typing()) {
                clicked.setTyping(true);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        for (TguiField field : fields) {
            if (field.typing()) {
                field.key(key, () -> Minecraft.getInstance().keyboardHandler.getClipboard());
                if (field == nameField) {
                    if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                        rename();
                    } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                        nameField.setValue(status.name());
                    }
                }
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        for (TguiField field : fields) {
            if (field.type(c)) {
                return true;
            }
        }
        return super.charTyped(c, modifiers);
    }

    // ---- What the buttons send --------------------------------------------------------------

    private void rename() {
        nameField.setTyping(false);
        send(new ComputerActionPayload(pos, ComputerActionPayload.Action.RENAME, nameField.value(), ShipBox.NONE));
    }

    /** Moves one side of the box out or in by a step, such as "+10" or "−1". */
    private void step(int side, String step) {
        int[] d = status.box().distances();
        d[side] += Integer.parseInt(step.replace('−', '-').replace("+", ""));
        send(new ComputerActionPayload(pos, ComputerActionPayload.Action.SET_BOX, "", ShipBox.clamped(d)));
    }

    /** Asks the server to plot, or launch, the flight the tab describes. */
    private void sendFlight(boolean launch) {
        if (planets.isEmpty()) {
            return;
        }
        FlightOrder order = new FlightOrder(
                planets.get(planetIndex).id(),
                whole(xField.value()),
                whole(zField.value()),
                orbit,
                decimal(speedField.value()));
        PacketDistributor.sendToServer(new ComputerFlightPayload(pos, launch, order));
    }

    private static int whole(String text) {
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double decimal(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
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

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
