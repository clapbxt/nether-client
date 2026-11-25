package dev.nether.ov;

import dev.nether.agent.Hook;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Module registry, HUD drawing loop and the input-side hooks. */
final class Modules {
    private Modules() {}

    static final List<Module> all = new ArrayList<>();
    // modules the input hooks and HUD loop need every event / frame (no lookups by id there)
    static final Crosshair CROSSHAIR = new Crosshair();
    static final Zoom ZOOM = new Zoom();
    static final Freelook FREELOOK = new Freelook();
    static final Fullbright FULLBRIGHT = new Fullbright();
    static final Waypoints WAYPOINTS = new Waypoints();
    static final MediaPlayer MEDIA = new MediaPlayer();
    static final Lyrics LYRICS = new Lyrics();
    static final LyricsBar LYRICS_BAR = new LyricsBar();
    static final Stopwatch STOPWATCH = new Stopwatch();
    static final Screenshots SHOTS = new Screenshots();
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
    static void shutdown() {
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
    static void release() {
        restoreOptions();
        Hook.shieldDy = 0; Hook.fireDy = 0; Hook.fireAlpha = -1; Hook.mountOf = null; // view editors back to vanilla
    }

    // ---------------------------------------------------------------- sensitivity: Zoom keeps one saved base

    private static double sensBase = -1;

    /**
     * Writes base sensitivity scaled by Zoom's factor (vanilla: speed = (0.6 v + 0.2)^3) and puts the base back
     * once it does not want a change.
     */
    static void syncSens() {
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

    static Module byId(String id) {
        for (Module m : all) if (m.id.equals(id)) return m;
        return null;
    }

    /** Gap kept between a HUD box and the screen edge, in pixels (2 GUI pixels). */
    static float edge() { return Gfx.gui * 2; }

    // ---------------------------------------------------------------- HUD layout: anchors + auto layout

    /** anchor value of a box that stacks into its corner ({@link Module#home}) until it is moved */
    static final String AUTO = "auto";
    static final String[] ANCHORS = {"tl", "tc", "tr", "ml", "mc", "mr", "bl", "bc", "br"};
    static final String[] ANCHOR_NAMES = {"TOP LEFT", "TOP", "TOP RIGHT", "LEFT", "CENTRE", "RIGHT", "BOTTOM LEFT", "BOTTOM", "BOTTOM RIGHT"};

    static int anchorIdx(String a) {
        if (a == null || a.length() != 2) return -1;
        int v = "tmb".indexOf(a.charAt(0)), h = "lcr".indexOf(a.charAt(1));
        return v < 0 || h < 0 ? -1 : v * 3 + h;
    }
    /** Anchor point as a fraction of the screen (0, 0.5, 1) on x (h = true) or y. */
    static float af(String a, boolean h) { return h ? "lcr".indexOf(a.charAt(1)) * 0.5f : "tmb".indexOf(a.charAt(0)) * 0.5f; }

    /** Stored anchor of m: one of {@link #ANCHORS}, {@link #AUTO}, or null = old x/y fractions (not migrated yet). */
    static String anchor(Module m) {
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
    static void place(int fbW, int fbH) {
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
    static void setPos(Module m, float x, float y, int fbW, int fbH, boolean fractions) {
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
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }

    /** HUD belongs to a loaded world (singleplayer or a server), never the title or loading screens. */
    static boolean inGame() { return Game.hasPlayer() || (Game.unavailable() && Input.gameMode == Input.DISABLED); }

    /**
     * Drawn over the finished frame, so the HUD steps aside wherever vanilla needs the screen: hidden with F1 / F3 and
     * behind any game screen (inventory, containers, pause); with chat open, boxes over the chat box or input line hide.
     * With our menu open (layout editor, previews) everything shows.
     */
    static void drawHud(int fbW, int fbH) {
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
        if (LYRICS.enabled() && world) LYRICS.drawWorld(fbW, fbH);
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
                m.draw(m.bx / k, m.by / k);
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
    static boolean sampling(Module m) { return Input.menuOpen && (Menu.hudEdit || m.id.equals(Menu.page)); }

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
    static String tint(boolean good, boolean ok, String v) { return (good ? "#3DDC84|" : ok ? "#FFB020|" : "#FF3B2F|") + v; }

    static final class Fps extends RowsModule {
        Fps() {
            super("fps", "FPS", "HUD", "Frames per second, updated every second.", true, 0.012, 0.02);
            add(Setting.bool("ms", "Show frame time", false));
            add(Setting.bool("tint", "Color by speed", false).hint("Green at 60 FPS and up, amber from 30, red below."));
            add(Setting.bool("stats", "Show average, minimum, 1% low", false).hint("Over the last 10 seconds. The 1% low is the speed of your worst frames."));
        }
        @Override void tick() { if (live() && bool("stats")) FrameStats.want = true; }
        private String c(float f, String v) { return bool("tint") ? tint(f >= 60, f >= 30, v) : v; }
        @Override String[][] rows() {
            int f = Overlay.fps;
            String v = bool("ms") && f > 0 ? f + "  " + String.format(Locale.ROOT, "%.1f ms", 1000.0 / f) : String.valueOf(f);
            if (!bool("stats")) return new String[][] {{"FPS", c(f, v)}};
            FrameStats.refresh(10, 1);
            if (FrameStats.avg <= 0) return new String[][] {{"FPS", c(f, v)}};
            return new String[][] {{"FPS", c(f, v)}, {"AVG", c(FrameStats.avg, String.valueOf(Math.round(FrameStats.avg)))},
                {"MIN", c(FrameStats.min, String.valueOf(Math.round(FrameStats.min)))}, {"1% LOW", c(FrameStats.low1, String.valueOf(Math.round(FrameStats.low1)))}};
        }
    }

    static final class Cps extends RowsModule {
        Cps() {
            super("cps", "CPS", "HUD", "Left and right clicks per second.", true, 0.012, 0.105);
            add(Setting.choice("show", "Show", "Split", "Split", "Combined", "Left", "Right").hint("Split = left | right, Combined = both added, or one button only."));
            add(Setting.bool("only", "Only while clicking", false).hint("The box is hidden until you click."));
        }
        @Override boolean visible() { return !bool("only") || Input.cps(0) + Input.cps(1) > 0 || sampling(this); }
        @Override String[][] rows() {
            int l = Input.cps(0), r = Input.cps(1);
            switch (str("show")) {
                case "Combined": return new String[][] {{"CPS", String.valueOf(l + r)}};
                case "Left": return new String[][] {{"CPS L", String.valueOf(l)}};
                case "Right": return new String[][] {{"CPS R", String.valueOf(r)}};
                default: return new String[][] {{"CPS", l + " | " + r}};
            }
        }
    }

    static final class Coords extends RowsModule {
        private String dim = "";
        Coords() {
            super("coords", "Coordinates", "HUD", "Position and the direction you are facing.", true, 0.012, 0.19);
            add(Setting.slider("decimals", "Decimals", 0, 3, 1, 1));
            add(Setting.bool("dir", "Show direction", true));
            add(Setting.bool("other", "Show Nether / Overworld match", false));
            needs(1);
        }
        @Override void tick() {
            if (!live() || !bool("other") || !due(500)) return;
            String k = Game.worldKey();
            dim = k == null ? "" : k.substring(k.indexOf('|') + 1);
        }
        @Override String[][] rows() {
            double[] p = Game.player();
            if (p == null) return new String[][] {{"POS", Game.unavailable() ? "not available" : "no world"}};
            if (Game.reducedDebug()) return new String[][] {{"POS", "hidden by server"}}; // F3 hides them here too
            String f = "%." + (int) num("decimals") + "f";
            String[] dir = {"SOUTH +Z", "WEST -X", "NORTH -Z", "EAST +X"};
            int d = (int) Math.floorMod((long) Math.floor((p[3] + 45) / 90), 4L);
            java.util.List<String[]> r = new java.util.ArrayList<>();
            r.add(new String[] {"X", String.format(Locale.ROOT, f, p[0])});
            r.add(new String[] {"Y", String.format(Locale.ROOT, f, p[1])});
            r.add(new String[] {"Z", String.format(Locale.ROOT, f, p[2])});
            if (bool("dir")) r.add(new String[] {"DIR", dir[d]});
            // portal planning: the matching spot in the other dimension (Nether is 1:8)
            boolean nether = dim.endsWith("the_nether"), over = dim.endsWith("overworld");
            if (bool("other") && (nether || over)) {
                double m = nether ? 8 : 1 / 8.0;
                r.add(new String[] {nether ? "OVERWORLD" : "NETHER", String.format(Locale.ROOT, "%d, %d", (long) Math.floor(p[0] * m), (long) Math.floor(p[2] * m))});
            }
            return r.toArray(new String[0][]);
        }
    }

    static final class Clock extends RowsModule {
        Clock() {
            super("clock", "Clock", "HUD", "Local time of day.", false, 0.92, 0.02);
            add(Setting.bool("h24", "24-hour clock", true));
            add(Setting.bool("seconds", "Show seconds", false));
            add(Setting.bool("date", "Show date", false));
        }
        @Override String[][] rows() {
            LocalTime t = LocalTime.now();
            boolean h24 = bool("h24"), sec = bool("seconds");
            int hh = h24 ? t.getHour() : (t.getHour() % 12 == 0 ? 12 : t.getHour() % 12);
            String s = String.format(Locale.ROOT, h24 ? "%02d:%02d" : "%d:%02d", hh, t.getMinute()) + (sec ? String.format(Locale.ROOT, ":%02d", t.getSecond()) : "") + (h24 ? "" : (t.getHour() < 12 ? " AM" : " PM"));
            if (bool("date")) {
                java.time.LocalDate d = java.time.LocalDate.now();
                String day = d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
                String mon = d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
                return new String[][] {{"TIME", s}, {"DATE", day + " " + d.getDayOfMonth() + " " + mon}};
            }
            return new String[][] {{"TIME", s}};
        }
    }

    /** Time spent in the current world/server; restarts when you join another one. */
    static final class Session extends RowsModule {
        private long since;
        private String where = "";
        private boolean was;

        Session() {
            super("session", "Session Timer", "HUD", "How long you've been in this world or server.", false, 0.87, 0.15);
            add(Setting.bool("seconds", "Show seconds", true));
            needs(1); // joining / leaving a world must be readable
        }

        @Override void tick() {
            if (!due(500)) return;
            boolean in = Game.hasPlayer();
            String w = in ? String.valueOf(Game.server()) : "";
            if (in && (!was || !w.equals(where))) { since = System.currentTimeMillis(); where = w; }
            was = in;
        }

        @Override String[][] rows() {
            long s = since == 0 ? 0 : (System.currentTimeMillis() - since) / 1000;
            String v = bool("seconds") ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%dh %02dm", s / 3600, s / 60 % 60);
            return new String[][] {{"SESSION", v}};
        }
    }

    static final class Memory extends RowsModule {
        Memory() {
            super("memory", "Memory", "HUD", "Java heap in use.", false, 0.87, 0.085);
            add(Setting.choice("mode", "Display", "Used / Max", "Used / Max", "Percent"));
        }
        @Override String[][] rows() {
            Runtime r = Runtime.getRuntime();
            double used = (r.totalMemory() - r.freeMemory()) / 1073741824.0, max = r.maxMemory() / 1073741824.0;
            String v = str("mode").equals("Percent") ? Math.round(used / max * 100) + "%" : String.format(Locale.ROOT, "%.1f / %.1f GB", used, max);
            return new String[][] {{"MEM", v}};
        }
    }

    static final class Keystrokes extends Module {
        static final float C = 24, G = 3;
        private final float[] an = new float[8];
        /** the game's own movement bindings {forward, left, back, right, jump} (rebound keys light the right boxes) */
        private final int[] bind = {87, 65, 83, 68, 32}, read = new int[5];
        private final String[] names = {"W", "A", "S", "D", "SPACE"};

        Keystrokes() {
            super("keys", "Keystrokes", "HUD", "WASD, space and mouse buttons with live CPS.", true, true, 1.0, 1.0);
            add(Setting.bool("mouse", "Mouse buttons", true));
            add(Setting.bool("cps", "Show CPS", true).when(() -> bool("mouse")));
            add(Setting.bool("space", "Space bar", true));
            add(Setting.slider("fade", "Pressed fade (ms)", 10, 400, 5, 55).hint("How long a key takes to light up and fade out."));
            drop("accent");
        }

        @Override void tick() {
            if (!live() || !due(1000)) return;
            migrate();
            if (!Game.moveKeys(read)) return;
            for (int i = 0; i < 5; i++) if (read[i] != bind[i]) { bind[i] = read[i]; names[i] = read[i] < 0 ? "-" : Menu.keyName(read[i]); }
        }

        private boolean down(int i) { int k = bind[i]; return k >= 0 && k < Input.keys.length && Input.keys[k]; }

        private boolean mouse() { return bool("mouse"); }
        private boolean space() { return bool("space"); }

        /** Once: an old Layout preset becomes the two switches it stood for. */
        private void migrate() {
            Object l = Config.raw(id, "layout");
            if (!(l instanceof String)) return;
            if (!"Custom".equals(l)) { Config.set(id, "mouse", l.equals("WASD + mouse") || l.equals("Full")); Config.set(id, "space", l.equals("WASD + space") || l.equals("Full")); }
            Config.set(id, "layout", null);
        }

        @Override void measure() {
            w = C * 3 + G * 2;
            h = C * 2 + G;
            if (mouse()) h += G + (bool("cps") ? 30 : C);
            if (space()) h += G + 14;
        }

        private void key(int i, boolean down, float x, float y, float kw, float kh, String label, String sub) {
            an[i] += ((down ? 1 : 0) - an[i]) * Math.min(1, Gfx.dt * 1000f / (float) Math.max(10, num("fade")));
            float a = an[i];
            int idle = bool("bg") ? Gfx.a(Gfx.BG, (float) num("bgAlpha")) : 0;
            int fill = Gfx.mix(idle, Gfx.a(Gfx.ICE, 0.94f), a);
            if ((fill >>> 24) > 0) Gfx.rect(x, y, kw, kh, 6, fill);
            Gfx.ring(x, y, kw, kh, 6, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0f), a));
            int tc = Gfx.mix(color(), Gfx.BG, a);
            float cy = y + kh / 2 - (sub != null ? 4.5f : 0);
            float ls = label.length() > 1 ? (label.length() > 5 && kw <= C ? 7 : 10) : 12;
            while (ls > 6 && Gfx.width(Gfx.SEMI, ls, label) > kw - 6) ls -= 0.5f; // a long key name (NUM ENTER, MOUSE 5) stays in its cell
            Gfx.textC(Gfx.SEMI, ls, x + kw / 2, cy, tc, Gfx.width(Gfx.SEMI, ls, label) > kw - 4 ? Menu.fit(label, Gfx.SEMI, ls, kw - 4, 0) : label, 0);
            if (sub != null) Gfx.textC(Gfx.MONO, 9, x + kw / 2, y + kh / 2 + 7.5f, Gfx.mix(Gfx.DIM, Gfx.BG, a), sub, 0);
        }

        @Override void draw(float x, float y) {
            // only what reaches the game: typing in chat, a sign or our menu lights nothing
            boolean on = Input.inWorld();
            key(0, on && down(0), x + C + G, y, C, C, names[0], null);
            key(1, on && down(1), x, y + C + G, C, C, names[1], null);
            key(2, on && down(2), x + C + G, y + C + G, C, C, names[2], null);
            key(3, on && down(3), x + (C + G) * 2, y + C + G, C, C, names[3], null);
            float yy = y + (C + G) * 2;
            if (mouse()) {
                float bw = (w - G) / 2, bh = bool("cps") ? 30 : C;
                boolean cps = bool("cps");
                key(4, on && Input.btn[0], x, yy, bw, bh, "LMB", cps ? String.valueOf(Input.cps(0)) : null);
                key(5, on && Input.btn[1], x + bw + G, yy, bw, bh, "RMB", cps ? String.valueOf(Input.cps(1)) : null);
                yy += bh + G;
            }
            if (space()) key(6, on && down(4), x, yy, w, 14, names[4], null);
        }
    }


    // ---------------------------------------------------------------- game-option modules (restored on release/exit)

    static final class Zoom extends Module {
        boolean held;
        private float cur = 1, hand = 1;
        private double origFov = -1;
        /** mouse speed factor while zoomed (1 = none), see {@link Modules#syncSens} */
        double sensScale = 1;

        Zoom() {
            super("zoom", "Zoom", "Visual", "Hold a key to zoom in smoothly. Scroll while zooming to adjust the level.", false, true, 0, 0);
            add(Setting.key("zoomKey", "Zoom key", 67));
            add(Setting.choice("mode", "Mode", "Hold", "Hold", "Toggle"));
            add(Setting.slider("level", "Zoom level", 1.5, 10, 0.5, 4));
            add(Setting.bool("smooth", "Smooth zoom", true));
            add(Setting.bool("scroll", "Scroll to adjust", true).hint("Up to 50x, for this zoom only: the next one starts at the zoom level again."));
            add(Setting.bool("sens", "Lower sensitivity while zoomed", true));
            add(Setting.bool("hideHand", "Hide held item while zoomed", false).hint("Eases it down out of view and back up (Fabric, Forge, NeoForge and 26.x)"));
            needs(3);
        }

        /** level scrolled to during this zoom (0 = the set level); dropped when the zoom ends */
        double live;

        boolean toggle() { return str("mode").equals("Toggle"); }
        double level() { return live > 0 ? live : num("level"); }
        @Override boolean available() { return super.available() && Game.optionsOk(); }

        @Override void tick() {
            if (!enabled() || !Game.playing()) { held = false; cur = 1; }
            if (!held) live = 0;
            float target = held ? (float) level() : 1f;
            // eased in log space: every step is the same share of the view, so it reads as one even glide
            if (bool("smooth") && !Config.calm()) cur = (float) Math.exp(Math.log(cur) + (Math.log(target) - Math.log(cur)) * Math.min(1f, Gfx.dt * 12f));
            else cur = target;
            if (Math.abs(cur - target) < 0.002f) cur = target;
            float ht = held && bool("hideHand") ? 0 : 1;
            hand = Config.calm() ? ht : hand + (ht - hand) * Math.min(1f, Gfx.dt * 10f);
            if (Math.abs(hand - ht) < 0.01f) hand = ht;
            Hook.handShow = hand;
            if (cur > 1f) {
                // hooked: GameRenderer's float FOV multiplier is divided (no whole-degree steps); else the FOV option
                if (Hook.fovHooked) Hook.fovDiv = cur;
                else {
                    if (origFov < 0) origFov = Game.fov();
                    Game.setFov(Math.max(2, origFov / cur));
                }
                sensScale = bool("sens") ? 1 / cur : 1; // mouse speed proportional to the field of view
                syncSens();
            } else restore();
        }

        void restore() {
            Hook.fovDiv = 1;
            if (origFov >= 0) { Game.setFov(origFov); origFov = -1; }
            if (sensScale != 1) { sensScale = 1; syncSens(); }
            cur = 1;
        }

        @Override void preview(float cx, float cy) {
            String kn = Menu.keyName((int) num("zoomKey"));
            float kw = Math.max(40, Gfx.width(Gfx.SEMI, 15, kn) + 26);
            Gfx.rect(cx - kw / 2, cy - 34, kw, 38, 8, Gfx.a(Gfx.ICE, 0.94f));
            Gfx.textC(Gfx.SEMI, 15, cx, cy - 15, Gfx.BG, kn, 0);
            Gfx.textC(Gfx.MONO, 10, cx, cy + 24, Gfx.DIM, str("mode").toUpperCase(Locale.ROOT) + "  -  " + String.format(Locale.ROOT, "%.1fx", num("level")), 0);
        }
    }

    static final class Fullbright extends Module {
        private double orig = Double.NaN;

        Fullbright() {
            super("fullbright", "Fullbright", "Visual", "Maximum brightness everywhere. Your own brightness setting is restored in Options and when you leave.", false, false, 0, 0);
            add(Setting.slider("level", "Brightness", 2, 15, 1, 12));
            needs(3);
        }

        @Override boolean available() { return super.available() && Game.optionsOk(); }

        private Class<?> cOpts, cPause;
        private boolean looked, inOpts;

        /** True from the game's Options screen through its sub-screens until the pause menu or the world is back: the game shows and saves gamma there. */
        private boolean options() {
            Object s = Game.screen();
            if (s == null) return inOpts = false;
            if (!looked) {
                looked = true;
                boolean l = Game.legacy();
                cOpts = Game.cls0(l ? Game.l("axn", "net.minecraft.client.gui.GuiOptions") : "net.minecraft.class_429");
                cPause = Game.cls0(l ? Game.l("axp", "net.minecraft.client.gui.GuiIngameMenu") : "net.minecraft.class_433");
            }
            if (cOpts != null && cOpts.isInstance(s)) inOpts = true;
            else if (cPause != null && cPause.isInstance(s)) inOpts = false;
            return inOpts;
        }

        @Override void tick() {
            // stays on behind chat, inventories and the pause menu (issue #20); off only where the game shows or saves the setting
            if (enabled() && Game.inWorld() && !options()) {
                if (Double.isNaN(orig)) orig = Game.gamma();
                double lv = num("level");
                if (Game.gamma() != lv) Game.setGamma(lv);
            } else restore();
        }

        void restore() {
            if (!Double.isNaN(orig)) { Game.setGamma(orig); orig = Double.NaN; }
        }

        @Override void preview(float cx, float cy) {
            float bw = 104, bh = 74, g = 14;
            for (int i = 0; i < 2; i++) {
                float x = i == 0 ? cx - g / 2 - bw : cx + g / 2;
                Gfx.rectG(x, cy - bh / 2, bw, bh, 8, i == 0 ? 0xFF050508 : 0xFFF4F7FB, i == 0 ? 0xFF111116 : 0xFFC9D1DB);
                Gfx.ring(x, cy - bh / 2, bw, bh, 8, 1, Gfx.LN);
                Gfx.textC(Gfx.SEMI, 10, x + bw / 2, cy, i == 0 ? Gfx.MUT : Gfx.BG, i == 0 ? "OFF" : "ON", 0);
            }
        }
    }

    // ---------------------------------------------------------------- display-only HUD modules

    static final class Potions extends Module {
        private static final String[] ROMAN = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        private static final float RH = 22, IC = 18;
        private final List<String[]> raw = new ArrayList<>();
        /** {name, time, icon key, ticks} per shown effect */
        private String[][] list = new String[0][];
        private float nameW, timeW;

        Potions() {
            super("potions", "Potion Effects", "HUD", "Active status effects with their icon, level and time left.", true, false, 0.87, 0.24);
            add(Setting.bool("icons", "Effect icons", true));
            add(Setting.bool("blink", "Blink when running out", true));
            add(Setting.slider("lowSecs", "Warn below (s)", 3, 60, 1, 10).hint("Time left under this turns red."));
            add(Setting.choice("sort", "Sort by", "Time left", "Time left", "Level", "Name").hint("Order of the list."));
            add(Setting.bool("ambient", "Hide beacon effects", false).hint("Leaves out the effects a beacon or conduit keeps giving you."));
            add(Setting.bool("compact", "Compact", false).when(() -> bool("icons")).hint("Icon and time only, tighter rows (names stay for effects without an icon)."));
            needs(4);
        }

        @Override void tick() {
            if (!live() || !due(250)) return;
            Game.effects(raw);
            if (bool("ambient")) raw.removeIf(a -> a.length > 4 && "1".equals(a[4]));
            // longest first (infinite on top) by default, so rows never swap places while they count down
            String sort = str("sort");
            raw.sort((a, b) -> {
                int x = ticks(a), y = ticks(b);
                if (sort.equals("Name")) return a[0].compareTo(b[0]);
                if (sort.equals("Level")) { int c = Integer.compare(amp(b), amp(a)); return c != 0 ? c : x == y ? a[0].compareTo(b[0]) : Integer.compare(y, x); }
                return x == y ? a[0].compareTo(b[0]) : Integer.compare(y, x);
            });
            if (raw.isEmpty() && sampling(this)) {
                boolean old = Game.legacy();
                list = new String[][] {{"SPEED II", "1:42", old ? "inv:0" : "mob_effect:minecraft:speed", "2040"},
                    {"REGENERATION", "0:08", old ? "inv:7" : "mob_effect:minecraft:regeneration", "160"},
                    {"NIGHT VISION", "7:30", old ? "inv:12" : "mob_effect:minecraft:night_vision", "9000"}};
                return;
            }
            int n = Math.min(8, raw.size());
            if (list.length != n) list = new String[n][];
            for (int i = 0; i < n; i++) {
                String[] x = raw.get(i);
                int amp = Integer.parseInt(x[2]), ticks = Integer.parseInt(x[1]);
                String name = x[0].toUpperCase(Locale.ROOT) + (amp > 0 ? " " + (amp < ROMAN.length ? ROMAN[amp] : String.valueOf(amp + 1)) : "");
                int sec = ticks / 20;
                list[i] = new String[] {name, ticks < 0 || ticks > 1_000_000 ? "INF" : String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60), x[3], String.valueOf(ticks)};
            }
        }

        private static int amp(String[] e) { try { return Integer.parseInt(e[2]); } catch (RuntimeException x) { return 0; } }
        /** compact: an effect with an icon is shown as icon + time only */
        private boolean nameShown(String[] r) { return !bool("compact") || !bool("icons") || Icons.effect(r[2]) == null; }

        private static int ticks(String[] e) { try { int t = Integer.parseInt(e[1]); return t < 0 ? Integer.MAX_VALUE : t; } catch (RuntimeException x) { return 0; } }

        @Override boolean visible() { return list.length > 0; }

        private float rh() { return bool("compact") ? 19 : RH; }

        @Override void measure() {
            nameW = timeW = 0;
            for (String[] r : list) { if (nameShown(r)) nameW = Math.max(nameW, Gfx.width(Gfx.SEMI, 11, r[0])); timeW = Math.max(timeW, Gfx.width(Gfx.MONO, 11, r[1])); }
            w = RowsModule.PX * 2 + (bool("icons") ? IC + 7 : 0) + nameW + (nameW > 0 ? 14 : 0) + timeW + (bool("accent") && bool("bg") ? 2 : 0);
            h = RowsModule.PY * 2 + rh() * Math.max(1, list.length);
        }

        @Override void draw(float x, float y) {
            panel(x, y, w, h);
            float lx = x + RowsModule.PX + (bool("accent") && bool("bg") ? 2 : 0);
            boolean icons = bool("icons"), blink = bool("blink");
            for (int i = 0; i < list.length; i++) {
                String[] r = list[i];
                float cy = y + RowsModule.PY + rh() * (i + 0.5f), tx = lx;
                if (icons) {
                    int[] ic = Icons.effect(r[2]);
                    if (ic != null) Gfx.icon(ic, lx, cy - IC / 2, IC);
                    else Gfx.rect(lx + 5, cy - 4, 8, 8, 2, Gfx.a(Gfx.DIM, 0.5f));
                    tx += IC + 7;
                }
                int t = Integer.parseInt(r[3]);
                int low = (int) num("lowSecs") * 20;
                int tc = t >= 0 && t < low ? Gfx.RED2 : Gfx.DIM;
                if (blink && t >= 0 && t < low && (System.currentTimeMillis() / 400) % 2 == 0 && !Config.calm()) tc = Gfx.a(tc, 0.35f);
                if (nameShown(r)) Gfx.text(Gfx.SEMI, 11, tx, cy, color(), r[0], bool("shadow"), 0);
                Gfx.text(Gfx.MONO, 11, x + w - RowsModule.PX - Gfx.width(Gfx.MONO, 11, r[1]), cy, tc, r[1], bool("shadow"), 0);
            }
        }
    }

    static final class Ping extends RowsModule {
        private String v = "-";

        Ping() { super("ping", "Ping", "HUD", "Latency to the server, from the player list.", false, 0.15, 0.02); needs(4);
            add(Setting.bool("tint", "Color by latency", true).hint("Green under 80 ms, amber under 160, red above."));
        }

        @Override void tick() {
            if (!live() || !due(500)) return;
            String sv = Game.server();
            int p = Game.ping();
            v = sv == null ? "-" : sv.equals("Singleplayer") ? "LOCAL" : p >= 0 ? (bool("tint") ? tint(p < 80, p < 160, p + " ms") : p + " ms") : "-";
        }

        @Override String[][] rows() { return new String[][] {{"PING", v}}; }
    }

    static final class Address extends RowsModule {
        private String v = "-";

        Address() {
            super("server", "Server Address", "HUD", "Address of the server you are connected to.", false, 0.15, 0.105); needs(4);
            add(Setting.bool("mask", "Streamer mode", false).hint("Shows play.***.net instead of the full address."));
        }

        @Override void tick() {
            if (!live() || !due(1000)) return;
            String sv = Game.server();
            if (sv != null && bool("mask") && !sv.equals("Singleplayer")) {
                String[] p = sv.replaceAll(":\\d+$", "").split("\\.");
                sv = p.length >= 3 && !p[0].matches("\\d+") ? p[0] + ".***." + p[p.length - 1] : p.length == 2 ? "***." + p[1] : "***";
            }
            v = sv == null ? "-" : sv.length() > 26 ? sv.substring(0, 25) + ".." : sv;
        }

        @Override String[][] rows() { return new String[][] {{"IP", v}}; }
    }

    static final class WorldTime extends RowsModule {
        private String day = "-", time = "-";

        WorldTime() {
            super("worldtime", "Day Counter", "HUD", "In-game day and time of day.", false, 0.15, 0.27);
            add(Setting.bool("time", "Show time of day", true));
            needs(4);
        }

        @Override void tick() {
            if (!live() || !due(250)) return;
            long t = Game.worldTime();
            if (t < 0) { day = time = "-"; return; }
            day = String.valueOf(t / 24000 + 1);
            int m = (int) ((t % 24000) * 60 / 1000 + 360) % 1440;
            time = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
        }

        @Override String[][] rows() {
            return bool("time") ? new String[][] {{"DAY", day}, {"TIME", time}} : new String[][] {{"DAY", day}};
        }
    }

    static final class Speed extends RowsModule {
        private final ArrayDeque<double[]> samples = new ArrayDeque<>();
        private double v;

        Speed() {
            super("speed", "Speed", "HUD", "Your movement speed from position changes.", false, 0.15, 0.19);
            add(Setting.choice("unit", "Unit", "Blocks/s", "Blocks/s", "km/h"));
            add(Setting.bool("vertical", "Include vertical movement", false));
            needs(1);
        }

        @Override void tick() {
            if (!live()) return;
            double[] p = Game.player();
            if (p == null) { samples.clear(); v = 0; return; }
            double now = System.nanoTime() / 1e9;
            double[] last = samples.peekLast();
            if (last != null && Math.abs(p[0] - last[1]) + Math.abs(p[2] - last[3]) > 24) samples.clear(); // teleport
            samples.addLast(new double[] {now, p[0], p[1], p[2]});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > 0.6) samples.pollFirst();
            double[] a = samples.peekFirst(), b = samples.peekLast();
            double dt = b[0] - a[0];
            if (dt < 0.1) return;
            double dx = b[1] - a[1], dz = b[3] - a[3], dy = bool("vertical") ? b[2] - a[2] : 0;
            v = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
        }

        @Override String[][] rows() {
            boolean kmh = str("unit").equals("km/h");
            return new String[][] {{"SPEED", String.format(Locale.ROOT, kmh ? "%.1f km/h" : "%.1f b/s", kmh ? v * 3.6 : v)}};
        }
    }

    static final class Compass extends Module {
        private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        private double bearing = 180;

        Compass() {
            super("compass", "Compass", "HUD", "A heading strip with cardinal directions.", true, false, 0.39, 0.02);
            add(Setting.bool("heading", "Show heading", true));
            add(Setting.bool("points", "Waypoint marks", true).hint("A colored tick on the strip for each waypoint, fading with distance."));
            needs(1);
        }

        private double px, pz;

        @Override void tick() {
            if (!live()) return;
            double[] p = Game.player();
            if (p != null) { bearing = ((p[3] + 180) % 360 + 360) % 360; px = p[0]; pz = p[2]; }
        }

        /** Waypoint ticks: one per waypoint of this world inside the strip, fading with distance (full within 100 blocks, gone at 1000). */
        private void waypointTicks(float x, float y, float cx, float ppd, float span) {
            if (!bool("points") || !WAYPOINTS.enabled() || !WAYPOINTS.bool("shown") || Game.reducedDebug()) return;
            List<java.util.Map<String, Object>> l = WAYPOINTS.list(false);
            if (l == null) return;
            for (java.util.Map<String, Object> w : l) {
                if (Boolean.FALSE.equals(w.get("on"))) continue;
                double dx = Waypoints.num(w, "x") + 0.5 - px, dz = Waypoints.num(w, "z") + 0.5 - pz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                double d = ((yaw + 180 - bearing + 540) % 360 + 360) % 360 - 180;
                if (Math.abs(d) > span || dist < 1) continue;
                float fade = Math.max(0.3f, 1 - (float) Math.max(0, dist - 100) / 900f) * (1 - (float) (d * d) / (span * span));
                if (fade <= 0.05f) continue;
                int c = Gfx.a(Waypoints.col(w), fade);
                float tx = cx + (float) d * ppd;
                Gfx.rect(tx - 1.5f, y + 4, 3, 7, 1, c);
            }
        }

        @Override void measure() { w = 250; h = bool("heading") ? 38 : 28; }

        @Override void draw(float x, float y) {
            panel(x, y, w, h);
            boolean hd = bool("heading");
            float cx = x + w / 2, ppd = 1.9f, span = 62, ty = y + (hd ? 25 : 20);
            int col = color();
            for (int deg = (int) Math.floor((bearing - span) / 5) * 5; deg <= bearing + span; deg += 5) {
                float d = (float) (deg - bearing);
                float fade = 1 - (d * d) / (span * span);
                if (fade <= 0.02f) continue;
                int hh = ((deg % 360) + 360) % 360;
                float tx = cx + d * ppd;
                if (hh % 45 == 0) {
                    String s = DIRS[hh / 45];
                    boolean major = s.length() == 1;
                    Gfx.textC(Gfx.SEMI, major ? 12 : 9, tx, y + 10, Gfx.a(major ? (hh == 0 ? Th.ac2 : col) : Gfx.DIM, fade), s, 0);
                } else if (hh % 15 == 0) Gfx.rect(tx - 0.5f, ty - 4, 1, 4, 0, Gfx.a(Gfx.DIM, fade));
                else Gfx.rect(tx - 0.5f, ty - 2, 1, 2, 0, Gfx.a(Gfx.MUT, fade));
            }
            waypointTicks(x, y, cx, ppd, span);
            Gfx.rect(cx - 1, y + 5, 2, h - (hd ? 20 : 10), 1, Gfx.a(Th.ac, 0.9f));
            if (hd) Gfx.textC(Gfx.MONO, 10, cx, y + h - 6, Gfx.DIM, String.valueOf(Math.round(bearing) % 360), 0);
        }
    }

    /**
     * Crosshair the way resource packs do it: the design is drawn into the built-in "Nether Client" pack (always the top
     * pack) as the game's own crosshair texture (1.20.2+ gui/sprites/hud/crosshair, before that the crosshair cell of
     * gui/icons copied from the packs below), then the game reloads its resources once our menu is closed. The game
     * draws it itself, so it shows exactly where and when vanilla's would, with vanilla's invert blending (white reads
     * on any background; colours would come out inverted, so there is no colour setting).
     */
    static final class Crosshair extends Module {
        Crosshair() {
            super("crosshair", "Crosshair", "Visual", "Your own crosshair shape, drawn by the game like a resource pack's.", false, false, 0, 0);
            add(Setting.choice("style", "Style", "Cross", "Cross", "Dot", "Circle", "Square").hint("Cross with gap 0 is a plus."));
            add(Setting.slider("size", "Size", 2, 12, 1, 5));
            add(Setting.slider("thickness", "Thickness", 0.5, 3, 0.5, 1));
            add(Setting.slider("gap", "Gap", 0, 8, 1, 3).when(() -> !str("style").equals("Dot")));
            add(Setting.bool("dot", "Center dot", false).when(() -> !str("style").equals("Dot")));
        }

        /** texels per GUI pixel of the texture we write (the game draws the crosshair 15 GUI px square) */
        private static final int R = 4;
        private static final String SPRITE = "assets/minecraft/textures/gui/sprites/hud/crosshair.png", ICONS = "assets/minecraft/textures/gui/icons.png";
        private String seen;
        private int seenGen = -1;
        private boolean reload;
        private long changedAt, checkedAt;

        private static java.nio.file.Path pack() { return java.nio.file.Paths.get("resourcepacks", "Nether Client").toAbsolutePath(); }
        private static boolean sprite() { return Game.loader().getResource(SPRITE) != null; }

        private String key() { return str("style") + num("size") + "/" + num("thickness") + "/" + num("gap") + bool("dot"); }

        /** The design, white on clear, n texels square with the crosshair's centre in the middle (1 unit = half a GUI px). */
        private java.awt.image.BufferedImage design(int n) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
            String st = str("style");
            float size = (float) num("size"), th = (float) num("thickness"), gap = (float) num("gap");
            float reach = st.equals("Dot") ? Math.max(th * 2, size / 2) / 2 : st.equals("Plus") ? size + gap
                : st.equals("Circle") || st.equals("Square") ? size + gap / 2 + th / 2 : gap + size;
            float u = n / 30f * Math.min(1, 14.5f / Math.max(1, reach)); // texels per unit, shrunk so the design fits the 15 px cell
            float c = n / 2f;
            int t = Math.max(1, Math.round(th * u)), ci = Math.round(c);
            switch (st) {
                case "Cross": case "Plus": {
                    boolean plus = st.equals("Plus");
                    int g0 = plus ? 0 : Math.round(gap * u), len = Math.max(1, Math.round((plus ? size + gap : size) * u));
                    int a = Math.round(c - t / 2f);
                    g.fillRect(ci - g0 - len, a, len, t);
                    g.fillRect(ci + g0, a, len, t);
                    g.fillRect(a, ci - g0 - len, t, len);
                    g.fillRect(a, ci + g0, t, len);
                    if (plus) g.fillRect(a, a, t, t);
                    break;
                }
                case "Dot": {
                    int d = Math.max(1, Math.round(Math.max(th * 2, size / 2) * u));
                    g.fillOval(Math.round(c - d / 2f), Math.round(c - d / 2f), d, d);
                    break;
                }
                default: {
                    float r = (size + gap / 2) * u;
                    boolean circle = st.equals("Circle");
                    int o0 = Math.round(c - r - t / 2f), os = Math.round(2 * r + t);
                    java.awt.Shape outer = circle ? new java.awt.geom.Ellipse2D.Float(c - r - t / 2f, c - r - t / 2f, 2 * r + t, 2 * r + t) : new java.awt.Rectangle(o0, o0, os, os);
                    java.awt.Shape inner = circle ? new java.awt.geom.Ellipse2D.Float(c - r + t / 2f, c - r + t / 2f, 2 * r - t, 2 * r - t) : new java.awt.Rectangle(o0 + t, o0 + t, os - 2 * t, os - 2 * t);
                    java.awt.geom.Area ring = new java.awt.geom.Area(outer);
                    ring.subtract(new java.awt.geom.Area(inner));
                    g.fill(ring);
                }
            }
            if (bool("dot") && !st.equals("Dot")) {
                int d = Math.max(1, Math.round(Math.max(th, 1) * u));
                g.fillRect(Math.round(c - d / 2f), Math.round(c - d / 2f), d, d);
            }
            g.dispose();
            return img;
        }

        /** 1.20.2+: the sprite itself. Before: icons copied from the packs below ours (upscaled), the crosshair cell replaced. */
        private byte[] png() throws Exception {
            java.awt.image.BufferedImage out;
            if (sprite()) out = design(15 * R);
            else {
                java.awt.image.BufferedImage base = Res.image(ICONS, "Nether Client");
                if (base == null || base.getWidth() < 256) return null;
                int k = base.getWidth() / 256, s = Math.max(k, R) / k * k; // texels per GUI px in the file
                out = new java.awt.image.BufferedImage(256 * s, base.getHeight() / k * s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = out.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(base, 0, 0, out.getWidth(), out.getHeight(), null);
                g.setComposite(java.awt.AlphaComposite.Clear);
                g.fillRect(0, 0, 15 * s, 15 * s);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
                g.drawImage(design(15 * s), 0, 0, null);
                g.dispose();
            }
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            javax.imageio.stream.MemoryCacheImageOutputStream mo = new javax.imageio.stream.MemoryCacheImageOutputStream(b);
            javax.imageio.ImageIO.write(out, "png", mo);
            mo.close();
            return b.toByteArray();
        }

        /** Keeps the pack file in step with the settings (checked 2x a second); a changed file asks the game to reload once our menu is closed. */
        void sync() {
            long now = System.currentTimeMillis();
            if ("Plus".equals(Config.raw(id, "style"))) { Config.set(id, "style", "Cross"); Config.set(id, "gap", 0.0); } // Plus was Cross at gap 0
            if (reload && !Input.menuOpen && now - changedAt > 400) { reload = false; reloadGame(); }
            if (now - checkedAt < 500) return;
            checkedAt = now;
            boolean on = wantedOn() && available();
            String key = on ? key() : "off";
            boolean legacy = !sprite();
            int gen = legacy ? Res.gen() : 0; // before 1.20.2 our icons copy follows the packs under it
            if (key.equals(seen) && gen == seenGen) return;
            seen = key;
            seenGen = gen;
            try {
                if (!java.nio.file.Files.isDirectory(pack())) return; // the launcher creates the pack; without it there is nothing to override
                java.nio.file.Path f = pack().resolve(legacy ? ICONS : SPRITE);
                if (!on) {
                    if (java.nio.file.Files.deleteIfExists(f)) { reload = true; changedAt = now; }
                    return;
                }
                byte[] b = png();
                if (b == null) return;
                if (java.nio.file.Files.exists(f) && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(f), b)) return;
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                java.nio.file.Files.write(tmp, b);
                java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                reload = true;
                changedAt = now;
            } catch (Throwable t) {
                System.out.println("[nether-agent] crosshair texture: " + t);
            }
        }

        /**
         * The game's own resource reload, found by shape (no names): the public no-argument method of the game class that
         * returns a future (1.14+ reloadResourcePacks / delayTextureReload, 1.8.9 scheduleResourcesRefresh; each reloads).
         */
        private static void reloadGame() {
            Object mc = Game.mc();
            if (mc == null) return;
            for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(m.getModifiers()) || !java.util.concurrent.Future.class.isAssignableFrom(m.getReturnType())) continue;
                try { m.invoke(mc); System.out.println("[nether-agent] crosshair: resources reloading"); }
                catch (Throwable t) { System.out.println("[nether-agent] crosshair reload: " + t); }
                return;
            }
        }

        private String previewKey;

        @Override void preview(float cx, float cy) {
            String key = key();
            if (!key.equals(previewKey) || !Gfx.hasTexture("xhair")) { previewKey = key; Gfx.texture("xhair", design(15 * R), false); }
            float s = 30 * 1.8f;
            Gfx.image("xhair", cx - s / 2, cy - s / 2, s, s, 0xFFFFFFFF, 0);
        }
    }
}
