package dev.nether.ov.platform;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.system.MemoryUtil.memGetFloat;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.MemoryUtil.memPutShort;
import static org.lwjgl.system.MemoryUtil.memSet;
import static org.lwjgl.system.MemoryUtil.memUTF8Safe;
import static org.lwjgl.system.MemoryUtil.nmemAlloc;

import dev.nether.ov.features.lyrics.LyricsWorldPanel;
import dev.nether.ov.framework.Input;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_TextInputEvent;

import dev.nether.agent.Hook;

/**
 * SDL3 platform (26.3+: the game moved from GLFW to SDL). Frames come from SDL_GL_SwapWindow; input is polled, so the
 * agent filters SDL_PollEvent (swallowed events are skipped right there), SDL_GetKeyboardState (held toggles, nothing
 * while the menu is open) and SDL_SetWindowRelativeMouseMode (the game's cursor capture). Key codes are SDL scancodes,
 * mapped to GLFW codes by {@link Keys}. Compiled against lwjgl-sdl in its own javac pass. No SDL class is touched before
 * the first frame (we may be installed while one of them is being transformed).
 */
public final class PlatSdl extends Plat implements Hook.Sdl {
    @Override public String clipboard() { String s = org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardText(); return s == null ? "" : s; }
    @Override public boolean setClipboard(String s) { return org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardText(s); }

    /** timestamp of the key events we push ourselves (they skip our filter) */
    private static final long SYNTH = 0x4E45544845524BL;
    private boolean closing, internal, textWas;
    private IntBuffer a, b;
    private ByteBuffer keys;
    private long synth;

    @Override public boolean sdl() { return true; }

    @Override public boolean focused() { return Input.window == 0 || (SDLVideo.SDL_GetWindowFlags(Input.window) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0; }

    @Override public int away(long window) {
        if (window == 0) return 0;
        long f = SDLVideo.SDL_GetWindowFlags(window);
        return (f & SDLVideo.SDL_WINDOW_MINIMIZED) != 0 ? 2 : (f & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0 ? 0 : 1;
    }

    @Override public void install() { Hook.sdl = this; }

    @Override public void size(long window, int[] o) {
        if (a == null) { a = memAllocInt(1); b = memAllocInt(1); }
        SDLVideo.SDL_GetWindowSizeInPixels(window, a, b);
        o[0] = a.get(0);
        o[1] = b.get(0);
        SDLVideo.SDL_GetWindowSize(window, a, b);
        o[2] = a.get(0);
        o[3] = b.get(0);
    }

    @Override public boolean closing(long window) { return closing; }

    @Override public void synthKey(int glfwKey, int action) {
        int sc = Keys.toSdl(glfwKey);
        if (sc <= 0 || Input.window == 0) return;
        if (synth == 0) synth = nmemAlloc(SDL_Event.SIZEOF);
        long p = synth;
        memSet(p, 0, SDL_Event.SIZEOF);
        memPutInt(p + SDL_KeyboardEvent.TYPE, action == 0 ? SDLEvents.SDL_EVENT_KEY_UP : SDLEvents.SDL_EVENT_KEY_DOWN);
        memPutLong(p + SDL_KeyboardEvent.TIMESTAMP, SYNTH);
        memPutInt(p + SDL_KeyboardEvent.WINDOWID, SDLVideo.SDL_GetWindowID(Input.window));
        memPutInt(p + SDL_KeyboardEvent.SCANCODE, sc);
        memPutInt(p + SDL_KeyboardEvent.KEY, SDLKeyboard.SDL_GetKeyFromScancode(sc, (short) 0, false));
        memPutShort(p + SDL_KeyboardEvent.MOD, (short) 0);
        memPutByte(p + SDL_KeyboardEvent.DOWN, (byte) (action == 0 ? 0 : 1));
        SDLEvents.nSDL_PushEvent(p);
    }

    @Override public void opened() {
        long w = Input.window;
        Input.gameMode = SDLMouse.SDL_GetWindowRelativeMouseMode(w) ? Input.DISABLED : Input.NORMAL;
        if (Input.gameMode == Input.DISABLED) {
            rel(w, false);
            SDLMouse.SDL_WarpMouseInWindow(w, Input.ww / 2f, Input.wh / 2f);
        }
        Input.mx = Input.ww / 2.0;
        Input.my = Input.wh / 2.0;
        textWas = SDLKeyboard.SDL_TextInputActive(w);
        if (!textWas) SDLKeyboard.SDL_StartTextInput(w); // search box typing
    }

    @Override public void closed() {
        long w = Input.window;
        if (!textWas) SDLKeyboard.SDL_StopTextInput(w);
        if (Input.gameMode == Input.DISABLED && !Input.devOnly) rel(w, true);
    }

    private void rel(long w, boolean on) {
        internal = true;
        try { SDLMouse.SDL_SetWindowRelativeMouseMode(w, on); } finally { internal = false; }
    }

    // ---------------------------------------------------------------- Hook.Sdl

    @Override public boolean poll(boolean r, Object event) {
        long p = ((SDL_Event) event).address();
        while (r) {
            boolean sw;
            try { sw = !Input.dead && swallow(p); } catch (Throwable t) { Input.fail(t); sw = false; } // one bad event never costs the game its input
            if (!sw) return true;
            r = SDLEvents.nSDL_PollEvent(p); // swallowed: look at the next one (this call is not filtered again)
        }
        return false;
    }

    private boolean swallow(long p) {
        int type = memGetInt(p);
        long w = Input.window;
        switch (type) {
            case SDLEvents.SDL_EVENT_QUIT:
            case SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED:
                closing = true;
                return false;
            case SDLEvents.SDL_EVENT_KEY_DOWN:
            case SDLEvents.SDL_EVENT_KEY_UP: {
                if (memGetLong(p + SDL_KeyboardEvent.TIMESTAMP) == SYNTH) return false;
                if (Input.devOnly) return true;
                boolean down = memGetByte(p + SDL_KeyboardEvent.DOWN) != 0, rep = memGetByte(p + SDL_KeyboardEvent.REPEAT) != 0;
                int g = Keys.fromSdl(memGetInt(p + SDL_KeyboardEvent.SCANCODE));
                return g >= 0 ? Input.onKey(w, g, down ? (rep ? 2 : 1) : 0) : Input.menuOpen || Input.worldGuiOpen;
            }
            case SDLEvents.SDL_EVENT_TEXT_INPUT: {
                if (Input.devOnly) return true;
                if (!Input.menuOpen && !Input.worldGuiOpen) return false;
                String s = memUTF8Safe(memGetAddress(p + SDL_TextInputEvent.TEXT));
                if (s != null) for (int i = 0; i < s.length(); i++) Input.onChar(s.charAt(i));
                return true;
            }
            case SDLEvents.SDL_EVENT_MOUSE_MOTION:
                if (Input.devOnly) return true;
                Input.mx = memGetFloat(p + SDL_MouseMotionEvent.X);
                Input.my = memGetFloat(p + SDL_MouseMotionEvent.Y);
                return Input.menuOpen || Input.worldGuiOpen;
            case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN:
            case SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP: {
                if (Input.devOnly) return true;
                int sb = memGetByte(p + SDL_MouseButtonEvent.BUTTON) & 255; // 1 left, 2 middle, 3 right, 4/5 extra
                int gb = sb == 1 ? 0 : sb == 3 ? 1 : sb == 2 ? 2 : sb - 1;
                return Input.onButton(w, gb, type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN ? 1 : 0);
            }
            case SDLEvents.SDL_EVENT_MOUSE_WHEEL:
                if (Input.devOnly) return true;
                return Input.onScroll(memGetFloat(p + SDL_MouseWheelEvent.Y));
            default:
                return false;
        }
    }

    @Override public Object keys(Object state) {
        if (!(state instanceof ByteBuffer)) return state;
        if (Input.dead) return state;
        boolean hide = Input.menuOpen || Input.worldGuiOpen && !LyricsWorldPanel.dragging() || Input.devOnly;
        if (!hide) return state;
        ByteBuffer s = (ByteBuffer) state;
        int n = s.capacity();
        if (keys == null || keys.capacity() != n) keys = memAlloc(n);
        for (int i = 0; i < n; i++) keys.put(i, (byte) 0);
        return keys;
    }

    @Override public boolean relative(boolean on) {
        if (internal) return on;
        Input.gameMode = on ? Input.DISABLED : Input.NORMAL;
        return (Input.menuOpen || Input.worldGuiOpen || Input.devOnly) && !Input.dead ? false : on;
    }
}
