package dev.nether.ov.framework;

import java.util.ArrayList;

import dev.nether.ov.features.hud.HitTracker;
import dev.nether.ov.features.lyrics.LyricsWorldPanel;
import dev.nether.ov.features.social.Party;
import dev.nether.ov.features.social.PartyPick;
import dev.nether.ov.game.Game;
import dev.nether.ov.game.Keybind;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.platform.Plat;

/**
 * Input layer (platform independent). The platform ({@link Plat}: GLFW callbacks on LWJGL3, polled Keyboard/Mouse on
 * LWJGL2) routes every event through the filters below first. While the menu is open the game sees nothing (key/button
 * releases still pass so nothing sticks); otherwise events pass through unchanged. State (keys, buttons, cursor, click
 * times) is kept for modules. Key codes are GLFW's everywhere (LWJGL2 codes are mapped by {@link Keys}).
 */
public final class Input {
    private Input() {}

    public static final int CURSOR = 0x33001, NORMAL = 0x34001, DISABLED = 0x34003;
    public static final int RELEASE = 0, PRESS = 1;
    /** bound mouse buttons are stored as key codes MOUSE + button (2 middle, 3 back, 4 forward), above every keyboard code */
    public static final int MOUSE = 400;

    public static long window;
    public static int ww = 1, wh = 1;
    public static double mx, my;
    public static final boolean[] keys = new boolean[512];
    public static final boolean[] btn = new boolean[8];
    private static final boolean[] gameBtn = new boolean[8], ourBtn = new boolean[8];
    private static final long[][] clicks = new long[2][128];
    private static final int[] clickN = new int[2];

    public static boolean menuOpen;
    public static boolean worldGuiOpen;
    /** The overlay died: every filter passes everything through, vanilla input is untouched. */
    public static volatile boolean dead;
    /** A printable menu key toggled the menu: its character event (which follows the key event) must not be typed. */
    private static long eatCharAt;
    /** Esc closed the menu: its repeats and release stay ours. */
    private static boolean escHeld;
    /** cursor mode the game wants (what it last asked for, or had when the menu opened) */
    public static int gameMode = NORMAL;

    // events for the menu UI, consumed once per frame
    public static boolean clickL, clickR, upL;
    public static double scrollY;
    /** Counts key, button and scroll events (a cached HUD rebuilds when it moves). */
    static int events;
    public static final StringBuilder chars = new StringBuilder();
    public static final ArrayList<int[]> keyEv = new ArrayList<>();

    /** Dev (-Dnether.keys or -Dnether.devInputOnly=1): real keyboard/mouse never reach the game and the OS cursor is not captured, so a test run cannot take over the desktop; only scripted events play. */
    public static final boolean devOnly = System.getProperty("nether.keys") != null || "1".equals(System.getProperty("nether.devInputOnly"));

    /** Playing in a world with no game screen and our menu closed. */
    public static boolean inWorld() { return !menuOpen && Game.playing(); }

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
    public static void devKey(int key, int action) {
        if (!onKey(window, key, action)) synthKey(key, action);
    }

    // ---------------------------------------------------------------- menu open/close

    /** The char event of the key just pressed must not type into what it opened (waypoint dialog). */
    static void eatChar() { eatCharAt = System.currentTimeMillis(); }

    /** a modifier menu key is down / something else was pressed meanwhile / its press was kept from the game */
    private static boolean modHeld, modUsed, modEaten;

    public static void setMenu(boolean on) {
        if (on && worldGuiOpen) setWorldGui(false);
        if (!on && worldGuiOpen) setWorldGui(false);
        if (on == menuOpen || window == 0) return;
        if (on) {
            // If focus changed while Escape was held, some window systems never deliver its release. A fresh menu
            // session must not inherit that stale latch or every Escape press in the new session is swallowed.
            escHeld = false;
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

    static void setWorldGui(boolean on) {
        if (on && menuOpen) return;
        if (on == worldGuiOpen) return;
        worldGuiOpen = on;
        if (window == 0) { if (!on) LyricsWorldPanel.inputClosed(); return; }
        if (on) Plat.cur.opened();
        else { Plat.cur.closed(); clickL = clickR = upL = false; LyricsWorldPanel.inputClosed(); }
    }

    // ---------------------------------------------------------------- filters (true = swallow)

    public static boolean onKey(long w, int key, int action) {
        events++;
        window = w;
        if (dead) return false;
        if (key >= 0 && key < keys.length) {
            if (action == PRESS) keys[key] = true;
            else if (action == RELEASE) keys[key] = false;
        }
        if (action == PRESS && Menu.capturing()) { // a bound letter / digit must not also type into the search box
            Menu.capture(key);
            if (key >= 32 && key <= 162) eatCharAt = System.currentTimeMillis();
            return true;
        }
        // the party lobby's chat field / key capture on our title screen
        if (!menuOpen && (MainMenu.title || MainMenu.pause || Party.capturing()) && Party.key(key, action)) return true;
        // the party leader's pick on the game's world / server list: Enter selects for the party instead of starting
        if (!menuOpen && PartyPick.key(key, action)) return true;
        if (worldGuiOpen) {
            if (Menu.capturing() && action == PRESS) { Menu.capture(key); return true; }
            if (key == 256 && action == PRESS) { LyricsWorldPanel.finishDrag(); return true; }
            if (LyricsWorldPanel.dragging()) return Modules.onKey(key, action); // movement and gameplay keys keep working during a border drag
            return true;
        }
        // Esc with nothing left to step back from closes the menu here, like the menu key (not a frame later, so the
        // game never sees its repeats or release and the cursor is handed back at once)
        if (key == 256 && escHeld) { if (action == RELEASE) escHeld = false; return true; }
        if (key == 256 && action == PRESS && menuOpen && Menu.escCloses()) { setMenu(false); escHeld = true; return true; }
        // a Shift / Ctrl / Alt menu key (R-Shift by default) is also a modifier: it toggles on release when tapped on its
        // own, and the game still sees it, so capitals in chat, signs and shift-clicks never open or close the menu
        if (key == Config.menuKey() && key >= 340 && key <= 347 && !Keybind.listening()) {
            if (action == PRESS) { modHeld = true; modUsed = false; modEaten = menuOpen; return modEaten; }
            if (action == RELEASE && modHeld) {
                modHeld = false;
                boolean eaten = modEaten;
                if (!modUsed && !(menuOpen && Menu.capturing())) { if (!menuOpen || Menu.leaveOk(() -> setMenu(false))) setMenu(!menuOpen); }
                return eaten; // the game gets the release of a press it saw
            }
            return menuOpen;
        }
        if (action == PRESS && modHeld) modUsed = true; // Shift+letter: a chord, not a tap
        if (action == PRESS && key == Config.menuKey() && !Keybind.listening()) {
            boolean printable = key >= 32 && key <= 162;
            // a letter menu key types into the search box / waypoint name instead of closing the menu
            if (menuOpen ? !(printable && Menu.textFocus()) : !typing(key)) {
                if (!menuOpen || Menu.leaveOk(() -> setMenu(false))) setMenu(!menuOpen);
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

    public static boolean onButton(long w, int b, int action) {
        events++;
        window = w;
        if (dead) return false;
        if (b < 0 || b >= btn.length) return menuOpen || worldGuiOpen;
        btn[b] = action == PRESS;
        if (action == PRESS && modHeld) modUsed = true; // shift-click
        // middle / side buttons bind like keys (code 400 + button); left and right stay the menu's own clicks
        if (b >= 2 && action == PRESS && Menu.capturing()) { Menu.capture(MOUSE + b); return true; }
        if (worldGuiOpen) {
            if (action == PRESS && LyricsWorldPanel.pointerPress(b)) ourBtn[b] = true;
            if (action != PRESS) {
                boolean dragEnded = LyricsWorldPanel.pointerRelease(b);
                if (ourBtn[b]) ourBtn[b] = false;
                if (dragEnded) return true;
            }
            return true;
        }
        if (!menuOpen && action == PRESS && b <= 1 && LyricsWorldPanel.visible() && LyricsWorldPanel.pointerPress(b)) { ourBtn[b] = true; return true; }
        if (action == PRESS && !menuOpen && Party.button(b)) return true;
        if (menuOpen) {
            if (action == PRESS) {
                if (b == 0) clickL = true;
                if (b == 1) clickR = true;
                return true;
            }
            if (b == 0) upL = true;
            if (b >= 2) Modules.onKey(MOUSE + b, 0);
            if (gameBtn[b]) { gameBtn[b] = false; return false; } // pass releases of buttons the game saw pressed
            return true;
        }
        // our title screen / pause button / HUD widgets (cursor free only) take their clicks; the game never sees those presses
        // bound middle / side buttons (Zoom on mouse 4 ...): releases always reach the module so a hold ends
        if (b >= 2 && Modules.onKey(MOUSE + b, action == PRESS ? 1 : 0) && action == PRESS) { ourBtn[b] = true; return true; }
        if (action == PRESS && (MainMenu.press(b) || Hits.press(b))) { ourBtn[b] = true; return true; }
        if (action != PRESS && ourBtn[b]) { ourBtn[b] = false; return true; }
        gameBtn[b] = action == PRESS;
        if (action == PRESS && b < 2 && Game.playing()) { click(b); if (b == 0) HitTracker.attack(); } // CPS: clicks in the world, not in inventories
        return false;
    }

    public static boolean onScroll(double y) {
        events++;
        if (dead) return false;
        if (menuOpen) { scrollY += y; return true; }
        if (worldGuiOpen) { LyricsWorldPanel.scroll(y); return true; }
        if (!menuOpen && LyricsWorldPanel.scroll(y)) return true;
        if (MainMenu.title || (MainMenu.pause && Party.panelOpen())) { Party.wheel += (float) y; return true; }
        return Modules.onScroll(y);
    }

    public static boolean onChar(int cp) {
        if (dead) return false;
        if (eatCharAt != 0) {
            boolean eat = System.currentTimeMillis() - eatCharAt < 250;
            eatCharAt = 0;
            if (eat) return true;
        }
        if (!menuOpen && (!worldGuiOpen || LyricsWorldPanel.dragging())) {
            if (Party.typing && MainMenu.title) { if (cp >= 32 && cp <= 0xFFFF && !Character.isSurrogate((char) cp)) Party.chars(String.valueOf((char) cp)); return true; }
            return false;
        }
        // any language: printable BMP characters (no controls, no surrogate halves: the glyph atlas is per char)
        if (cp >= 32 && cp <= 0xFFFF && !(cp >= 127 && cp < 160) && !Character.isSurrogate((char) cp)) chars.append((char) cp);
        return true;
    }

    private static boolean failed;

    public static void fail(Throwable t) {
        if (!failed) { failed = true; System.out.println("[nether-agent] input filter error: " + t); t.printStackTrace(System.out); }
    }
}
