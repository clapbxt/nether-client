package dev.nether.ov.framework;

import dev.nether.agent.Hook;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.nether.ov.Overlay;
import dev.nether.ov.features.hud.Armor;
import dev.nether.ov.features.hud.Fps;
import dev.nether.ov.features.hud.Cps;
import dev.nether.ov.features.hud.Coords;
import dev.nether.ov.features.hud.Clock;
import dev.nether.ov.features.hud.Session;
import dev.nether.ov.features.hud.Memory;
import dev.nether.ov.features.hud.Keystrokes;
import dev.nether.ov.features.hud.Zoom;
import dev.nether.ov.features.hud.Fullbright;
import dev.nether.ov.features.hud.Potions;
import dev.nether.ov.features.hud.Ping;
import dev.nether.ov.features.hud.Address;
import dev.nether.ov.features.hud.WorldTime;
import dev.nether.ov.features.hud.Speed;
import dev.nether.ov.features.hud.Compass;
import dev.nether.ov.features.hud.Crosshair;
import dev.nether.ov.features.hud.Combo;
import dev.nether.ov.features.hud.Items;
import dev.nether.ov.features.hud.Reach;
import dev.nether.ov.features.hud.SelfTag;
import dev.nether.ov.features.hud.Stopwatch;
import dev.nether.ov.features.hud.TntTimer;
import dev.nether.ov.features.hud.Waypoints;
import dev.nether.ov.features.lyrics.Lyrics;
import dev.nether.ov.features.lyrics.LyricsBar;
import dev.nether.ov.features.lyrics.LyricsWorldPanel;
import dev.nether.ov.features.media.MediaPlayer;
import dev.nether.ov.features.performance.BackgroundFps;
import dev.nether.ov.features.performance.BackgroundReload;
import dev.nether.ov.features.performance.Performance;
import dev.nether.ov.features.screenshots.Screenshots;
import dev.nether.ov.features.visual.CustomSky;
import dev.nether.ov.features.visual.Freelook;
import dev.nether.ov.features.visual.SkinLayers;
import dev.nether.ov.features.visual.Views;
import dev.nether.ov.game.Game;
import dev.nether.ov.game.Res;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Icons;
import dev.nether.ov.ui.Th;
import dev.nether.ov.ui.Toast;

/** Module registry, HUD drawing loop and the input-side hooks. */
public final class Modules {
    private Modules() {}

    public static final List<Module> all = new ArrayList<>();
    // modules the input hooks and HUD loop need every event / frame (no lookups by id there)
    static final Crosshair CROSSHAIR = new Crosshair();
    static final Zoom ZOOM = new Zoom();
    static final Freelook FREELOOK = new Freelook();
    static final Fullbright FULLBRIGHT = new Fullbright();
    public static final Waypoints WAYPOINTS = new Waypoints();
    public static final MediaPlayer MEDIA = new MediaPlayer();
    public static final Lyrics LYRICS = new Lyrics();
    public static final LyricsBar LYRICS_BAR = new LyricsBar();
    static final Stopwatch STOPWATCH = new Stopwatch();
    public static final Screenshots SHOTS = new Screenshots();
    static final SelfTag SELFTAG = new SelfTag();

    static {
        all.add(new Fps());
        all.add(new Cps());
        all.add(new Keystrokes());
        all.add(new Coords());
        all.add(new Clock());
        all.add(new Session());
        all.add(new Memory());
        all.add(CROSSHAIR);
        all.add(ZOOM);
        all.add(FREELOOK);
        all.add(FULLBRIGHT);
        all.add(SkinLayers.M);
        all.add(TntTimer.M);
        all.add(CustomSky.M);
        all.add(new Views.Shield());
        all.add(new Views.Hand());
        all.add(new Views.Fire());
        all.add(new Views.Ride());
        all.add(new Armor());
        all.add(new Potions());
        all.add(new Ping());
        all.add(new Address());
        all.add(new Compass());
        all.add(new WorldTime());
        all.add(new Speed());
        all.add(WAYPOINTS);
        all.add(MEDIA);
        all.add(LYRICS);
        all.add(LYRICS_BAR);
        // c5
        all.add(new Reach());
        all.add(new Combo());
        all.add(new Items());
        all.add(STOPWATCH);
        all.add(SHOTS);
        all.add(SELFTAG);
        all.add(new BackgroundReload());
        all.add(BackgroundFps.M);
        all.add(Performance.M);
        all.add(MainMenu.MODULE);
        Runtime.getRuntime().addShutdownHook(new Thread(Modules::exitHook));
    }

    private static volatile boolean closedOnRender;

    /** Render thread, every frame while the game is quitting: puts every temporary game option back so options.txt never sees our values. */
    public static void shutdown() {
        restoreOptions();
        closedOnRender = true;
        Config.flush();
    }

    /** JVM exit: the render thread normally did the restore already; only a game that died without a closing frame needs it here. */
    private static void exitHook() {
        try { if (!closedOnRender) { restoreOptions(); } } catch (Throwable ignored) { }
        Config.flush();
    }

    private static void restoreOptions() {
        ZOOM.restore();
        FULLBRIGHT.restore();
    }

    /** Overlay death: options back (the game must keep no value we set for it). */
    public static void release() {
        restoreOptions();
        Hook.shieldDy = 0; Hook.fireDy = 0; Hook.fireAlpha = -1; Hook.mountOf = null; // view editors back to vanilla
    }

    // ---------------------------------------------------------------- sensitivity: Zoom keeps one saved base

    private static double sensBase = -1;

    /**
     * Writes base sensitivity scaled by Zoom's factor (vanilla: speed = (0.6 v + 0.2)^3) and puts the base back
     * once it does not want a change.
     */
    public static void syncSens() {
        double k = ZOOM.sensScale;
        if (k != 1) {
            if (sensBase < 0) sensBase = Game.sens();
            double d = (0.6 * sensBase + 0.2) * Math.cbrt(k);
            Game.setSens(Math.max(0, (d - 0.2) / 0.6));
        } else if (sensBase >= 0) {
            Game.setSens(sensBase);
            sensBase = -1;
        }
    }

    /** Scroll from the game's callback chain; true swallows it (zoom adjust). */
    static boolean onScroll(double y) {
        Zoom z = ZOOM;
        if (!z.held || !z.enabled() || !z.bool("scroll") || y == 0) return false;
        // x1.25 per notch (even steps at any zoom), up to 50x; only this zoom: the next one starts at the set level
        z.live = Math.max(1.5, Math.min(50, z.level() * (y > 0 ? 1.25 : 0.8)));
        return true;
    }

    public static Module byId(String id) {
        for (Module m : all) if (m.id.equals(id)) return m;
        return null;
    }

    /** Gap kept between a HUD box and the screen edge, in pixels (2 GUI pixels). */
    public static float edge() { return Gfx.gui * 2; }

    // ---------------------------------------------------------------- HUD layout: anchors + auto layout

    /** anchor value of a box that stacks into its corner ({@link Module#home}) until it is moved */
    public static final String AUTO = "auto";
    static final String[] ANCHORS = {"tl", "tc", "tr", "ml", "mc", "mr", "bl", "bc", "br"};
    public static final String[] ANCHOR_NAMES = {"TOP LEFT", "TOP", "TOP RIGHT", "LEFT", "CENTRE", "RIGHT", "BOTTOM LEFT", "BOTTOM", "BOTTOM RIGHT"};

    public static int anchorIdx(String a) {
        if (a == null || a.length() != 2) return -1;
        int v = "tmb".indexOf(a.charAt(0)), h = "lcr".indexOf(a.charAt(1));
        return v < 0 || h < 0 ? -1 : v * 3 + h;
    }
    /** Anchor point as a fraction of the screen (0, 0.5, 1) on x (h = true) or y. */
    public static float af(String a, boolean h) { return h ? "lcr".indexOf(a.charAt(1)) * 0.5f : "tmb".indexOf(a.charAt(0)) * 0.5f; }

    /** Stored anchor of m: one of {@link #ANCHORS}, {@link #AUTO}, or null = old x/y fractions (not migrated yet). */
    public static String anchor(Module m) {
        Object a = Config.raw(m.id, "anchor");
        if (a instanceof String && (AUTO.equals(a) || anchorIdx((String) a) >= 0)) return (String) a;
        return Config.raw(m.id, "x") instanceof Number || Config.raw(m.id, "y") instanceof Number ? null : AUTO;
    }

    /**
     * Lays out every enabled HUD box for this frame (sets lk, bx, by, placed). Anchored boxes keep the distance from
     * their anchor (in GUI pixels, so window size / GUI scale changes keep them in their corner, and a box that grows
     * grows away from it). Old configs store top-left fractions: the first frame a box is laid out they are converted
     * once to the anchor nearest its centre with the exact offset of the same pixel, so nothing moves. Boxes never moved
     * stack into their home corner in module order, 2 GUI px apart, closing gaps when one is off
     * (corner stacking adapted from Velo Client's HudAutoLayout, MIT, see agent/NOTICE.md).
     */
    public static void place(int fbW, int fbH) {
        boolean edit = Menu.editing();
        float e = edge(), gap = 2 * Gfx.gui;
        List<Module> auto = null;
        fixed.clear();
        for (Module m : all) {
            m.placed = false;
            if (!m.hud || !m.hudPiece() || !m.enabled() || !(edit || m.visible())) continue;
            m.lk = m.layout();
            m.placed = true;
            float bw = m.w * m.lk, bh = m.h * m.lk;
            String a = anchor(m);
            if (AUTO.equals(a)) { if (auto == null) auto = new ArrayList<>(); auto.add(m); continue; }
            float x, y;
            if (a == null) {
                x = clamp((float) Config.num(m.id, "x", m.dx) * fbW, e, fbW - bw - e);
                y = clamp((float) Config.num(m.id, "y", m.dy) * fbH, e, fbH - bh - e);
                if (fbW >= 320 && fbH >= 240) {
                    setPos(m, x, y, fbW, fbH, false);
                    String n = anchor(m);
                    if (n != null) System.out.println("[nether-agent] layout: " + m.id + " x/y -> anchor " + n + " (" + Config.num(m.id, "ox", 0) + ", " + Config.num(m.id, "oy", 0) + ")");
                }
            } else {
                float ax = af(a, true), ay = af(a, false), g = Gfx.gui;
                x = clamp(ax * fbW + (float) Config.num(m.id, "ox", 0) * g - ax * bw, 0, fbW - bw); // placed boxes may sit flush (like the hotbar)
                y = clamp(ay * fbH + (float) Config.num(m.id, "oy", 0) * g - ay * bh, 0, fbH - bh);
            }
            m.bx = x;
            m.by = y;
            fixed.add(m);
        }
        if (auto == null) return;
        // tops first: the side stacks start below them
        float[] down = new float[3];
        for (int h = 0; h < 3; h++) down[h] = stack(auto, "t" + "lcr".charAt(h), e, fbW, fbH, e, gap);
        for (int h = 0; h < 3; h++) {
            String a = "m" + "lcr".charAt(h);
            float tot = -gap;
            for (Module m : auto) if (a.equals(m.home())) tot += m.h * m.lk + gap;
            if (tot > 0) stack(auto, a, Math.max(down[h], (fbH - tot) / 2), fbW, fbH, e, gap);
            // bottom centre starts above the hotbar, hearts and XP bar (about 52 GUI px)
            stackUp(auto, "b" + "lcr".charAt(h), fbH - e - (h == 1 ? 52 * Gfx.gui : 0), fbW, e, gap);
        }
    }

    /** boxes placed by the user this frame: auto stacks step past them instead of covering them */
    private static final List<Module> fixed = new ArrayList<>();

    /** y moved past every user-placed box the auto box (x, y, w, h) would cover (down = below them, else above). */
    private static float clear(float x, float y, float w, float h, float gap, boolean down) {
        for (int pass = 0; pass < 8; pass++) {
            boolean moved = false;
            for (Module f : fixed) {
                float fw = f.w * f.lk, fh = f.h * f.lk;
                if (x < f.bx + fw && x + w > f.bx && y < f.by + fh && y + h > f.by) { y = down ? f.by + fh + gap : f.by - h - gap; moved = true; }
            }
            if (!moved) break;
        }
        return y;
    }

    /** Auto boxes homed at a, downwards from y; returns the y under the last one. */
    private static float stack(List<Module> auto, String a, float y, int fbW, int fbH, float e, float gap) {
        for (Module m : auto) {
            if (!a.equals(m.home())) continue;
            float bw = m.w * m.lk, bh = m.h * m.lk;
            m.bx = autoX(a, bw, fbW, e);
            y = clear(m.bx, y, bw, bh, gap, true);
            m.by = clamp(y, e, fbH - bh - e);
            y += bh + gap;
        }
        return y;
    }

    private static void stackUp(List<Module> auto, String a, float y, int fbW, float e, float gap) {
        for (Module m : auto) {
            if (!a.equals(m.home())) continue;
            float bw = m.w * m.lk, bh = m.h * m.lk;
            m.bx = autoX(a, bw, fbW, e);
            y = clear(m.bx, y - bh, bw, bh, gap, false) + bh;
            y -= bh;
            m.by = Math.max(e, y);
            y -= gap;
        }
    }

    private static float autoX(String a, float bw, int fbW, float e) {
        char h = a.charAt(1);
        return h == 'l' ? e : h == 'r' ? Math.max(e, fbW - bw - e) : Math.max(e, (fbW - bw) / 2);
    }

    /**
     * Pins m's box with its top-left at (x, y) pixels (clamped on screen) to the anchor nearest its centre (screen
     * thirds), offset in GUI pixels. fractions = also refresh the old x/y keys (the launcher still reads those).
     */
    public static void setPos(Module m, float x, float y, int fbW, int fbH, boolean fractions) {
        float bw = m.w * m.lk, bh = m.h * m.lk, g = Gfx.gui;
        x = clamp(x, 0, fbW - bw);
        y = clamp(y, 0, fbH - bh);
        float cx = (x + bw / 2) / fbW, cy = (y + bh / 2) / fbH;
        String a = (cy < 1 / 3f ? "t" : cy > 2 / 3f ? "b" : "m") + (cx < 1 / 3f ? "l" : cx > 2 / 3f ? "r" : "c");
        float ax = af(a, true), ay = af(a, false);
        Config.set(m.id, "anchor", a);
        Config.set(m.id, "ox", (double) ((x + ax * bw - ax * fbW) / g));
        Config.set(m.id, "oy", (double) ((y + ay * bh - ay * fbH) / g));
        if (fractions) { Config.set(m.id, "x", (double) (x / fbW)); Config.set(m.id, "y", (double) (y / fbH)); }
        m.bx = x;
        m.by = y;
    }

    /** lo..hi, or lo when the box is wider than the room (the top-left stays visible). */
    public static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }

    /** HUD belongs to a loaded world (singleplayer or a server), never the title or loading screens. */
    static boolean inGame() { return Game.hasPlayer() || (Game.unavailable() && Input.gameMode == Input.DISABLED); }

    /**
     * Drawn over the finished frame, so the HUD steps aside wherever vanilla needs the screen: hidden with F1 / F3 and
     * behind any game screen (inventory, containers, pause); with chat open, boxes over the chat box or input line hide.
     * With our menu open (layout editor, previews) everything shows.
     */
    public static void drawHud(int fbW, int fbH) {
        boolean menu = Input.menuOpen;
        if (!menu && (!inGame() || Game.hudHidden() || (Game.screenOpen() && !Game.chatOpen()))) {
            for (Module m : all) m.tick();
            if (!Input.inWorld() || (Game.screenOpen() && !Game.chatOpen())) Hits.clear();
            if (!Game.hudHidden() && LYRICS.enabled()) menuLyrics(fbW, fbH);
            if (LYRICS.enabled() && Input.inWorld() && !Game.screenOpen() && LyricsWorldPanel.visible()) LyricsWorldPanel.draw(fbW, fbH);
            return;
        }
        float[] chat = null;
        if (!menu && Game.chatOpen()) {
            float g = Gfx.gui;
            float[] cb = Game.chatBox();
            // {right edge of the history, top of the history, top of the input line} in pixels
            chat = new float[] {(cb[0] + 12) * g, fbH - (cb[1] > 0 ? cb[1] + 44 : 16) * g, fbH - 16 * g};
        }
        float prev = Gfx.s;
        boolean r0 = Gfx.round;
        Gfx.round = Th.round(); // v6: rounded panels, bars and chips over the world too
        try { hud(fbW, fbH, chat); } finally { Gfx.round = r0; Gfx.s = prev; }
    }

    /** Title screen and game screens (pause, inventory, options): the lyrics panel stays, alone (issue #23). */
    private static void menuLyrics(int fbW, int fbH) {
        float prev = Gfx.s;
        boolean r0 = Gfx.round;
        Gfx.round = Th.round();
        LYRICS.menu = true;
        try {
            // clear of the menu's buttons (the HUD spot often sits on them): bottom centre, on our title screen bottom
            // left above its footer (the centre has the player and their name)
            if (LYRICS.visible()) {
                float k = LYRICS.layout(), lw = LYRICS.w * k, lh = LYRICS.h * k;
                boolean title = MainMenu.title;
                LYRICS.draw((title ? 12 * Gfx.gui : (fbW - lw) / 2) / k, (fbH - lh - (title ? 30 : 10) * Gfx.gui) / k);
            }
        } finally {
            LYRICS.menu = false;
            Gfx.round = r0;
            Gfx.s = prev;
        }
    }

    private static void hud(int fbW, int fbH, float[] chat) {
        Slow.mark("hud start");
        for (Module m : all) { m.tick(); Slow.mark2("tick ", m.id); }
        CROSSHAIR.sync();
        boxes(fbW, fbH, chat);
        boolean world = Input.inWorld() && !Game.hudHidden();
        if (WAYPOINTS.enabled() && world) WAYPOINTS.drawMarkers(fbW, fbH);
        if (TntTimer.M.enabled() && world) TntTimer.M.drawWorld(fbW, fbH);
        if (LYRICS.enabled() && world || Menu.live == LYRICS && Game.playing()) LYRICS.drawWorld(fbW, fbH); // the menu's world preview too
        if (LYRICS.enabled() && Input.inWorld() && (world || LyricsWorldPanel.visible() || Input.worldGuiOpen)) LyricsWorldPanel.draw(fbW, fbH);
        SELFTAG.drawWorld(fbW, fbH, SELFTAG.enabled() && world);
        if (world) { Toast.draw(fbW); SHOTS.drawCard(fbW); menuHint(fbW); }
    }

    // ---------------------------------------------------------------- HUD boxes, cached (Performance "Cache HUD")

    private static final Gfx.Rec boxRec = new Gfx.Rec();
    private static long boxNs;
    private static int boxEv, boxW, boxH, boxKey;
    private static float boxDt;
    /** Rebuilds per second at most while nothing else asks for one (above most monitors' refresh, so no visible step). */
    static final long BOX_NS = 1_000_000_000L / 144;

    /**
     * The HUD boxes. With Performance "Cache HUD" on, their geometry is built at most ~144 times a second and the recorded
     * GL ops are replayed on the frames between; any key / button / scroll, a window or GUI-scale change, chat opening, the
     * menu or a theme change rebuilds at once. Animations get the time of the skipped frames (Gfx.dt summed).
     * World-anchored markers (waypoints, lyrics, self tag) and toasts are drawn live every frame, outside this.
     */
    private static void boxes(int fbW, int fbH, float[] chat) {
        boxDt += Gfx.dt;
        boolean cache = Performance.M.hudCache() && !Input.menuOpen;
        long now = System.nanoTime();
        int key = (chat == null ? 0 : 1) | (Gfx.round ? 2 : 0) | (Gfx.pixel ? 4 : 0) | Float.floatToIntBits(Gfx.gui) << 3;
        if (cache && now - boxNs < BOX_NS && boxEv == Input.events && boxW == fbW && boxH == fbH && boxKey == key && Gfx.replay(boxRec)) {
            Slow.mark("hud replay");
            return;
        }
        float dt0 = Gfx.dt;
        Gfx.dt = Math.min(0.1f, boxDt);
        boxDt = 0;
        boxNs = now; boxEv = Input.events; boxW = fbW; boxH = fbH; boxKey = key;
        if (cache) Gfx.record(boxRec);
        try {
            place(fbW, fbH);
            Slow.mark("place");
            for (Module m : all) {
                if (!m.placed || !m.visible()) continue;
                float k = m.lk, bot = m.by + m.h * k;
                if (chat != null && bot > chat[2]) continue;
                if (chat != null && m.bx < chat[0] && bot > chat[1]) continue;
                Gfx.s = k;
                Gfx.hudPix = m.mcFont();
                try { m.draw(m.bx / k, m.by / k); } finally { Gfx.hudPix = false; }
                Slow.mark2("draw ", m.id);
            }
        } finally {
            if (cache) Gfx.recordEnd();
            else boxRec.gen = -1;
            Gfx.dt = dt0;
        }
    }

    // ---------------------------------------------------------------- first-run hint: where the menu is

    private static long hintFrom, worldSince;

    /** Once per profile: a few seconds into the first world, a toast names the menu key (modules.client.hintShown). */
    private static void menuHint(int fbW) {
        long now = System.currentTimeMillis();
        if (hintFrom == 0) {
            if (Config.bool("client", "hintShown", false)) { hintFrom = -1; return; }
            if (worldSince == 0) worldSince = now;
            if (now - worldSince < 2500) return;
            hintFrom = now;
            Config.set("client", "hintShown", true);
        }
        if (hintFrom < 0) return;
        long age = now - hintFrom;
        if (age > 8000) { hintFrom = -1; return; }
        float a = Config.calm() ? 1 : Math.min(1, Math.min(age / 200f, (8000 - age) / 400f));
        float s0 = Gfx.s, a0 = Gfx.alpha;
        Gfx.s = Gfx.base;
        Gfx.alpha = a0 * a;
        String key = Menu.keyName(Config.menuKey()), pre = "Press", post = "for the Nether Client menu";
        float kw = Gfx.width(Gfx.UI, 11, key) + 16, w1 = Gfx.width(Gfx.UI, 12, pre), w2 = Gfx.width(Gfx.UI, 12, post);
        float tw = 16 + w1 + 8 + kw + 8 + w2 + 16, tx = fbW / Gfx.s / 2 - tw / 2, ty = 84, th = 32;
        Toast.glass(tx, ty, tw, th);
        float x = tx + 16, cy = ty + th / 2;
        Gfx.text(Gfx.UI, 12, x, cy, Gfx.DIM, pre);
        x += w1 + 8;
        Gfx.rect(x, cy - 10, kw, 20, 4, Gfx.S3);
        Gfx.textC(Gfx.UI, 11, x + kw / 2, cy, Gfx.ICE, key, 0);
        x += kw + 8;
        Gfx.text(Gfx.UI, 12, x, cy, Gfx.ICE, post);
        Gfx.s = s0;
        Gfx.alpha = a0;
    }

    /** The menu was found: no hint needed any more. */
    static void hintSeen() {
        if (hintFrom > 0) hintFrom = Math.min(hintFrom, System.currentTimeMillis() - 7600); // showing: fade out now
        else if (hintFrom == 0) hintFrom = -1;
        if (!Config.bool("client", "hintShown", false)) Config.set("client", "hintShown", true);
    }

    /** Sample data (armor, effects) only where a user expects a mock-up: the layout editor and the module's settings preview. */
    public static boolean sampling(Module m) { return Input.menuOpen && (Menu.hudEdit || m.id.equals(Menu.page)); }

    // ---------------------------------------------------------------- input hooks

    /** Key event from the game's callback chain; true swallows it. */
    static boolean onKey(int key, int action) {
        boolean press = action != 0;
        // keys after which the game writes options.txt at once (F11 fullscreen, Ctrl+B narrator): put our values back first
        if (action == 1 && (key == 300 || key == 66 && (Input.keys[341] || Input.keys[345]))) restoreOptions();
        Zoom zm = ZOOM;
        if (key == (int) zm.num("zoomKey")) {
            if (action != 0 && zm.enabled() && Input.inWorld()) { // the game never sees the zoom key (no hotbar save on C)
                if (action == 1) zm.held = zm.toggle() ? !zm.held : true;
                return true;
            }
            if (action == 0 && !zm.toggle()) zm.held = false;
        }
        if (FREELOOK.key(key, action)) return true;
        if (press && !Input.inWorld()) return false;
        Waypoints wp = WAYPOINTS;
        if (action == 1 && wp.enabled() && key == (int) wp.num("addKey")) { Input.eatChar(); wp.addHere(true); return true; }
        if (action == 1 && wp.enabled() && key == (int) wp.num("toggleKey")) { wp.flip(); return true; }
        if (action == 1 && (MEDIA.key(key) || LYRICS.key(key) || STOPWATCH.key(key) || SHOTS.key(key))) return true;
        return false;
    }

    // ---------------------------------------------------------------- modules

    /** green / amber / red for good / ok / bad numbers ("#RRGGBB|" prefix understood by RowsModule). */
    public static String tint(boolean good, boolean ok, String v) { return (good ? "#3DDC84|" : ok ? "#FFB020|" : "#FF3B2F|") + v; }

    // The built-in HUD modules (FPS, CPS, Coordinates, Clock, Session, Memory, Keystrokes, Zoom, Fullbright, Potions,
    // Ping, Address, World Time, Speed, Compass, Crosshair) are in features/hud, one file each.
}
