package dev.nether.ov.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.nether.ov.features.hud.Waypoints;
import dev.nether.ov.features.lyrics.Lyrics;
import dev.nether.ov.features.visual.ViewEditor;
import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Modules;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.menu.title.Pano;
import dev.nether.ov.ui.ColorPicker;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Sfx;
import dev.nether.ov.ui.Th;

/**
 * The settings page of every module but Lyrics (that one has {@link LyricsStudio}), after the Lyrics studio's look:
 * a header (icon, name, category, description, HUD layout / live edit, reset all, the on switch), a sidebar with a
 * settings search and the page's sections, a live preview stage of what the module draws (folds down to a strip), and
 * the settings as titled cards, one per {@link Setting#header} group (settings before the first header are "General").
 * A row is its label and hint, its control and, once the value is not the default, an accent dot and a reset arrow.
 * Controls: animated switch; slider with the default marked, a value field to type into and fine steps (Shift x10,
 * Shift-drag for fine moves); segmented choice, or a dropdown when the options do not fit; colour swatch opening
 * {@link ColorPicker} (alpha, rainbow, presets); key capture with a clash mark and clear; text field; action button.
 * The same cards draw inline (Settings > Title screen) and as plain rows (the live view editor sheet).
 */
final class SettingsPage {
    private SettingsPage() {}

    static final float RH = 50, HEAD = 46, GAP = 12, SIDE = 184, PV_BAR = 24;
    /** what the page search holds (typed into via Menu's field with kind {@link Menu#QUERY}) */
    static String query = "";
    static final String QKEY = "page:query";
    private static String pageId, curQ = "";
    /** folded cards this session, "<module id>#<group title>" */
    private static final Set<String> closed = new HashSet<>();
    /** eased positions relative to their row (segmented selection, sidebar marker) */
    private static final Map<String, float[]> pos = new HashMap<>();
    private static String resetAsk;
    private static long resetAt;
    // the open dropdown ("<id>:<key>") and where its button was drawn this frame
    private static String dd;
    private static Module ddM;
    private static Setting ddS;
    private static float ddX, ddY, ddW;
    private static boolean ddSeen;
    // slider being dragged and where the drag began (relative moves allow Shift for fine steps)
    private static String slDrag;
    private static float slL;
    private static double slV;
    /** offsets of the cards in the scrolled content (sidebar jumps / active section) */
    private static final List<Float> secY = new ArrayList<>();
    private static final Map<Class<?>, Boolean> ownPreview = new HashMap<>();

    /** One card: a header's group of settings (wp: the Waypoints list). */
    private static final class Group {
        final String title;
        final List<Setting> rows = new ArrayList<>();
        boolean wp;
        Group(String t) { title = t; }
    }

    // ---------------------------------------------------------------- the module page

    /** Module m's page inside the Mods screen's list area. */
    static void page(Module m, float lx, float top, float lw, float viewH, float wheel) {
        if (!m.id.equals(pageId)) { pageId = m.id; query = ""; dd = null; resetAsk = null; pos.remove("side"); }
        boolean[] added = openFolds(m);
        float a0 = Gfx.alpha, pk = Menu.k(Menu.pageT, 0, 0.26f);
        Gfx.alpha = a0 * pk;
        try {
            Menu.navCols = 1;
            curQ = liveQuery();
            float x = lx + 16 + (1 - pk) * 30, w = lw - 32, y = top + 6, bottom = top + viewH - 6;
            header(m, x, y, w);
            y += 64;
            String note = m.lockNote();
            if (note != null) {
                Gfx.rect(x, y, w, 34, 6, Gfx.a(Th.ac, 0.12f));
                Gfx.ring(x, y, w, 34, 6, 1, Gfx.a(Th.ac, 0.35f));
                Gfx.text(Gfx.SEMI, 11, x + 14, y + 17, Th.tx, note, false, 1f);
                y += 44;
            }
            boolean side = w >= 640;
            float cx = side ? x + SIDE + 16 : x, cw = side ? w - SIDE - 16 : w;
            List<Group> gs = groups(m, curQ);
            float ph = previewH(m, bottom - y), cy = y + (ph > 0 ? ph + 10 : 0), view = bottom - cy;
            float contentH = contentH(m, gs, cw) + 8;
            float maxS = Menu.scrollTo(contentH, view, wheel);
            if (side) sidebar(m, gs, x, y, SIDE, bottom - y, maxS);
            if (ph > 0) preview(m, cx, y, cw, ph);
            if (view < 40) return;
            Menu.beginClip(cx - 8, cy, cw + 16, view);
            cards(m, gs, cx, cy + 2 - Menu.scroll, cw);
            Menu.endClip();
            Menu.scrollBar(cx, cy, cw + 12, view, contentH, maxS);
        } finally {
            Gfx.alpha = a0;
            closeFolds(m, added);
        }
    }

    /** Settings > Title screen: module m's cards (no header, sidebar or search) from y. */
    static void inline(Module m, float x, float y, float w) {
        boolean[] added = openFolds(m);
        try { curQ = ""; cards(m, groups(m, ""), x, y, w); } finally { closeFolds(m, added); }
    }

    static float inlineH(Module m, float w) {
        boolean[] added = openFolds(m);
        try { curQ = ""; return contentH(m, groups(m, ""), w); } finally { closeFolds(m, added); }
    }

    /** Live view editor sheet: plain rows under small section titles from y; returns the y after them. */
    static float flat(Module m, float x, float y, float w) {
        boolean[] added = openFolds(m);
        try {
            curQ = "";
            List<Group> gs = groups(m, "");
            for (Group g : gs) {
                if (gs.size() > 1) { Gfx.text(Gfx.MONO, 9, x, y + 16, Th.mut, g.title.toUpperCase(Locale.ROOT), false, 2f); y += 26; }
                for (int i = 0; i < g.rows.size(); i++) y = row(m, g.rows.get(i), x, y, w, i < g.rows.size() - 1);
                y += 6;
            }
            return y;
        } finally { closeFolds(m, added); }
    }

    static float flatH(Module m, float w) {
        boolean[] added = openFolds(m);
        try {
            List<Group> gs = groups(m, "");
            float h = 0;
            for (Group g : gs) { if (gs.size() > 1) h += 26; for (Setting s : g.rows) h += rowH(m, s); h += 6; }
            return h;
        } finally { closeFolds(m, added); }
    }

    /** Typing on a module page searches its settings (Lyrics has its own page). */
    static boolean takesTyping(String id) {
        Module m = Modules.byId(id);
        return m != null && !(m instanceof Lyrics);
    }

    /** Ctrl+F or a typed letter on the page: start typing into the settings search (with t typed). */
    static void search(String t) {
        if (!QKEY.equals(Menu.editSet)) Menu.startEdit(QKEY, Menu.QUERY, query, 32);
        if (t != null && !t.isEmpty()) Menu.typeField(t);
    }

    /** Esc on the page: clears a search first; true when it did. */
    static boolean clearSearch() {
        if (query.isEmpty() && !QKEY.equals(Menu.editSet)) return false;
        query = "";
        if (QKEY.equals(Menu.editSet)) Menu.editSet = null;
        return true;
    }

    private static String liveQuery() { return (QKEY.equals(Menu.editSet) ? Menu.editBuf : query).trim(); }

    // ---------------------------------------------------------------- frame hooks (the dropdown is drawn over everything)

    /** Before the screens draw: an open dropdown takes this frame's click (an option, or closing it). */
    static void before() {
        frame++;
        ddSeen = false;
        if (dd == null || !Menu.click) return;
        float[] r = ddRect();
        if (Menu.L >= r[0] && Menu.L < r[0] + r[2] && Menu.T >= r[1] + 4 && Menu.T < r[1] + r[3] - 4) {
            int i = (int) ((Menu.T - r[1] - 4) / 28);
            if (ddS != null && i >= 0 && i < ddS.opts.length) { Config.set(ddM.id, ddS.key, ddS.opts[i]); Sfx.click(); }
        }
        dd = null;
        Menu.click = false;
    }

    /** After the screens: the open dropdown over them (closed when its row was not drawn this frame). */
    static void after() {
        if (dd == null) return;
        if (!ddSeen) { dd = null; return; }
        float[] r = ddRect();
        String cur = Config.str(ddM.id, ddS.key, (String) ddS.def);
        Gfx.rect(r[0] - 1, r[1] + 5, r[2] + 2, r[3], 8, Gfx.a(0xFF000000, Th.light() ? 0.14f : 0.42f));
        Gfx.rect(r[0], r[1], r[2], r[3], 7, Th.light() ? Th.s1 : Th.s3);
        Gfx.ring(r[0], r[1], r[2], r[3], 7, 1, Th.ln2);
        for (int i = 0; i < ddS.opts.length; i++) {
            float oy = r[1] + 4 + i * 28;
            boolean hv = Menu.L >= r[0] && Menu.L < r[0] + r[2] && Menu.T >= oy && Menu.T < oy + 28, sel = ddS.opts[i].equals(cur);
            if (hv) Gfx.rect(r[0] + 4, oy + 1, r[2] - 8, 26, 5, Gfx.a(Th.ov, 0.08f));
            Gfx.text(Gfx.UI, 11.5f, r[0] + 14, oy + 14, sel ? Th.tx : Gfx.mix(Th.dim, Th.tx, hv ? 0.7f : 0), Menu.fit(ddS.opts[i], Gfx.UI, 11.5f, r[2] - 44, 0));
            if (sel) Gfx.rect(r[0] + r[2] - 18, oy + 11, 6, 6, 3, Th.ac);
        }
    }

    /** {x, y, w, h} of the open dropdown list: under its button, above it when it would leave the screen. */
    private static float[] ddRect() {
        int n = ddS == null ? 0 : ddS.opts.length;
        float h = n * 28 + 8, y = ddY + 4;
        if (y + h > Menu.H - 8) y = Math.max(8, ddY - 30 - 4 - h);
        return new float[] {ddX, y, ddW, h};
    }

    // ---------------------------------------------------------------- groups and values

    /** Fold groups (the HUD Appearance group) show their settings only while open: the page shows them as cards. */
    private static boolean[] openFolds(Module m) {
        List<Setting> l = m.settings;
        boolean[] added = new boolean[l.size()];
        for (int i = 0; i < l.size(); i++) if (l.get(i).fold != null) added[i] = Setting.open.add(l.get(i).fold);
        return added;
    }

    private static void closeFolds(Module m, boolean[] added) {
        List<Setting> l = m.settings;
        for (int i = 0; i < l.size() && i < added.length; i++) if (added[i]) Setting.open.remove(l.get(i).fold);
    }

    /** The cards of m: one per header, settings shown now and matching q (a matching title keeps its whole card). */
    private static List<Group> groups(Module m, String q) {
        String lq = q.toLowerCase(Locale.ROOT);
        List<Group> out = new ArrayList<>();
        if (m instanceof Waypoints && (lq.isEmpty() || "saved waypoints".contains(lq) || "this world".contains(lq))) {
            Group g = new Group("Saved waypoints");
            g.wp = true;
            out.add(g);
        }
        Group g = null;
        boolean all = false;
        for (Setting s : m.settings) {
            if (s.t == Setting.HEADER) { g = new Group(s.label); out.add(g); all = !lq.isEmpty() && s.label.toLowerCase(Locale.ROOT).contains(lq); continue; }
            if (!s.shown()) continue;
            if (g == null) { g = new Group("General"); out.add(g); all = false; }
            if (lq.isEmpty() || all || matches(s, lq)) g.rows.add(s);
        }
        out.removeIf(x -> !x.wp && x.rows.isEmpty());
        return out;
    }

    private static boolean matches(Setting s, String lq) {
        return s.label.toLowerCase(Locale.ROOT).contains(lq) || (s.hint != null && s.hint.toLowerCase(Locale.ROOT).contains(lq))
            || (s.opts != null && s.t == Setting.CHOICE && String.join(" ", s.opts).toLowerCase(Locale.ROOT).contains(lq));
    }

    /** The stored value differs from the setting's default. */
    static boolean changed(Module m, Setting s) {
        if (!s.stored() || Config.raw(m.id, s.key) == null) return false;
        Object d = s.def;
        if (d instanceof Boolean) return Config.bool(m.id, s.key, (Boolean) d) != (Boolean) d;
        if (d instanceof Number) return Math.abs(Config.num(m.id, s.key, ((Number) d).doubleValue()) - ((Number) d).doubleValue()) > 1e-9;
        if (d instanceof String) {
            String v = Config.str(m.id, s.key, (String) d);
            if (s.t == Setting.COLOR && !"chroma".equals(v)) return Config.argb(v) != Config.argb((String) d);
            return !v.equals(d);
        }
        return false;
    }

    private static void reset(Module m, Setting s) {
        if (ColorPicker.isOpen(m.id, s.key)) ColorPicker.close();
        if ((m.id + ":" + s.key).equals(Menu.editSet)) Menu.editSet = null;
        Config.set(m.id, s.key, s.def);
        Sfx.click();
    }

    private static int changedIn(Module m, Group g) {
        int n = 0;
        for (Setting s : g.rows) if (changed(m, s)) n++;
        return n;
    }

    /** The default, as the reset arrow's tooltip names it. */
    private static String defText(Setting s) {
        switch (s.t) {
            case Setting.BOOL: return Boolean.TRUE.equals(s.def) ? "on" : "off";
            case Setting.SLIDER: return fmt(((Number) s.def).doubleValue(), s.step);
            case Setting.KEY: return Menu.keyName(((Number) s.def).intValue());
            case Setting.TEXT: return ((String) s.def).isEmpty() ? "empty" : (String) s.def;
            default: return String.valueOf(s.def);
        }
    }

    /** v with as many decimals as the step has. */
    static String fmt(double v, double step) {
        int d = 0;
        double st = step;
        while (d < 3 && Math.abs(st - Math.rint(st)) > 1e-6) { st *= 10; d++; }
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    private static double snap(double v, Setting s) {
        v = Math.round(v / s.step) * s.step;
        v = Math.round(v * 1e6) / 1e6;
        return Math.max(s.min, Math.min(s.max, v));
    }

    // ---------------------------------------------------------------- header and sidebar

    private static void header(Module m, float x, float y, float w) {
        boolean on = m.enabled(), lock = m.locked();
        float on1 = Menu.an("pgon" + m.id, on), ic = 48;
        Gfx.rect(x, y, ic, ic, 10, Gfx.mix(Th.s3, Gfx.mix(Th.s3, Th.ac, 0.22f), on1));
        Gfx.ring(x, y, ic, ic, 10, 1, Gfx.mix(Th.ln2, Gfx.a(Th.ac, 0.6f), on1));
        float a1 = Gfx.alpha;
        Gfx.alpha = a1 * (0.6f + 0.4f * on1);
        boolean item = ModIcons.drawItem(m.id, x + 12, y + 12, 24);
        Gfx.alpha = a1;
        if (!item && !ModIcons.draw(m.id, x + 12, y + 12, 24, Gfx.mix(Th.dim, Th.tx, on1))) Gfx.textC(Gfx.HEAD, 18, x + ic / 2, y + ic / 2, Th.dim, m.name.substring(0, 1), 0);

        // right side, from the right: on switch, reset all, layout / live
        float rx = x + w;
        String st = lock ? "Unavailable" : on ? "Enabled" : "Disabled";
        boolean fs = Menu.focus(rx - 44, y + 13, 44, 22);
        toggle("pgsw" + m.id, rx - 44, y + 13, on, 44, 24);
        float sw0 = Gfx.width(Gfx.SEMI, 11, st, 0.6f);
        Gfx.text(Gfx.SEMI, 11, rx - 54 - sw0, y + 25, lock ? Th.mut : on ? Th.tx : Th.dim, st, false, 0.6f);
        if (!lock && (Menu.hit(rx - 60 - sw0, y + 6, sw0 + 60, 38) || Menu.used(fs) != 0)) { Config.set(m.id, "enabled", !on); Sfx.toggle(!on); }
        rx -= 70 + sw0;
        boolean ask = m.id.equals(resetAsk) && System.currentTimeMillis() - resetAt < 3000;
        String rl = ask ? "CONFIRM RESET" : "RESET ALL";
        float rw = Gfx.width(Gfx.SEMI, 11, rl, 1.2f) + 28;
        rx -= rw;
        if (Menu.fbutton("pgreset" + m.id, rl, rx, y + 10, rw, 30, ask)) {
            if (ask) { ColorPicker.close(); Menu.commitEdit(); m.reset(); resetAsk = null; Sfx.toggle(false); }
            else { resetAsk = m.id; resetAt = System.currentTimeMillis(); }
        }
        if (Menu.in(rx, y + 10, rw, 30)) Menu.tip = ask ? "Click again to put every setting back (also on / off and the HUD position)" : "Every setting of " + m.name + " back to its default";
        if (Menu.liveOk(m)) {
            String ll = "EDIT LIVE";
            float lw = Gfx.width(Gfx.SEMI, 11, ll, 1.2f) + 28;
            rx -= lw + 8;
            if (Menu.fbutton("pglive" + m.id, ll, rx, y + 10, lw, 30, true)) { Menu.commitEdit(); ColorPicker.close(); Menu.startLive(m); }
            if (Menu.in(rx, y + 10, lw, 30)) Menu.tip = "The menu steps aside: change it over the running game";
        }
        if (m.hudPiece()) {
            String el = "EDIT LAYOUT";
            float ew = Gfx.width(Gfx.SEMI, 11, el, 1.2f) + 28;
            rx -= ew + 8;
            if (Menu.fbutton("pglay" + m.id, el, rx, y + 10, ew, 30, false)) { Menu.commitEdit(); ColorPicker.close(); HudEditor.select(m.id); Menu.hudEdit = true; }
            if (Menu.in(rx, y + 10, ew, 30)) Menu.tip = "Move and resize it on the HUD  (Ctrl E)";
        }

        // name, category, star; description
        float tx = x + ic + 16, room = rx - 16 - tx;
        float nw = Gfx.text(Gfx.SEMI, 17, tx, y + 15, Th.tx, Menu.fit(m.name, Gfx.SEMI, 17, room - 110, 0));
        String cat = m.cat.toUpperCase(Locale.ROOT);
        float cw = Gfx.width(Gfx.MONO, 8, cat, 1.4f) + 14;
        Gfx.rect(tx + nw + 10, y + 7, cw, 17, 8.5f, Th.s3);
        Gfx.text(Gfx.MONO, 8, tx + nw + 17, y + 15.5f, Th.dim, cat, false, 1.4f);
        boolean fav = m.fav();
        float sx = tx + nw + 10 + cw + 6;
        boolean sh = Menu.in(sx, y + 4, 22, 22);
        Menu.drawStar(sx + 11, y + 15, fav, fav ? 0xFFFFB020 : Gfx.mix(Th.mut, Th.tx, Menu.an("pgstar" + m.id, sh)));
        if (sh) Menu.tip = fav ? "Remove from favourites" : "Add to favourites (pinned on top of every list)";
        if (Menu.hit(sx, y + 4, 22, 22)) Config.set(m.id, "fav", !fav);
        String d = Menu.fit(m.desc, Gfx.UI, 11.5f, room, 0);
        Gfx.text(Gfx.UI, 11.5f, tx, y + 36, Th.dim, d);
        if (!d.equals(m.desc) && Menu.in(tx, y + 28, room, 18)) Menu.tip = m.desc;
    }

    /** Search field, the sections (click = jump there) and how much is changed. */
    private static void sidebar(Module m, List<Group> gs, float x, float y, float w, float h, float maxS) {
        Gfx.rect(x, y, w, h, Th.R, Gfx.a(Th.ov, Th.light() ? 0.03f : 0.02f));
        Gfx.ring(x, y, w, h, Th.R, 1, Th.ln);
        searchField(x + 10, y + 10, w - 20);
        float yy = y + 56;
        Gfx.text(Gfx.MONO, 8.5f, x + 14, yy + 6, Th.mut, "SECTIONS", false, 2f);
        yy += 18;
        int act = 0;
        for (int i = 0; i < secY.size() && i < gs.size(); i++) if (secY.get(i) <= Menu.scroll + 30) act = i;
        if (maxS > 0 && Menu.scroll >= maxS - 2 && !gs.isEmpty()) act = gs.size() - 1; // scrolled to the end: the last card is the one read
        int total = 0;
        float ey = yy;
        for (int i = 0; i < gs.size(); i++) {
            Group g = gs.get(i);
            if (ey + 30 > y + h - 40) break;
            boolean sel = i == act, hv = Menu.in(x + 6, ey, w - 12, 30), f = Menu.focus(x + 6, ey, w - 12, 30);
            float t = Menu.an("sdh" + m.id + i, hv);
            if (t > 0.01f) Gfx.rect(x + 6, ey, w - 12, 30, 6, Gfx.a(Th.ov, 0.05f * t));
            int n = changedIn(m, g);
            total += n;
            Gfx.text(Gfx.UI, 12, x + 20, ey + 15, sel ? Th.tx : Gfx.mix(Th.dim, Th.tx, t), Menu.fit(g.title, Gfx.UI, 12, w - 64, 0));
            String c = g.wp ? "" : String.valueOf(g.rows.size());
            if (n > 0) { Gfx.rect(x + w - 22, ey + 12, 6, 6, 3, Th.ac); }
            else Gfx.text(Gfx.UI, 10.5f, x + w - 16 - Gfx.width(Gfx.UI, 10.5f, c), ey + 15, Th.mut, c);
            if (n > 0 && hv) Menu.tip = n + (n == 1 ? " setting changed" : " settings changed");
            if (Menu.hit(x + 6, ey, w - 12, 30) || Menu.used(f) != 0) {
                closed.remove(m.id + "#" + g.title);
                if (i < secY.size()) Menu.scrollT = secY.get(i);
            }
            ey += 32;
        }
        // the marker slides to the section in view
        if (!gs.isEmpty()) {
            float ty = yy + act * 32 + 7;
            float[] p = pos.get("side");
            if (p == null || Config.calm()) pos.put("side", p = new float[] {ty});
            p[0] += (ty - p[0]) * Math.min(1, Gfx.dt * 16);
            if (p[0] + 16 < y + h - 40) Gfx.rect(x + 9, p[0], 3, 16, 1.5f, Th.ac);
        }
        if (gs.isEmpty()) Gfx.text(Gfx.UI, 11, x + 20, yy + 15, Th.mut, "No match");
        // footer: how many settings differ from the defaults
        float fy = y + h - 30;
        Gfx.rect(x + 12, fy - 6, w - 24, 1, 0, Th.ln);
        if (curQ.isEmpty()) {
            Gfx.rect(x + 16, fy + 9, 6, 6, 3, total > 0 ? Th.ac : Gfx.a(Th.mut, 0.6f));
            Gfx.text(Gfx.UI, 10.5f, x + 28, fy + 12, Th.mut, total == 0 ? "All at defaults" : total + (total == 1 ? " setting changed" : " settings changed"));
        } else Gfx.text(Gfx.UI, 10.5f, x + 16, fy + 12, Th.mut, "Esc clears the search");
    }

    /** The settings search: magnifier, live filter as you type, clear. */
    private static void searchField(float x, float y, float w) {
        boolean ed = QKEY.equals(Menu.editSet);
        String v = ed ? Menu.editBuf : query;
        float h = 32;
        boolean hv = Menu.in(x, y, w, h);
        float t = Menu.an("pgq", ed || hv);
        Gfx.rect(x, y, w, h, 7, Gfx.a(Th.ov, 0.04f + 0.03f * t));
        Gfx.ring(x, y, w, h, 7, 1, ed ? Gfx.a(Th.ac, 0.8f) : Gfx.mix(Th.ln, Th.ln2, t));
        ModIcons.draw("zoom", x + 10, y + h / 2 - 7, 14, ed ? Th.tx : Th.mut);
        float tx = x + 32, room = w - 32 - 26;
        String shown = v;
        if (ed) while (shown.length() > 1 && Gfx.width(Gfx.UI, 12, shown) > room) shown = shown.substring(1);
        float tw = Gfx.text(Gfx.UI, 12, tx, y + h / 2, v.isEmpty() ? Th.mut : Th.tx, v.isEmpty() && !ed ? "Search settings" : shown);
        if (v.isEmpty() && ed) tw = 0;
        if (ed && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(tx + tw + 1, y + h / 2 - 7, 1.5f, 14, 0, Th.ac2);
        if (!v.isEmpty()) {
            boolean xh = Menu.in(x + w - 26, y + 4, 22, h - 8);
            Menu.drawX(x + w - 15, y + h / 2, 3.4f, Gfx.mix(Th.mut, Th.tx, Menu.an("pgqx", xh)));
            if (Menu.hit(x + w - 26, y + 4, 22, h - 8)) { clearSearch(); return; }
        } else if (!ed) Gfx.text(Gfx.UI, 9.5f, x + w - 10 - Gfx.width(Gfx.UI, 9.5f, "Ctrl F"), y + h / 2, Th.mut, "Ctrl F");
        if (!ed && Menu.hit(x, y, w, h)) search(null);
    }

    // ---------------------------------------------------------------- preview stage

    /** Draws what the module puts on screen (its own preview()): HUD boxes, crosshair, zoom, hand... */
    static boolean hasPreview(Module m) {
        if (ViewEditor.has(m) || m.hudPiece()) return true;
        return ownPreview.computeIfAbsent(m.getClass(), c -> {
            try { c.getDeclaredMethod("preview", float.class, float.class); return true; } catch (NoSuchMethodException e) { return false; }
        });
    }

    private static boolean pvOpen() { return Config.bool("client", "settingsPreview", true); }

    /** Height of the preview block (bar + stage) for the room left; folded = the bar only, short windows get a compact stage. */
    private static float previewH(Module m, float room) {
        if (!hasPreview(m)) return 0;
        if (!pvOpen()) return PV_BAR + 8;
        if (ViewEditor.has(m)) return PV_BAR + ViewEditor.H;
        return PV_BAR + Math.max(84, Math.min(156, room - 300));
    }

    private static void preview(Module m, float x, float y, float w, float h) {
        boolean open = pvOpen();
        // bar: title, what it shows, hide / show
        boolean on = m.enabled();
        Gfx.rect(x + 1, y + 6, 6, 6, 3, on ? 0xFF3DDC84 : Th.mut);
        Gfx.text(Gfx.MONO, 8.5f, x + 13, y + 9.5f, Th.dim, "PREVIEW", false, 2f);
        String what = !on ? "Turn it on to see it in game" : m.hudPiece() ? "Live: the real HUD box at its scale" : ViewEditor.has(m) ? "Drag in the picture to adjust" : "Live";
        Gfx.text(Gfx.UI, 10.5f, x + 13 + Gfx.width(Gfx.MONO, 8.5f, "PREVIEW", 2f) + 10, y + 9.5f, Th.mut, what);
        String tl = open ? "Hide" : "Show";
        float tw = Gfx.width(Gfx.UI, 10.5f, tl) + 22;
        boolean th = Menu.in(x + w - tw, y, tw, 20);
        int tc = Gfx.mix(Th.mut, Th.tx, Menu.an("pvt", th));
        Gfx.text(Gfx.UI, 10.5f, x + w - tw, y + 9.5f, tc, tl);
        Menu.chevron(x + w - 8, y + 9.5f, open, tc);
        boolean fb = Menu.focus(x + w - tw, y, tw, 20);
        if (Menu.hit(x + w - tw, y - 2, tw + 4, 22) || Menu.used(fb) != 0) Config.set("client", "settingsPreview", !open);
        if (th) Menu.tip = open ? "Fold the preview away for more room" : "Show the preview again";
        if (!open) { Gfx.rect(x, y + PV_BAR - 4, w, 1, 0, Th.ln); return; }
        float sy = y + PV_BAR, sh = h - PV_BAR;
        if (ViewEditor.has(m)) { ViewEditor.draw(m, x, sy, w, "ve" + m.id); return; }
        int[] clip = Gfx.clipIn(x, sy, w, sh);
        if (!Pano.still(MainMenu.MODULE.str("pano"), x, sy, w, sh, 205, 0.42f, 0)) Gfx.rectG(x, sy, w, sh, 0, 0xFF1B2030, 0xFF0B0C10);
        Gfx.rect(x, sy, w, sh, 0, 0x4D000000);
        Gfx.rectG(x, sy + sh * 0.55f, w, sh * 0.45f, 0, 0x00000000, 0x59000000);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * (on ? 1 : 0.55f);
        try { m.preview(x + w / 2, sy + sh / 2); } catch (RuntimeException e) { /* a preview needs game state that is not there */ }
        Gfx.alpha = a0;
        if (m.hudPiece() && m.w < 1) Gfx.textC(Gfx.UI, 11, x + w / 2, sy + sh / 2, 0xB3FFFFFF, "Nothing to show right now", 0);
        if (m.hudPiece()) tag(x + w - 10, sy + sh - 26, "SCALE " + fmt(m.scale(), 0.01) + "x", true);
        Gfx.reclip(clip);
        Gfx.ring(x, sy, w, sh, 0, 1, Th.ln2);
    }

    private static void tag(float x, float y, String s, boolean right) {
        float tw = Gfx.width(Gfx.MONO, 8, s, 1) + 14;
        if (right) x -= tw;
        Gfx.rect(x, y, tw, 18, 9, 0x99000000);
        Gfx.text(Gfx.MONO, 8, x + 7, y + 9, 0xCCFFFFFF, s, false, 1);
    }

    // ---------------------------------------------------------------- cards

    private static float bodyH(Module m, Group g) {
        if (g.wp) return Menu.wpHeight((Waypoints) m) - 8;
        float h = 4;
        for (Setting s : g.rows) h += rowH(m, s);
        return h + 4;
    }

    /** Eased open amount of a card, advanced once per frame (heights are read by the measure and the draw). */
    private static float openK(Module m, Group g) {
        if (!curQ.isEmpty()) return 1;
        String k = m.id + "#" + g.title;
        float t = closed.contains(k) ? 0 : 1;
        float[] v = folds.get(k);
        if (v == null || Config.calm()) folds.put(k, v = new float[] {t, frame});
        if (v[1] != frame) { v[0] += (t - v[0]) * Math.min(1, Gfx.dt * 14); if (Math.abs(t - v[0]) < 0.002f) v[0] = t; v[1] = frame; }
        return v[0];
    }

    private static final Map<String, float[]> folds = new HashMap<>();
    private static int frame;

    private static float contentH(Module m, List<Group> gs, float w) {
        if (gs.isEmpty()) return 120;
        float h = 0;
        for (Group g : gs) h += HEAD + bodyH(m, g) * openK(m, g) + GAP;
        return h;
    }

    private static void cards(Module m, List<Group> gs, float x, float y, float w) {
        secY.clear();
        float y0 = y;
        if (gs.isEmpty()) {
            Gfx.textC(Gfx.HEAD, 14, x + w / 2, y + 50, Th.dim, "No settings match", 0);
            Gfx.textC(Gfx.UI, 11, x + w / 2, y + 74, Th.mut, "Esc clears the search", 0);
            return;
        }
        for (Group g : gs) {
            secY.add(y - y0);
            y = card(m, g, x, y, w) + GAP;
        }
    }

    private static float card(Module m, Group g, float x, float y, float w) {
        String ck = m.id + "#" + g.title;
        boolean search = !curQ.isEmpty();
        float o = openK(m, g), body = bodyH(m, g), h = HEAD + body * o;
        Gfx.rect(x, y, w, h, Th.R, Th.s2);
        Gfx.ring(x, y, w, h, Th.R, 1, Th.ln);
        // head: title, count / changed, reset this card, fold
        int n = changedIn(m, g);
        boolean hv = Menu.in(x, y, w, HEAD), f = Menu.focus(x, y, w, HEAD);
        float ht = Menu.an("cdh" + ck, hv);
        float tw = Gfx.text(Gfx.SEMI, 13, x + 16, y + HEAD / 2, Th.tx, g.title);
        String sub = g.wp ? "This server, world and dimension" : n > 0 ? n + " changed" : g.rows.size() + (g.rows.size() == 1 ? " setting" : " settings");
        Gfx.text(Gfx.UI, 10.5f, x + 16 + tw + 10, y + HEAD / 2, n > 0 ? Gfx.mix(Th.ac2, Th.tx, 0.15f) : Th.mut, sub);
        float rx = x + w - 16;
        if (!search) { Menu.chevron(rx - 6, y + HEAD / 2, o > 0.5f, Gfx.mix(Th.mut, Th.tx, ht)); rx -= 24; }
        boolean resetHit = false;
        if (n > 0) {
            String rl = "Reset";
            float rw = Gfx.width(Gfx.UI, 10.5f, rl) + 34;
            boolean rh = Menu.in(rx - rw, y + HEAD / 2 - 12, rw, 24);
            float rt = Menu.an("cdr" + ck, rh);
            Gfx.rect(rx - rw, y + HEAD / 2 - 12, rw, 24, 6, Gfx.a(Th.ov, 0.04f + 0.05f * rt));
            ModIcons.draw("refresh", rx - rw + 9, y + HEAD / 2 - 6, 12, Gfx.mix(Th.dim, Th.tx, rt));
            Gfx.text(Gfx.UI, 10.5f, rx - rw + 25, y + HEAD / 2, Gfx.mix(Th.dim, Th.tx, rt), rl);
            if (rh) Menu.tip = "Put the " + n + " changed setting" + (n == 1 ? "" : "s") + " of " + g.title + " back to default";
            if (Menu.hit(rx - rw, y + HEAD / 2 - 12, rw, 24)) { for (Setting s : g.rows) if (changed(m, s)) reset(m, s); resetHit = true; }
        }
        if (!search && !resetHit && (Menu.hit(x, y, w, HEAD) || Menu.used(f) != 0)) { if (!closed.remove(ck)) closed.add(ck); }
        if (o < 0.01f) return y + h;
        Gfx.rect(x + 16, y + HEAD - 1, w - 32, 1, 0, Gfx.a(Th.ln, o));
        int[] clip = Gfx.clipIn(x, y + HEAD, w, body * o);
        float ry = y + HEAD + 4;
        if (g.wp) Menu.wpList((Waypoints) m, x + 18, ry - 22, w - 36);
        else for (int i = 0; i < g.rows.size(); i++) ry = row(m, g.rows.get(i), x + 18, ry, w - 36, i < g.rows.size() - 1);
        Gfx.reclip(clip);
        return y + h;
    }

    // ---------------------------------------------------------------- rows

    static float rowH(Module m, Setting s) {
        return RH + (s.t == Setting.COLOR && ColorPicker.isOpen(m.id, s.key) ? ColorPicker.H + 6 : 0);
    }

    /** One setting at y in (x, w): label + hint, control, reset arrow once changed; returns the y after it. */
    static float row(Module m, Setting s, float x, float y, float w, boolean line) {
        String id = m.id + "." + s.key, ek = m.id + ":" + s.key;
        float xr = x + w, cy = y + RH / 2;
        boolean f = Menu.focus(x - 8, y + 3, w + 16, RH - 6), hv = Menu.in(x - 8, y, w + 16, RH);
        float ht = Menu.an("rw" + id, hv);
        if (ht > 0.01f) Gfx.rect(x - 8, y + 3, w + 16, RH - 6, 6, Gfx.a(Th.ov, 0.03f * ht));
        boolean ch = changed(m, s);
        // reset arrow (kept in its slot so controls line up)
        float cr = xr - 28;
        if (ch) {
            boolean rh = Menu.in(xr - 24, cy - 12, 24, 24);
            float rt = Menu.an("rr" + id, rh);
            if (rt > 0.01f) Gfx.rect(xr - 24, cy - 12, 24, 24, 12, Gfx.a(Th.ov, 0.08f * rt));
            ModIcons.draw("refresh", xr - 19, cy - 7, 14, Gfx.mix(Th.mut, Th.tx, Math.max(rt, ht * 0.5f)));
            if (rh) Menu.tip = "Reset to default (" + defText(s) + ")";
            if (Menu.hit(xr - 24, cy - 12, 24, 24)) { reset(m, s); ch = false; }
        }
        float rh = RH, cw;
        switch (s.t) {
            case Setting.BOOL: cw = bool(m, s, cr, y, f, x, w); break;
            case Setting.SLIDER: cw = slider(m, s, id, ek, cr, cy, w, f); break;
            case Setting.COLOR: {
                cw = color(m, s, id, cr, cy, f);
                if (ColorPicker.isOpen(m.id, s.key)) { ColorPicker.draw(m, x, y + RH - 2, w); rh += ColorPicker.H + 6; }
                break;
            }
            case Setting.CHOICE: cw = choice(m, s, id, ek, cr, cy, w, f); break;
            case Setting.KEY: cw = key(m, s, ek, cr, cy, f, hv); break;
            case Setting.TEXT: {
                float tw = Math.min(260, w * 0.42f);
                Menu.field(ek, Menu.TXT, Config.str(m.id, s.key, (String) s.def), "Empty", cr - tw, cy - 14, tw, 28, Math.max(1, (int) s.max), f);
                cw = tw;
                break;
            }
            case Setting.ACTION: {
                String bl = s.opts != null && s.opts.length > 0 ? s.opts[0] : "Run";
                float bw = Math.max(96, Gfx.width(Gfx.SEMI, 11, bl, 1.2f) + 32);
                if ((Menu.button("a" + id, bl, cr - bw, cy - 15, bw, 30, false) | Menu.used(f) != 0) && s.run != null) s.run.run();
                cw = bw;
                break;
            }
            default: cw = 0;
        }
        // changed dot, label, hint (full text on hover when it does not fit)
        float ct = Menu.an("rc" + id, ch);
        boolean hint = s.hint != null;
        float ly = cy - (hint ? 8 : 0), room = cr - cw - 18 - x;
        if (ct > 0.01f) Gfx.rect(x - 11, ly - 2.5f, 5, 5, 2.5f, Gfx.a(Th.ac, ct));
        String lb = Menu.fit(s.label, Gfx.UI, 12.5f, room, 0);
        Gfx.text(Gfx.UI, 12.5f, x, ly, Th.tx, lb);
        if (hint) {
            String hs = Menu.fit(s.hint, Gfx.UI, 10.5f, room, 0);
            Gfx.text(Gfx.UI, 10.5f, x, cy + 9, Th.mut, hs);
            if ((!hs.equals(s.hint) || !lb.equals(s.label)) && Menu.in(x, y, room, RH)) Menu.tip = (lb.equals(s.label) ? "" : s.label + ": ") + s.hint;
        } else if (!lb.equals(s.label) && Menu.in(x, y, room, RH)) Menu.tip = s.label;
        if (line) Gfx.rect(x, y + rh - 0.5f, w, 1, 0, Th.ln);
        return y + rh;
    }

    /** Animated switch (w x h), knob with a soft shadow. */
    static void toggle(String id, float x, float y, boolean on, float w, float h) {
        float t = Menu.an(id, on), r = h / 2, k = h - 6;
        Gfx.rect(x, y, w, h, r, Gfx.mix(Gfx.a(Th.ov, Th.light() ? 0.14f : 0.12f), Th.ac, t));
        float kx = x + 3 + (w - 6 - k) * t;
        Gfx.rect(kx, y + 4, k, k, k / 2, 0x33000000);
        Gfx.rect(kx, y + 3, k, k, k / 2, Gfx.mix(Th.light() ? 0xFFFFFFFF : Th.dim, 0xFFFFFFFF, t));
    }

    private static float bool(Module m, Setting s, float cr, float y, boolean f, float x, float w) {
        boolean v = Config.bool(m.id, s.key, (Boolean) s.def);
        toggle("tg" + m.id + "." + s.key, cr - 40, y + RH / 2 - 11, v, 40, 22);
        int a = Menu.adjust(f);
        // the whole row flips it (the reset arrow took its click first)
        if (Menu.hit(x - 8, y + 3, w + 16 - 28, RH - 6) || Menu.used(f) != 0) { Config.set(m.id, s.key, !v); Sfx.toggle(!v); }
        else if (a != 0 && (a > 0) != v) Config.set(m.id, s.key, a > 0);
        return 40;
    }

    private static float slider(Module m, Setting s, String id, String ek, float cr, float cy, float w, boolean f) {
        double v = Config.num(m.id, s.key, ((Number) s.def).doubleValue()), d = ((Number) s.def).doubleValue();
        boolean compact = w < 460, shift = Input.keys[340] || Input.keys[344];
        float vw = 60, bw = 24, gw = compact ? vw : bw * 2 + vw + 8, gx = cr - gw;
        float tw = compact ? Math.max(90, Math.min(150, w * 0.32f)) : Math.max(130, Math.min(230, w * 0.3f)), tx = gx - 18 - tw;
        // keys: Left / Right one step (Shift x10)
        int a = Menu.adjust(f);
        double nv = v;
        if (a != 0) nv = snap(v + a * s.step, s);
        // drag: a press jumps to the spot, then moves relative (Shift = a fifth of the speed)
        boolean dr = Menu.dragging("sl" + id, tx - 8, cy - 12, tw + 16, 24);
        if (dr) {
            if (!id.equals(slDrag)) { slDrag = id; slL = Menu.L; slV = snap(s.min + Math.max(0, Math.min(1, (Menu.L - tx) / tw)) * (s.max - s.min), s); nv = slV; Menu.commitEdit(); }
            else nv = snap(slV + (Menu.L - slL) / tw * (s.max - s.min) * (shift ? 0.2 : 1), s);
            if (shift) { slV = nv; slL = Menu.L; } // fine moves continue from where they are
        } else if (id.equals(slDrag)) slDrag = null;
        // fine steps
        if (!compact) {
            int k = shift ? 10 : 1;
            if (step("sm" + id, "-", gx, cy, bw, nv > s.min)) nv = snap(nv - k * s.step, s);
            if (step("sp" + id, "+", gx + bw + 8 + vw, cy, bw, nv < s.max)) nv = snap(nv + k * s.step, s);
        }
        if (nv != v) Config.set(m.id, s.key, nv);
        // track: fill, steps as dots when few, the default as a notch, knob (value bubble while dragged)
        float t = Menu.an("slh" + id, dr || Menu.in(tx - 8, cy - 12, tw + 16, 24));
        float fr = (float) ((nv - s.min) / (s.max - s.min));
        Gfx.rect(tx, cy - 2.5f, tw, 5, 2.5f, Gfx.a(Th.ov, Th.light() ? 0.14f : 0.11f));
        Gfx.rect(tx, cy - 2.5f, Math.max(5, tw * fr), 5, 2.5f, Th.ac);
        double steps = (s.max - s.min) / s.step;
        if (steps <= 12) for (int i = 1; i < steps; i++) { float dx = tx + (float) (tw * i / steps); Gfx.rect(dx - 1, cy - 1, 2, 2, 1, Gfx.a(i * s.step + s.min <= nv ? 0xFFFFFFFF : Th.ov, 0.35f)); }
        float dfx = tx + tw * (float) ((d - s.min) / (s.max - s.min));
        Gfx.rect(dfx - 0.75f, cy + 5, 1.5f, 4, 0, Gfx.a(Th.mut, 0.9f));
        float kx = tx + tw * fr, r = 7 + 1.5f * t;
        Gfx.rect(kx - r, cy - r + 1, r * 2, r * 2, r, 0x40000000);
        Gfx.rect(kx - r, cy - r, r * 2, r * 2, r, 0xFFFFFFFF);
        Gfx.rect(kx - 2.5f, cy - 2.5f, 5, 5, 2.5f, Gfx.a(Th.ac, t));
        if (dr) {
            String bs = fmt(nv, s.step);
            float bbw = Gfx.width(Gfx.MONO, 10, bs) + 14;
            Gfx.rect(kx - bbw / 2, cy - 34, bbw, 20, 6, Th.light() ? 0xFF18181B : 0xFFF4F4F5);
            Gfx.textC(Gfx.MONO, 10, kx, cy - 24, Th.light() ? 0xFFFFFFFF : 0xFF18181B, bs, 0);
        } else if (Menu.in(tx - 8, cy - 12, tw + 16, 24)) Menu.tip = "Drag, or click a spot  ·  Shift-drag for fine steps  ·  default " + fmt(d, s.step);
        // value: click to type
        float vx = compact ? gx : gx + bw + 4;
        if (ek.equals(Menu.editSet)) Menu.field(ek, Menu.NUM, fmt(nv, s.step), "", vx, cy - 13, vw, 26, 10, false);
        else {
            String vs = fmt(nv, s.step);
            boolean vh = Menu.in(vx, cy - 13, vw, 26);
            float vt = Menu.an("sv" + id, vh);
            Gfx.rect(vx, cy - 13, vw, 26, 6, Gfx.a(Th.ov, 0.05f + 0.04f * vt));
            Gfx.ring(vx, cy - 13, vw, 26, 6, 1, Gfx.mix(Th.ln, Th.ln2, vt));
            Gfx.textC(Gfx.MONO, 11, vx + vw / 2, cy, Gfx.mix(Th.dim, Th.tx, 0.6f + 0.4f * vt), vs, 0);
            if (vh) Menu.tip = "Click to type a value (" + fmt(s.min, s.step) + " to " + fmt(s.max, s.step) + ")";
            if (Menu.hit(vx, cy - 13, vw, 26) || Menu.used(f) != 0) Menu.startEdit(ek, Menu.NUM, vs, 10);
        }
        return cr - tx;
    }

    /** Small - / + button; true when pressed. */
    private static boolean step(String id, String label, float x, float cy, float w, boolean ok) {
        boolean hv = ok && Menu.in(x, cy - 12, w, 24);
        float t = Menu.an(id, hv);
        Gfx.rect(x, cy - 12, w, 24, 6, Gfx.a(Th.ov, (ok ? 0.05f : 0.025f) + 0.07f * t));
        float c = 0.5f;
        int col = !ok ? Gfx.a(Th.mut, 0.5f) : Gfx.mix(Th.dim, Th.tx, t);
        Gfx.rect(x + w / 2 - 4.5f, cy - c, 9, 1.5f, 0, col);
        if (label.equals("+")) Gfx.rect(x + w / 2 - 0.75f, cy - 4.5f + 0.25f, 1.5f, 9, 0, col);
        if (hv) Menu.tip = (label.equals("+") ? "One step up" : "One step down") + "  ·  Shift: ten steps";
        return ok && Menu.hit(x, cy - 12, w, 24);
    }

    private static float color(Module m, Setting s, String id, float cr, float cy, boolean f) {
        boolean op = ColorPicker.isOpen(m.id, s.key), ch = ColorPicker.chromaOn(m, s.key);
        int col = ch ? Module.chroma() : Config.color(m.id, s.key, (int) Config.argb((String) s.def));
        float bw = 150, x = cr - bw, by = cy - 14;
        boolean hv = Menu.in(x, by, bw, 28);
        float t = Menu.an("cl" + id, hv || op);
        Gfx.rect(x, by, bw, 28, 7, Gfx.a(Th.ov, 0.04f + 0.04f * t));
        Gfx.ring(x, by, bw, 28, 7, 1, op ? Gfx.a(Th.ac, 0.8f) : Gfx.mix(Th.ln, Th.ln2, t));
        ColorPicker.swatch(x + 6, by + 5, 18, 18, col);
        int al = col >>> 24;
        String hx = ch ? "RAINBOW" : Config.hex(col | 0xFF000000).substring(0, 7);
        float hw = Gfx.text(Gfx.MONO, 10.5f, x + 32, cy, Th.tx, hx);
        if (!ch && al < 255) Gfx.text(Gfx.UI, 10, x + 32 + hw + 6, cy, Th.mut, Math.round(al * 100 / 255f) + "%");
        Menu.chevron(x + bw - 15, cy, op, Gfx.mix(Th.dim, Th.tx, t));
        if (Menu.hit(x, by, bw, 28) || Menu.used(f) != 0) ColorPicker.toggle(m, s.key);
        return bw;
    }

    private static float choice(Module m, Setting s, String id, String ek, float cr, float cy, float w, boolean f) {
        String cur = Config.str(m.id, s.key, (String) s.def);
        int ci = 0;
        float total = 0;
        for (int o = 0; o < s.opts.length; o++) { total += Gfx.width(Gfx.UI, 11.5f, s.opts[o]) + 24; if (s.opts[o].equals(cur)) ci = o; }
        boolean seg = s.opts.length <= 5 && total <= Math.min(380, w * 0.56f);
        int a = Menu.adjust(f), u = Menu.used(f);
        if (a != 0) Config.set(m.id, s.key, s.opts[Math.max(0, Math.min(s.opts.length - 1, ci + Integer.signum(a)))]);
        if (seg) {
            if (u != 0) Config.set(m.id, s.key, s.opts[(ci + 1) % s.opts.length]);
            float x0 = cr - total - 6;
            Gfx.rect(x0, cy - 15, total + 6, 30, 8, Gfx.a(Th.ov, Th.light() ? 0.07f : 0.05f));
            // selection pill eases to the picked option (kept relative to the control while the page scrolls)
            float sx = 3;
            for (int o = 0; o < ci; o++) sx += Gfx.width(Gfx.UI, 11.5f, s.opts[o]) + 24;
            float sw = Gfx.width(Gfx.UI, 11.5f, s.opts[ci]) + 24;
            float[] p = pos.get("sg" + id);
            if (p == null || Config.calm()) pos.put("sg" + id, p = new float[] {sx, sw});
            float k = Math.min(1, Gfx.dt * 16);
            p[0] += (sx - p[0]) * k;
            p[1] += (sw - p[1]) * k;
            Gfx.rect(x0 + p[0], cy - 12, p[1], 24, 6, Th.ac);
            float ox = x0 + 3;
            for (int o = 0; o < s.opts.length; o++) {
                float ow = Gfx.width(Gfx.UI, 11.5f, s.opts[o]) + 24;
                boolean sel = o == ci, oh = Menu.in(ox, cy - 12, ow, 24);
                float ht = Menu.an("sgh" + id + o, oh);
                Gfx.textC(Gfx.UI, 11.5f, ox + ow / 2, cy, sel ? 0xFFFFFFFF : Gfx.mix(Th.dim, Th.tx, ht), s.opts[o], 0);
                if (Menu.hit(ox, cy - 12, ow, 24) && !sel) Config.set(m.id, s.key, s.opts[o]);
                ox += ow;
            }
            return total + 6;
        }
        // dropdown
        float mw = 0;
        for (String o : s.opts) mw = Math.max(mw, Gfx.width(Gfx.UI, 11.5f, o));
        float bw = Math.max(150, Math.min(Math.min(240, w * 0.45f), mw + 50)), x = cr - bw;
        boolean op = ek.equals(dd), hv = Menu.in(x, cy - 14, bw, 28);
        float t = Menu.an("dd" + id, hv || op);
        Gfx.rect(x, cy - 14, bw, 28, 7, Gfx.a(Th.ov, 0.04f + 0.04f * t));
        Gfx.ring(x, cy - 14, bw, 28, 7, 1, op ? Gfx.a(Th.ac, 0.8f) : Gfx.mix(Th.ln, Th.ln2, t));
        Gfx.text(Gfx.UI, 11.5f, x + 12, cy, Th.tx, Menu.fit(cur, Gfx.UI, 11.5f, bw - 40, 0));
        Menu.chevron(x + bw - 15, cy, op, Gfx.mix(Th.dim, Th.tx, t));
        if (op) { ddSeen = true; ddX = x; ddY = cy + 14; ddW = bw; ddM = m; ddS = s; }
        if (Menu.hit(x, cy - 14, bw, 28) || u != 0) {
            if (op) dd = null;
            else { dd = ek; ddM = m; ddS = s; ddX = x; ddY = cy + 14; ddW = bw; ddSeen = true; }
        }
        return bw;
    }

    private static float key(Module m, Setting s, String ek, float cr, float cy, boolean f, boolean rowHover) {
        int code = (int) Config.num(m.id, s.key, ((Number) s.def).doubleValue());
        float bw = Math.max(118, Gfx.width(Gfx.SEMI, 11, Menu.keyName(code)) + 30);
        boolean[] hard = new boolean[1];
        String clash = Controls.conflict(m, s, hard);
        float x = cr - bw, used = bw;
        // clear (unbind) while hovered and bound
        if (code >= 0 && rowHover && !ek.equals(Menu.capture)) {
            boolean xh = Menu.in(x - 28, cy - 11, 22, 22);
            float xt = Menu.an("kx" + ek, xh);
            Gfx.rect(x - 28, cy - 11, 22, 22, 11, Gfx.a(Th.ov, 0.05f + 0.07f * xt));
            Menu.drawX(x - 17, cy, 3.2f, Gfx.mix(Th.mut, Th.tx, xt));
            if (xh) Menu.tip = "Unbind (Delete while capturing does the same)";
            if (Menu.hit(x - 28, cy - 11, 22, 22)) Config.set(m.id, s.key, -1.0);
        }
        Menu.keyButton(ek, code, x, cy - 14, bw, clash != null && hard[0], f);
        if (ek.equals(Menu.capture)) Menu.tip = "Press a key or mouse button  ·  Esc cancels  ·  Delete unbinds";
        if (clash != null) {
            int cc = hard[0] ? Th.ac2 : 0xFFFFB020;
            Gfx.rect(x - 40, cy - 3, 6, 6, 3, cc);
            if (Menu.in(x - 46, cy - 10, 18, 20)) Menu.tip = clash.charAt(0) + clash.substring(1).toLowerCase(Locale.ROOT);
            used += 48;
        } else if (code >= 0) used += 30;
        return used;
    }
}
