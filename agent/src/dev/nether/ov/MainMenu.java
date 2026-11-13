package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client "menu" group: our own title screen (backgrounds, three layouts, button styles, logo + splash, clock, account,
 * quick join) and a Nether button on the pause screen. Vanilla's screens stay underneath: on the title screen its
 * widgets and splash are removed, its logo and version text faded out (modern) or covered (1.8.9), the panorama keeps
 * running. Buttons open vanilla's own screens through reflection. A NETHER / VANILLA switch sits in the top-left corner
 * of both our screen and vanilla's, so either one is a click away; the module switch (synced with the launcher's
 * modules.client.mainMenu) is the same setting. Needs bound names (Fabric intermediary / Mojang / same-version obf
 * tables, 1.8.9 notch / SRG); elsewhere vanilla's menu stays untouched.
 */
final class MainMenu {
    private MainMenu() {}

    // before MODULE: its settings read them during class init
    private static final String[] LAYOUTS = {"Center", "Left", "Bottom"}, STYLES = {"Solid", "Outline", "Minimal"}, LOGOS = {"Wordmark", "Mark", "Hidden"};

    static final Module MODULE = new Module("menu", "Main Menu", "Visual",
        "Nether title screen: backgrounds, layouts, quick join and your account. Switch to vanilla from the title screen itself.", false, true, 0, 0) { };

    static {
        Module m = MODULE;
        m.needs(4);
        m.add(Setting.header("Background"));
        m.add(Setting.choice("bg", "Background", "Panorama", MenuBack.KINDS));
        m.add(Setting.slider("dim", "Dim", 0, 0.8, 0.05, 0.3).hint("Darkens the panorama or image behind the menu."));
        m.add(Setting.slider("vignette", "Vignette", 0, 1, 0.05, 0.5));
        m.add(Setting.bool("motion", "Animate background", true).hint("Ember drift, grid sweep, slow pan on images. Reduce motion stops it."));
        m.add(Setting.choice("imageFit", "Image fit", "Fill", "Fill", "Fit", "Stretch"));
        m.add(Setting.slider("imageBlur", "Image blur", 0, 1, 0.1, 0).hint("Images only: the panorama is drawn by the game and stays sharp."));
        m.add(Setting.slider("slideshow", "Slideshow (seconds)", 0, 120, 5, 0).hint("0 keeps one image, otherwise every image in the folder in turn."));
        m.add(Setting.text("imageFile", "Image file", "", 96).hint("A file in client/backgrounds; empty shows the first one."));
        m.add(Setting.action("folder", "Backgrounds folder", "OPEN", MenuBack::openFolder).hint("PNG, JPG or GIF (first frame)."));
        m.add(Setting.action("next", "Next image", "NEXT", MenuBack::next));
        m.add(Setting.header("Layout"));
        m.add(Setting.choice("layout", "Layout", "Center", LAYOUTS));
        m.add(Setting.choice("buttons", "Button style", "Solid", STYLES));
        m.add(Setting.choice("logo", "Logo", "Wordmark", LOGOS));
        m.add(Setting.bool("splash", "Splash text", true));
        m.add(Setting.bool("clock", "Clock", true).hint("Follows the Clock module's 24-hour setting."));
        m.add(Setting.bool("account", "Account", true).hint("Your name and skin head (skin from Mojang's profile service)."));
        m.add(Setting.header("Quick join"));
        m.add(Setting.slider("servers", "Servers shown", 0, 5, 1, 3).hint("From your multiplayer list, last played first. 0 hides the card."));
        m.add(Setting.bool("ping", "Live server status", true).hint("Pings the listed servers for players and latency, like the multiplayer screen."));
        m.add(Setting.header("Pause screen"));
        m.add(Setting.bool("pauseButton", "Nether button", true));
    }

    private static Class<?> cTitle, cPause, cMods;
    private static Field fFading, fFadeStart, fSplash;
    private static boolean looked, legacy;
    /** our title screen is drawn this frame (every click on it is ours) */
    static boolean title, pause;
    private static boolean vanillaTitle;
    private static boolean pressed, click;
    private static long shownAt;
    /** the title screen whose widgets we removed (rebuilt by {@link #restoreVanilla}) and the splash vanilla picked for it */
    private static Object stripped, splashOf;
    private static String splash;

    static final int SINGLE = 0, MULTI = 1, MODS = 2, OPTIONS = 3, QUIT = 4, NETHER = 5;
    private static final String[] LABELS = {"SINGLEPLAYER", "MULTIPLAYER", "MODS", "OPTIONS", "QUIT", "NETHER CLIENT"};

    // ---------------------------------------------------------------- on / off (module switch <-> launcher key)

    private static int syncM = -1, syncE = -1;

    /** Our title screen wanted: the module switch, kept equal to modules.client.mainMenu (the launcher's switch). */
    private static boolean wanted() {
        int mm = Config.bool("client", "mainMenu", true) ? 1 : 0, en = MODULE.wantedOn() ? 1 : 0;
        if (syncM < 0 ? mm != en : mm != syncM) { if (en != mm) Config.set("menu", "enabled", mm == 1); en = mm; }
        else if (en != syncE) { Config.set("client", "mainMenu", en == 1); mm = en; }
        syncM = mm;
        syncE = en;
        return en == 1;
    }

    private static void look() {
        if (looked) return;
        looked = true;
        legacy = Game.legacy();
        try {
            cTitle = Game.cls(legacy ? Game.l("aya", "net.minecraft.client.gui.GuiMainMenu") : "net.minecraft.class_442");
            cPause = Game.cls(legacy ? Game.l("axp", "net.minecraft.client.gui.GuiIngameMenu") : "net.minecraft.class_433");
        } catch (Throwable t) {
            cTitle = null;
            System.out.println("[nether-agent] main menu unavailable: " + t);
            return;
        }
        fSplash = Game.field(cTitle, legacy ? Game.l("r", "field_73975_c") : "field_2586");
        if (!legacy) { fFading = Game.field(cTitle, "field_18222"); fFadeStart = Game.field(cTitle, "field_17772"); }
        // the loader's mod list (its button lived on the title screen we replace): Mod Menu, Forge, NeoForge, Forge 1.8.9-1.12.2
        String[] mods = legacy ? new String[] {"net.minecraftforge.fml.client.GuiModList"}
            : new String[] {"com.terraformersmc.modmenu.gui.ModsScreen", "net.neoforged.neoforge.client.gui.ModListScreen", "net.minecraftforge.client.gui.ModListScreen"};
        for (String n : mods) {
            try { cMods = Class.forName(n, false, cTitle.getClassLoader()); break; } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- frame

    /** Before the in-game menu each frame. */
    static void frame(int fbW, int fbH) {
        boolean was = title;
        title = pause = vanillaTitle = false;
        click = pressed;
        pressed = false;
        nb = 0;
        modal = null;
        if (!MODULE.injected() || !MODULE.available()) return;
        look();
        if (cTitle == null) return;
        QuickJoin.track();
        boolean want = wanted();
        Object s = Game.screen();
        if (s == null) { panelOpen = false; return; }
        boolean isTitle = cTitle.isInstance(s);
        if (isTitle && !want && stripped == s) restoreVanilla(); // switched off here: vanilla rebuilds its buttons and fades back in
        Gfx.minPx = 9; // corner text and labels stay legible in small windows
        try {
            if (isTitle && want) {
                title = true;
                if (!was) shownAt = System.nanoTime();
                strip(s);
                stripped = s;
                drawTitle(fbW, fbH, s);
            } else if (isTitle) {
                vanillaTitle = true;
                panelOpen = false;
                units(Math.min(Gfx.base, Math.min(fbW / 640f, fbH / 480f)));
                topBar(false, s);
            } else if (cPause != null && cPause.isInstance(s) && !Input.menuOpen && MODULE.bool("pauseButton")) {
                // F3+Esc pauses without the menu (screenshots): no button either
                if (legacy || !Boolean.FALSE.equals(Game.fld(s, "field_19319"))) {
                    pause = true;
                    units(Gfx.base);
                    if (chip("pause", 12, 12, 32, "NETHER", keyName(), true, false)) Input.setMenu(true);
                }
            }
        } finally {
            Gfx.minPx = 0;
            Gfx.s = Gfx.base;
        }
    }

    /** Overlay death or switched off: re-open the title screen we stripped, so vanilla rebuilds its buttons and splash. */
    static void restoreVanilla() {
        Object s = stripped;
        stripped = null;
        title = pause = false;
        if (s == null || s != Game.screen()) return;
        try {
            if (legacy && fSplash != null && splash != null) fSplash.set(s, splash);
            if (fFading != null && Config.calm()) fFading.setBoolean(s, false);
        } catch (Throwable ignored) { }
        open(Game.mc(), s);
    }

    /** Remove vanilla's title widgets and splash (they come back on every resize/init); fade its logo and version text out. */
    private static void strip(Object s) {
        try {
            if (splashOf != s) { splashOf = s; splash = null; }
            Object sp = fSplash != null ? fSplash.get(s) : null;
            if (sp != null && !"".equals(sp)) {
                if (splash == null) splash = sp instanceof String ? (String) sp : str(Game.call(Game.fld(sp, "field_44666"), "getString"));
                fSplash.set(s, legacy ? "" : null);
            }
            if (legacy) {
                Object l = Game.fld(s, Game.l("n", "field_146292_n")); // buttonList
                if (l instanceof List && !((List<?>) l).isEmpty()) ((List<?>) l).clear();
            } else {
                Object r = Game.fld(s, "field_33816");
                if (r instanceof List && !((List<?>) r).isEmpty()) Game.call(s, "method_37067");
                // keep the screen inside its fade-in: panorama full, logo / version / realms alpha 0 (no renderer code)
                if (fFading != null && fFadeStart != null) {
                    fFading.setBoolean(s, true);
                    fFadeStart.setLong(s, System.nanoTime() / 1_000_000L - 900);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static String str(Object o) { return o instanceof String && !((String) o).isEmpty() ? (String) o : null; }

    // ---------------------------------------------------------------- input

    /** boxes (framebuffer px) of our controls on vanilla's title / pause screen, from the last frame */
    private static final float[] boxes = new float[16];
    private static int nb;

    /** Mouse press from the input chain while no Nether menu is open; true swallows it. */
    static boolean press(int b) {
        if (title) { if (b == 0) pressed = true; return true; }
        if ((vanillaTitle || pause) && b == 0) {
            float x = fx(), y = fy();
            for (int i = 0; i < nb; i += 4) if (x >= boxes[i] && y >= boxes[i + 1] && x < boxes[i] + boxes[i + 2] && y < boxes[i + 1] + boxes[i + 3]) { pressed = true; return true; }
        }
        return false;
    }

    private static void box(float x, float y, float w, float h) {
        if (title || nb + 4 > boxes.length) return;
        float k = Gfx.s;
        boxes[nb++] = x * k; boxes[nb++] = y * k; boxes[nb++] = w * k; boxes[nb++] = h * k;
    }

    private static float fx() { return (float) (Input.mx * Gfx.fbW / Input.ww); }
    private static float fy() { return (float) (Input.my * Gfx.fbH / Input.wh); }

    /** cursor in units of the current scale; the quick panel's rect (units) while it is open */
    private static float MX, MY;
    private static float[] modal;
    private static boolean inModal;

    private static void units(float k) { Gfx.s = k; MX = fx() / k; MY = fy() / k; }

    /** Cursor over this rect (and not hidden by the open quick panel, unless we are drawing it). */
    static boolean over(float x, float y, float w, float h) {
        if (Input.menuOpen) return false;
        if (modal != null && !inModal && MX >= modal[0] && MY >= modal[1] && MX < modal[0] + modal[2] && MY < modal[1] + modal[3]) return false;
        return MX >= x && MY >= y && MX < x + w && MY < y + h;
    }

    /** Clicked this frame (consumes the click). */
    static boolean hit(float x, float y, float w, float h) {
        if (!click || !over(x, y, w, h)) return false;
        click = false;
        return true;
    }

    private static final Map<String, float[]> anims = new HashMap<>();

    /** Eased 0..1 hover/state value for id. */
    static float an(String id, boolean on) {
        float[] v = anims.get(id);
        if (v == null) anims.put(id, v = new float[1]);
        v[0] += ((on ? 1 : 0) - v[0]) * (Config.calm() ? 1 : Math.min(1, Gfx.dt * 14));
        return v[0];
    }

    /** str cut with an ellipsis to fit maxW units. */
    static String fit(int f, float size, String str, float sp, float maxW) {
        if (str == null) return "";
        if (Gfx.width(f, size, str, sp) <= maxW) return str;
        int n = str.length();
        while (n > 1 && Gfx.width(f, size, str.substring(0, n) + "...", sp) > maxW) n--;
        return str.substring(0, n).trim() + "...";
    }

    private static String keyName() { return Menu.keyName(Config.menuKey()); }

    // ---------------------------------------------------------------- title screen

    private static void drawTitle(int fbW, int fbH, Object screen) {
        float k = Math.min(Gfx.base, Math.min(fbW / 640f, fbH / 480f)); // follows GUI scale; always at least 640 x 480 units
        units(k);
        float W = fbW / k, H = fbH / k;
        // content eases in when the screen appears (calm mode: at once); early clicks are ignored
        float t = Config.calm() ? 1 : Math.min(1, (System.nanoTime() - shownAt) / 3.5e8f);
        float e = 1 - (float) Math.pow(1 - t, 3);
        if (t < 0.6f) click = false;
        modal = panelOpen ? new float[] {12, 52, PW, PH} : null;
        if (panelOpen && click && !over(12, 52, PW, PH) && !inside(custBox)) { panelOpen = false; click = false; modal = null; }
        account();

        String kind = MODULE.str("bg");
        MenuBack.draw(W, H, kind, legacy, MX, MY);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * e;
        float rise = (1 - e) * 8;
        String layout = MODULE.str("layout");
        boolean accTop = true;
        if (layout.equals("Left")) accTop = rail(W, H, screen, rise);
        else if (layout.equals("Bottom")) dock(W, H, screen, rise);
        else center(W, H, screen, rise);
        corner(W, accTop);
        footer(W, H, layout.equals("Bottom"));
        Gfx.alpha = a0;
        topBar(true, screen);
        if (panelOpen) panel(W, H);
        else panelA = 0;
        // dev: -Dnether.titleClick=<button index> presses that button once the screen has settled; -Dnether.titlePanel=1 opens the quick panel
        String dev = System.getProperty("nether.titleClick");
        if (dev != null && t >= 1 && (System.nanoTime() - shownAt) > 2e9) { System.clearProperty("nether.titleClick"); act(Integer.parseInt(dev), screen); }
        if ("1".equals(System.getProperty("nether.titlePanel"))) { System.clearProperty("nether.titlePanel"); panelOpen = true; }
    }

    private static boolean inside(float[] b) { return b != null && over(b[0], b[1], b[2], b[3]); }

    /** Logo block height for the current logo option (splash included). */
    private static float logoH(float sc) {
        String mode = MODULE.str("logo");
        if (mode.equals("Hidden")) return 0;
        float h = mode.equals("Mark") ? 70 : 122;
        if (MODULE.bool("splash") && splash != null) h += 18;
        return h * sc;
    }

    /** Logo (mark / wordmark / CLIENT tag / splash); centred on x or left-aligned at x. */
    private static void logo(float x, float y, boolean centred, float sc) {
        String mode = MODULE.str("logo");
        if (mode.equals("Hidden")) return;
        float sy;
        if (mode.equals("Mark")) {
            float s = 54 * sc;
            ghast(centred ? x - s / 2 : x, y, s);
            sy = y + 70 * sc;
        } else {
            float s = 46 * sc;
            ghast(centred ? x - s / 2 : x + 2, y, s);
            if (centred) {
                Gfx.textC(Gfx.HEAD, 34 * sc, x, y + 80 * sc, Gfx.ICE, "NETHER", 3f * sc);
                Gfx.textC(Gfx.MONO, 9 * sc, x, y + 104 * sc, Gfx.RED2, "CLIENT", 6f * sc);
            } else {
                Gfx.text(Gfx.HEAD, 34 * sc, x, y + 80 * sc, Gfx.ICE, "NETHER", false, 3f * sc);
                Gfx.text(Gfx.MONO, 9 * sc, x + 2, y + 104 * sc, Gfx.RED2, "CLIENT", false, 6f * sc);
            }
            sy = y + 122 * sc;
        }
        if (MODULE.bool("splash") && splash != null) {
            String sp = fit(Gfx.SEMI, 11, "/ " + splash, 0.4f, 300);
            if (centred) Gfx.textC(Gfx.SEMI, 11, x, sy + 6, Gfx.a(Gfx.RED2, 0.95f), sp, 0.4f);
            else Gfx.text(Gfx.SEMI, 11, x + 2, sy + 6, Gfx.a(Gfx.RED2, 0.95f), sp, false, 0.4f);
        }
    }

    /** Stack: logo and buttons in a centre column, quick join bottom right when it clears the column. */
    private static void center(float W, float H, Object scr, float rise) {
        boolean mods = cMods != null;
        float bw = 232, bh = 30, gap = 6, cx = W / 2;
        int rows = mods ? 4 : 3;
        float lh = logoH(1), block = lh + (lh > 0 ? 10 : 0) + rows * (bh + gap) + 8 + bh;
        float top = 60 + Math.max(0, (H - 96 - block) * 0.45f) + rise;
        // legibility scrim behind the column (any background)
        float sw = bw + 300;
        MenuBack.hgrad(cx - sw / 2, 0, sw / 2, H, Gfx.a(Gfx.BG, 0), Gfx.a(Gfx.BG, 0.45f));
        MenuBack.hgrad(cx, 0, sw / 2, H, Gfx.a(Gfx.BG, 0.45f), Gfx.a(Gfx.BG, 0));
        logo(cx, top, true, 1);
        float x0 = cx - bw / 2, y = top + lh + (lh > 0 ? 10 : 0), half = (bw - gap) / 2;
        button(SINGLE, x0, y, bw, bh, false, scr); y += bh + gap;
        button(MULTI, x0, y, bw, bh, false, scr); y += bh + gap;
        if (mods) { button(MODS, x0, y, bw, bh, false, scr); y += bh + gap; }
        button(OPTIONS, x0, y, half, bh, false, scr);
        button(QUIT, x0 + half + gap, y, half, bh, false, scr);
        y += bh + gap + 8;
        button(NETHER, x0, y, bw, bh, false, scr);
        float cw = 256;
        if (W - 16 - cw > cx + bw / 2 + 24) QuickJoin.card(W - 16 - cw, H - 36, cw, 76, scr);
    }

    /** Rail: a left panel with logo, buttons and the account card; quick join bottom right. Returns: account goes top right. */
    private static boolean rail(float W, float H, Object scr, float rise) {
        float RW = Math.min(330, Math.max(270, W * 0.3f));
        Gfx.rect(0, 0, RW, H, 0, Gfx.a(Gfx.BG, 0.84f));
        Gfx.rect(RW - MenuBack.lw(), 0, MenuBack.lw(), H, 0, Gfx.LN2);
        boolean mods = cMods != null;
        float x = 34, bw = RW - 68, bh = 30, gap = 6;
        float y = 70 + rise;
        float lh = logoH(1);
        logo(x, y, false, 1);
        y += lh + (lh > 0 ? 14 : 0);
        float half = (bw - gap) / 2;
        button(SINGLE, x, y, bw, bh, true, scr); y += bh + gap;
        button(MULTI, x, y, bw, bh, true, scr); y += bh + gap;
        if (mods) { button(MODS, x, y, bw, bh, true, scr); y += bh + gap; }
        button(OPTIONS, x, y, half, bh, true, scr);
        button(QUIT, x + half + gap, y, half, bh, true, scr);
        y += bh + gap + 8;
        button(NETHER, x, y, bw, bh, true, scr);
        y += bh;
        boolean accTop = true;
        if (MODULE.bool("account") && y + 20 < H - 34 - 44) { accTop = accountCard(x, H - 34 - 44, bw) <= 0; }
        float cw = 256;
        if (W - 16 - cw > RW + 24) QuickJoin.card(W - 16 - cw, H - 36, cw, 76, scr);
        return accTop;
    }

    /** Dock: big logo left, a full-width button bar at the bottom, quick join above the bar on the right. */
    private static void dock(float W, float H, Object scr, float rise) {
        float barH = 58, by = H - barH - 26;
        Gfx.rect(0, by, W, barH, 0, Gfx.a(Gfx.BG, 0.84f));
        Gfx.rect(0, by, W, MenuBack.lw(), 0, Gfx.LN2);
        int[] ids = cMods != null ? new int[] {SINGLE, MULTI, MODS, OPTIONS, QUIT, NETHER} : new int[] {SINGLE, MULTI, OPTIONS, QUIT, NETHER};
        float gap = 8, bh = 34, bw = Math.min(156, (W - 48 - gap * (ids.length - 1)) / ids.length);
        float x = (W - (ids.length * bw + (ids.length - 1) * gap)) / 2;
        for (int id : ids) { button(id, x, by + (barH - bh) / 2, bw, bh, false, scr); x += bw + gap; }
        float sc = Math.min(1.35f, Math.max(1, (by - 120) / 260f));
        float lh = logoH(sc);
        MenuBack.hgrad(0, 0, W * 0.55f, by, Gfx.a(Gfx.BG, 0.5f), Gfx.a(Gfx.BG, 0));
        logo(48, 56 + Math.max(0, (by - 56 - lh) * 0.5f) + rise, false, sc);
        float cw = 256;
        if (W - 16 - cw > 48 + 280 * sc) QuickJoin.card(W - 16 - cw, by - 14, cw, 76, scr);
    }

    /** One menu button in the chosen style; acts on click. left = label at the left edge (rail). */
    private static void button(int i, float x, float y, float w, float h, boolean left, Object screen) {
        String style = MODULE.str("buttons"), label = LABELS[i];
        boolean accent = i == NETHER, hv = over(x, y, w, h);
        float t = an(label, hv), l = MenuBack.lw();
        String key = accent ? keyName() : null;
        float fs = 12.5f, sp = 1.6f, tw = Gfx.width(Gfx.SEMI, fs, label, sp);
        float kw = key != null ? Gfx.width(Gfx.MONO, 9, key, 0.6f) + 10 : 0;
        if (tw > w - 22) { fs = Math.max(9.5f, fs * (w - 22) / tw); sp = 0.8f; tw = Gfx.width(Gfx.SEMI, fs, label, sp); }
        boolean showKey = key != null && w - tw - kw > 52;
        int txt;
        switch (style) {
            case "Outline":
                Gfx.rect(x, y, w, h, 0, accent ? Gfx.a(Gfx.RED, 0.16f + 0.3f * t) : Gfx.a(Gfx.ICE, 0.02f + 0.06f * t));
                Gfx.ring(x, y, w, h, 0, 0.5f, accent ? Gfx.a(Gfx.RED2, 0.7f + 0.3f * t) : Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.6f), t));
                txt = Gfx.mix(Gfx.DIM, Gfx.ICE, accent ? 1 : 0.55f + 0.45f * t);
                break;
            case "Minimal":
                if (left) Gfx.rect(x, y + h * 0.3f, (accent ? 2 : 0) + 3 * t, h * 0.4f, 0, Gfx.a(Gfx.RED2, accent ? 1 : t));
                else {
                    float uw = (tw - sp) * (accent ? 0.35f + 0.65f * t : t);
                    Gfx.rect(x + (w - uw) / 2, y + h - 5, uw, Math.max(l, 1.5f), 0, Gfx.RED2);
                }
                txt = accent ? Gfx.mix(Gfx.RED2, 0xFFFFFFFF, t * 0.6f) : Gfx.mix(Gfx.a(Gfx.ICE, 0.72f), 0xFFFFFFFF, t);
                break;
            default: // Pit Wall solid
                Gfx.rect(x, y, w, h, 0, accent ? Gfx.mix(0xFF3A0300, Gfx.RED, t) : Gfx.mix(Gfx.a(Gfx.CARD, 0.92f), Gfx.CARD2, t));
                Gfx.rect(x, y + h - l, w, l, 0, accent ? Gfx.a(Gfx.RED2, 0.6f + 0.4f * t) : Gfx.a(Gfx.ICE, 0.08f + 0.1f * t));
                Gfx.rect(x, y, 2 + 2 * t, h, 0, Gfx.a(Gfx.RED2, accent ? 1 : t));
                txt = Gfx.mix(Gfx.DIM, Gfx.ICE, accent ? 1 : 0.55f + 0.45f * t);
        }
        float shift = style.equals("Minimal") && !left ? 0 : t * 2;
        if (left) Gfx.text(Gfx.SEMI, fs, x + 14 + shift, y + h / 2, txt, label, false, sp);
        else Gfx.textC(Gfx.SEMI, fs, x + w / 2 + shift - (showKey ? kw / 2 + 4 : 0), y + h / 2, txt, label, sp);
        if (showKey) { // the key that opens the same menu in game
            float kx = left ? x + w - kw - 10 : x + w / 2 + (tw - sp) / 2 - kw / 2 + 4 + shift;
            Gfx.rect(kx, y + h / 2 - 7, kw, 14, 0, Gfx.a(Gfx.ICE, 0.12f + 0.06f * t));
            Gfx.textC(Gfx.MONO, 9, kx + kw / 2, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, t), key, 0.6f);
        }
        if (hit(x, y, w, h)) act(i, screen);
    }

    /** Top right: clock and (unless the rail holds it) the account card. */
    private static void corner(float W, boolean account) {
        float x = W - 16;
        if (account && MODULE.bool("account")) {
            float w = 184;
            if (accountCard(x - w, 12, w) > 0) x -= w + 18;
        }
        if (!MODULE.bool("clock")) return;
        LocalDateTime d = LocalDateTime.now();
        boolean h24 = Config.bool("clock", "h24", true);
        int hh = h24 ? d.getHour() : (d.getHour() + 11) % 12 + 1;
        String tm = String.format(Locale.ROOT, h24 ? "%02d:%02d" : "%d:%02d", hh, d.getMinute()) + (h24 ? "" : d.getHour() < 12 ? " AM" : " PM");
        String dt = d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT) + " " + d.getDayOfMonth() + " "
            + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
        Gfx.text(Gfx.HEAD, 24, x - Gfx.width(Gfx.HEAD, 24, tm, 1f), 26, Gfx.ICE, tm, false, 1f);
        Gfx.text(Gfx.MONO, 9, x - Gfx.width(Gfx.MONO, 9, dt, 1.5f) + 1.5f, 46, Gfx.SUB, dt, false, 1.5f);
    }

    private static String version, loader;

    /** Bottom: game version / loader / client version left; Mojang's notice right (when room). */
    private static void footer(float W, float H, boolean dock) {
        if (version == null) {
            String v = Game.version();
            loader = loaderName();
            String nv = System.getProperty("nether.version");
            version = "MINECRAFT " + (v != null ? v.toUpperCase(Locale.ROOT) : "") + " / " + loader + (nv != null ? " / NETHER " + nv : "");
        }
        float lw = Gfx.text(Gfx.MONO, 9, 12, H - 12, Gfx.a(Gfx.ICE, 0.62f), version, false, 0.6f);
        String c1 = "COPYRIGHT MOJANG AB. DO NOT DISTRIBUTE!", c2 = "NOT AFFILIATED WITH MOJANG OR MICROSOFT";
        float w1 = Gfx.width(Gfx.MONO, 9, c1, 0.6f), w2 = Gfx.width(Gfx.MONO, 9, c2, 0.6f);
        if (lw + w1 + 40 > W) return;
        Gfx.text(Gfx.MONO, 9, W - 10 - w1, H - 12, Gfx.a(Gfx.ICE, 0.8f), c1, false, 0.6f);
        if (!dock) Gfx.text(Gfx.MONO, 9, W - 10 - w2, H - 12 - Math.max(14, 12.5f / Gfx.s), Gfx.a(Gfx.ICE, 0.62f), c2, false, 0.6f);
    }

    private static String loaderName() {
        String[][] k = {{"org.quiltmc.loader.api.QuiltLoader", "QUILT"}, {"net.fabricmc.loader.api.FabricLoader", "FABRIC"},
            {"net.neoforged.fml.loading.FMLLoader", "NEOFORGE"}, {"net.minecraftforge.fml.loading.FMLLoader", "FORGE"}, {"net.minecraftforge.fml.common.Loader", "FORGE"}};
        for (String[] e : k) {
            try { Class.forName(e[0], false, Game.loader()); return e[1]; } catch (Throwable ignored) { }
        }
        if (cMods != null) return cMods.getName().contains("neoforged") ? "NEOFORGE" : cMods.getName().contains("minecraftforge") ? "FORGE" : "FABRIC";
        return "VANILLA";
    }

    // ---------------------------------------------------------------- account (name + skin head)

    private static String accName, accUuid;
    private static boolean accTried;
    private static int headState; // 0 to do, 1 profile asked, 2 skin asked, 3 done (head or none)
    private static Net.Req req;

    private static void account() {
        if (!accTried) {
            accTried = true;
            try {
                Object ses = Game.call(Game.mc(), legacy ? Game.l("L", "func_110432_I") : "method_1548");
                accName = str(Game.call(ses, legacy ? Game.l("c", "func_111285_a") : "method_1676"));
                Object u = Game.call(ses, legacy ? Game.l("b", "func_148255_b") : "method_44717");
                if (u != null) accUuid = String.valueOf(u).replace("-", "").toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) { }
        }
        if (accUuid == null || accUuid.length() != 32 || headState >= 3 || !MODULE.bool("account")) return;
        try {
            if (headState == 0) {
                req = Net.get("https://sessionserver.mojang.com/session/minecraft/profile/" + accUuid, 6 * 3600_000L, 64 << 10);
                headState = 1;
            } else if (req.done()) {
                if (!req.ok()) { headState = 3; return; }
                if (headState == 1) {
                    String url = skinUrl(req.text());
                    if (url == null) { headState = 3; return; }
                    req = Net.get(url.replace("http://", "https://"), 7 * 86400_000L, 1 << 20);
                    headState = 2;
                } else {
                    headState = 3;
                    BufferedImage skin = Icons.decode(new java.io.ByteArrayInputStream(req.body));
                    if (skin == null || skin.getWidth() < 64) return;
                    int u = skin.getWidth() / 64; // HD skins: same layout, bigger pixels
                    BufferedImage head = new BufferedImage(8 * u, 8 * u, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = head.createGraphics();
                    g.drawImage(skin, 0, 0, 8 * u, 8 * u, 8 * u, 8 * u, 16 * u, 16 * u, null);
                    g.drawImage(skin, 0, 0, 8 * u, 8 * u, 40 * u, 8 * u, 48 * u, 16 * u, null); // hat layer
                    g.dispose();
                    Gfx.texture("menu.head", head, false);
                }
            }
        } catch (Throwable t) {
            headState = 3;
        }
    }

    /** textures.SKIN.url from a session-server profile. */
    @SuppressWarnings("unchecked")
    private static String skinUrl(String json) {
        try {
            Object p = ((Map<String, Object>) Json.parse(json)).get("properties");
            for (Object o : (List<Object>) p) {
                Map<String, Object> m = (Map<String, Object>) o;
                if (!"textures".equals(m.get("name"))) continue;
                String dec = new String(java.util.Base64.getDecoder().decode((String) m.get("value")), java.nio.charset.StandardCharsets.UTF_8);
                Object skin = ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) Json.parse(dec)).get("textures")).get("SKIN"));
                return skin instanceof Map ? str(((Map<String, Object>) skin).get("url")) : null;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Account card (head, name, greeting); returns its height, 0 when the name is unknown. */
    private static float accountCard(float x, float y, float w) {
        if (accName == null) return 0;
        float h = 44;
        Gfx.rect(x, y, w, h, 0, Gfx.a(Gfx.BG, 0.8f));
        Gfx.ring(x, y, w, h, 0, 0.5f, Gfx.LN2);
        if (Gfx.hasTexture("menu.head")) Gfx.image("menu.head", x + 8, y + 8, 28, 28, 0xFFFFFFFF, 0);
        else {
            Gfx.rect(x + 8, y + 8, 28, 28, 0, Gfx.CARD2);
            Gfx.rect(x + 8, y + 34, 28, 2, 0, Gfx.RED2);
            Gfx.textC(Gfx.HEAD, 16, x + 22, y + 21, Gfx.ICE, accName.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
        Gfx.text(Gfx.SEMI, 13, x + 46, y + 16, Gfx.ICE, fit(Gfx.SEMI, 13, accName, 0.4f, w - 56), false, 0.4f);
        int hr = LocalDateTime.now().getHour();
        String g = hr < 5 ? "Up late" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
        Gfx.text(Gfx.UI, 10.5f, x + 46, y + 31, Gfx.SUB, g, false, 0);
        return h;
    }

    // ---------------------------------------------------------------- top bar (both title screens) + pause chip

    private static float[] custBox;

    /** Top-left controls, in the same place on our title screen and on vanilla's: the menu switch, then CUSTOMISE (ours) or the NETHER button (vanilla). */
    private static void topBar(boolean ours, Object scr) {
        float x = 12, y = 12, h = 32;
        x += seg(x, y, h, ours) + 6;
        if (ours) {
            float w = chipW("CUSTOMISE", null, false) + 18;
            custBox = new float[] {x, y, w, h};
            if (chip("cust", x, y, h, "CUSTOMISE", null, false, panelOpen)) panelOpen = !panelOpen;
        } else if (chip("nether", x, y, h, "NETHER", keyName(), true, false)) Input.setMenu(true);
    }

    /** MENU [NETHER | VANILLA] switch with a red sliding thumb; returns its width. */
    private static float seg(float x, float y, float h, boolean ours) {
        String l0 = "MENU", a = "NETHER", b = "VANILLA";
        float lw0 = Gfx.width(Gfx.MONO, 9, l0, 1.4f), wa = Gfx.width(Gfx.SEMI, 11, a, 1.2f) + 22, wb = Gfx.width(Gfx.SEMI, 11, b, 1.2f) + 22;
        float w = 12 + lw0 + 8 + wa + wb + 4;
        Gfx.rect(x, y, w, h, 0, Gfx.a(Gfx.BG, 0.86f));
        Gfx.ring(x, y, w, h, 0, 0.5f, Gfx.LN2);
        Gfx.text(Gfx.MONO, 9, x + 12, y + h / 2, Gfx.SUB, l0, false, 1.4f);
        float sx = x + 12 + lw0 + 8, sy = y + 4, sh = h - 8;
        float p = an("seg", !ours), ha = an("segA", ours ? false : over(sx, y, wa, h)), hb = an("segB", ours && over(sx + wa, y, wb, h));
        Gfx.rect(sx + p * wa, sy, wa + (wb - wa) * p, sh, 0, Gfx.RED);
        Gfx.textC(Gfx.SEMI, 11, sx + wa / 2, y + h / 2, Gfx.mix(Gfx.mix(Gfx.DIM, Gfx.ICE, ha), 0xFFFFFFFF, 1 - p), a, 1.2f);
        Gfx.textC(Gfx.SEMI, 11, sx + wa + wb / 2, y + h / 2, Gfx.mix(Gfx.mix(Gfx.DIM, Gfx.ICE, hb), 0xFFFFFFFF, p), b, 1.2f);
        box(x, y, w, h);
        if (!ours && hit(sx, y, wa, h)) Config.set("menu", "enabled", true);
        if (ours && hit(sx + wa, y, wb, h)) { panelOpen = false; Config.set("menu", "enabled", false); }
        return w;
    }

    private static float chipW(String label, String key, boolean mark) {
        float kw = key != null ? Gfx.width(Gfx.MONO, 9, key, 0.6f) + 10 : 0;
        return 12 + (mark ? 22 : 0) + Gfx.width(Gfx.SEMI, 11, label, 1.2f) + (key != null ? 8 + kw : 0) + 12;
    }

    /**
     * Small top-bar button: NETHER (ghast mark + key chip, red) or a neutral one with a settings glyph; drawn the same on
     * the title and pause screens. Returns true when clicked.
     */
    private static boolean chip(String id, float x, float y, float h, String label, String key, boolean mark, boolean active) {
        float kw = key != null ? Gfx.width(Gfx.MONO, 9, key, 0.6f) + 10 : 0;
        float w = chipW(label, key, mark) + (mark ? 0 : 18);
        float t = an(id, over(x, y, w, h) || active), l = MenuBack.lw();
        Gfx.rect(x, y, w, h, 0, Gfx.mix(Gfx.a(Gfx.BG, 0.86f), mark ? 0xFF3A0300 : Gfx.CARD2, t));
        if (!mark) Gfx.ring(x, y, w, h, 0, 0.5f, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.35f), t));
        Gfx.rect(x, y, 2 + t, h, 0, Gfx.a(Gfx.RED2, mark ? 1 : t));
        if (mark) Gfx.rect(x, y + h - l, w, l, 0, Gfx.a(Gfx.RED2, 0.35f + 0.65f * t));
        float tx = x + 12;
        if (mark) { ghast(tx, y + (h - 16.5f) / 2, 14); tx += 22; }
        else { // three sliders
            for (int i = 0; i < 3; i++) {
                float ly = y + h / 2 - 5 + i * 5;
                Gfx.rect(tx, ly, 12, Math.max(l, 1.2f), 0, Gfx.mix(Gfx.SUB, Gfx.ICE, t));
                Gfx.rect(tx + (i == 1 ? 7 : 2), ly - 1.5f, 3, 3 + Math.max(l, 1.2f), 0, i == 1 ? Gfx.RED2 : Gfx.mix(Gfx.SUB, Gfx.ICE, t));
            }
            tx += 18;
        }
        float tw = Gfx.text(Gfx.SEMI, 11, tx, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, 0.65f + 0.35f * t), label, false, 1.2f);
        if (key != null) {
            float kx = tx + tw + 8 - 1.2f;
            Gfx.rect(kx, y + 9, kw, h - 18, 0, Gfx.a(Gfx.ICE, 0.10f + 0.06f * t));
            Gfx.textC(Gfx.MONO, 9, kx + kw / 2, y + h / 2, Gfx.mix(Gfx.SUB, Gfx.ICE, t), key, 0.6f);
        }
        box(x, y, w, h);
        return hit(x, y, w, h);
    }

    // ---------------------------------------------------------------- quick customise panel

    private static boolean panelOpen;
    private static float panelA;
    private static final float PW = 500, PH = 286;
    private static final String[] DIMS = {"OFF", "20%", "40%", "60%", "80%"};

    private static void panel(float W, float H) {
        panelA = Config.calm() ? 1 : Math.min(1, panelA + Gfx.dt * 8);
        float x = 12, y = 52 - (1 - panelA) * 6, w = PW, h = PH;
        inModal = true;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * panelA;
        Gfx.rect(x + 3, y + 5, w, h, 0, Gfx.a(Gfx.BLACK, 0.4f));
        Gfx.rect(x, y, w, h, 0, Gfx.CARD);
        Gfx.ring(x, y, w, h, 0, 0.5f, Gfx.LN2);
        Gfx.rect(x, y, w, 2, 0, Gfx.RED);
        float cw = (w - 52) / 2, c1 = x + 16, c2 = x + 16 + cw + 20, yy = y + 18;
        // left: background, dim, show
        label("BACKGROUND", c1, yy);
        choices("bg", MenuBack.KINDS, c1, yy + 12, cw, 3);
        label("DIM", c1, yy + 82);
        double dim = MODULE.num("dim");
        for (int i = 0; i < DIMS.length; i++) {
            float cwi = (cw - 4 * 4) / 5, cx = c1 + i * (cwi + 4);
            if (cell("dim" + i, DIMS[i], cx, yy + 94, cwi, Math.abs(dim - i * 0.2) < 0.06)) Config.set("menu", "dim", i * 0.2);
        }
        label("SHOW", c1, yy + 136);
        String[] tk = {"clock", "account", "servers", "splash"}, tl = {"CLOCK", "ACCOUNT", "QUICK JOIN", "SPLASH"};
        for (int i = 0; i < 4; i++) {
            float bx = c1 + (i % 2) * (cw / 2 + 2), by = yy + 148 + (i / 2) * 28, bw = cw / 2 - 2;
            boolean on = tk[i].equals("servers") ? MODULE.num("servers") > 0 : MODULE.bool(tk[i]);
            if (toggle("tg" + i, tl[i], bx, by, bw, on))
                Config.set("menu", tk[i], tk[i].equals("servers") ? (Object) (on ? 0.0 : 3.0) : (Object) !on);
        }
        // right: layout, buttons, logo
        label("LAYOUT", c2, yy);
        choices("layout", LAYOUTS, c2, yy + 12, cw, 3);
        label("BUTTONS", c2, yy + 54);
        choices("buttons", STYLES, c2, yy + 66, cw, 3);
        label("LOGO", c2, yy + 108);
        choices("logo", LOGOS, c2, yy + 120, cw, 3);
        // image folder (right column, under logo)
        if (MODULE.str("bg").equals("Image")) {
            int n = MenuBack.count();
            label(n == 0 ? "NO IMAGES YET" : n + (n == 1 ? " IMAGE" : " IMAGES"), c2, yy + 162);
            if (cell("folder", "OPEN FOLDER", c2, yy + 174, (cw - 4) / 2, false)) MenuBack.openFolder();
            if (n > 1 && cell("nextimg", "NEXT IMAGE", c2 + (cw + 4) / 2, yy + 174, (cw - 4) / 2, false)) MenuBack.next();
            if (n == 0) Gfx.text(Gfx.UI, 10.5f, c2, yy + 214, Gfx.SUB, "Add PNG or JPG files to client/backgrounds.", false, 0);
        }
        // footer
        float fy = y + h - 42;
        Gfx.rect(x + 16, fy - 8, w - 32, MenuBack.lw(), 0, Gfx.LN);
        Gfx.text(Gfx.UI, 10.5f, c1, fy + 13, Gfx.SUB, "Blur, motion, slideshow and more in the full settings.", false, 0);
        float bw = Gfx.width(Gfx.SEMI, 11, "ALL SETTINGS", 1.2f) + 28;
        if (cell("all", "ALL SETTINGS", x + w - 16 - bw, fy, bw, false)) { panelOpen = false; Menu.page = "menu"; Input.setMenu(true); }
        Gfx.alpha = a0;
        inModal = false;
    }

    private static void label(String s, float x, float y) { Gfx.text(Gfx.MONO, 9, x, y, Gfx.SUB, s, false, 2f); }

    /** Grid of option cells for setting key (red = selected, like the menu's choice rows). */
    private static void choices(String key, String[] opts, float x, float y, float w, int cols) {
        String cur = MODULE.str(key);
        float cwi = (w - (cols - 1) * 4) / cols;
        for (int i = 0; i < opts.length; i++) {
            float cx = x + (i % cols) * (cwi + 4), cy = y + (i / cols) * 28;
            if (cell(key + i, opts[i].toUpperCase(Locale.ROOT), cx, cy, cwi, opts[i].equals(cur))) Config.set("menu", key, opts[i]);
        }
    }

    private static boolean cell(String id, String label, float x, float y, float w, boolean sel) {
        float h = 24, t = an("c" + id, over(x, y, w, h)), s = an("s" + id, sel);
        Gfx.rect(x, y, w, h, 0, Gfx.mix(Gfx.a(Gfx.ICE, 0.04f + 0.06f * t), Gfx.RED, s));
        float sp = Gfx.width(Gfx.SEMI, 10, label, 1f) > w - 10 ? 0.3f : 1f;
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h / 2, Gfx.mix(Gfx.mix(Gfx.DIM, Gfx.ICE, t), 0xFFFFFFFF, s), fit(Gfx.SEMI, 10, label, sp, w - 6), sp);
        return hit(x, y, w, h);
    }

    private static boolean toggle(String id, String label, float x, float y, float w, boolean on) {
        float h = 24, t = an("c" + id, over(x, y, w, h)), s = an("s" + id, on);
        Gfx.rect(x, y, w, h, 0, Gfx.a(Gfx.ICE, 0.03f + 0.05f * t));
        float bx = x + 8, by = y + h / 2 - 5;
        Gfx.rect(bx, by, 10, 10, 0, Gfx.mix(Gfx.a(Gfx.ICE, 0.08f), Gfx.RED, s));
        Gfx.ring(bx, by, 10, 10, 0, 0.5f, Gfx.mix(Gfx.LN2, Gfx.RED2, s));
        Gfx.text(Gfx.SEMI, 10, x + 26, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, s * 0.8f)), label, false, 1f);
        return hit(x, y, w, h);
    }

    // ---------------------------------------------------------------- marks + actions

    /** The ghast mark from the logo, in flat rects (size = body width). */
    static void ghast(float x, float y, float s) {
        float u = s / 16;
        Gfx.rect(x, y, s, 12 * u, 2 * u, Gfx.ICE);
        Gfx.rect(x, y + 10.5f * u, s, 1.5f * u, 0, 0xFFC9CED6);
        Gfx.rect(x + 12 * u, y, 4 * u, 3 * u, 0, Gfx.BG);
        Gfx.rect(x + 14 * u, y + 3 * u, 2 * u, 2 * u, 0, Gfx.BG);
        Gfx.rect(x + 3 * u, y + 4.6f * u, 3.4f * u, 0.9f * u, 0.4f * u, Gfx.BG);
        Gfx.rect(x + 9.6f * u, y + 4.6f * u, 3.4f * u, 0.9f * u, 0.4f * u, Gfx.BG);
        Gfx.rect(x + 6.8f * u, y + 6.6f * u, 2.4f * u, 3 * u, 0.6f * u, Gfx.BG);
        float[] tl = {4.5f, 7, 3.5f, 6.5f, 5};
        for (int i = 0; i < 5; i++) Gfx.rect(x + (0.8f + i * 3.1f) * u, y + 12 * u, 1.9f * u, tl[i] * u, 0.8f * u, i % 2 == 0 ? Gfx.ICE : 0xFFC9CED6);
    }

    static void act(int i, Object screen) {
        Object mc = Game.mc();
        switch (i) {
            case SINGLE:
                if (legacy) open(mc, makeFirst(screen, Game.l("axv", "net.minecraft.client.gui.GuiSelectWorld"), "net.minecraft.client.gui.GuiWorldSelection"));
                else open(mc, makeFirst(screen, "net.minecraft.class_526"));
                break;
            case MULTI: open(mc, makeFirst(screen, legacy ? Game.l("azh", "net.minecraft.client.gui.GuiMultiplayer") : "net.minecraft.class_500")); break;
            case MODS: open(mc, make(cMods, screen)); break;
            case OPTIONS:
                if (legacy) open(mc, make(Game.cls0(Game.l("axn", "net.minecraft.client.gui.GuiOptions")), screen, Game.fld(mc, Game.l("t", "field_71474_y"))));
                else open(mc, make(Game.cls0("net.minecraft.class_429"), screen, Game.fld(mc, "field_1690")));
                break;
            case QUIT: Game.call(mc, legacy ? Game.l("m", "func_71400_g") : "method_1592"); break;
            default: panelOpen = false; Input.setMenu(true);
        }
    }

    static void open(Object mc, Object screen) {
        if (screen != null) Game.call(mc, legacy ? Game.l("a", "func_147108_a") : "method_1507", screen);
    }

    /** The first of these screen classes that exists, made with its (parent screen) constructor. */
    private static Object makeFirst(Object parent, String... names) {
        for (String n : names) {
            Class<?> c = Game.cls0(n);
            if (c != null) return make(c, parent);
        }
        return null;
    }

    /** New instance of a game class from its constructor that accepts these arguments (boxed values fill primitives). */
    static Object make(Class<?> cls, Object... a) {
        if (cls == null) return null;
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != a.length) continue;
                boolean ok = true;
                for (int i = 0; i < p.length && ok; i++)
                    ok = a[i] == null ? !p[i].isPrimitive() : p[i].isPrimitive() ? (p[i] == boolean.class) == (a[i] instanceof Boolean) : p[i].isInstance(a[i]);
                if (!ok) continue;
                c.setAccessible(true);
                return c.newInstance(a);
            }
        } catch (Throwable t) {
            System.out.println("[nether-agent] could not open " + cls.getName() + ": " + t);
        }
        return null;
    }
}
