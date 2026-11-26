package dev.nether.ov.platform;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCharCallbackI;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWCharModsCallbackI;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

import dev.nether.ov.features.lyrics.LyricsWorldPanel;
import dev.nether.ov.framework.Input;

import dev.nether.agent.Hook;

/**
 * GLFW platform. GLFW.glfwSet*Callback is wrapped by the agent, so every callback the game registers is routed through
 * the filters in {@link Input} first. A mod that installs its own callback and chains to the previous one gets our
 * wrapper back as a plain GLFW callback and is wrapped again: a per-kind depth counter makes the inner wrappers pass
 * straight through, so every event is filtered exactly once (and synthesised keys not at all).
 */
public final class InputGlfw extends Plat {
    @Override public String clipboard() { String s = Input.window == 0 ? null : GLFW.glfwGetClipboardString(Input.window); return s == null ? "" : s; }
    @Override public boolean setClipboard(String s) { if (Input.window == 0) return false; GLFW.glfwSetClipboardString(Input.window, s); return true; }

    private static boolean internal, regrabbed;
    private static double offX, offY, fx, fy;
    // original key callback, for synthesised key events (toggle sprint/sneak)
    private static GLFWKeyCallbackI keyOrig;
    // wrappers currently running per kind (chained callbacks / synthesised keys pass through inner ones)
    private static int keyDepth, btnDepth, posDepth, scrollDepth, charDepth;
    private final int[] a = new int[1], b = new int[1];

    @Override public void install() {
        Hook.wrapper = new Wrapper();
        Hook.poll = new Poller();
    }

    @Override public void size(long window, int[] o) {
        GLFW.glfwGetFramebufferSize(window, a, b);
        o[0] = a[0];
        o[1] = b[0];
        GLFW.glfwGetWindowSize(window, a, b);
        o[2] = a[0];
        o[3] = b[0];
    }

    @Override public boolean closing(long window) { return GLFW.glfwWindowShouldClose(window); }

    @Override public void synthKey(int key, int action) {
        if (keyOrig == null) return;
        keyDepth++;
        try { keyOrig.invoke(Input.window, key, GLFW.glfwGetKeyScancode(key), action, 0); } finally { keyDepth--; }
    }

    @Override public boolean focused() { return Input.window == 0 || GLFW.glfwGetWindowAttrib(Input.window, GLFW.GLFW_FOCUSED) != 0; }

    @Override public int away(long window) {
        if (window == 0) return 0;
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) != 0) return 2;
        return GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != 0 ? 0 : 1;
    }

    @Override public void opened() {
        long window = Input.window;
        Input.gameMode = GLFW.glfwGetInputMode(window, Input.CURSOR);
        regrabbed = false;
        if (Input.gameMode != Input.NORMAL) {
            setMode(Input.NORMAL);
            GLFW.glfwSetCursorPos(window, Input.ww / 2.0, Input.wh / 2.0);
            Input.mx = Input.ww / 2.0;
            Input.my = Input.wh / 2.0;
        }
    }

    @Override public void closed() {
        long window = Input.window;
        if (Input.gameMode == Input.DISABLED && !regrabbed) {
            double[] x = new double[1], y = new double[1];
            GLFW.glfwGetCursorPos(window, x, y);
            offX = fx - x[0];
            offY = fy - y[0];
        }
        if (Input.gameMode != GLFW.glfwGetInputMode(window, Input.CURSOR)) setMode(Input.gameMode);
    }

    private static void setMode(int mode) {
        internal = true;
        try { GLFW.glfwSetInputMode(Input.window, Input.CURSOR, mode); } finally { internal = false; }
    }

    // ---------------------------------------------------------------- wrapped callbacks

    private static final class KeyCb extends GLFWKeyCallback {
        final GLFWKeyCallbackI orig;
        KeyCb(GLFWKeyCallbackI o) { orig = o; keyOrig = o; }
        @Override public void invoke(long w, int key, int sc, int action, int mods) {
            if (keyDepth > 0) { orig.invoke(w, key, sc, action, mods); return; }
            if (Input.devOnly) return;
            keyDepth++;
            try {
                boolean swallow = false;
                try { swallow = Input.onKey(w, key, action); } catch (Throwable t) { Input.fail(t); }
                if (!swallow) orig.invoke(w, key, sc, action, mods);
            } finally { keyDepth--; }
        }
    }

    private static final class ButtonCb extends GLFWMouseButtonCallback {
        final GLFWMouseButtonCallbackI orig;
        ButtonCb(GLFWMouseButtonCallbackI o) { orig = o; }
        @Override public void invoke(long w, int b, int action, int mods) {
            if (btnDepth > 0) { orig.invoke(w, b, action, mods); return; }
            if (Input.devOnly) return;
            btnDepth++;
            try {
                boolean swallow = false;
                try { swallow = Input.onButton(w, b, action); } catch (Throwable t) { Input.fail(t); }
                if (!swallow) orig.invoke(w, b, action, mods);
            } finally { btnDepth--; }
        }
    }

    private static final class CursorCb extends GLFWCursorPosCallback {
        final GLFWCursorPosCallbackI orig;
        CursorCb(GLFWCursorPosCallbackI o) { orig = o; }
        @Override public void invoke(long w, double x, double y) {
            if (posDepth > 0 || Input.dead) { orig.invoke(w, x, y); return; }
            if (Input.devOnly) return;
            Input.window = w;
            Input.mx = x;
            Input.my = y;
            if (Input.menuOpen || Input.worldGuiOpen) return;
            boolean shift = Input.gameMode == Input.DISABLED;
            fx = shift ? x + offX : x;
            fy = shift ? y + offY : y;
            posDepth++;
            try { orig.invoke(w, fx, fy); } finally { posDepth--; }
        }
    }

    private static final class ScrollCb extends GLFWScrollCallback {
        final GLFWScrollCallbackI orig;
        ScrollCb(GLFWScrollCallbackI o) { orig = o; }
        @Override public void invoke(long w, double x, double y) {
            if (scrollDepth > 0) { orig.invoke(w, x, y); return; }
            if (Input.devOnly) return;
            scrollDepth++;
            try {
                boolean swallow = false;
                try { swallow = Input.onScroll(y); } catch (Throwable t) { Input.fail(t); }
                if (!swallow) orig.invoke(w, x, y);
            } finally { scrollDepth--; }
        }
    }

    private static final class CharCb extends GLFWCharCallback {
        final GLFWCharCallbackI orig;
        CharCb(GLFWCharCallbackI o) { orig = o; }
        @Override public void invoke(long w, int cp) {
            if (charDepth > 0) { orig.invoke(w, cp); return; }
            if (Input.devOnly) return;
            charDepth++;
            try { if (!chr(cp)) orig.invoke(w, cp); } finally { charDepth--; }
        }
    }

    private static final class CharModsCb extends GLFWCharModsCallback {
        final GLFWCharModsCallbackI orig;
        CharModsCb(GLFWCharModsCallbackI o) { orig = o; }
        @Override public void invoke(long w, int cp, int mods) {
            if (charDepth > 0) { orig.invoke(w, cp, mods); return; }
            if (Input.devOnly) return;
            charDepth++;
            try { if (!chr(cp)) orig.invoke(w, cp, mods); } finally { charDepth--; }
        }
    }

    private static boolean chr(int cp) {
        try { return Input.onChar(cp); } catch (Throwable t) { Input.fail(t); return false; }
    }

    // ---------------------------------------------------------------- hook registration

    static final class Wrapper implements Hook.Wrap {
        @Override public Object wrap(int kind, long w, Object cb) {
            Input.window = w;
            switch (kind) {
                case 0: return cb instanceof KeyCb ? cb : new KeyCb((GLFWKeyCallbackI) cb);
                case 1: return cb instanceof ButtonCb ? cb : new ButtonCb((GLFWMouseButtonCallbackI) cb);
                case 2: return cb instanceof CursorCb ? cb : new CursorCb((GLFWCursorPosCallbackI) cb);
                case 3: return cb instanceof ScrollCb ? cb : new ScrollCb((GLFWScrollCallbackI) cb);
                case 4: return cb instanceof CharCb ? cb : new CharCb((GLFWCharCallbackI) cb);
                case 5: return cb instanceof CharModsCb ? cb : new CharModsCb((GLFWCharModsCallbackI) cb);
                default: return cb;
            }
        }
    }

    static final class Poller implements Hook.Poll {
        @Override public int key(int r, long w, int key) {
            if (Input.dead) return r;
            if (Input.menuOpen || Input.worldGuiOpen && !LyricsWorldPanel.dragging()) return 0;
            return Input.devOnly ? 0 : r;
        }

        @Override public int btn(int r, long w, int b) { return Input.dead ? r : Input.menuOpen || Input.worldGuiOpen || Input.devOnly ? 0 : r; }

        @Override public int mode(long w, int mode, int value) {
            if (mode != Input.CURSOR || internal) return value;
            Input.gameMode = value;
            if (value == Input.DISABLED) { offX = offY = 0; if (!Input.worldGuiOpen) regrabbed = true; }
            else if (!Input.menuOpen && !Input.worldGuiOpen) { offX = offY = 0; }
            if (Input.dead) return value;
            return Input.menuOpen || Input.worldGuiOpen || Input.devOnly ? Input.NORMAL : value;
        }
    }
}
