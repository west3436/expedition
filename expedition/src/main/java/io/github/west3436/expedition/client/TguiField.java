package io.github.west3436.expedition.client;

import io.github.west3436.ss13.client.tgui.Ui;
import io.github.west3436.ss13.client.tgui.Ui.Face;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

/**
 * A line to type in, drawn in SS13 Framework's TGUI look as TGUI's input is: a dark box with a lit
 * edge, the text in the kit's font, and a caret. The kit draws one only for a device's label, so a
 * window with its own lines to type in, as the Shuttle Computer's has, keeps one of these for each.
 *
 * <p>The window passes it the clicks, keys and characters it gets: a click on it starts typing, a
 * click anywhere else stops, and while it is typing it takes every key but Esc, which stops it. Text
 * too long for the box moves along so the caret stays in it. With nothing typed it shows what the
 * line stands for, dimmed.
 */
final class TguiField {
    private static final int FIELD = 0xFF0A0A0A;
    private static final int EDGE = 0xBF88BFFF;
    private static final int EDGE_TYPING = 0xFF88BFFF;
    private static final int PLACEHOLDER = 0x66FFFFFF;
    private static final float HAIRLINE = 0.5f;
    private static final long BLINK_MILLIS = 500;

    private final int maxLength;
    private final Predicate<String> allowed;
    private String text = "";
    private int caret;
    private boolean typing;
    private char pendingHigh;
    private long touched;
    private int x;
    private int y;
    private int w;
    private int h;

    /**
     * A line to type in.
     *
     * @param maxLength the most characters it holds
     * @param allowed what it may hold; text that would leave it holding anything else isn't taken
     */
    TguiField(int maxLength, Predicate<String> allowed) {
        this.maxLength = maxLength;
        this.allowed = allowed;
    }

    /** What it holds. */
    String value() {
        return text;
    }

    /** Puts text in it, with the caret at the end. */
    void setValue(String value) {
        text = value.length() > maxLength ? value.substring(0, maxLength) : value;
        caret = text.length();
    }

    /** Whether it is taking keys. */
    boolean typing() {
        return typing;
    }

    /** Starts or stops taking keys. */
    void setTyping(boolean typing) {
        this.typing = typing;
        pendingHigh = 0;
        touched = Util.getMillis();
        if (typing) {
            caret = text.length();
        }
    }

    /** Draws it, and remembers where, for the clicks of the next frame. */
    void draw(Ui ui, int x, int y, int w, int h, Component placeholder) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.fill(x, y, w, h, FIELD);
        int edge = typing ? EDGE_TYPING : EDGE;
        ui.box(x, y, w, HAIRLINE, edge);
        ui.box(x, y + h - HAIRLINE, w, HAIRLINE, edge);
        ui.box(x, y + HAIRLINE, HAIRLINE, h - 2 * HAIRLINE, edge);
        ui.box(x + w - HAIRLINE, y + HAIRLINE, HAIRLINE, h - 2 * HAIRLINE, edge);
        int inside = w - 2 * Ui.PAD;
        float before = ui.width(Component.literal(text.substring(0, caret)), Face.BODY);
        float along = typing ? Math.max(0, before - inside) : 0;
        ui.clip(x + 1, y, w - 2, h);
        if (text.isEmpty()) {
            ui.line(ui.fit(placeholder, Face.BODY, inside), x + Ui.PAD, y, h, PLACEHOLDER, Face.BODY);
        } else if (typing) {
            ui.line(Component.literal(text), x + Ui.PAD - along, y, h, Ui.WHITE, Face.BODY);
        } else {
            ui.line(ui.fit(Component.literal(text), Face.BODY, inside), x + Ui.PAD, y, h, Ui.WHITE, Face.BODY);
        }
        if (typing && (Util.getMillis() - touched) / BLINK_MILLIS % 2 == 0) {
            ui.box(x + Ui.PAD + before - along, y + 2, HAIRLINE, h - 4, Ui.WHITE);
        }
        ui.endClip();
    }

    /** Whether a point is on it, where it was last drawn. */
    boolean over(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /**
     * A key pressed while it is typing. Enter and Esc stop it; the rest edit the text.
     *
     * @return whether it took the key
     */
    boolean key(int key, Supplier<String> clipboard) {
        if (!typing) {
            return false;
        }
        touched = Util.getMillis();
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> setTyping(false);
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (caret > 0) {
                    int from = text.offsetByCodePoints(caret, -1);
                    edit(text.substring(0, from) + text.substring(caret), from);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (caret < text.length()) {
                    edit(text.substring(0, caret) + text.substring(text.offsetByCodePoints(caret, 1)), caret);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> caret = caret > 0 ? text.offsetByCodePoints(caret, -1) : 0;
            case GLFW.GLFW_KEY_RIGHT -> caret = caret < text.length() ? text.offsetByCodePoints(caret, 1) : caret;
            case GLFW.GLFW_KEY_HOME -> caret = 0;
            case GLFW.GLFW_KEY_END -> caret = text.length();
            default -> {
                if (Screen.isPaste(key)) {
                    insert(StringUtil.filterText(clipboard.get()));
                }
            }
        }
        return true;
    }

    /**
     * A character typed while it is typing. One beyond the font's first plane arrives as two, and is
     * kept or dropped whole.
     *
     * @return whether it took the character
     */
    boolean type(char c) {
        if (!typing) {
            return false;
        }
        touched = Util.getMillis();
        if (Character.isHighSurrogate(c)) {
            pendingHigh = c;
            return true;
        }
        if (Character.isLowSurrogate(c)) {
            if (pendingHigh != 0) {
                insert(String.valueOf(new char[] {pendingHigh, c}));
            }
        } else if (StringUtil.isAllowedChatCharacter(c)) {
            insert(String.valueOf(c));
        }
        pendingHigh = 0;
        return true;
    }

    /** Puts text in at the caret, as much of it as there is room for, if what results is allowed. */
    private void insert(String added) {
        int room = maxLength - text.length();
        if (added.length() > room) {
            added = added.substring(0, Math.max(0, room));
            if (!added.isEmpty() && Character.isHighSurrogate(added.charAt(added.length() - 1))) {
                added = added.substring(0, added.length() - 1);
            }
        }
        edit(text.substring(0, caret) + added + text.substring(caret), caret + added.length());
    }

    private void edit(String edited, int newCaret) {
        if (allowed.test(edited)) {
            text = edited;
            caret = newCaret;
        }
    }
}
