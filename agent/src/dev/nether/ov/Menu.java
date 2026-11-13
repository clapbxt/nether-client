package dev.nether.ov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-game menu (immediate mode): module grid (search, tabs, favourites), per-module settings page (inline
 * colour picker, type-in slider values), the CONTROLS ({@link Controls}), CLIENT ({@link Profiles}) and WARDROBE ({@link Wardrobe}) tabs, and the HUD
 * layout editor ({@link HudEditor}). Laid out in a 720-unit-tall logical space (Gfx.s = framebuffer height / 720).
 * Keyboard: arrows / Tab move the focus, Enter / Space use it, Left / Right adjust it, Esc steps back; Ctrl+F searches,
 * Ctrl+E opens the layout editor, Ctrl+V pastes into the field being typed in.
 */
final class Menu {
    private Menu() {}

    /** Space the Controls / Profiles / Wardrobe pages draw in (set every frame from the panel; they anchor at px + 28, py + 124). */
    static float PW = 880, PH = 540;
    static final String[] TABS = {"Favorites", "All", "HUD", "Visual", "PvP", "Utility", "Wardrobe", "HUD Editor", "Settings"};
    private static final String[] TAB_ICON = {"favorites", "all", "hud", "visual", "pvp", "utility", "wardrobe", "hudeditor", "settings"};
    /** sidebar entries from here on are the CLIENT group */
    static final int T_WARDROBE = 6, T_HUDED = 7, T_SETTINGS = 8;
    private static int sub;

    static boolean hudEdit;
    static String page;
    static int tab;
    private static String search = "";
    private static boolean searchFocus;
    static String capture;
    private static String drag;
    static float scroll, scrollT;
    private static float open, gridScroll, tabX = -1, tabW;
    static float L, T, sc = 1, W, H;
    static boolean click, clickRt;
    private static boolean clipOn;
    private static float cx0, cy0, cx1, cy1;
    private static final Map<String, Float> anim = new HashMap<>();
    /** tooltip drawn over everything this frame (set by whatever the mouse is on) */
    static String tip;

    // keyboard focus: every focusable control registers in draw order (focus()); fCur is the focused one
    static int fCur = -1, navCols = 1;
    private static int fIdx, fCount, act, adj, gridFocus = -1;
    static boolean kbNav;
    private static boolean kbScroll;

    static boolean editing() { return hudEdit && Input.menuOpen; }
    static boolean capturing() { return capture != null; }
    /** Typed characters go to a text field (search with text or focus, a waypoint name): a letter menu key types there. */
    static boolean textFocus() { return fieldFocus() || (!hudEdit && tab < T_WARDROBE && (searchFocus || !search.isEmpty())); }
    /** A waypoint name, a text setting, a slider value, a hex colour or a profile name is being typed into. */
    static boolean fieldFocus() { return editWp >= 0 || editSet != null; }

    /** Smallest text in the menu, in pixels: small windows / GUI scale 1 never get unreadable labels. */
    static final int MIN_PX = 9;

    /** From the title screen's quick panel: the Settings tab with the title screen's options open. */
    static void openMainMenuSettings() { tab = T_SETTINGS; sub = 0; page = "menu"; scroll = scrollT = 0; }

    static void opened() { drag = capture = null; searchFocus = false; scroll = scrollT = 0; fCur = -1; kbNav = false; }
    static void closed() {
        commitEdit();
        drag = capture = null;
        searchFocus = false;
        hudEdit = false;
        ColorPicker.close();
        HudEditor.reset();
    }

    static void capture(int key) {
        if (key != 256) {
            if (capture.equals("menu")) { if (key != 261) Config.setMenuKey(key); } // the menu always needs a key
            else {
                int i = capture.indexOf(':');
                Config.set(capture.substring(0, i), capture.substring(i + 1), key == 261 ? -1.0 : (double) key); // Delete = unbound
            }
        }
        capture = null;
    }

    static String keyName(int k) {
        switch (k) {
            case -1: return "NONE";
            case 32: return "SPACE"; case 256: return "ESC"; case 257: return "ENTER"; case 258: return "TAB"; case 259: return "BACKSPACE";
            case 260: return "INSERT"; case 261: return "DELETE"; case 266: return "PAGE UP"; case 267: return "PAGE DOWN"; case 268: return "HOME"; case 269: return "END";
            case 262: return "RIGHT"; case 263: return "LEFT"; case 264: return "DOWN"; case 265: return "UP"; case 335: return "NUM ENTER";
            case 340: return "L-SHIFT"; case 341: return "L-CTRL"; case 342: return "L-ALT"; case 344: return "R-SHIFT"; case 345: return "R-CTRL"; case 346: return "R-ALT";
            case 280: return "CAPS"; case 96: return "`"; case 45: return "-"; case 61: return "="; case 91: return "["; case 93: return "]"; case 92: return "\\";
            case 59: return ";"; case 39: return "'"; case 44: return ","; case 46: return "."; case 47: return "/";
            default:
                if (k >= 290 && k <= 314) return "F" + (k - 289);
                if ((k >= 65 && k <= 90) || (k >= 48 && k <= 57)) return String.valueOf((char) k);
                if (k >= 320 && k <= 329) return "NUM " + (k - 320);
                return "KEY " + k;
        }
    }

    // ---------------------------------------------------------------- helpers

    static boolean in(float x, float y, float w, float h) {
        if (clipOn && (L < cx0 || L >= cx1 || T < cy0 || T >= cy1)) return false;
        return L >= x && L < x + w && T >= y && T < y + h;
    }

    static boolean hit(float x, float y, float w, float h) {
        if (click && in(x, y, w, h)) { click = false; return true; }
        return false;
    }

    static float an(String id, boolean on) {
        Float v = anim.get(id);
        float t = on ? 1 : 0;
        if (v == null || Config.calm()) v = t;
        v += (t - v) * Math.min(1, Gfx.dt * 14);
        anim.put(id, v);
        return v;
    }

    static void beginClip(float x, float y, float w, float h) {
        Gfx.clip(x, y, w, h);
        clipOn = true; cx0 = x; cy0 = y; cx1 = x + w; cy1 = y + h;
    }

    static void endClip() { Gfx.unclip(); clipOn = false; }

    static List<String> wrap(String s, int f, float size, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (Gfx.width(f, size, t) > maxW && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else { line = new StringBuilder(t); }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** s, shortened with ".." to fit maxW logical units. */
    static String fit(String s, int f, float size, float maxW, float spacing) {
        if (Gfx.width(f, size, s, spacing) <= maxW) return s;
        while (s.length() > 1 && Gfx.width(f, size, s + "..", spacing) > maxW) s = s.substring(0, s.length() - 1);
        int sp = s.lastIndexOf(' ');
        if (sp > s.length() / 2) s = s.substring(0, sp); // whole words when the cut is near the end
        return s.trim().replaceAll("[,.;:]+$", "") + "..";
    }

    private static String fmt(double v, double step) {
        return step >= 1 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** v on the setting's step grid, inside its range. */
    private static double snap(double v, Setting s) {
        v = Math.round(v / s.step) * s.step;
        v = Math.round(v * 1e6) / 1e6;
        return Math.max(s.min, Math.min(s.max, v));
    }

    // ---------------------------------------------------------------- keyboard focus

    /** Registers a keyboard-focusable control (in draw order); true when it has the focus (red ring while keys are used). */
    static boolean focus(float x, float y, float w, float h) {
        if (fIdx++ != fCur) return false;
        if (kbScroll && clipOn) { // keep the focused control in view
            if (y < cy0 + 6) scrollT -= cy0 + 6 - y;
            else if (y + h > cy1 - 6) scrollT += y + h - (cy1 - 6);
        }
        kbScroll = false;
        if (kbNav) Gfx.ring(x - 3, y - 3, w + 6, h + 6, 6, 1.5f, Th.ac2);
        return true;
    }

    /** Enter (257) or Space (32) on the focused control f, else 0 (consumed). */
    static int used(boolean f) {
        if (!f || act == 0) return 0;
        int a = act;
        act = 0;
        return a;
    }

    /** Left / Right on the focused control f: -1 / +1 (x10 with Shift), else 0 (consumed). */
    static int adjust(boolean f) {
        if (!f || adj == 0) return 0;
        int a = adj;
        adj = 0;
        return a;
    }

    private static void nav(int d) {
        kbNav = true;
        kbScroll = true;
        if (fCur < 0) { fCur = 0; return; }
        int n = fCur + d;
        if (n < 0) n = fCur;
        else if (n >= fCount) n = Math.abs(d) > 1 && fCur / d < (fCount - 1) / d ? fCount - 1 : fCur; // down from a short last row
        fCur = n;
    }

    private static void setTab(int i) {
        tab = i;
        scroll = scrollT = 0;
        fCur = -1;
        capture = null;
    }

    private static void openPage(String id) {
        commitEdit();
        gridScroll = scrollT;
        gridFocus = fCur;
        page = id;
        scroll = scrollT = 0;
        fCur = kbNav ? 0 : -1;
    }

    private static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (k == 256) { if (press) esc(); return; }
        if (hudEdit) { HudEditor.key(k, press, shift, ctrl); return; }
        if (fieldFocus()) {
            if (k == 259) { if (!editBuf.isEmpty()) editBuf = editBuf.substring(0, editBuf.length() - 1); }
            else if ((k == 257 || k == 335) && press) commitEdit();
            else if (k == 86 && ctrl && press) typeField(Plat.cur.clipboard());
            return;
        }
        if (!press && k != 259 && k != 258 && (k < 262 || k > 267)) return; // held keys repeat only Backspace, Tab, arrows, paging
        if (ctrl) {
            if (k == 70) { commitEdit(); ColorPicker.close(); page = null; if (tab >= T_WARDROBE) setTab(1); searchFocus = true; return; } // Ctrl+F
            if (k == 69) { commitEdit(); ColorPicker.close(); page = null; hudEdit = true; return; }                                   // Ctrl+E
            if (k == 86) { typedSearch(Plat.cur.clipboard()); return; }                                                                // Ctrl+V
            return;
        }
        if (k == 259) {
            if (!search.isEmpty() && tab < T_WARDROBE) { search = search.substring(0, search.length() - 1); searchFocus = true; scroll = scrollT = 0; fCur = -1; }
            return;
        }
        switch (k) {
            case 258: if (page == null) { int n = (tab + (shift ? TABS.length - 1 : 1)) % TABS.length; if (n == T_HUDED) n = (n + (shift ? TABS.length - 1 : 1)) % TABS.length; setTab(n); } else nav(shift ? -1 : 1); kbNav = true; break;
            case 265: nav(-navCols); break;
            case 264: nav(navCols); break;
            case 262: case 263: {
                int d = k == 262 ? 1 : -1;
                if (navCols > 1) nav(d);
                else if (fCur >= 0) { adj = shift ? d * 10 : d; kbNav = true; }
                break;
            }
            case 257: case 335:
                if (fCur >= 0) { act = 257; kbNav = true; }
                else if (page == null && tab < T_WARDROBE) { searchFocus = false; if (!search.isEmpty()) { fCur = 0; kbNav = true; kbScroll = true; } }
                break;
            case 32: if (fCur >= 0 && (page != null || search.isEmpty())) { act = 32; kbNav = true; } break;
            case 266: scrollT -= 300; break;
            case 267: scrollT += 300; break;
            case 268: scrollT = 0; break;
            case 269: scrollT = 1e6f; break;
            default: break;
        }
    }

    /** Esc steps back one level: typing -> layout editor selection -> layout editor -> colour picker -> search -> page -> close. */
    private static void esc() {
        if (fieldFocus()) { editWp = -1; editSet = null; return; }
        if (hudEdit) { if (!HudEditor.esc()) { hudEdit = false; HudEditor.reset(); } return; }
        if (ColorPicker.id != null) { ColorPicker.close(); return; }
        if (page == null && (searchFocus || !search.isEmpty())) { search = ""; searchFocus = false; scroll = scrollT = 0; fCur = -1; return; }
        if (page != null) { commitEdit(); page = null; scroll = scrollT = gridScroll; fCur = kbNav ? gridFocus : -1; return; }
        Input.setMenu(false);
    }

    private static void typedSearch(String t) {
        if (hudEdit || capture != null || t.isEmpty() || (tab == T_SETTINGS && sub != 0)) return;
        String q = (search + t.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')).replaceFirst("^\\s+", "");
        q = q.substring(0, Math.min(24, q.length()));
        if (q.equals(search)) return; // a lone space: nothing to search for (Space toggles the focused card)
        search = q;
        searchFocus = true;
        scroll = scrollT = 0;
        fCur = -1;
        if (tab >= T_WARDROBE) tab = 1; // typing on a client page searches the modules
        commitEdit();
        page = null;
    }

    // ---------------------------------------------------------------- text fields

    static final int TXT = 0, NUM = 1, HEX = 2, NAME = 3;
    static int editWp = -1, editMax = 64, editKind;
    static String editBuf = "";
    /** Field being typed into: "<module id>:<key>" (Enter or a click elsewhere saves, Esc cancels). */
    static String editSet;

    static void startEdit(String key, int kind, String value, int max) {
        commitEdit();
        editSet = key;
        editKind = kind;
        editBuf = value;
        editMax = max;
    }

    static void typeField(String t) {
        StringBuilder b = new StringBuilder(editBuf);
        for (char c : t.toCharArray()) {
            if (c < 32) continue;
            if (editKind == NUM && "0123456789.,-".indexOf(c) < 0) continue;
            if (editKind == HEX && Character.digit(c, 16) < 0) continue;
            b.append(c == ',' && editKind == NUM ? '.' : c);
        }
        int max = editWp >= 0 ? 24 : editMax;
        editBuf = b.substring(0, Math.min(max, b.length()));
    }

    static void commitEdit() {
        if (editSet != null) {
            int i = editSet.indexOf(':');
            String id = editSet.substring(0, i), key = editSet.substring(i + 1), v = editBuf.trim();
            editSet = null;
            switch (editKind) {
                case NUM: {
                    Module m = Modules.byId(id);
                    Setting s = m == null ? null : m.setting(key);
                    if (s != null) try { Config.set(id, key, snap(Double.parseDouble(v), s)); } catch (NumberFormatException ignored) { }
                    break;
                }
                case HEX: ColorPicker.hex(id, key, v); break;
                case NAME: Profiles.nameBuf = v; break;
                default: Config.set(id, key, v);
            }
        }
        if (editWp < 0) return;
        Waypoints w = (Waypoints) Modules.byId("waypoints");
        List<Map<String, Object>> l = w.list(false);
        if (l != null && editWp < l.size() && !editBuf.trim().isEmpty()) { l.get(editWp).put("name", editBuf.trim()); w.changed(); }
        editWp = -1;
    }

    /** Single-line text field: a click (or Enter on the focused field) starts typing; Enter or a click elsewhere saves. */
    static void field(String key, int kind, String value, String empty, float x, float y, float w, float h, int max, boolean f) {
        boolean ed = key.equals(editSet);
        if (kind == HEX) value = value.replace("#", ""); // the field draws its own #
        String v = ed ? editBuf : value;
        float t = an("fld" + key, ed || in(x, y, w, h));
        Gfx.rect(x, y, w, h, 6, Gfx.a(Th.ov, 0.035f + 0.03f * t));
        Gfx.ring(x, y, w, h, 6, 1, ed ? Th.ac : Gfx.mix(Th.ln, Th.ln2, t));
        int font = kind == HEX || kind == NUM ? Gfx.MONO : Gfx.UI;
        float tx = x + 10, cy = y + h / 2;
        if (kind == HEX) tx += Gfx.text(Gfx.MONO, 12, tx, cy, Th.mut, "#") + 1;
        float room = x + w - tx - 10;
        String shown = v;
        if (ed) while (shown.length() > 1 && Gfx.width(font, 12, shown) > room) shown = shown.substring(1); // typing: keep the end in view
        else shown = v.isEmpty() ? empty : fit(v, font, 12, room, 0);
        float fw = Gfx.text(font, 12, tx, cy, !ed && v.isEmpty() ? Th.mut : Th.tx, shown);
        if (ed && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(tx + fw + 1, cy - 7, 1.5f, 14, 0, Th.ac2);
        if (!ed && (hit(x, y, w, h) || used(f) != 0)) startEdit(key, kind, value, max);
    }

    // ---------------------------------------------------------------- widgets

    static boolean button(String id, String label, float x, float y, float w, float h, boolean primary) {
        boolean hv = in(x, y, w, h);
        float t = an(id, hv);
        boolean down = hv && Input.btn[0];
        if (primary) {
            Gfx.rect(x, y, w, h, 6, down ? 0xFFB80500 : Gfx.mix(Th.ac, Th.ac2, t));
            Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + h / 2, 0xFFFFFFFF, label, 1.2f);
        } else {
            Gfx.rect(x, y, w, h, 6, Gfx.a(Th.ov, down ? 0.02f : 0.035f + 0.05f * t));
            Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Th.ln, Th.ln2, t));
            Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + h / 2, Gfx.mix(Th.dim, Th.tx, t), label, 1.2f);
        }
        return hit(x, y, w, h);
    }

    /** A button that also takes the keyboard focus (Enter / Space presses it). */
    static boolean fbutton(String id, String label, float x, float y, float w, float h, boolean primary) {
        boolean f = focus(x, y, w, h);
        return button(id, label, x, y, w, h, primary) | used(f) != 0;
    }

    /** Anti-aliased shapes in a 16-unit box, rasterised once per pixel size. */
    private static final java.awt.Shape X_SHAPE, STAR, STAR_O, CHEV_D, CHEV_U;
    static {
        java.awt.BasicStroke st = new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(2, 2); p.lineTo(14, 14); p.moveTo(14, 2); p.lineTo(2, 14);
        X_SHAPE = st.createStrokedShape(p);
        java.awt.geom.Path2D.Float s = new java.awt.geom.Path2D.Float();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5, r = (i & 1) == 0 ? 6.8 : 2.9;
            float x = (float) (8 + r * Math.cos(a)), y = (float) (8.6 + r * Math.sin(a));
            if (i == 0) s.moveTo(x, y); else s.lineTo(x, y);
        }
        s.closePath();
        STAR = s;
        STAR_O = new java.awt.BasicStroke(1.3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND).createStrokedShape(s);
        java.awt.BasicStroke cs = new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
        java.awt.geom.Path2D.Float d = new java.awt.geom.Path2D.Float();
        d.moveTo(4, 6); d.lineTo(8, 10); d.lineTo(12, 6);
        CHEV_D = cs.createStrokedShape(d);
        java.awt.geom.Path2D.Float u = new java.awt.geom.Path2D.Float();
        u.moveTo(4, 10); u.lineTo(8, 6); u.lineTo(12, 10);
        CHEV_U = cs.createStrokedShape(u);
    }

    static void drawX(float cx, float cy, float r, int col) {
        float size = r * 2 * 16 / 12f;
        Gfx.mask("x", X_SHAPE, 16, cx - size / 2, cy - size / 2, size, col);
    }

    private static void drawStar(float cx, float cy, boolean filled, int col) {
        Gfx.mask(filled ? "star" : "staro", filled ? STAR : STAR_O, 16, cx - 8, cy - 8, 16, col);
    }

    static void chevron(float cx, float cy, boolean up, int col) {
        Gfx.mask(up ? "chevu" : "chevd", up ? CHEV_U : CHEV_D, 16, cx - 6, cy - 6, 12, col);
    }

    static void sw(String id, float x, float y, boolean on) {
        float t = an(id, on);
        Gfx.rect(x, y, 36, 20, 10, Gfx.mix(Th.s3, Th.ac, t));
        Gfx.rect(x + 3 + 16 * t, y + 3, 14, 14, 7, Gfx.mix(Th.dim, 0xFFFFFFFF, t));
    }

    private static double slider(String id, float x, float y, float w, double v, double min, double max, double step) {
        boolean hv = in(x - 6, y - 10, w + 12, 20) || id.equals(drag);
        float t = an(id, hv);
        if (click && in(x - 6, y - 10, w + 12, 20)) { drag = id; click = false; }
        if (id.equals(drag)) {
            if (Input.btn[0]) {
                double f = Math.max(0, Math.min(1, (L - x) / w));
                v = min + f * (max - min);
                v = Math.round(v / step) * step;
                v = Math.max(min, Math.min(max, v));
            } else drag = null;
        }
        float f = (float) ((v - min) / (max - min));
        Gfx.rect(x, y - 2, w, 4, 2, 0xFF23232C);
        Gfx.rect(x, y - 2, Math.max(4, w * f), 4, 2, Th.ac);
        float r = 6 + 1.5f * t;
        Gfx.rect(x + w * f - r, y - r, r * 2, r * 2, r, 0xFFEAEEF3);
        return v;
    }

    /** Key binding button; red ring while it waits for a key (or when the key clashes). */
    static void keyButton(String cid, int code, float x, float y, float w, boolean clash, boolean f) {
        boolean cap = cid.equals(capture);
        String name = cap ? "PRESS A KEY" : keyName(code);
        boolean hv = in(x, y, w, 28);
        float t = an("k" + cid, hv || cap);
        Gfx.rect(x, y, w, 28, 6, Gfx.a(Th.ov, 0.035f + 0.04f * t));
        Gfx.ring(x, y, w, 28, 6, 1, cap || clash ? Th.ac : Gfx.mix(Th.ln, Th.ln2, t));
        Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + 14, code < 0 && !cap ? Th.mut : Th.tx, name, 1f);
        if (hit(x, y, w, 28) || used(f) != 0) capture = cid;
    }

    // ---------------------------------------------------------------- frame

    static void frame(int fbW, int fbH, float dt) {
        MainMenu.frame(fbW, fbH);
        Gfx.minPx = 0;
        Gfx.s = Gfx.base;
        boolean want = Input.menuOpen;
        open = Config.calm() ? (want ? 1 : 0) : Math.max(0, Math.min(1, open + (want ? 1 : -1) * dt * 7));
        if (open <= 0) {
            Input.clickL = Input.clickR = false;
            Gfx.alpha = 1;
            Modules.drawHud(fbW, fbH);
            return;
        }
        // 720-unit-tall space, never smaller than 0.8 px/unit, but the whole panel always fits the window (narrow / short windows)
        sc = Math.min(Math.max(0.8f, fbH / 720f), Math.min(fbW / 900f, fbH / 610f));
        W = fbW / sc;
        H = fbH / sc;
        Gfx.dt = dt;
        L = (float) (Input.mx * fbW / Input.ww) / sc;
        T = (float) (Input.my * fbH / Input.wh) / sc;
        click = want && Input.clickL;
        clickRt = want && Input.clickR;
        Input.clickL = Input.clickR = Input.upL = false;
        float wheel = (float) Input.scrollY;
        Input.scrollY = 0;
        String typed = Input.chars.toString();
        Input.chars.setLength(0);
        List<int[]> evs = new ArrayList<>(Input.keyEv);
        Input.keyEv.clear();
        if (!want) { click = false; clickRt = false; wheel = 0; typed = ""; evs.clear(); }
        if (click && capture != null) capture = null; // clicking anywhere cancels a key capture (the click still lands)
        if (click || clickRt) kbNav = false;
        tip = null;
        act = adj = 0;
        boolean shift = Input.keys[340] || Input.keys[344], ctrl = Input.keys[341] || Input.keys[345];
        for (int[] ev : evs) key(ev[0], ev[1] == 1, shift, ctrl);
        if (!typed.isEmpty()) { if (fieldFocus()) typeField(typed); else typedSearch(typed); }

        float e = 1 - (float) Math.pow(1 - open, 3);
        fIdx = 0;
        if (hudEdit && want) {
            Gfx.alpha = 1;
            HudEditor.draw(fbW, fbH, wheel, e);
        } else {
            Gfx.alpha = 1;
            Modules.drawHud(fbW, fbH);
            Gfx.alpha = e;
            Gfx.s = sc;
            Gfx.minPx = MIN_PX;
            Gfx.round = true; // rounded panel, rows and controls (HUD modules stay square)
            try { panel(wheel, e); } finally { Gfx.round = false; }
            if (click && fieldFocus()) commitEdit(); // clicked somewhere else
            if (click) Hits.press(0); // a click the panel did not use may land on a HUD widget
        }
        fCount = fIdx;
        if (fCur >= fCount) fCur = fCount - 1;
        Gfx.minPx = 0;
        Gfx.alpha = 1;
        Gfx.s = sc;
    }

    // ---------------------------------------------------------------- main panel (v6: sidebar + list)

    private static final int SIDE = 210, TOP = 56, FOOT = 28, ROW = 44;

    /** Module in this sidebar category (0 favourites, 1 all, 2 HUD, 3 visual, 4 PvP, 5 utility). */
    private static boolean inTab(Module m, int t) {
        switch (t) {
            case 0: return m.fav();
            case 1: return true;
            case 2: return "HUD".equals(m.cat);
            case 3: return "Visual".equals(m.cat);
            case 4: return PVP.contains(m.id);
            default: return "Utility".equals(m.cat) || "Movement".equals(m.cat);
        }
    }

    private static final java.util.Set<String> PVP = new java.util.HashSet<>(java.util.Arrays.asList("cps", "keys", "reach", "combo", "crosshair", "armor", "potions", "items"));

    private static int onCount(int t) {
        int n = 0;
        for (Module m : Modules.all) if (!m.hidden() && inTab(m, t) && m.enabled()) n++;
        return n;
    }

    private static int count(int t) {
        int n = 0;
        for (Module m : Modules.all) if (!m.hidden() && inTab(m, t)) n++;
        return n;
    }

    private static void panel(float wheel, float e) {
        Gfx.rect(0, 0, W, H, 0, Gfx.a(0xFF000000, Th.light() ? 0.18f : 0.34f));
        float pw = Math.max(760, Math.min(1180, W * 0.86f)), ph = Math.max(500, Math.min(640, H * 0.86f));
        float px = (W - pw) / 2, py = (H - ph) / 2 + (1 - e) * 14;
        Gfx.rect(px, py, pw, ph, 8, Gfx.a(Th.s1, 0.80f));
        float mx = px + SIDE, mw = pw - SIDE;
        sidebar(px, py, ph);
        Gfx.ring(px, py, pw, ph, 8, 1, Th.ln2);
        // legacy page space for Controls / Profiles / Wardrobe: they draw at (px + 28, py + 124) inside PW x PH
        PW = mw + 16;
        PH = ph + 62;
        float cpx = px + SIDE - 8, cpy = py - 68;
        boolean modules = tab < T_WARDROBE;
        String title = TABS[tab];
        String stat = modules ? onCount(tab) + " of " + count(tab) + " on" : "";
        topBar(mx, py, mw, title, stat, modules);
        if (tab == T_WARDROBE) Wardrobe.draw(cpx, cpy, wheel);
        else if (tab == T_SETTINGS) settingsPage(cpx, cpy, wheel, mx, py, mw, ph);
        else list(mx, py + TOP, mw, ph - TOP - FOOT, wheel);
        // footer: the keys, and the same count
        Gfx.rect(mx, py + ph - FOOT, mw, 1, 0, Th.ln);
        String foot = Profiles.status();
        boolean bad = Profiles.statusBad();
        if (foot == null) foot = tab < T_WARDROBE ? "Up/Down move  ·  Enter toggle  ·  Right settings  ·  Esc close" : "Esc close";
        Gfx.text(Gfx.UI, 11, mx + 20, py + ph - FOOT / 2f, bad ? Th.ac2 : Th.mut, foot);
        if (modules) {
            String r = onCount(tab) + " of " + count(tab) + " on";
            Gfx.text(Gfx.UI, 11, mx + mw - 20 - Gfx.width(Gfx.UI, 11, r), py + ph - FOOT / 2f, Th.mut, r);
        }
        if (tip != null) tooltip(px);
    }

    private static void sidebar(float px, float py, float ph) {
        Gfx.clip(px, py, SIDE, ph);
        Gfx.rect(px, py, SIDE + 12, ph, 8, Gfx.a(Th.ov, Th.light() ? 0.0f : 0.03f));
        Gfx.unclip();
        Gfx.rect(px + SIDE - 1, py, 1, ph, 0, Th.ln);
        Gfx.text(Gfx.HEAD, 14, px + 18, py + 30, Th.ac2, "Nether Client");
        float y = py + 56;
        y = group("MODULES", px, y);
        for (int i = 0; i < T_WARDROBE; i++) { navItem(i, px, y, count(i)); y += 32; }
        y += 6;
        y = group("CLIENT", px, y);
        for (int i = T_WARDROBE; i <= T_SETTINGS; i++) { navItem(i, px, y, -1); y += 32; }
        // profile chip
        float cy = py + ph - 34;
        Gfx.rect(px, cy - 22, SIDE - 1, 1, 0, Th.ln);
        String nm = MainMenu.accName == null || MainMenu.accName.isEmpty() ? "Player" : MainMenu.accName;
        Gfx.rect(px + 16, cy - 10, 24, 24, 4, Th.s3);
        Gfx.textC(Gfx.HEAD, 12, px + 28, cy + 2, Th.tx, nm.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        Gfx.text(Gfx.UI, 12, px + 50, cy - 3, Th.tx, fit(nm, Gfx.UI, 12, SIDE - 66, 0));
        Gfx.text(Gfx.UI, 10, px + 50, cy + 10, Th.mut, "Default profile");
    }

    private static float group(String label, float px, float y) {
        float w = Gfx.text(Gfx.UI, 10, px + 18, y + 8, Th.mut, label, false, 1.2f);
        Gfx.rect(px + 18 + w + 8, y + 8, SIDE - 36 - w - 8, 1, 0, Th.ln);
        return y + 20;
    }

    private static void navItem(int i, float px, float y, int cnt) {
        float x = px + 8, w = SIDE - 16, h = 30;
        boolean sel = tab == i, hv = in(x, y, w, h);
        float t = an("nv" + i, hv), s = an("nvs" + i, sel);
        if (s > 0.02f) {
            Gfx.rect(x, y, w, h, 4, Gfx.a(Th.ac, 0.16f * s));
            Gfx.rect(px, y + 5, 3, h - 10, 1.5f, Gfx.a(Th.ac, s));
        } else if (t > 0.02f) Gfx.rect(x, y, w, h, 4, Gfx.a(Th.ov, 0.07f * t));
        ModIcons.draw(TAB_ICON[i], x + 10, y + h / 2 - 7.5f, 15, Gfx.mix(Th.dim, Th.tx, Math.max(s, t)));
        Gfx.text(Gfx.UI, 13, x + 34, y + h / 2, Gfx.mix(Th.dim, Th.tx, Math.max(s, t)), TABS[i]);
        if (cnt >= 0) { String c = String.valueOf(cnt); Gfx.text(Gfx.UI, 11, x + w - 10 - Gfx.width(Gfx.UI, 11, c), y + h / 2, sel ? Th.tx : Th.mut, c); }
        if (hit(x, y, w, h)) {
            if (i == T_HUDED) { commitEdit(); ColorPicker.close(); hudEdit = true; page = null; }
            else if (i != tab) { commitEdit(); ColorPicker.close(); page = null; setTab(i); searchFocus = false; }
        }
    }

    private static void topBar(float mx, float py, float mw, String title, String stat, boolean searchable) {
        float cy = py + TOP / 2f;
        float tw = Gfx.text(Gfx.HEAD, 16, mx + 20, cy, Th.tx, title);
        if (!stat.isEmpty()) Gfx.text(Gfx.UI, 12, mx + 20 + tw + 12, cy, Th.mut, stat);
        float cx = mx + mw - 16 - 28;
        boolean xh = in(cx, cy - 14, 28, 28);
        float xt = an("close", xh);
        Gfx.rect(cx, cy - 14, 28, 28, 4, Gfx.a(Th.ov, 0.07f * xt));
        drawX(cx + 14, cy, 4.2f, Gfx.mix(Th.mut, Th.tx, xt));
        if (hit(cx, cy - 14, 28, 28)) Input.setMenu(false);
        float sx = cx - 12 - 250;
        if (searchable) {
            boolean sh = in(sx, cy - 15, 250, 30);
            float qx = sx + 250 - 40;
            if (!search.isEmpty() && hit(qx, cy - 12, 22, 24)) { search = ""; searchFocus = true; scroll = scrollT = 0; }
            if (click && !sh) searchFocus = false;
            if (hit(sx, cy - 15, 250, 30)) searchFocus = true;
            float st = an("search", searchFocus || sh);
            Gfx.rect(sx, cy - 15, 250, 30, 4, Gfx.a(Th.s1, 0.5f));
            Gfx.ring(sx, cy - 15, 250, 30, 4, 1, Gfx.mix(Th.ln2, Th.ac, searchFocus ? st : 0));
            ModIcons.draw("zoom", sx + 9, cy - 7, 14, Th.mut);
            if (search.isEmpty() && !searchFocus) {
                Gfx.text(Gfx.UI, 12, sx + 30, cy, Th.mut, "Search modules");
                Gfx.text(Gfx.UI, 10, sx + 250 - 12 - Gfx.width(Gfx.UI, 10, "Ctrl F"), cy, Th.mut, "Ctrl F");
            } else {
                String shown = search;
                while (shown.length() > 1 && Gfx.width(Gfx.UI, 12, shown) > 250 - 30 - 40) shown = shown.substring(1);
                float w = Gfx.text(Gfx.UI, 12, sx + 30, cy, Th.tx, shown);
                if (searchFocus && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(sx + 31 + w, cy - 7, 1.5f, 14, 0, Th.ac2);
            }
            if (!search.isEmpty()) drawX(qx + 11, cy, 3.4f, Gfx.mix(Th.mut, Th.tx, an("sclear", in(qx, cy - 12, 22, 24))));
        }
        Gfx.rect(mx, py + TOP, mw, 1, 0, Th.ln);
    }

    private static void tooltip(float px) {
        List<String> tl = wrap(tip, Gfx.UI, 11, 300);
        float tipW = 0;
        for (String t : tl) tipW = Math.max(tipW, Gfx.width(Gfx.UI, 11, t));
        float bh = 12 + tl.size() * 16;
        float bx = Math.max(4, Math.min(L + 14, W - tipW - 30)), by = T + 18;
        if (by + bh > H - 4) by = T - 10 - bh;
        Gfx.rect(bx, by, tipW + 24, bh, 6, Gfx.a(Th.s2, 0.97f));
        Gfx.ring(bx, by, tipW + 24, bh, 6, 1, Th.ln2);
        for (int i = 0; i < tl.size(); i++) Gfx.text(Gfx.UI, 11, bx + 12, by + 14 + i * 16, Th.dim, tl.get(i));
    }

    private static List<Module> filtered() {
        List<Module> l = new ArrayList<>();
        String q = search.toLowerCase(Locale.ROOT);
        for (Module m : Modules.all) {
            if (m.hidden() || !inTab(m, tab)) continue;
            if (!q.isEmpty() && !(m.name.toLowerCase(Locale.ROOT).contains(q) || m.desc.toLowerCase(Locale.ROOT).contains(q) || m.cat.toLowerCase(Locale.ROOT).contains(q))) continue;
            l.add(m);
        }
        l.sort((a, b) -> Boolean.compare(b.fav(), a.fav())); // favourites first (stable: module order otherwise)
        return l;
    }

    /** Toggle pill 30 x 17. */
    static void pill(String id, float x, float y, boolean on) {
        float t = an(id, on);
        Gfx.rect(x, y, 30, 17, 8.5f, Gfx.mix(Th.s3, Th.ac, t));
        Gfx.rect(x + 3 + 13 * t, y + 3, 11, 11, 5.5f, Gfx.mix(Th.dim, 0xFFFFFFFF, t));
    }

    private static void list(float lx, float top, float lw, float viewH, float wheel) {
        navCols = 1;
        List<Module> list = filtered();
        float x = lx + 16, w = lw - 32;
        // sections: one per category in the All tab, else a single one
        List<String> secs = new ArrayList<>();
        for (Module m : list) { String s = tab == 1 ? m.cat : TABS[tab]; if (!secs.contains(s)) secs.add(s); }
        if (list.isEmpty()) {
            boolean q = !search.isEmpty();
            String head = q ? "No modules match" : tab == 0 ? "No favourites yet" : "No modules in this category";
            String sub = q ? "Esc clears the search" : tab == 0 ? "Star a module to pin it here and on top of every list" : "Nothing here for this instance";
            Gfx.textC(Gfx.HEAD, 14, lx + lw / 2, top + 80, Th.dim, head, 0);
            Gfx.textC(Gfx.UI, 11, lx + lw / 2, top + 104, Th.mut, sub, 0);
            return;
        }
        float contentH = 8;
        for (String s : secs) {
            contentH += 30;
            for (Module m : list) if ((tab == 1 ? m.cat : TABS[tab]).equals(s)) contentH += ROW + 2 + (m.id.equals(page) ? expH(m) : 0);
        }
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 46));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        beginClip(lx, top, lw, viewH);
        float y = top + 8 - scroll;
        for (String s : secs) {
            int n = 0;
            for (Module m : list) if ((tab == 1 ? m.cat : TABS[tab]).equals(s)) n++;
            float tw = Gfx.text(Gfx.HEAD, 12, x + 4, y + 12, Th.dim, s);
            String c = String.valueOf(n);
            float cw = Gfx.width(Gfx.UI, 10, c) + 12;
            Gfx.rect(x + 4 + tw + 10, y + 4, cw, 16, 8, Th.s3);
            Gfx.textC(Gfx.UI, 10, x + 4 + tw + 10 + cw / 2, y + 12, Th.dim, c, 0);
            y += 30;
            for (Module m : list) {
                if (!(tab == 1 ? m.cat : TABS[tab]).equals(s)) continue;
                boolean open = m.id.equals(page);
                float eh = open ? expH(m) : 0;
                boolean f = focus(x, y, w, ROW);
                if (f || (y < top + viewH && y + ROW + eh > top - 4)) row(m, x, y, w, f, open);
                if (open && y + ROW + eh > top && y < top + viewH) expand(m, x, y + ROW, w);
                y += ROW + 2 + eh;
            }
        }
        endClip();
        if (maxS > 0) {
            float th = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(lx + lw - 8, top + (viewH - th) * (scroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
    }

    /** One list row: icon box, name + description, star, key chip, toggle. Click the name = settings, the pill = on / off. */
    private static void row(Module m, float x, float y, float w, boolean f, boolean open) {
        boolean on = m.enabled(), lock = m.locked();
        float hv0 = in(x, y, w, ROW) ? 1 : 0;
        float t = an("r" + m.id, hv0 > 0), o = an("ro" + m.id, open), on1 = an("on" + m.id, on);
        Gfx.rect(x, y, w, ROW, 4, Gfx.a(Th.ov, 0.04f * t + 0.05f * o));
        if (o > 0.02f) Gfx.rect(x, y + 5, 2, ROW - 10, 1, Gfx.a(Th.ac, o));
        float a0 = Gfx.alpha;
        if (lock) Gfx.alpha = a0 * 0.55f;
        Gfx.rect(x + 12, y + (ROW - 28) / 2, 28, 28, 4, Gfx.a(Th.ov, 0.06f));
        float a1 = Gfx.alpha;
        Gfx.alpha = a1 * (0.55f + 0.45f * on1); // item icons keep their colours: off = faded
        boolean item = ModIcons.drawItem(m.id, x + 18, y + ROW / 2f - 8, 16);
        Gfx.alpha = a1;
        if (!item && !ModIcons.draw(m.id, x + 12 + 6.5f, y + ROW / 2f - 7.5f, 15, Gfx.mix(Th.dim, Th.ac2, on1)))
            Gfx.textC(Gfx.HEAD, 12, x + 26, y + ROW / 2f, Th.dim, m.name.substring(0, 1), 0);
        // right side first: pill, key chip, star
        float rx = x + w - 14 - 30;
        boolean pillHit = hit(rx - 8, y + 4, 52, ROW - 8);
        pill("p" + m.id, rx, y + (ROW - 17) / 2, on);
        float lim = rx - 14;
        Setting ks = null;
        for (Setting s : m.settings) if (s.t == Setting.KEY) { ks = s; break; }
        if (ks != null) {
            int code = (int) Config.num(m.id, ks.key, ((Number) ks.def).doubleValue());
            if (code >= 0) {
                String kn = keyName(code);
                float kw = Gfx.width(Gfx.UI, 10, kn) + 14;
                Gfx.rect(lim - kw, y + ROW / 2f - 10, kw, 20, 4, Th.s3);
                Gfx.textC(Gfx.UI, 10, lim - kw / 2, y + ROW / 2f, Th.dim, kn, 0);
                lim -= kw + 10;
            }
        }
        boolean fav = m.fav(), sh = in(lim - 22, y + ROW / 2f - 11, 22, 22);
        if (fav || hv0 > 0) {
            drawStar(lim - 11, y + ROW / 2f, fav, fav ? 0xFFFFB020 : Gfx.mix(Th.mut, Th.tx, an("star" + m.id, sh)));
            if (hit(lim - 22, y + ROW / 2f - 11, 22, 22)) { Config.set(m.id, "fav", !fav); pillHit = false; }
            lim -= 30;
        }
        // name + description on one line
        float nx = x + 52, room = lim - nx - 8;
        String nm = fit(m.name, Gfx.UI, 13, room, 0);
        float nw = Gfx.text(Gfx.UI, 13, nx, y + ROW / 2f, lock ? Th.dim : Th.tx, nm);
        float dr = room - nw - 12;
        if (dr > 60) Gfx.text(Gfx.UI, 11, nx + nw + 12, y + ROW / 2f, Th.mut, fit(m.desc, Gfx.UI, 11, dr, 0));
        Gfx.alpha = a0;
        if (lock && in(x, y, w, ROW) && m.lockNote() != null) tip = m.lockNote();
        int u = used(f), a = adjust(f);
        if (!lock && (pillHit || u != 0)) Config.set(m.id, "enabled", !on);
        else if (hit(x, y, w, ROW) || a > 0) { commitEdit(); ColorPicker.close(); page = open && a <= 0 ? null : m.id; }
        else if (a < 0 && open) { commitEdit(); ColorPicker.close(); page = null; }
    }

    // ---------------------------------------------------------------- settings of one module, inline under its row

    private static final float SROW = 46;

    private static float expH(Module m) {
        float h = 10;
        if (m.lockNote() != null) h += 42;
        for (Setting s : m.settings) h += SROW + (s.t == Setting.COLOR && ColorPicker.isOpen(m.id, s.key) ? ColorPicker.H : 0);
        h += SROW; // the Enabled-free footer: reset buttons
        if (m instanceof Waypoints) h += wpHeight((Waypoints) m);
        return h;
    }

    private static void expand(Module m, float x, float y, float w) {
        if (ColorPicker.id != null && !ColorPicker.id.equals(m.id)) ColorPicker.close();
        float h = expH(m);
        Gfx.rect(x + 6, y, w - 12, h - 4, 4, Gfx.a(Th.ov, 0.025f));
        float lx = x + 52, lw = w - 52 - 20;
        float yy = y + 6;
        String note = m.lockNote();
        if (note != null) {
            Gfx.rect(lx, yy, lw, 34, 4, Gfx.a(Th.ac, 0.12f));
            Gfx.rect(lx, yy, 3, 34, 1, Th.ac2);
            Gfx.text(Gfx.UI, 11, lx + 14, yy + 17, Th.tx, note);
            yy += 42;
        }
        Waypoints wp = m instanceof Waypoints ? (Waypoints) m : null;
        if (wp == null && editWp >= 0) commitEdit();
        yy = settingRows(m, lx, yy, lw);
        if (wp != null) { wpList(wp, lx, yy, lw); yy += wpHeight(wp); }
        String rl = "Reset to default";
        float rw = Gfx.width(Gfx.SEMI, 11, rl, 1.2f) + 32;
        if (fbutton("reset" + m.id, rl, lx, yy + 6, rw, 28, false)) { ColorPicker.close(); m.reset(); }
    }

    /** The setting rows of module m from y; returns the y after them. */
    private static float settingRows(Module m, float lx, float y, float lw) {
        float rowH = SROW;
        for (Setting s : m.settings) {
            float xr = lx + lw, rh = rowH;
            if (s.t == Setting.HEADER) {
                Gfx.text(Gfx.UI, 10, lx, y + rowH - 14, Th.mut, s.label.toUpperCase(Locale.ROOT), false, 1.2f);
                Gfx.rect(lx, y + rowH - 1, lw, 1, 0, Th.ln2);
                y += rowH;
                continue;
            }
            boolean f = focus(lx - 4, y + 3, lw + 8, rowH - 6);
            Gfx.text(Gfx.UI, 12, lx, y + rowH / 2 - (s.hint != null ? 7 : 0), Th.tx, s.label);
            String id = m.id + "." + s.key, ek = m.id + ":" + s.key;
            float cw = 0;
            switch (s.t) {
                case Setting.BOOL: {
                    boolean v = Config.bool(m.id, s.key, (Boolean) s.def);
                    pill("s" + id, xr - 30, y + (rowH - 17) / 2, v);
                    int a = adjust(f);
                    if (hit(xr - 40, y + 6, 44, 34) || used(f) != 0) Config.set(m.id, s.key, !v);
                    else if (a != 0 && (a > 0) != v) Config.set(m.id, s.key, a > 0);
                    cw = 44;
                    break;
                }
                case Setting.SLIDER: {
                    double v = Config.num(m.id, s.key, ((Number) s.def).doubleValue());
                    float tl = 170, vw = 64, vx = xr - tl - 16 - vw;
                    double nv = slider("s" + id, xr - tl, y + rowH / 2, tl, v, s.min, s.max, s.step);
                    int a = adjust(f);
                    if (a != 0) nv = snap(v + a * s.step, s);
                    if (nv != v) Config.set(m.id, s.key, nv);
                    if (ek.equals(editSet)) field(ek, NUM, fmt(nv, s.step), "", vx, y + 9, vw, 28, 10, false);
                    else {
                        String vs = fmt(nv, s.step);
                        boolean vh = in(vx, y + 9, vw, 28);
                        float vt = an("sv" + id, vh);
                        if (vt > 0.02f) Gfx.ring(vx, y + 9, vw, 28, 4, 1, Gfx.a(Th.ln2, vt));
                        Gfx.text(Gfx.UI, 12, vx + vw - 10 - Gfx.width(Gfx.UI, 12, vs), y + rowH / 2, Gfx.mix(Th.dim, Th.tx, vt), vs);
                        if (vh) tip = "Click to type a value";
                        if (hit(vx, y + 9, vw, 28) || used(f) != 0) startEdit(ek, NUM, vs, 10);
                    }
                    cw = tl + 16 + vw;
                    break;
                }
                case Setting.COLOR: {
                    boolean op = ColorPicker.isOpen(m.id, s.key), ch = ColorPicker.chromaOn(m, s.key);
                    int col = ch ? Module.chroma() : Config.color(m.id, s.key, (int) Config.argb((String) s.def));
                    float bw = 136, x = xr - bw, by = y + 9;
                    boolean hv = in(x, by, bw, 28);
                    float t = an("cl" + id, hv || op);
                    Gfx.rect(x, by, bw, 28, 4, Gfx.a(Th.ov, 0.035f + 0.03f * t));
                    Gfx.ring(x, by, bw, 28, 4, 1, op ? Th.ac : Gfx.mix(Th.ln, Th.ln2, t));
                    ColorPicker.swatch(x + 7, by + 6, 16, 16, col);
                    Gfx.text(Gfx.UI, 11, x + 31, by + 14, Th.tx, ch ? "RAINBOW" : Config.hex(col));
                    chevron(x + bw - 15, by + 14, op, Gfx.mix(Th.dim, Th.tx, t));
                    if (hit(x, by, bw, 28) || used(f) != 0) ColorPicker.toggle(m, s.key);
                    cw = bw;
                    if (ColorPicker.isOpen(m.id, s.key)) { ColorPicker.draw(m, lx, y + rowH, lw); rh += ColorPicker.H; }
                    break;
                }
                case Setting.CHOICE: {
                    String cur = Config.str(m.id, s.key, (String) s.def);
                    float total = 0;
                    int ci = 0;
                    for (int o = 0; o < s.opts.length; o++) { total += Gfx.width(Gfx.UI, 11, s.opts[o]) + 22; if (s.opts[o].equals(cur)) ci = o; }
                    int a = adjust(f), u = used(f);
                    if (a != 0) Config.set(m.id, s.key, s.opts[Math.max(0, Math.min(s.opts.length - 1, ci + Integer.signum(a)))]);
                    else if (u != 0) Config.set(m.id, s.key, s.opts[(ci + 1) % s.opts.length]);
                    float cy = y + rowH / 2;
                    if (total > 330) { // too wide for chips: a stepper, click either side
                        float bw = 190, x = xr - bw;
                        Gfx.rect(x, cy - 14, bw, 28, 4, Gfx.a(Th.ov, 0.05f));
                        Gfx.textC(Gfx.UI, 11, x + bw / 2, cy, Th.tx, fit(cur, Gfx.UI, 11, bw - 56, 0), 0);
                        boolean lh = in(x, cy - 14, 28, 28), rh2 = in(x + bw - 28, cy - 14, 28, 28);
                        Gfx.textC(Gfx.UI, 14, x + 14, cy, Gfx.mix(Th.mut, Th.tx, an("chl" + id, lh)), "<", 0);
                        Gfx.textC(Gfx.UI, 14, x + bw - 14, cy, Gfx.mix(Th.mut, Th.tx, an("chr" + id, rh2)), ">", 0);
                        if (hit(x, cy - 14, 28, 28)) Config.set(m.id, s.key, s.opts[(ci + s.opts.length - 1) % s.opts.length]);
                        if (hit(x + bw - 28, cy - 14, 28, 28)) Config.set(m.id, s.key, s.opts[(ci + 1) % s.opts.length]);
                        cw = bw + 10;
                        break;
                    }
                    cw = total + 10;
                    float x = xr - total;
                    Gfx.rect(x - 3, cy - 15, total + 6, 30, 5, Gfx.a(Th.ov, 0.05f));
                    for (String o : s.opts) {
                        float ow = Gfx.width(Gfx.UI, 11, o) + 22;
                        boolean sel = o.equals(cur);
                        float t = an("ch" + id + o, sel);
                        if (t > 0.02f) Gfx.rect(x, cy - 12, ow, 24, 4, Gfx.a(Th.ac, t));
                        Gfx.textC(Gfx.UI, 11, x + ow / 2, cy, Gfx.mix(Th.dim, 0xFFFFFFFF, Math.max(t, an("hv" + id + o, in(x, cy - 12, ow, 24)) * 0.6f)), o, 0);
                        if (hit(x, cy - 12, ow, 24)) Config.set(m.id, s.key, o);
                        x += ow;
                    }
                    break;
                }
                case Setting.KEY: {
                    int code = (int) Config.num(m.id, s.key, ((Number) s.def).doubleValue());
                    float bw = Math.max(110, Gfx.width(Gfx.UI, 11, keyName(code)) + 28);
                    boolean[] hard = new boolean[1];
                    String clash = Controls.conflict(m, s, hard);
                    keyButton(ek, code, xr - bw, y + 9, bw, clash != null && hard[0], f);
                    cw = bw;
                    if (clash != null) {
                        int cc = hard[0] ? Th.ac2 : 0xFFFFB020;
                        Gfx.rect(xr - bw - 14, y + rowH / 2 - 2, 4, 4, 2, cc);
                        if (in(xr - bw - 22, y + 6, 22, 34)) tip = clash.charAt(0) + clash.substring(1).toLowerCase(Locale.ROOT);
                        cw += 18;
                    }
                    break;
                }
                case Setting.TEXT: {
                    field(ek, TXT, Config.str(m.id, s.key, (String) s.def), "Empty", xr - 220, y + 9, 220, 28, Math.max(1, (int) s.max), f);
                    cw = 220;
                    break;
                }
                case Setting.ACTION: {
                    String bl = s.opts != null && s.opts.length > 0 ? s.opts[0] : "Run";
                    float bw = Math.max(96, Gfx.width(Gfx.UI, 11, bl) + 32);
                    if ((button("a" + id, bl, xr - bw, y + 8, bw, 30, false) | used(f) != 0) && s.run != null) s.run.run();
                    cw = bw;
                    break;
                }
                default: break;
            }
            Gfx.rect(lx, y + rh - 1, lw, 1, 0, Th.ln);
            if (s.hint != null) {
                float hw = lw - cw - 20;
                String h = fit(s.hint, Gfx.UI, 10, hw, 0);
                Gfx.text(Gfx.UI, 10, lx, y + rowH / 2 + 9, Th.mut, h);
                if (!h.equals(s.hint) && in(lx, y, hw, rowH)) tip = s.hint;
            }
            y += rh;
        }
        return y;
    }

    // ---------------------------------------------------------------- settings tab (look, main menu, keys, profiles)

    private static final String[] ACC_NAMES = {"Red", "Ember", "Acid", "Cobalt", "Violet"};
    private static final String[][] ACC = {{"#E5372D", "#FF6157"}, {"#E8590C", "#FF7A2E"}, {"#22A845", "#3DDC6A"}, {"#1F6FEB", "#4C8DFF"}, {"#8B3DFF", "#A66BFF"}};

    private static void settingsPage(float cpx, float cpy, float wheel, float mx, float py, float mw, float ph) {
        navCols = 1;
        String[] names = {"General", "Controls", "Profiles"};
        float x = mx + 20, y = py + TOP + 10;
        for (int i = 0; i < 3; i++) {
            float w = Gfx.width(Gfx.UI, 12, names[i]) + 28;
            boolean sel = sub == i, hv = in(x, y, w, 28);
            float t = an("ss" + i, sel);
            Gfx.rect(x, y, w, 28, 4, Gfx.a(Th.ac, 0.16f * t));
            if (!sel && hv) Gfx.rect(x, y, w, 28, 4, Gfx.a(Th.ov, 0.07f));
            Gfx.textC(Gfx.UI, 12, x + w / 2, y + 14, Gfx.mix(Th.dim, Th.tx, t), names[i], 0);
            if (hit(x, y, w, 28) && sub != i) { sub = i; scroll = scrollT = 0; fCur = -1; commitEdit(); }
            x += w + 6;
        }
        if (sub == 1) { float sp = PH; PH -= 40; Controls.draw(cpx, cpy + 40, wheel); PH = sp; return; }
        if (sub == 2) { float sp = PH; PH -= 40; Profiles.draw(cpx, cpy + 40, wheel); PH = sp; return; }
        float lx = mx + 20, lw = mw - 40, top = py + TOP + 48, viewH = py + ph - FOOT - top;
        float contentH = 8 + 62 + 62 + 62 + 30 + (page != null && page.equals("menu") ? expH(MainMenu.MODULE) : 0) + ROW + 20;
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 46));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        beginClip(lx - 8, top, lw + 16, viewH);
        float yy = top + 8 - scroll;
        // theme
        Gfx.text(Gfx.UI, 13, lx, yy + 15, Th.tx, "Theme");
        Gfx.text(Gfx.UI, 10, lx, yy + 31, Th.mut, "The look of this menu and the launcher.");
        float cx = lx + lw - 4 * 96;
        for (int i = 0; i < 4; i++) {
            boolean sel = Th.theme == i, hv = in(cx, yy + 8, 90, 28);
            float t = an("th" + i, sel);
            Gfx.rect(cx, yy + 8, 90, 28, 4, Gfx.a(Th.ac, 0.16f * t));
            Gfx.ring(cx, yy + 8, 90, 28, 4, 1, Gfx.mix(Th.ln2, Th.ac, t));
            Gfx.textC(Gfx.UI, 11, cx + 45, yy + 22, Gfx.mix(Th.dim, Th.tx, Math.max(t, hv ? 1 : 0)), Th.NAMES[i], 0);
            if (hit(cx, yy + 8, 90, 28)) Config.set("client", "theme", Th.NAMES[i]);
            cx += 96;
        }
        Gfx.rect(lx, yy + 61, lw, 1, 0, Th.ln);
        yy += 62;
        // accent
        Gfx.text(Gfx.UI, 13, lx, yy + 15, Th.tx, "Accent colour");
        Gfx.text(Gfx.UI, 10, lx, yy + 31, Th.mut, "Selection, switches and play marks.");
        cx = lx + lw - 5 * 40;
        for (int i = 0; i < 5; i++) {
            boolean sel = ACC[i][0].equalsIgnoreCase(Config.str("client", "accent", "#E5372D"));
            int c = 0xFF000000 | Integer.parseInt(ACC[i][0].substring(1), 16);
            Gfx.rect(cx, yy + 10, 28, 24, 4, c);
            if (sel) Gfx.ring(cx - 3, yy + 7, 34, 30, 6, 2, Th.tx);
            else if (in(cx, yy + 10, 28, 24)) Gfx.ring(cx - 3, yy + 7, 34, 30, 6, 1, Th.ln2);
            if (hit(cx, yy + 10, 28, 24)) { Config.set("client", "accent", ACC[i][0]); Config.set("client", "accent2", ACC[i][1]); }
            cx += 40;
        }
        Gfx.rect(lx, yy + 61, lw, 1, 0, Th.ln);
        yy += 62;
        // reduce motion
        boolean calm = Config.bool("client", "calm", false);
        Gfx.text(Gfx.UI, 13, lx, yy + 15, Th.tx, "Reduce motion");
        Gfx.text(Gfx.UI, 10, lx, yy + 31, Th.mut, Config.launcherCalm() ? "Set by the launcher." : "Menu animations snap; nothing blinks.");
        pill("calm", lx + lw - 30, yy + 14, calm || Config.launcherCalm());
        if (!Config.launcherCalm() && (hit(lx + lw - 40, yy + 4, 40, 34))) Config.set("client", "calm", !calm);
        Gfx.rect(lx, yy + 61, lw, 1, 0, Th.ln);
        yy += 62;
        Gfx.text(Gfx.UI, 10, lx, yy + 14, Th.mut, "MAIN MENU", false, 1.2f);
        yy += 30;
        Module mm = MainMenu.MODULE;
        boolean open = "menu".equals(page);
        boolean f = focus(lx, yy, lw, ROW);
        boolean hv = in(lx, yy, lw, ROW);
        Gfx.rect(lx, yy, lw, ROW, 4, Gfx.a(Th.ov, hv ? 0.04f : open ? 0.05f : 0f));
        Gfx.rect(lx + 12, yy + 8, 28, 28, 4, Gfx.a(Th.ov, 0.06f));
        ModIcons.draw("menu", lx + 18.5f, yy + ROW / 2f - 7.5f, 15, Th.dim);
        float nw = Gfx.text(Gfx.UI, 13, lx + 52, yy + ROW / 2f, Th.tx, "Title screen");
        Gfx.text(Gfx.UI, 11, lx + 64 + nw, yy + ROW / 2f, Th.mut, fit("Backgrounds, layout, quick join, account", Gfx.UI, 11, lw - 64 - nw - 40, 0));
        chevron(lx + lw - 20, yy + ROW / 2f, open, Th.dim);
        if (hit(lx, yy, lw, ROW) || used(f) != 0) { commitEdit(); ColorPicker.close(); page = open ? null : "menu"; }
        yy += ROW + 2;
        if (open) expand(mm, lx, yy, lw);
        endClip();
        if (maxS > 0) {
            float th = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(mx + mw - 8, top + (viewH - th) * (scroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
    }

    // ---------------------------------------------------------------- waypoint list (settings page of Waypoints)

    private static float wpHeight(Waypoints w) {
        List<Map<String, Object>> l = w.list(false);
        return 66 + Math.max(1, l == null ? 0 : l.size()) * 46;
    }

    private static void wpList(Waypoints w, float lx, float y, float lw) {
        float xr = lx + lw;
        String k = w.world();
        Gfx.text(Gfx.MONO, 9, lx, y + 33, Th.mut, "THIS WORLD", false, 2f);
        String wn = k == null ? "NOT IN A WORLD" : k.substring(3).replace("|minecraft:", "  /  ").replace('|', '/').replace('_', ' ').toUpperCase(Locale.ROOT);
        float ww = xr - 176 - 16 - (lx + 96);
        while (wn.length() > 4 && Gfx.width(Gfx.SEMI, 11, wn, 0.8f) > ww) wn = wn.substring(0, wn.length() - 3) + "..";
        Gfx.text(Gfx.SEMI, 11, lx + 96, y + 33, Th.dim, wn, false, 0.8f);
        if (k != null && Game.player() != null && fbutton("wpadd", "ADD AT MY POSITION", xr - 176, y + 18, 176, 30, true)) w.addHere();
        List<Map<String, Object>> l = w.list(false);
        float ry = y + 66;
        Gfx.rect(lx, ry - 1, lw, 1, 0, Th.ln);
        if (l == null || l.isEmpty()) {
            String msg = k == null ? "Join a world to add waypoints for it." : "No waypoints here yet. Press " + keyName((int) w.num("addKey")) + " in game or use the button.";
            Gfx.text(Gfx.UI, 12, lx, ry + 23, Th.mut, msg);
            return;
        }
        for (int i = 0; i < l.size(); i++) {
            Map<String, Object> p = l.get(i);
            float top = ry + i * 46, cy = top + 23;
            Gfx.rect(lx, top + 45, lw, 1, 0, Th.ln);
            // colour: click cycles through the palette
            boolean sh = in(lx - 2, cy - 11, 22, 22);
            float st = an("wpc" + i, sh);
            Gfx.rect(lx + 1, cy - 8, 16, 16, 4, Waypoints.col(p));
            if (st > 0.02f) Gfx.ring(lx - 2, cy - 11, 22, 22, 6, 1.5f, Gfx.a(Th.tx, st));
            if (hit(lx - 2, cy - 11, 22, 22)) {
                String cur = Waypoints.str(p, "color", Module.PALETTE[1]);
                int ci = 0;
                for (int c = 0; c < Module.PALETTE.length; c++) if (Module.PALETTE[c].equalsIgnoreCase(cur)) ci = c;
                p.put("color", Module.PALETTE[(ci + 1) % Module.PALETTE.length]);
                w.changed();
            }
            // name: click to rename (Enter saves, Esc cancels)
            boolean ed = editWp == i;
            float nx = lx + 30, nw = 196;
            boolean f = focus(nx - 8, cy - 14, nw, 28);
            boolean nh = in(nx - 8, cy - 14, nw, 28);
            float nt = an("wpn" + i, nh || ed);
            Gfx.rect(nx - 8, cy - 14, nw, 28, 6, Gfx.a(Th.ov, 0.045f * nt));
            Gfx.ring(nx - 8, cy - 14, nw, 28, 6, 1, ed ? Th.ac : Gfx.a(Th.ln2, nt));
            String nm = ed ? editBuf : Waypoints.str(p, "name", "Waypoint");
            float tw = Gfx.text(Gfx.UI, 13, nx, cy, Th.tx, nm);
            if (ed && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(nx + tw + 1, cy - 8, 1.5f, 16, 0, Th.ac2);
            if ((hit(nx - 8, cy - 14, nw, 28) || used(f) != 0) && !ed) { commitEdit(); editWp = i; editKind = TXT; editBuf = Waypoints.str(p, "name", ""); }
            // position
            Gfx.text(Gfx.MONO, 11, lx + 238, cy, Th.dim, String.format(Locale.ROOT, "%d / %d / %d",
                (long) Waypoints.num(p, "x"), (long) Waypoints.num(p, "y"), (long) Waypoints.num(p, "z")));
            // shown on screen
            boolean on = !Boolean.FALSE.equals(p.get("on"));
            sw("wpv" + i, xr - 36 - 40, cy - 10, on);
            if (hit(xr - 36 - 46, cy - 14, 48, 28)) { p.put("on", !on); w.changed(); }
            // delete
            boolean dh = in(xr - 26, cy - 13, 26, 26);
            float dt = an("wpd" + i, dh);
            Gfx.rect(xr - 26, cy - 13, 26, 26, 6, Gfx.a(Th.ac, 0.2f * dt));
            drawX(xr - 13, cy, 4f, Gfx.mix(Th.mut, Th.ac2, dt));
            if (hit(xr - 26, cy - 13, 26, 26)) {
                if (editWp == i) editWp = -1; else if (editWp > i) editWp--;
                l.remove(i);
                w.changed();
                return;
            }
        }
    }
}
