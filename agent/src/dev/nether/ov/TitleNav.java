package dev.nether.ov;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The title screen's top bar (2026-11-20 redesign; Valorant / Fortnite lobbies, see research/Game lobby navigation
 * research): one bar on every title-screen page, one click to each page, the active one underlined in the accent.
 * PLAY is the lobby (MainMenu.lobby); MODS, LOCKER (wardrobe), SOCIAL (friends), GALLERY (screenshots) and SETTINGS
 * (the gear) are pages under the bar, drawn by Menu / Screenshots in page mode. Right: online count, party (PLAY only),
 * account, gear menu (Nether settings, Minecraft options, Mod Menu, customise), quit.
 *
 * Drawn by MainMenu while no page is open (its input) and by Menu while one is (its input), always at the title
 * screen's own scale, so the bar never moves between pages. Input comes in framebuffer pixels.
 */
final class TitleNav {
    private TitleNav() {}

    static final int PLAY = 0, MODS = 1, LOCKER = 2, SOCIAL = 3, GALLERY = 4, SETTINGS = 5;
    private static final String[] TABS = {"PLAY", "MODS", "LOCKER", "SOCIAL", "GALLERY"};
    /** bar height in title units */
    static final float H = 54;

    private static float k = 1, mx, my;
    private static boolean click, used;
    private static String tip;
    private static boolean gearOpen;
    private static float ulX = -1, ulW;
    private static final java.util.Map<String, Float> anim = new java.util.HashMap<>();

    /** The title screen's unit size for this framebuffer (MainMenu.drawTitle's). */
    static float scale(int fbW, int fbH) { return Math.min(Gfx.base, Math.min(fbW / 640f, fbH / 480f)); }

    /** Bottom of the bar in framebuffer pixels (pages start under it). */
    static float bottomPx(int fbW, int fbH) { return H * scale(fbW, fbH); }

    /** Which page shows now. */
    static int current() {
        if (!Input.menuOpen) return PLAY;
        if (Screenshots.open) return GALLERY;
        if (Menu.tab == Menu.T_WARDROBE) return LOCKER;
        if (Menu.tab == Menu.T_FRIENDS) return SOCIAL;
        if (Menu.tab == Menu.T_SETTINGS) return SETTINGS;
        return MODS;
    }

    /** Opens a page (or the lobby). */
    static void go(int p) {
        gearOpen = false;
        if (p == current()) return;
        if (p != GALLERY) Screenshots.open = false;
        switch (p) {
            case PLAY: Input.setMenu(false); break;
            case MODS: Menu.openAt(Menu.lastCat); break;
            case LOCKER: Menu.openAt(Menu.T_WARDROBE); break;
            case SOCIAL: Menu.openAt(Menu.T_FRIENDS); break;
            case SETTINGS: Menu.openAt(Menu.T_SETTINGS); break;
            case GALLERY: Screenshots.show(); break;
        }
    }

    private static float an(String id, boolean on) {
        Float v = anim.get(id);
        float t = on ? 1 : 0;
        if (v == null || Config.calm()) v = t;
        v += (t - v) * Math.min(1, Gfx.dt * 14);
        anim.put(id, v);
        return v;
    }

    private static boolean over(float x, float y, float w, float h) { return mx >= x && my >= y && mx < x + w && my < y + h; }

    private static boolean hit(float x, float y, float w, float h) {
        if (click && over(x, y, w, h)) { click = false; used = true; Sfx.click(); return true; }
        return false;
    }

    /**
     * Draws the bar (and the gear menu) at the title scale. mxPx / myPx: mouse in framebuffer px; clicked: a click this
     * frame for us to take. Returns true when the bar used the click (the caller drops it). party: draw the PARTY chip
     * (MainMenu input only, so the lobby).
     */
    static boolean draw(int fbW, int fbH, float mxPx, float myPx, boolean clicked, boolean party) {
        devPages();
        float s0 = Gfx.s, a0 = Gfx.alpha;
        k = scale(fbW, fbH);
        Gfx.s = k;
        Gfx.alpha = 1;
        mx = mxPx / k;
        my = myPx / k;
        click = clicked;
        used = false;
        tip = null;
        float W = fbW / k;
        // gear menu first for input (it sits over the bar's right end and the page under it)
        float[] gm = gearOpen ? gearRect(W) : null;
        boolean inGear = gm != null && over(gm[0], gm[1], gm[2], gm[3]);
        boolean gearClick = click && inGear;
        if (gearClick) click = false;
        if (gearOpen && click && !inGear) { gearOpen = false; click = false; used = true; }

        // the bar: dark at the top, fading out; a hairline at its foot
        Gfx.rectG(0, 0, W, H + 26, 0, Gfx.a(Gfx.BG, 0.86f), Gfx.a(Gfx.BG, 0));
        Gfx.rect(0, H - 1, W, 1, 0, Gfx.a(Gfx.ICE, 0.07f));

        // brand: fireball + NETHER
        float x = 22, cy = H / 2;
        MainMenu.mark(x, cy - 13, 22);
        x += 30;
        boolean tight = W < 980; // narrow windows: smaller tabs so the right cluster never runs into them
        float fs = tight ? 13 : 15, pad = tight ? 20 : 28;
        // Nether Pixel (HEAD) runs wide and snaps to 16: narrow windows set the bar in Barlow so the tabs still fit
        int nf = tight ? Gfx.SEMI : Gfx.HEAD;
        float bw = Gfx.text(nf, fs, x, cy, Gfx.ICE, "NETHER", false, 1.2f);
        x += bw + (tight ? 24 : 30);
        Gfx.rect(x - 15, cy - 11, 1, 22, 0, Gfx.a(Gfx.ICE, 0.12f));

        // tabs
        int cur = current();
        float tx = x;
        float[] txs = new float[TABS.length], tws = new float[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            float w = Gfx.width(nf, fs, TABS[i], 1.4f) + pad;
            txs[i] = tx;
            tws[i] = w;
            boolean on = cur == i, hv = over(tx, cy - 16, w, 32);
            float t = an("t" + i, hv), a = an("a" + i, on);
            // Feather's tab row: the open page is a solid accent plate, the rest quiet dark plates
            Gfx.rect(tx, cy - 16, w, 32, 6, Gfx.mix(Gfx.a(0xFF000000, 0.32f + 0.1f * t), Th.ac, a));
            int col = Gfx.mix(Gfx.mix(Gfx.DIM, Gfx.ICE, t), 0xFFFFFFFF, a);
            Gfx.textC(nf, fs, tx + w / 2, cy, col, TABS[i], 1.4f);
            if (i == SOCIAL) badge(tx + w - 4, cy - 15, Friends.badge() + Party.badge());
            if (hit(tx, cy - 16, w, 32)) go(i);
            tx += w + 6;
        }

        // right cluster, right to left
        float rx = W - 18;
        rx -= 32;
        if (iconBtn("quit", "close", rx, cy - 16, 32, false)) MainMenu.act(MainMenu.QUIT, Game.screen());
        if (over(rx, cy - 16, 32, 32)) tip = "Quit game";
        rx -= 38;
        if (iconBtn("gear", "settings", rx, cy - 16, 32, gearOpen || cur == SETTINGS)) gearOpen = !gearOpen;
        if (over(rx, cy - 16, 32, 32) && !gearOpen) tip = "Settings";
        rx -= 12;
        rx = account(rx, cy);
        if (party) {
            float pw = partyW();
            if (pw > 0 && rx - pw - 10 > tx + 6) { rx -= pw + 10; Party.chip(rx, cy - 16, 32); MainMenu.setPartyChip(new float[] {rx, cy - 16, pw, 32}); }
        }
        int n = Friends.everyone;
        if (n > 0) {
            String t = String.format(Locale.US, "%,d", n), u = " online";
            float w = 14 + Gfx.width(Gfx.SEMI, 11, t) + Gfx.width(Gfx.UI, 11, u);
            if (rx - w - 16 > tx + 10) {
                rx -= w + 16;
                Gfx.rect(rx, cy - 3.5f, 7, 7, 3.5f, 0xFF3FB950);
                float lw = Gfx.text(Gfx.SEMI, 11, rx + 14, cy, Gfx.ICE, t);
                Gfx.text(Gfx.UI, 11, rx + 14 + lw, cy, Gfx.DIM, u);
                if (over(rx, cy - 10, w, 20)) tip = "Nether players online right now";
            }
        }

        pendingClick = gearClick;
        lastTip = tip;
        Gfx.s = s0;
        Gfx.alpha = a0;
        return used || gearClick;
    }

    private static boolean pendingClick;
    private static String lastTip;

    /** After the page (or the lobby) drew: the gear menu and the bar's tooltip on top of everything. */
    static void drawOver(int fbW, int fbH) {
        float s0 = Gfx.s, a0 = Gfx.alpha;
        k = scale(fbW, fbH);
        Gfx.s = k;
        Gfx.alpha = 1;
        float W = fbW / k;
        click = pendingClick;
        pendingClick = false;
        if (gearOpen) gear(W);
        tip = lastTip;
        if (tip != null && !gearOpen) tooltip(W);
        Gfx.s = s0;
        Gfx.alpha = a0;
    }

    private static long devAt;
    private static int devIdx;

    /** Dev captures: -Dnether.titlePages=1,2,3,... opens those pages one after another, 3 s apart, starting 6 s in. */
    private static void devPages() {
        String dp = System.getProperty("nether.titlePages");
        if (dp == null) return;
        long now = System.currentTimeMillis();
        if (devAt == 0) devAt = now + 6000;
        String[] p = dp.split(",");
        if (now < devAt || devIdx >= p.length) return;
        int g = Integer.parseInt(p[devIdx++].trim());
        System.out.println("[nether-agent] dev page " + g);
        if (g == 9) gearOpen = true; else go(g);
        devAt = now + 3000;
    }

    private static float partyW() {
        int n = Party.size(), b = Party.badge();
        if (n <= 0) return 0;
        String label = n > 1 ? "PARTY  " + n : "PARTY";
        return 12 + 20 + Gfx.width(Gfx.SEMI, 11, label) + 14 + (b > 0 ? 22 : 0);
    }

    private static void badge(float x, float y, int n) {
        if (n <= 0) return;
        String b = n > 9 ? "9+" : String.valueOf(n);
        float w = Math.max(14, Gfx.width(Gfx.SEMI, 9, b) + 7);
        Gfx.rect(x - w / 2, y - 7, w, 14, 7, 0xFFE5372D); // notification count: plain red, not the accent
        Gfx.textC(Gfx.SEMI, 9, x, y, 0xFFFFFFFF, b, 0);
    }

    private static boolean iconBtn(String id, String icon, float x, float y, float s, boolean on) {
        boolean hv = over(x, y, s, s);
        float t = an("i" + id, hv), a = an("o" + id, on);
        Gfx.rect(x, y, s, s, 8, Gfx.a(Gfx.ICE, 0.05f * t + 0.09f * a));
        if (a > 0.01f) Gfx.ring(x, y, s, s, 8, 1, Gfx.a(Gfx.LN2, a));
        float is = 16;
        if (icon.equals("close")) Menu.drawX(x + s / 2, y + s / 2, 4.6f, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, a)));
        else ModIcons.draw(icon, x + (s - is) / 2, y + (s - is) / 2, is, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, a)));
        return hit(x, y, s, s);
    }

    /** Head + name, ending at x; returns the new right edge. Clicking it opens the Locker (your look). */
    private static float account(float x, float cy) {
        String n = MainMenu.accName;
        if (n == null) return x;
        float nw = Gfx.width(Gfx.SEMI, 12, n), w = 8 + 26 + 10 + nw + 12, h = 34, ax = x - w;
        boolean hv = over(ax, cy - h / 2, w, h);
        float t = an("acc", hv);
        Gfx.rect(ax, cy - h / 2, w, h, 8, Gfx.mix(Gfx.a(Gfx.CARD, 0.55f), Gfx.CARD2, t));
        Gfx.ring(ax, cy - h / 2, w, h, 8, 1, Gfx.mix(Gfx.a(Gfx.ICE, 0.08f), Gfx.LN2, t));
        if (Gfx.hasTexture("menu.head")) Gfx.image("menu.head", ax + 6, cy - 13, 26, 26, 0xFFFFFFFF, 5);
        else {
            Gfx.rect(ax + 6, cy - 13, 26, 26, 5, Gfx.S3);
            Gfx.textC(Gfx.HEAD, 14, ax + 19, cy, Gfx.ICE, n.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
        Gfx.text(Gfx.SEMI, 12, ax + 42, cy, Gfx.ICE, n);
        if (hv) tip = "Your look  ·  Locker";
        if (hit(ax, cy - h / 2, w, h)) go(LOCKER);
        return ax;
    }

    // ---------------------------------------------------------------- gear menu

    private static List<String[]> gearItems() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"nether", "Nether settings", "Modules, theme, keybinds, profiles"});
        l.add(new String[] {"options", "Minecraft options", "Video, controls, sound, language"});
        if (MainMenu.hasModMenu()) l.add(new String[] {"modmenu", "Mod Menu", "Your Fabric mods and their settings"});
        l.add(new String[] {"custom", "Customise this screen", "Background, dim, quick join, splash"});
        return l;
    }

    private static float[] gearRect(float W) {
        float w = 250, h = 12 + gearItems().size() * 44;
        return new float[] {W - 18 - 70 - w + 32, H - 4, w, h};
    }

    private static void gear(float W) {
        float[] r = gearRect(W);
        Gfx.rect(r[0], r[1] + 4, r[2], r[3], 10, Gfx.a(Gfx.BLACK, 0.4f));
        Gfx.rect(r[0], r[1], r[2], r[3], 10, Gfx.a(Gfx.CARD, 0.97f));
        Gfx.ring(r[0], r[1], r[2], r[3], 10, 1, Gfx.LN2);
        List<String[]> l = gearItems();
        for (int i = 0; i < l.size(); i++) {
            float y = r[1] + 6 + i * 44, x = r[0] + 6, w = r[2] - 12;
            boolean hv = over(x, y, w, 44);
            float t = an("g" + i, hv);
            if (t > 0.01f) Gfx.rect(x, y, w, 44, 6, Gfx.a(Gfx.ICE, 0.06f * t));
            Gfx.text(Gfx.SEMI, 12, x + 12, y + 16, Gfx.ICE, l.get(i)[1]);
            Gfx.text(Gfx.UI, 10, x + 12, y + 31, Gfx.DIM, l.get(i)[2]);
            if (hit(x, y, w, 44)) {
                gearOpen = false;
                switch (l.get(i)[0]) {
                    case "nether": go(SETTINGS); break;
                    case "options": go(PLAY); MainMenu.act(MainMenu.OPTIONS, Game.screen()); break;
                    case "modmenu": go(PLAY); MainMenu.act(MainMenu.MODS, Game.screen()); break;
                    case "custom": go(PLAY); MainMenu.customise(); break;
                }
            }
        }
    }

    private static void tooltip(float W) {
        float w = Gfx.width(Gfx.UI, 10.5f, tip) + 20, h = 24;
        float x = Math.max(8, Math.min(W - w - 8, mx - w / 2)), y = H + 6;
        Gfx.rect(x, y, w, h, 6, Gfx.a(Gfx.CARD2, 0.97f));
        Gfx.ring(x, y, w, h, 6, 1, Gfx.LN2);
        Gfx.text(Gfx.UI, 10.5f, x + 10, y + h / 2, Gfx.ICE, tip);
    }
}
