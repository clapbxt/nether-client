package dev.nether.ov;

import java.util.ArrayList;

/**
 * Input layer (platform independent). The platform ({@link Plat}: GLFW callbacks on LWJGL3, polled Keyboard/Mouse on
 * LWJGL2) routes every event through the filters below first. While the menu is open the game sees nothing (key/button
 * releases still pass so nothing sticks); otherwise events pass through unchanged. State (keys, buttons, cursor, click
 * times) is kept for modules. Key codes are GLFW's everywhere (LWJGL2 codes are mapped by {@link Keys}).
 */
final class Input {
    private Input() {}

    static final int CURSOR = 0x33001, NORMAL = 0x34001, DISABLED = 0x34003;
    static final int RELEASE = 0, PRESS = 1;

    static long window;
    static int ww = 1, wh = 1;
    static double mx, my;
    static final boolean[] keys = new boolean[512];
    static final boolean[] btn = new boolean[8];
    private static final boolean[] gameBtn = new boolean[8], ourBtn = new boolean[8];
    private static final long[][] clicks = new long[2][128];
    private static final int[] clickN = new int[2];

    static boolean menuOpen;
    /** The overlay died: every filter passes everything through, vanilla input is untouched. */
    static volatile boolean dead;
    /** A printable menu key toggled the menu: its character event (which follows the key event) must not be typed. */
    private static long eatCharAt;
    /** cursor mode the game wants (what it last asked for, or had when the menu opened) */
    static int gameMode = NORMAL;

    // events for the menu UI, consumed once per frame
    static boolean clickL, clickR, upL;
    static double scrollY;
    static final StringBuilder chars = new StringBuilder();
    static final ArrayList<int[]> keyEv = new ArrayList<>();

    /** Dev (-Dnether.keys or -Dnether.devInputOnly=1): real keyboard/mouse never reach the game and the OS cursor is not captured, so a test run cannot take over the desktop; only scripted events play. */
    static final boolean devOnly = System.getProperty("nether.keys") != null || "1".equals(System.getProperty("nether.devInputOnly"));

    /** Playing in a world with no game screen and our menu closed. */
    static boolean inWorld() { return !menuOpen && Game.playing(); }

    // ---------------------------------------------------------------- queries

    static int cps(int button) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (long t : clicks[button]) if (t != 0 && now - t <= 1000) n++;
        return n;
    }

    private static void click(int b) {
        clicks[b][clickN[b]++ & 127] = System.currentTimeMillis();
    }

    /** Send a key event to the game as if it came from the keyboard (menu closed only). */
    static void synthKey(int key, int action) {
        if (window == 0) return;
        try {
            Plat.cur.synthKey(key, action);
        } catch (Throwable t) {
            System.out.println("[nether-agent] synth key failed: " + t);
        }
    }

    /** Dev: a key event as if typed on the keyboard (through our filters, then to the game unless swallowed). */
    static void devKey(int key, int action) {
        if (!onKey(window, key, action)) synthKey(key, action);
    }

    // ---------------------------------------------------------------- menu open/close

    static void setMenu(boolean on) {
        if (on == menuOpen || window == 0) return;
        if (on) {
            menuOpen = true;
            Plat.cur.opened();
            Menu.opened();
            Modules.hintSeen();
        } else {
            menuOpen = false;
            Plat.cur.closed();
            clickL = clickR = upL = false;
            keyEv.clear();
            chars.setLength(0);
            Menu.closed();
        }
    }

    // ---------------------------------------------------------------- filters (true = swallow)

    static boolean onKey(long w, int key, int action) {
        window = w;
        if (dead) return false;
        if (key >= 0 && key < keys.length) {
            if (action == PRESS) keys[key] = true;
            else if (action == RELEASE) keys[key] = false;
        }
        if (action == PRESS && Menu.capturing()) { Menu.capture(key); return true; }
        if (action == PRESS && key == Config.menuKey() && !Keybind.listening()) {
            boolean printable = key >= 32 && key <= 162;
            // a letter menu key types into the search box / waypoint name instead of closing the menu
            if (menuOpen ? !(printable && Menu.textFocus()) : !typing(key)) {
                setMenu(!menuOpen);
                if (printable) eatCharAt = System.currentTimeMillis();
                return true;
            }
        }
        if (menuOpen) {
            if (action == RELEASE) return Modules.onKey(key, action);
            keyEv.add(new int[] {key, action});
            return true;
        }
        return Modules.onKey(key, action);
    }

    /** A letter/digit menu key must not open the menu while a game screen (chat, sign, search box) has the keyboard. */
    private static boolean typing(int key) { return key >= 32 && key <= 162 && gameMode == NORMAL; }

    static boolean onButton(long w, int b, int action) {
        window = w;
        if (dead) return false;
        if (b < 0 || b >= btn.length) return menuOpen;
        btn[b] = action == PRESS;
        if (menuOpen) {
            if (action == PRESS) {
                if (b == 0) clickL = true;
                if (b == 1) clickR = true;
                return true;
            }
            if (b == 0) upL = true;
            if (gameBtn[b]) { gameBtn[b] = false; return false; } // pass releases of buttons the game saw pressed
            return true;
        }
        // our title screen / pause button / HUD widgets (cursor free only) take their clicks; the game never sees those presses
        if (action == PRESS && (MainMenu.press(b) || Hits.press(b))) { ourBtn[b] = true; return true; }
        if (action != PRESS && ourBtn[b]) { ourBtn[b] = false; return true; }
        gameBtn[b] = action == PRESS;
        if (action == PRESS && b < 2 && Game.playing()) { click(b); if (b == 0) HitTracker.attack(); } // CPS: clicks in the world, not in inventories
        return false;
    }

    static boolean onScroll(double y) {
        if (dead) return false;
        if (menuOpen) { scrollY += y; return true; }
        return Modules.onScroll(y);
    }

    static boolean onChar(int cp) {
        if (dead) return false;
        if (eatCharAt != 0) {
            boolean eat = System.currentTimeMillis() - eatCharAt < 250;
            eatCharAt = 0;
            if (eat) return true;
        }
        if (!menuOpen) return false;
        // any language: printable BMP characters (no controls, no surrogate halves: the glyph atlas is per char)
        if (cp >= 32 && cp <= 0xFFFF && !(cp >= 127 && cp < 160) && !Character.isSurrogate((char) cp)) chars.append((char) cp);
        return true;
    }

    private static boolean failed;

    static void fail(Throwable t) {
        if (!failed) { failed = true; System.out.println("[nether-agent] input filter error: " + t); t.printStackTrace(System.out); }
    }
}
