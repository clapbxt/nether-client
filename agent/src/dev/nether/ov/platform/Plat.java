package dev.nether.ov.platform;

/** Windowing/input platform: GLFW (LWJGL3, 1.13-26.2), SDL3 (26.3+) or LWJGL2 (1.8.9-1.12.2). Everything platform specific hides behind this. */
public abstract class Plat {
    public static Plat cur;

    /** Registers the input hooks with the agent's Hook shim. */
    public abstract void install();

    /** out = {framebuffer w, h, window w, h}. */
    public abstract void size(long window, int[] out);

    /** The game is quitting (it saves options right after this frame). */
    public abstract boolean closing(long window);

    /** Menu just opened (Input.menuOpen is already true): release the cursor, remember what the game wanted. */
    public abstract void opened();

    /** Menu just closed: give the game its cursor mode back. */
    public abstract void closed();

    /** Key event to the game; key is GLFW-coded. */
    public abstract void synthKey(int glfwKey, int action);

    /** Once per frame before drawing: refresh polled state (cursor position). */
    public void tick() {}

    /** The game window has keyboard focus (a toggle re-sends its key when focus comes back). */
    public boolean focused() { return true; }

    /** Where the window is: 0 in front (focused), 1 behind another window, 2 minimized (Background FPS). */
    public int away(long window) { return focused() ? 0 : 1; }

    public boolean legacy() { return false; }

    /** Clipboard text, "" when empty or unreadable (render thread). Default: AWT (platforms override with their own). */
    public String clipboard() {
        try {
            Object o = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return o instanceof String ? (String) o : "";
        } catch (Throwable t) { return ""; }
    }

    /** Puts text on the clipboard; false when it could not. */
    public boolean setClipboard(String s) {
        try { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null); return true; }
        catch (Throwable t) { return false; }
    }

    /** SDL3 (26.3+): key codes are SDL scancodes, the Controls entry is not registered. */
    public boolean sdl() { return false; }
}
