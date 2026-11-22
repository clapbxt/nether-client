package dev.nether.ov;

import java.util.ArrayDeque;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import dev.nether.agent.Hook;

/**
 * LWJGL2 platform (Minecraft 1.8.9): input is polled, so there are no callbacks to wrap. The agent filters the
 * Keyboard/Mouse statics instead: while a next() loop runs, every event is first handed to {@link Input}; swallowed
 * events are drained right there and next() simply moves on (returns false once nothing is left for the game). While
 * the menu is open the game therefore sees no events, polled state reads as "nothing pressed" and the cursor is
 * released with Mouse.setGrabbed(false) (restored on close). Compiled against LWJGL2 in a separate javac pass.
 */
final class PlatLwjgl2 extends Plat implements Hook.Legacy {
    @Override String clipboard() { try { String s = org.lwjgl.Sys.getClipboard(); return s == null ? "" : s; } catch (Throwable t) { return super.clipboard(); } }

    private static final boolean DEBUG = "1".equals(System.getProperty("nether.debug"));
    private static final long WINDOW = 1L; // LWJGL2 has no window handle; any non-zero value
    private boolean inside, internal, prevRepeat;
    private final ArrayDeque<int[]> synthQ = new ArrayDeque<>();
    private int[] synth; // synthetic event being delivered: {lwjgl key, 1 down / 0 up}

    @Override boolean legacy() { return true; }

    @Override void install() { Hook.legacy = this; }

    @Override void size(long window, int[] o) {
        o[0] = o[2] = Display.getWidth();
        o[1] = o[3] = Display.getHeight();
    }

    @Override boolean closing(long window) { return Display.isCloseRequested() || Game.legacyStopping(); }

    @Override boolean focused() { return Display.isActive(); }

    @Override int away(long window) { return !Display.isVisible() ? 2 : Display.isActive() ? 0 : 1; }

    @Override void tick() {
        if (Input.menuOpen || Input.worldGuiOpen) {
            Input.mx = Mouse.getX();
            Input.my = Display.getHeight() - 1 - Mouse.getY();
        }
    }

    private void grab(boolean g) {
        internal = true;
        try { Mouse.setGrabbed(g); } finally { internal = false; }
    }

    @Override void opened() {
        boolean grabbed = Mouse.isGrabbed();
        Input.gameMode = grabbed ? Input.DISABLED : Input.NORMAL;
        if (grabbed) {
            grab(false);
            Mouse.setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2);
        }
        Input.mx = Display.getWidth() / 2.0;
        Input.my = Display.getHeight() / 2.0;
        prevRepeat = Keyboard.areRepeatEventsEnabled();
        Keyboard.enableRepeatEvents(true); // the menu's text fields want key repeat
    }

    @Override void closed() {
        Keyboard.enableRepeatEvents(prevRepeat);
        if (Input.gameMode == Input.DISABLED) {
            grab(true);
            Mouse.getDX(); // drop the movement accumulated while the cursor was free, so the camera doesn't jump
            Mouse.getDY();
        }
    }

    @Override void synthKey(int glfwKey, int action) {
        int lw = Keys.toLwjgl(glfwKey);
        if (lw != 0) synthQ.add(new int[] {lw, action == 0 ? 0 : 1});
    }

    // ---------------------------------------------------------------- Keyboard

    @Override public boolean kbNext(boolean r) {
        if (inside) return r;
        inside = true;
        try {
            synth = null;
            while (r) {
                int lw = Keyboard.getEventKey();
                boolean down = Keyboard.getEventKeyState();
                char ch = Keyboard.getEventCharacter();
                int action = down ? (Keyboard.isRepeatEvent() ? 2 : 1) : 0;
                int g = Keys.toGlfw(lw);
                boolean swallow;
                try { // one bad event must never cost the game its keyboard
                    swallow = Input.devOnly || (g >= 0 ? Input.onKey(WINDOW, g, action) : Input.menuOpen || Input.worldGuiOpen);
                    if (!Input.devOnly && down && ch != 0 && Input.onChar(ch)) swallow = true;
                } catch (Throwable t) { Input.fail(t); swallow = false; }
                if (!swallow) return true;
                r = Keyboard.next(); // swallowed: look at the next event (this call is not filtered again)
            }
            if (!Input.menuOpen && !Input.worldGuiOpen && !synthQ.isEmpty()) { synth = synthQ.poll(); return true; }
            return false;
        } finally {
            inside = false;
        }
    }

    @Override public int kbKey(int r) { return synth != null ? synth[0] : r; }
    @Override public boolean kbState(boolean r) { return synth != null ? synth[1] != 0 : r; }
    @Override public char kbChar(char r) { return synth != null ? 0 : r; }

    @Override public boolean kbDown(boolean r, int key) {
        if (inside) return r;
        if (Input.dead) return r;
        if (Input.menuOpen || Input.worldGuiOpen) return false;
        return r && !Input.devOnly;
    }

    // ---------------------------------------------------------------- Mouse

    @Override public boolean msNext(boolean r) {
        if (inside) return r;
        inside = true;
        try {
            while (r) {
                int b = Mouse.getEventButton(), dw = Mouse.getEventDWheel();
                boolean swallow = (Input.menuOpen || Input.worldGuiOpen || Input.devOnly) && !Input.dead;
                try {
                    if (b >= 0 && !Input.devOnly) {
                        if (!Input.menuOpen || Input.worldGuiOpen) { Input.mx = Mouse.getEventX(); Input.my = Display.getHeight() - 1 - Mouse.getEventY(); } // where the click was (HUD widgets, title screen)
                        swallow = Input.onButton(WINDOW, b, Mouse.getEventButtonState() ? 1 : 0);
                    }
                    if (DEBUG && (b >= 0 || dw != 0)) System.out.println("[nether-agent] mouse event b=" + b + " dw=" + dw + " menu=" + Input.menuOpen + " worldGui=" + Input.worldGuiOpen + " swallow=" + swallow);
                    else if (dw != 0 && !Input.devOnly) swallow = Input.onScroll(dw / 120.0);
                } catch (Throwable t) { Input.fail(t); swallow = false; }
                if (!swallow) return true;
                r = Mouse.next();
            }
            return false;
        } finally {
            inside = false;
        }
    }

    @Override public int msButton(int r) { return r; }
    @Override public boolean msState(boolean r) { return r; }
    @Override public int msWheel(int r) { return r; }
    @Override public int msDX(int r) { return inside || !Input.menuOpen && !Input.worldGuiOpen && !Input.devOnly ? r : 0; }
    @Override public int msDY(int r) { return inside || !Input.menuOpen && !Input.worldGuiOpen && !Input.devOnly ? r : 0; }
    @Override public boolean msDown(boolean r, int b) { return inside || !Input.menuOpen && !Input.worldGuiOpen && !Input.devOnly ? r : false; }

    @Override public boolean msGrab(boolean g) {
        if (internal) return g;
        Input.gameMode = g ? Input.DISABLED : Input.NORMAL;
        return (Input.menuOpen || Input.worldGuiOpen || Input.devOnly) && !Input.dead ? false : g;
    }
}
