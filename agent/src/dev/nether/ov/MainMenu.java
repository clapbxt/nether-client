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
 * Built-in main menu (always on, not a listed module; the hidden "menu" module only holds its settings): our own title
 * screen (backgrounds, three layouts, button styles, logo + splash, clock, account, quick join) and a Nether button on the
 * pause screen. Vanilla's screens stay underneath: on the title screen its widgets and splash are removed, its logo and
 * version text faded out (modern) or covered (1.8.9), the panorama keeps running. Buttons open vanilla's own screens
 * through reflection. Needs bound names (Fabric intermediary / Mojang / same-version obf
 * tables, 1.8.9 notch / SRG); elsewhere vanilla's menu stays untouched.
 */
final class MainMenu {
    private MainMenu() {}

    // before MODULE: its settings read them during class init
    private static final String[] LAYOUTS = {"Lobby", "Center", "Left", "Bottom"}, STYLES = {"Solid", "Outline", "Minimal"}, LOGOS = {"Wordmark", "Mark", "Hidden"};

    static final Module MODULE = new Module("menu", "Main Menu", "Visual",
        "Nether title screen: backgrounds, layouts, quick join and your account.", false, true, 0, 0) {
        @Override boolean hidden() { return true; }
        @Override boolean wantedOn() { return true; }
    };

    static {
        Module m = MODULE;
        m.needs(4);
        m.add(Setting.header("Background"));
        m.add(Setting.choice("bg", "Background", "Panorama", MenuBack.KINDS));
        m.add(Setting.slider("dim", "Dim", 0, 0.8, 0.05, 0.3).hint("Darkens the panorama or image behind the menu."));
        m.add(Setting.bool("motion", "Animate background", true).hint("Ember drift, grid sweep, slow pan on images. Reduce motion stops it."));
        java.util.function.BooleanSupplier img = () -> m.str("bg").equals("Image");
        m.add(Setting.choice("imageFit", "Image fit", "Fill", "Fill", "Fit", "Stretch").when(img));
        m.add(Setting.slider("imageBlur", "Image blur", 0, 1, 0.1, 0).when(img));
        m.add(Setting.slider("slideshow", "Slideshow (seconds)", 0, 120, 5, 0).hint("0 keeps one image, otherwise every image in the folder in turn.").when(img));
        m.add(Setting.text("imageFile", "Image file", "", 96).hint("A file in client/backgrounds; empty shows the first one.").when(img));
        m.add(Setting.action("folder", "Backgrounds folder", "OPEN", MenuBack::openFolder).hint("PNG, JPG or GIF (first frame).").when(img));
        m.add(Setting.action("next", "Next image", "NEXT", MenuBack::next).when(img));
        m.add(Setting.header("Layout"));
        m.add(Setting.choice("layout", "Layout", "Lobby", LAYOUTS).hint("Lobby: your party on stage, Fortnite style."));
        m.add(Setting.bool("partyStage", "Party on stage", true).hint("Lobby layout: you and your party in your skins."));
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
    static boolean title, pause, packs;
    private static boolean pressed, click;
    private static long shownAt;
    /** the title screen whose widgets we removed (rebuilt by {@link #restoreVanilla}) and the splash vanilla picked for it */
    private static Object stripped, splashOf;
    private static String splash;

    static final int SINGLE = 0, MULTI = 1, MODS = 2, OPTIONS = 3, QUIT = 4, NETHER = 5;
    // MODS (the Fabric mod list) reads MOD MENU, so it never competes with the Nether MODS in the actions row
    private static final String[] LABELS = {"SINGLEPLAYER", "MULTIPLAYER", "MOD MENU", "OPTIONS", "QUIT", "NETHER CLIENT"};

    private static void look() {
        if (looked) return;
        looked = true;
        // parties (2026-11): the Lobby layout becomes the title screen once (Config stored the old default, Center)
        if (!Config.bool("menu", "lobbyV1", false)) { Config.set("menu", "layout", "Lobby"); Config.set("menu", "lobbyV1", true); }
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
        title = pause = packs = false;
        click = pressed;
        pressed = false;
        nb = 0;
        modal = null;
        if (!MODULE.available()) return;
        look();
        if (cTitle == null) return;
        QuickJoin.track();
        Object s = Game.screen();
        if (s == null) { panelOpen = false; return; }
        boolean isTitle = cTitle.isInstance(s);
        Gfx.minPx = 9; // corner text and labels stay legible in small windows
        boolean r0 = Gfx.round;
        Gfx.round = Th.round(); // v6: rounded buttons, chips and glass
        try {
            if (isTitle) {
                title = true;
                if (!was) shownAt = System.nanoTime();
                strip(s);
                stripped = s;
                drawTitle(fbW, fbH, s);
                WorldShare.title(s);
                Friends.title(s);
                units(Gfx.base);
                WorldShare.card(Gfx.fbW / Gfx.s, 56);
            } else if (!Input.menuOpen && packScreen(s)) {
                // the game's resource pack screen: a vanilla-style GET PACKS button in the footer row opens the Modrinth browser
                packs = true;
                units(Gfx.base);
                if (packButton("Get Packs", false)) PackBrowser.show();
                if (packButton("Combine", true)) PackMixer.show();
            } else if (cPause != null && cPause.isInstance(s) && !Input.menuOpen && MODULE.bool("pauseButton")) {
                // F3+Esc pauses without the menu (screenshots): no button either
                if (legacy || !Boolean.FALSE.equals(Game.fld(s, "field_19319"))) {
                    pause = true;
                    units(Gfx.base);
                    if (chip("pause", 12, 12, 32, "NETHER", keyName(), true, false, false)) Input.setMenu(true);
                    // singleplayer: invite friends into this world (WorldShare)
                    if (WorldShare.server() != null) {
                        boolean on = WorldShare.sharing();
                        if (chip("invite", 12 + chipW("NETHER", keyName(), true) + 8, 12, 32, on ? "SHARING" : "INVITE", null, false, on, true)) WorldShare.show();
                    }
                    WorldShare.card(Gfx.fbW / Gfx.s, 12);
                }
            }
        } finally {
            Gfx.minPx = 0;
            Gfx.round = r0;
            Gfx.s = Gfx.base;
        }
    }

    private static int footerVer = -1;

    /** The pack screen puts its buttons in a 33 px footer layout (1.20.5+ / 26.x) rather than at height - 48. */
    private static boolean footerLayout() {
        if (legacy) return false;
        if (footerVer < 0) {
            footerVer = 1;
            String v = Game.version();
            java.util.regex.Matcher m = v == null ? null : java.util.regex.Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?").matcher(v);
            if (m != null && m.find()) {
                int mi = Integer.parseInt(m.group(1)), pa = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                footerVer = mi > 20 || (mi == 20 && pa >= 5) ? 1 : 0;
            }
        }
        return footerVer == 1;
    }

    /**
     * GET PACKS beside vanilla's Open Pack Folder / Done (150 px each, 8 px apart, centred) in the game's own button
     * texture, fireball mark left of the label; top right when the row has no room. COMBINE (left = true) mirrors it
     * left of Open Pack Folder, or sits under GET PACKS in the corner. Units: 2 per GUI px.
     */
    private static boolean packButton(String label, boolean left) {
        int gs = Math.max(1, Math.round(Gfx.base * 2));
        int gw = (int) Math.ceil(Gfx.fbW / (double) gs), gh = (int) Math.ceil(Gfx.fbH / (double) gs);
        boolean vf = Vanilla.font();
        float tw = vf ? Vanilla.width(label) : Gfx.width(Gfx.SEMI, 11, label);
        float w = Math.max(160, tw + 16 + 8 + 32), h = 40;
        float x = left ? (gw / 2 - 162) * 2 - w : (gw / 2 + 162) * 2, y = (gh - (footerLayout() ? 27 : 48)) * 2;
        if (x < 8 || x + w > gw * 2 - 8) { x = gw * 2 - w - 8; y = left ? 8 + h + 8 : 8; }
        boolean hov = over(x, y, w, h);
        if (!Vanilla.button(x, y, w, hov)) {
            Gfx.rect(x, y, w, h, 0, hov ? 0xFF6F6F6F : 0xFF4A4A4A);
            Gfx.ring(x, y, w, h, 0, 2, hov ? 0xFFFFFFFF : 0xFF000000);
        }
        float cx = x + (w - (16 + 8 + tw)) / 2;
        mark(cx, y + (h - 18.9f) / 2, 16);
        int col = legacy && hov ? 0xFFFFFFA0 : 0xFFFFFFFF;
        if (vf) Vanilla.text(label, cx + 24, y + 12, col, true);
        else Gfx.text(Gfx.SEMI, 11, cx + 24, y + h / 2, col, label);
        box(x, y, w, h);
        return hit(x, y, w, h);
    }

    /** Overlay death: re-open the title screen we stripped, so vanilla rebuilds its buttons and splash. */
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

    private static final java.util.Map<Class<?>, Boolean> packCache = new java.util.WeakHashMap<>();

    /**
     * The game's resource pack screen, found without names: a screen holding a translatable text with the key
     * resourcePack.title (1.16+, any mappings), or Forge 1.8.9-1.12.2's GuiScreenResourcePacks. Answer cached per class.
     */
    private static boolean packScreen(Object s) {
        Class<?> c = s.getClass();
        Boolean known = packCache.get(c);
        if (known != null) return known;
        boolean yes = c.getName().endsWith("GuiScreenResourcePacks");
        try {
            for (Class<?> k = c; !yes && k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(s);
                    if (v != null && !(v instanceof java.util.Collection) && String.valueOf(v).contains("'resourcePack.title'")) { yes = true; break; }
                }
            }
        } catch (Throwable ignored) { }
        packCache.put(c, yes);
        return yes;
    }

    // ---------------------------------------------------------------- input

    /** boxes (framebuffer px) of our controls on vanilla's title / pause screen, from the last frame */
    private static final float[] boxes = new float[48];
    private static int nb;

    /** Mouse press from the input chain while no Nether menu is open; true swallows it. */
    static boolean press(int b) {
        if (title) { if (b == 0) pressed = true; return true; }
        if ((pause || packs) && b == 0) {
            float x = fx(), y = fy();
            for (int i = 0; i < nb; i += 4) if (x >= boxes[i] && y >= boxes[i + 1] && x < boxes[i] + boxes[i + 2] && y < boxes[i + 1] + boxes[i + 3]) { pressed = true; return true; }
        }
        return false;
    }

    static void box(float x, float y, float w, float h) {
        if (title || nb + 4 > boxes.length) return;
        float k = Gfx.s;
        boxes[nb++] = x * k; boxes[nb++] = y * k; boxes[nb++] = w * k; boxes[nb++] = h * k;
    }

    private static float fx() { return (float) (Input.mx * Gfx.fbW / Input.ww); }
    private static float fy() { return (float) (Input.my * Gfx.fbH / Input.wh); }

    /** cursor in units of the current scale; the quick panel's rect (units) while it is open */
    private static float MX, MY;
    private static float[] modal;
    static boolean inModal;

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
        modal = panelOpen ? new float[] {12, 52, PW, PH} : Party.modal(W, H);
        tip = null;
        boolean inPanel = MX >= 12 && MY >= 52 && MX < 12 + PW && MY < 52 + PH; // raw test: over() is false under the open panel
        if (panelOpen && click && !inPanel && !inside(custBox)) { panelOpen = false; click = false; modal = null; }
        account();

        String kind = MODULE.str("bg");
        MenuBack.draw(W, H, kind, legacy, MX, MY);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * e;
        float rise = (1 - e) * 8;
        String layout = MODULE.str("layout");
        boolean accTop = true;
        // where the party chat and the PLAY card may go in this layout
        float chatX = 30, chatBottom = H - 30, playBottom = H - 36;
        if (layout.equals("Left")) { accTop = rail(W, H, screen, rise); chatX = Math.min(330, Math.max(270, W * 0.3f)) + 16; }
        else if (layout.equals("Bottom")) { dock(W, H, screen, rise); chatBottom = H - 58 - 26 - 12; playBottom = H - 58 - 26 - 14; }
        else if (layout.equals("Center")) center(W, H, screen, rise);
        else lobby(W, H, screen, rise);
        corner(W, accTop);
        footer(W, H, layout.equals("Bottom"));
        Party.title(W, H, chatX, chatBottom, playBottom, screen);
        Gfx.alpha = a0;
        topBar();
        if (panelOpen) panel(W, H);
        else panelA = 0;
        if (tip != null && !panelOpen) tooltip(W, H);
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
            mark(centred ? x - s / 2 : x, y, s);
            sy = y + 70 * sc;
        } else {
            // the brand logo (fireball over the NETHER CLIENT wordmark), 116 tall
            float h = 116 * sc;
            Logo.brand(centred ? x - Logo.brandWidth(h) / 2 : x, y, h);
            sy = y + 122 * sc;
        }
        if (MODULE.bool("splash") && splash != null) {
            String sp = fit(Gfx.UI, 11, splash, 0, 300);
            if (centred) Gfx.textC(Gfx.UI, 11, x, sy + 6, Gfx.DIM, sp, 0);
            else Gfx.text(Gfx.UI, 11, x + 2, sy + 6, Gfx.DIM, sp);
        }
    }

    /**
     * Lobby: logo and buttons in a left column, your party on the stage to the right (Party.stage), quick join (solo) or
     * the party's PLAY card bottom right.
     */
    private static void lobby(float W, float H, Object scr, float rise) {
        boolean mods = cMods != null;
        float x = 30, bw = Math.min(220, Math.max(180, W * 0.24f)), bh = 30, gap = 6;
        MenuBack.hgrad(0, 0, bw + 160, H, Gfx.a(Gfx.BG, 0.6f), Gfx.a(Gfx.BG, 0));
        float sc = Math.min(0.8f, Math.max(0.6f, (H - 300) / 260f));
        float lh = logoH(sc);
        int rows = mods ? 4 : 3;
        float block = lh + (lh > 0 ? 16 : 0) + rows * (bh + gap) - gap + ACT_GAP + ACT_H;
        float y = Math.max(56, 60 + (H * 0.62f - block) * 0.35f) + rise;
        logo(x, y, false, sc);
        y += lh + (lh > 0 ? 16 : 0);
        float half = (bw - gap) / 2;
        button(SINGLE, x, y, bw, bh, true, scr); y += bh + gap;
        button(MULTI, x, y, bw, bh, true, scr); y += bh + gap;
        if (mods) { button(MODS, x, y, bw, bh, true, scr); y += bh + gap; }
        button(OPTIONS, x, y, half, bh, false, scr);
        button(QUIT, x + half + gap, y, half, bh, false, scr);
        // in a party the online count would run into the party chat (bottom left)
        actions(x, y + bh + ACT_GAP, bw, !Party.active(), false);
        float sx = x + bw + 20, sw = W - sx - 16;
        // feet stay above the PLAY card (bottom right) so nameplates never run under it
        float stageBottom = Party.active() ? H - 36 - 96 - 10 : H - 60;
        if (MODULE.bool("partyStage")) Party.stage(sx, 70, sw, stageBottom - 70, scr);
        float cw = 256;
        if (!Party.active() && W - 16 - cw > sx) QuickJoin.card(W - 16 - cw, H - 36, cw, 76, scr);
    }

    static String tip;
    /** A tooltip near the cursor this frame (title screen). */
    static void tip(String t) { tip = t; }

    private static void tooltip(float W, float H) {
        float w = Math.min(320, Gfx.width(Gfx.UI, 10.5f, tip) + 20), h = 24;
        float x = Math.min(W - w - 8, MX + 14), y = Math.min(H - h - 8, MY + 18);
        Gfx.rect(x, y, w, h, 6, Gfx.a(Gfx.CARD2, 0.97f));
        Gfx.ring(x, y, w, h, 6, 1, Gfx.LN2);
        Gfx.text(Gfx.UI, 10.5f, x + 10, y + h / 2, Gfx.ICE, fit(Gfx.UI, 10.5f, tip, 0, w - 20));
    }

    /** cursor (units), a click still waiting this frame, the raw cursor test, and eating the click */
    static float mx() { return MX; }
    static float my() { return MY; }
    static boolean clickNow() { return click; }
    static void eat() { click = false; }
    static boolean overRaw(float x, float y, float w, float h) { return MX >= x && MY >= y && MX < x + w && MY < y + h; }
    static float[] chipBox() { return partyChip; }
    private static float[] partyChip;

    /** Game loader as a label (FABRIC, FORGE, NEOFORGE, QUILT, VANILLA). */
    static String loader() { if (loader == null) loader = loaderName(); return loader; }

    /** Stack: logo and buttons in a centre column, quick join bottom right when it clears the column. */
    private static void center(float W, float H, Object scr, float rise) {
        boolean mods = cMods != null;
        float bw = 232, bh = 30, gap = 6, cx = W / 2;
        int rows = mods ? 4 : 3;
        float lh = logoH(1), block = lh + (lh > 0 ? 10 : 0) + rows * (bh + gap) - gap + ACT_GAP + ACT_H + 22;
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
        actions(x0, y + bh + ACT_GAP, bw, true, true);
        float cw = 256;
        if (!Party.active() && W - 16 - cw > cx + bw / 2 + 24) QuickJoin.card(W - 16 - cw, H - 36, cw, 76, scr);
    }

    /** Rail: a left panel with logo, buttons and the account card; quick join bottom right. Returns: account goes top right. */
    private static boolean rail(float W, float H, Object scr, float rise) {
        float RW = Math.min(330, Math.max(270, W * 0.3f));
        Gfx.rect(0, 0, RW, H, 0, Gfx.a(Gfx.BG, 0.84f));
        Gfx.rect(RW - MenuBack.lw(), 0, MenuBack.lw(), H, 0, Gfx.LN);
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
        y += bh + ACT_GAP;
        actions(x, y, bw, true, false);
        y += ACT_H + 22;
        boolean accTop = true;
        if (MODULE.bool("account") && y + 20 < H - 34 - 44) { accTop = accountCard(x, H - 34 - 44, bw) <= 0; }
        float cw = 256;
        if (!Party.active() && W - 16 - cw > RW + 24) QuickJoin.card(W - 16 - cw, H - 36, cw, 76, scr);
        return accTop;
    }

    /** Dock: big logo left, a full-width button bar at the bottom, quick join above the bar on the right. */
    private static void dock(float W, float H, Object scr, float rise) {
        float barH = 58, by = H - barH - 26;
        Gfx.rect(0, by, W, barH, 0, Gfx.a(Gfx.BG, 0.84f));
        Gfx.rect(0, by, W, MenuBack.lw(), 0, Gfx.LN);
        int[] ids = cMods != null ? new int[] {SINGLE, MULTI, MODS, OPTIONS, QUIT} : new int[] {SINGLE, MULTI, OPTIONS, QUIT};
        float gap = 8, bh = 34, bw = Math.min(156, (W - 48 - gap * (ids.length - 1)) / ids.length);
        float x = (W - (ids.length * bw + (ids.length - 1) * gap)) / 2;
        for (int id : ids) { button(id, x, by + (barH - bh) / 2, bw, bh, false, scr); x += bw + gap; }
        float aw = 300; // the actions row sits centred just above the bar
        actions((W - aw) / 2, by - ACT_H - 14, aw, false, true);
        float sc = Math.min(1.35f, Math.max(1, (by - 120) / 260f));
        float lh = logoH(sc);
        MenuBack.hgrad(0, 0, W * 0.55f, by, Gfx.a(Gfx.BG, 0.5f), Gfx.a(Gfx.BG, 0));
        logo(48, 56 + Math.max(0, (by - 56 - lh) * 0.5f) + rise, false, sc);
        float cw = 256;
        if (!Party.active() && W - 16 - cw > 48 + 280 * sc) QuickJoin.card(W - 16 - cw, by - 14, cw, 76, scr);
    }

    // ---------------------------------------------------------------- the Nether actions row

    static final float ACT_H = 34, ACT_GAP = 10;

    /**
     * The Right Shift hub's actions as part of the title screen itself (2026-11-19): settings, a wide MODS, wardrobe and
     * friends (the hub's order), with the live player count under it when `online`.
     */
    private static void actions(float x, float y, float w, boolean online, boolean centred) {
        float gap = 6, sq = ACT_H, mw = w - 3 * sq - 3 * gap;
        int go = -1;
        if (actBtn("as", x, y, sq, sq, "settings", null, "Nether settings")) go = Menu.T_SETTINGS;
        if (actBtn("am", x + sq + gap, y, mw, sq, null, "MODS", "Nether mods and HUD  \u00b7  " + keyName())) go = Menu.lastCat;
        if (actBtn("aw", x + sq + mw + gap * 2, y, sq, sq, "wardrobe", null, "Wardrobe")) go = Menu.T_WARDROBE;
        float fx = x + sq * 2 + mw + gap * 3;
        if (actBtn("af", fx, y, sq, sq, "friends", null, "Friends")) go = Menu.T_FRIENDS;
        int badge = Friends.badge();
        if (badge > 0) { // requests + asks to join
            String b = badge > 9 ? "9+" : String.valueOf(badge);
            float bw0 = Math.max(14, Gfx.width(Gfx.UI, 9, b) + 7);
            Gfx.rect(fx + sq - bw0 + 4, y - 4, bw0, 14, 7, Th.ac);
            Gfx.textC(Gfx.UI, 9, fx + sq - bw0 / 2 + 4, y + 3, 0xFFFFFFFF, b, 0);
        }
        int n = Friends.everyone;
        if (online && n > 0) { // green dot + "1,284 players online"
            String t = String.format(java.util.Locale.US, "%,d", n), u = n == 1 ? "  player online" : "  players online";
            float tw = Gfx.width(Gfx.SEMI, 10.5f, t), uw = Gfx.width(Gfx.UI, 10.5f, u);
            float lx = centred ? x + w / 2 - (12 + tw + uw) / 2 : x + 2, ly = y + sq + 13;
            Gfx.rect(lx, ly - 4, 8, 8, 4, Gfx.a(0xFF3FB950, 0.2f));
            Gfx.rect(lx + 1.5f, ly - 2.5f, 5, 5, 2.5f, 0xFF3FB950);
            Gfx.text(Gfx.SEMI, 10.5f, lx + 12, ly, Gfx.ICE, t);
            Gfx.text(Gfx.UI, 10.5f, lx + 12 + tw, ly, Gfx.DIM, u);
        }
        if (go >= 0) { panelOpen = false; Menu.openAt(go); }
    }

    /** An actions-row button: the title buttons' glass with a line icon or a label, and a tooltip on hover. */
    private static boolean actBtn(String id, float x, float y, float w, float h, String icon, String label, String tipText) {
        boolean hv = over(x, y, w, h), down = hv && Input.btn[0];
        float t = an(id, hv);
        Gfx.rect(x, y, w, h, 6, down ? Gfx.S3 : Gfx.mix(Gfx.a(Gfx.CARD, 0.8f), Gfx.CARD2, t));
        Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Gfx.LN, Gfx.LN2, t));
        int c = Gfx.mix(Gfx.DIM, Gfx.ICE, 0.45f + 0.55f * t);
        float dy = down ? 1 : 0;
        if (icon != null) ModIcons.draw(icon, x + w / 2 - 9, y + h / 2 - 9 + dy, 18, c);
        else Gfx.textC(Gfx.SEMI, 13, x + w / 2, y + h / 2 + dy, c, label, 0);
        if (hv && tipText != null) tip(tipText);
        return hit(x, y, w, h);
    }

    /**
     * One menu button in the chosen style (v6 flat, radius 6; NETHER CLIENT is the screen's one accent action, radius 8);
     * acts on click. left = label at the left edge (rail).
     */
    private static void button(int i, float x, float y, float w, float h, boolean left, Object screen) {
        String style = MODULE.str("buttons"), label = LABELS[i];
        boolean accent = i == NETHER, hv = over(x, y, w, h), down = hv && Input.btn[0];
        float t = an(label, hv);
        String key = accent ? keyName() : null;
        float fs = 12.5f, tw = Gfx.width(Gfx.SEMI, fs, label);
        float kw = key != null ? Gfx.width(Gfx.UI, 10, key) + 12 : 0;
        if (tw > w - 22) { fs = Math.max(9.5f, fs * (w - 22) / tw); tw = Gfx.width(Gfx.SEMI, fs, label); }
        boolean showKey = key != null && w - tw - kw > 52;
        int txt;
        if (accent) {
            Gfx.rect(x, y, w, h, 8, down ? Gfx.mix(Th.ac, Gfx.BLACK, 0.18f) : Gfx.mix(Th.ac, Th.ac2, t));
            txt = 0xFFFFFFFF;
        } else {
            switch (style) {
                case "Outline":
                    Gfx.rect(x, y, w, h, 6, Gfx.a(Gfx.ICE, (down ? 0.02f : 0.06f) * t));
                    Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.4f), t));
                    break;
                case "Minimal":
                    if (t > 0.01f) Gfx.rect(x, y, w, h, 6, Gfx.a(Gfx.ICE, 0.06f * t));
                    if (left && t > 0.01f) Gfx.rect(x, y + 6, 3, h - 12, 1.5f, Gfx.a(Th.ac, t));
                    break;
                default: // flat glass
                    Gfx.rect(x, y, w, h, 6, down ? Gfx.S3 : Gfx.mix(Gfx.a(Gfx.CARD, 0.8f), Gfx.CARD2, t));
                    Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Gfx.LN, Gfx.LN2, t));
            }
            txt = Gfx.mix(Gfx.DIM, Gfx.ICE, 0.45f + 0.55f * t);
        }
        float dy = down ? 1 : 0;
        if (left) Gfx.text(Gfx.SEMI, fs, x + 14, y + h / 2 + dy, txt, label);
        else Gfx.textC(Gfx.SEMI, fs, x + w / 2 - (showKey ? kw / 2 + 4 : 0), y + h / 2 + dy, txt, label, 0);
        if (showKey) { // the key that opens the same menu in game
            float kx = left ? x + w - kw - 10 : x + w / 2 + tw / 2 - kw / 2 + 4;
            Gfx.rect(kx, y + h / 2 - 8 + dy, kw, 16, 4, Gfx.a(Gfx.BLACK, 0.22f));
            Gfx.textC(Gfx.UI, 10, kx + kw / 2, y + h / 2 + dy, 0xFFFFFFFF, key, 0);
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
        Gfx.text(Gfx.HEAD, 24, x - Gfx.width(Gfx.HEAD, 24, tm), 26, Gfx.ICE, tm);
        Gfx.text(Gfx.UI, 10, x - Gfx.width(Gfx.UI, 10, dt), 46, Gfx.DIM, dt);
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
        float lw = Gfx.text(Gfx.UI, 9, 12, H - 12, Gfx.a(Gfx.ICE, 0.6f), version);
        String c1 = "Copyright Mojang AB. Do not distribute!", c2 = "Not affiliated with Mojang or Microsoft";
        float w1 = Gfx.width(Gfx.UI, 9, c1), w2 = Gfx.width(Gfx.UI, 9, c2);
        if (lw + w1 + 40 > W) return;
        Gfx.text(Gfx.UI, 9, W - 12 - w1, H - 12, Gfx.a(Gfx.ICE, 0.75f), c1);
        if (!dock) Gfx.text(Gfx.UI, 9, W - 12 - w2, H - 12 - Math.max(14, 12.5f / Gfx.s), Gfx.a(Gfx.ICE, 0.6f), c2);
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

    static String accName;
    private static String accUuid;
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
                if (u == null && !legacy) u = Game.call(ses, "method_1673"); // before 1.19: the uuid string
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
        glass(x, y, w, h);
        if (Gfx.hasTexture("menu.head")) Gfx.image("menu.head", x + 8, y + 8, 28, 28, 0xFFFFFFFF, 4);
        else {
            Gfx.rect(x + 8, y + 8, 28, 28, 4, Gfx.S3);
            Gfx.textC(Gfx.HEAD, 16, x + 22, y + 22, Gfx.ICE, accName.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
        Gfx.text(Gfx.SEMI, 13, x + 46, y + 16, Gfx.ICE, fit(Gfx.SEMI, 13, accName, 0, w - 56));
        int hr = LocalDateTime.now().getHour();
        String g = hr < 5 ? "Up late" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
        Gfx.text(Gfx.UI, 10.5f, x + 46, y + 31, Gfx.DIM, g);
        return h;
    }

    /** v6 floating glass (Home): s1 at ~80% (no blur in game), 10% hairline, radius 10. */
    static void glass(float x, float y, float w, float h) {
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.8f));
        Gfx.ring(x, y, w, h, 10, 1, 0x1AFFFFFF);
    }

    // ---------------------------------------------------------------- top bar (our title screen) + pause chip

    private static float[] custBox;

    /** Top-left CUSTOMISE control of our title screen. */
    private static void topBar() {
        float x = 12, y = 12, h = 32;
        float w = chipW("CUSTOMISE", null, false) + 18;
        custBox = new float[] {x, y, w, h};
        if (chip("cust", x, y, h, "CUSTOMISE", null, false, panelOpen, false)) { panelOpen = !panelOpen; Party.drawerOpen = false; }
        float pw = Party.chip(x + w + 8, y, h);
        partyChip = pw > 0 ? new float[] {x + w + 8, y, pw, h} : null;
    }

    private static float chipW(String label, String key, boolean mark) {
        float kw = key != null ? Gfx.width(Gfx.UI, 10, key) + 12 : 0;
        return 12 + (mark ? 22 : 0) + Gfx.width(Gfx.SEMI, 11, label) + (key != null ? 8 + kw : 0) + 12;
    }

    /**
     * Small top-bar button: NETHER (ghast mark + key chip, red) or a neutral one with a settings glyph; drawn the same on
     * the title and pause screens. Returns true when clicked.
     */
    private static boolean chip(String id, float x, float y, float h, String label, String key, boolean mark, boolean active, boolean person) {
        float kw = key != null ? Gfx.width(Gfx.UI, 10, key) + 12 : 0;
        float w = chipW(label, key, mark) + (mark ? 0 : 18);
        float t = an(id, over(x, y, w, h) || active);
        Gfx.rect(x, y, w, h, 6, Gfx.mix(Gfx.a(Gfx.CARD, 0.86f), Gfx.CARD2, t));
        Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.4f), t));
        float tx = x + 12;
        if (mark) { mark(tx, y + (h - 16.5f) / 2, 14); tx += 22; }
        else if (person) { // a person with a plus (Invite to world)
            int c = active ? Th.ac2 : Gfx.mix(Gfx.DIM, Gfx.ICE, t);
            float cy = y + h / 2;
            Gfx.rect(tx + 2, cy - 7, 5.5f, 5.5f, 2.75f, c);
            Gfx.rect(tx, cy, 9.5f, 6, 3, c);
            Gfx.rect(tx + 11, cy - 2.6f, 5, 1.4f, 0.7f, c);
            Gfx.rect(tx + 12.8f, cy - 4.4f, 1.4f, 5, 0.7f, c);
            tx += 18;
        }
        else { // three sliders
            int c = Gfx.mix(Gfx.DIM, Gfx.ICE, t);
            for (int i = 0; i < 3; i++) {
                float ly = y + h / 2 - 5 + i * 5;
                Gfx.rect(tx, ly - 0.6f, 12, 1.2f, 0.6f, c);
                Gfx.rect(tx + (i == 1 ? 7 : 2), ly - 1.5f, 3, 3, 1.5f, c);
            }
            tx += 18;
        }
        float tw = Gfx.text(Gfx.SEMI, 11, tx, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, 0.6f + 0.4f * t), label);
        if (key != null) {
            float kx = tx + tw + 8;
            Gfx.rect(kx, y + h / 2 - 8, kw, 16, 4, Gfx.S3);
            Gfx.textC(Gfx.UI, 10, kx + kw / 2, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, t), key, 0);
        }
        box(x, y, w, h);
        return hit(x, y, w, h);
    }

    // ---------------------------------------------------------------- quick customise panel

    private static boolean panelOpen;
    private static float panelA;
    private static final float PW = 300, PH = 152;
    private static final String[] DIMS = {"OFF", "20%", "40%", "60%", "80%"};

    private static void panel(float W, float H) {
        panelA = Config.calm() ? 1 : Math.min(1, panelA + Gfx.dt * 8);
        float x = 12, y = 52 - (1 - panelA) * 6, w = PW, h = PH;
        inModal = true;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * panelA;
        Gfx.rect(x, y + 4, w, h, 10, Gfx.a(Gfx.BLACK, 0.4f));
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.96f));
        Gfx.ring(x, y, w, h, 10, 1, Gfx.LN2);
        float cw = w - 32, c1 = x + 16, yy = y + 18;
        label("DIM", c1, yy);
        double dim = MODULE.num("dim");
        for (int i = 0; i < DIMS.length; i++) {
            float cwi = (cw - 4 * 4) / 5, cx = c1 + i * (cwi + 4);
            if (cell("dim" + i, DIMS[i], cx, yy + 12, cwi, Math.abs(dim - i * 0.2) < 0.06)) Config.set("menu", "dim", i * 0.2);
        }
        label("SHOW", c1, yy + 54);
        String[] tk = {"clock", "account", "servers", "splash"}, tl = {"CLOCK", "ACCOUNT", "QUICK JOIN", "SPLASH"};
        for (int i = 0; i < 4; i++) {
            float bx = c1 + (i % 2) * (cw / 2 + 2), by = yy + 66 + (i / 2) * 28, bw = cw / 2 - 2;
            boolean on = tk[i].equals("servers") ? MODULE.num("servers") > 0 : MODULE.bool(tk[i]);
            if (toggle("tg" + i, tl[i], bx, by, bw, on))
                Config.set("menu", tk[i], tk[i].equals("servers") ? (Object) (on ? 0.0 : 3.0) : (Object) !on);
        }
        Gfx.alpha = a0;
        inModal = false;
    }

    private static void label(String s, float x, float y) { Gfx.text(Gfx.UI, 9, x, y, Gfx.SUB, s); }

    /** Seg-style option cell: selected = s3 fill + hairline, hover = faint fill (v6 `Seg`). */
    private static boolean cell(String id, String label, float x, float y, float w, boolean sel) {
        float h = 24, t = an("c" + id, over(x, y, w, h)), s = an("s" + id, sel);
        Gfx.rect(x, y, w, h, 4, Gfx.mix(Gfx.a(Gfx.ICE, 0.03f + 0.04f * t), Gfx.S3, s));
        if (s > 0.02f) Gfx.ring(x, y, w, h, 4, 1, Gfx.a(Gfx.LN2, s));
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, s)), fit(Gfx.SEMI, 10, label, 0, w - 6), 0);
        return hit(x, y, w, h);
    }

    /** Label + v6 pill toggle (30 x 17, accent when on); the whole row is clickable. */
    private static boolean toggle(String id, String label, float x, float y, float w, boolean on) {
        float h = 24, t = an("c" + id, over(x, y, w, h)), s = an("s" + id, on);
        Gfx.rect(x, y, w, h, 4, Gfx.a(Gfx.ICE, 0.03f + 0.04f * t));
        Gfx.text(Gfx.SEMI, 10, x + 8, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, s * 0.8f)), fit(Gfx.SEMI, 10, label, 0, w - 50));
        float px = x + w - 36, py = y + (h - 17) / 2;
        Gfx.rect(px, py, 30, 17, 8.5f, Gfx.mix(Gfx.S3, Th.ac, s));
        Gfx.rect(px + 3 + 13 * s, py + 3, 11, 11, 5.5f, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, s));
        return hit(x, y, w, h);
    }

    // ---------------------------------------------------------------- marks + actions

    /** The logo mark (pixel fireball) centred in an s x 19s/16 box (the old ghast mark's box, so layouts keep their spacing). */
    static void mark(float x, float y, float s) {
        float h = s * 19 / 16;
        Logo.draw(x + (s - Logo.width(h)) / 2, y, h);
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
            case OPTIONS: open(mc, options(mc, screen, Game.cls0(legacy ? Game.l("axn", "net.minecraft.client.gui.GuiOptions") : "net.minecraft.class_429"))); break;
            case QUIT: Game.call(mc, legacy ? Game.l("m", "func_71400_g") : "method_1592"); break;
            default: panelOpen = false; Input.setMenu(true);
        }
    }

    static void open(Object mc, Object screen) {
        if (screen != null) Game.setScreen(screen);
    }

    /**
     * The options screen: its (parent, options) constructor with the game's options object, found by type on the game instance
     * (no field name to get wrong on any version or loader), else the named field. 26.x adds a third (boolean inGame).
     */
    private static Object options(Object mc, Object parent, Class<?> cls) {
        if (cls == null) return null;
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                boolean three = p.length == 3 && p[2] == boolean.class;
                if (p.length != 2 && !three || !p[0].isInstance(parent)) continue;
                for (Class<?> k = mc.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (!p[1].isAssignableFrom(f.getType()) || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object o = f.get(mc);
                        if (o == null) continue;
                        c.setAccessible(true);
                        return three ? c.newInstance(parent, o, false) : c.newInstance(parent, o);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[nether-agent] could not open options: " + t);
        }
        return make(cls, parent, Game.fld(mc, legacy ? Game.l("t", "field_71474_y") : "field_1690"));
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
