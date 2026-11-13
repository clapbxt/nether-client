package dev.nether.ov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-game menu (immediate mode): module grid (search, tabs, favourites, safety badges), per-module settings page (inline
 * colour picker, type-in slider values), the CONTROLS ({@link Controls}) and CLIENT ({@link Profiles}) tabs, and the HUD
 * layout editor ({@link HudEditor}). Laid out in a 720-unit-tall logical space (Gfx.s = framebuffer height / 720).
 * Keyboard: arrows / Tab move the focus, Enter / Space use it, Left / Right adjust it, Esc steps back; Ctrl+F searches,
 * Ctrl+E opens the layout editor, Ctrl+V pastes into the field being typed in.
 */
final class Menu {
    private Menu() {}

    static final float PW = 880, PH = 540;
    static final String[] TABS = {"ALL", "FAVOURITES", "ENABLED", "HUD", "MOVEMENT", "VISUAL", "UTILITY", "CONTROLS", "CLIENT"};
    private static final String[] TAB_CAT = {null, "*fav", "*on", "HUD", "Movement", "Visual", "Utility", null, null};
    /** tabs from here on are pages of their own (drawn on the right of the tab row) */
    static final int T_CONTROLS = 7, T_CLIENT = 8;

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
    static boolean textFocus() { return fieldFocus() || (page == null && !hudEdit && (searchFocus || !search.isEmpty())); }
    /** A waypoint name, a text setting, a slider value, a hex colour or a profile name is being typed into. */
    static boolean fieldFocus() { return editWp >= 0 || editSet != null; }

    /** Smallest text in the menu, in pixels: small windows / GUI scale 1 never get unreadable labels. */
    static final int MIN_PX = 9;

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
        if (kbNav) Gfx.ring(x - 3, y - 3, w + 6, h + 6, 6, 1.5f, Gfx.RED2);
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
            if (k == 70) { commitEdit(); ColorPicker.close(); page = null; if (tab >= T_CONTROLS) setTab(0); searchFocus = true; return; } // Ctrl+F
            if (k == 69) { commitEdit(); ColorPicker.close(); page = null; hudEdit = true; return; }                                   // Ctrl+E
            if (k == 86) { typedSearch(Plat.cur.clipboard()); return; }                                                                // Ctrl+V
            return;
        }
        if (k == 259) {
            if (page == null && !search.isEmpty()) { search = search.substring(0, search.length() - 1); searchFocus = true; scroll = scrollT = 0; fCur = -1; }
            return;
        }
        switch (k) {
            case 258: if (page == null) setTab((tab + (shift ? TABS.length - 1 : 1)) % TABS.length); else nav(shift ? -1 : 1); kbNav = true; break;
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
                else if (page == null && tab < T_CONTROLS) { searchFocus = false; if (!search.isEmpty()) { fCur = 0; kbNav = true; kbScroll = true; } }
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
        if (page != null || hudEdit || capture != null || t.isEmpty()) return;
        String q = (search + t.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')).replaceFirst("^\\s+", "");
        q = q.substring(0, Math.min(24, q.length()));
        if (q.equals(search)) return; // a lone space: nothing to search for (Space toggles the focused card)
        search = q;
        searchFocus = true;
        scroll = scrollT = 0;
        fCur = -1;
        if (tab >= T_CONTROLS) tab = 0; // typing on CONTROLS / CLIENT searches the modules
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
        Gfx.rect(x, y, w, h, 6, Gfx.a(0xFFFFFFFF, 0.035f + 0.03f * t));
        Gfx.ring(x, y, w, h, 6, 1, ed ? Gfx.RED : Gfx.mix(Gfx.LN, Gfx.LN2, t));
        int font = kind == HEX || kind == NUM ? Gfx.MONO : Gfx.UI;
        float tx = x + 10, cy = y + h / 2;
        if (kind == HEX) tx += Gfx.text(Gfx.MONO, 12, tx, cy, Gfx.SUB, "#") + 1;
        float room = x + w - tx - 10;
        String shown = v;
        if (ed) while (shown.length() > 1 && Gfx.width(font, 12, shown) > room) shown = shown.substring(1); // typing: keep the end in view
        else shown = v.isEmpty() ? empty : fit(v, font, 12, room, 0);
        float fw = Gfx.text(font, 12, tx, cy, !ed && v.isEmpty() ? Gfx.MUT : Gfx.ICE, shown);
        if (ed && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(tx + fw + 1, cy - 7, 1.5f, 14, 0, Gfx.RED2);
        if (!ed && (hit(x, y, w, h) || used(f) != 0)) startEdit(key, kind, value, max);
    }

    // ---------------------------------------------------------------- widgets

    static boolean button(String id, String label, float x, float y, float w, float h, boolean primary) {
        boolean hv = in(x, y, w, h);
        float t = an(id, hv);
        boolean down = hv && Input.btn[0];
        if (primary) {
            Gfx.rect(x, y, w, h, 6, down ? 0xFFB80500 : Gfx.mix(Gfx.RED, Gfx.RED2, t));
            Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + h / 2, 0xFFFFFFFF, label, 1.2f);
        } else {
            Gfx.rect(x, y, w, h, 6, Gfx.a(0xFFFFFFFF, down ? 0.02f : 0.035f + 0.05f * t));
            Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Gfx.LN, Gfx.LN2, t));
            Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, t), label, 1.2f);
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
        Gfx.rect(x, y, 36, 20, 10, Gfx.mix(Gfx.S3, Gfx.RED, t));
        Gfx.rect(x + 3 + 16 * t, y + 3, 14, 14, 7, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, t));
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
        Gfx.rect(x, y - 2, Math.max(4, w * f), 4, 2, Gfx.RED);
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
        Gfx.rect(x, y, w, 28, 6, Gfx.a(0xFFFFFFFF, 0.035f + 0.04f * t));
        Gfx.ring(x, y, w, 28, 6, 1, cap || clash ? Gfx.RED : Gfx.mix(Gfx.LN, Gfx.LN2, t));
        Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + 14, code < 0 && !cap ? Gfx.SUB : Gfx.ICE, name, 1f);
        if (hit(x, y, w, 28) || used(f) != 0) capture = cid;
    }

    /** Safety tag of m as a dot + label at (x, cy); hover explains it. Returns its width. */
    static float badge(Module m, float x, float cy) {
        int s = m.safety();
        int col = s == Module.SAFE ? 0xFF3DDC84 : s == Module.COSMETIC ? Gfx.DIM : 0xFFFFB020;
        String t = Module.SAFETY[s];
        float tw = Gfx.width(Gfx.MONO, 9, t, 1.4f);
        Gfx.rect(x, cy - 2, 4, 4, 0, col);
        Gfx.text(Gfx.MONO, 9, x + 9, cy, Gfx.mix(col, Gfx.SUB, 0.3f), t, false, 1.4f);
        if (in(x - 4, cy - 9, tw + 17, 18)) tip = Module.SAFETY_TIP[s];
        return 9 + tw;
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
        sc = Math.min(Math.max(0.8f, fbH / 720f), Math.min(fbW / (PW + 24), fbH / (PH + 24)));
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
            panel(wheel, e);
            if (click && fieldFocus()) commitEdit(); // clicked somewhere else
            if (click) Hits.press(0); // a click the panel did not use may land on a HUD widget
        }
        fCount = fIdx;
        if (fCur >= fCount) fCur = fCount - 1;
        Gfx.minPx = 0;
        Gfx.alpha = 1;
        Gfx.s = sc;
    }

    // ---------------------------------------------------------------- main panel

    private static void panel(float wheel, float e) {
        Gfx.rect(0, 0, W, H, 0, Gfx.a(0xFF050507, 0.62f));
        float px = (W - PW) / 2, py = (H - PH) / 2 + (1 - e) * 14;
        for (int i = 3; i >= 1; i--) Gfx.rect(px - i * 7, py - i * 4 + 10, PW + i * 14, PH + i * 8, 16 + i * 4, Gfx.a(0xFF000000, 0.10f));
        Gfx.rect(px, py, PW, PH, 10, Gfx.BG);
        Gfx.ring(px, py, PW, PH, 10, 1, 0x24FFFFFF);
        Gfx.rect(px + 28, py, 56, 2, 0, Gfx.RED);

        // header
        float hy = py + 36;
        float lw = Gfx.text(Gfx.HEAD, 24, px + 28, hy, Gfx.ICE, "NETHER", false, 4f);
        Gfx.text(Gfx.MONO, 9, px + 28 + lw + 10, hy + 2, Gfx.SUB, "CLIENT", false, 3f);
        float cxp = px + PW - 28 - 28;
        boolean xh = in(cxp, py + 22, 28, 28);
        float xt = an("close", xh);
        Gfx.rect(cxp, py + 22, 28, 28, 6, Gfx.a(0xFFFFFFFF, 0.035f + 0.06f * xt));
        drawX(cxp + 14, py + 36, 4.2f, Gfx.mix(Gfx.DIM, Gfx.ICE, xt));
        if (hit(cxp, py + 22, 28, 28)) Input.setMenu(false);
        float ex = cxp - 12 - 158;
        if (button("edit", "EDIT HUD LAYOUT", ex, py + 21, 158, 30, true)) { commitEdit(); ColorPicker.close(); hudEdit = true; page = null; }
        if (in(ex, py + 21, 158, 30)) tip = "Move, scale and hide HUD modules (Ctrl+E)";
        float sx = ex - 12;
        if (page == null) {
            sx = ex - 12 - 170;
            boolean sh = in(sx, py + 21, 170, 30);
            float qx = sx + 170 - 26, qy = py + 24;
            if (!search.isEmpty() && hit(qx, qy, 22, 24)) { search = ""; searchFocus = true; scroll = scrollT = 0; }
            if (click && !sh) searchFocus = false;
            if (hit(sx, py + 21, 170, 30)) { searchFocus = true; if (tab >= T_CONTROLS) setTab(0); }
            float st = an("search", searchFocus || sh);
            Gfx.rect(sx, py + 21, 170, 30, 6, Gfx.a(0xFFFFFFFF, 0.04f));
            Gfx.ring(sx, py + 21, 170, 30, 6, 1, Gfx.mix(Gfx.LN, searchFocus ? Gfx.RED : Gfx.LN2, st));
            if (search.isEmpty() && !searchFocus) Gfx.text(Gfx.SEMI, 10, sx + 14, py + 36, Gfx.SUB, "TYPE TO SEARCH", false, 1.2f);
            else {
                String shown = search; // the end of a long query stays visible
                while (shown.length() > 1 && Gfx.width(Gfx.UI, 13, shown) > 170 - 14 - 34) shown = shown.substring(1);
                float tw = Gfx.text(Gfx.UI, 13, sx + 14, py + 36, Gfx.ICE, shown);
                if (searchFocus && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(sx + 15 + tw, py + 28, 1.5f, 16, 0, Gfx.RED2);
            }
            if (!search.isEmpty()) drawX(qx + 11, qy + 12, 3.4f, Gfx.mix(Gfx.MUT, Gfx.ICE, an("sclear", in(qx, qy, 22, 24)))); // clear
        }
        safePill(sx - 12 - 176, py + 21);
        Gfx.rect(px, py + 72, PW, 1, 0, Gfx.LN);

        if (page != null) settings(px, py, wheel);
        else {
            tabs(px, py);
            if (tab == T_CONTROLS) Controls.draw(px, py, wheel);
            else if (tab == T_CLIENT) Profiles.draw(px, py, wheel);
            else grid(px, py, wheel);
        }

        // what the keys do, while they are in use
        String foot = Profiles.status();
        if (foot == null && kbNav) foot = page != null ? "UP / DOWN MOVE  -  LEFT / RIGHT ADJUST  -  ENTER CHANGES  -  ESC BACK"
            : tab < T_CONTROLS ? "ARROWS MOVE  -  SPACE TURNS ON / OFF  -  ENTER OPENS SETTINGS  -  TAB NEXT TAB  -  ESC BACK"
            : tab == T_CONTROLS ? "UP / DOWN MOVE  -  ENTER REBINDS  -  DELETE CLEARS  -  ESC CANCELS"
            : "UP / DOWN MOVE  -  ENTER USES  -  TAB NEXT TAB  -  ESC CLOSES";
        else if (foot == null && page == null && tab == T_CONTROLS) foot = "CLICK A KEY TO REBIND IT  -  DELETE CLEARS IT  -  ESC CANCELS";
        if (foot != null) Gfx.textC(Gfx.MONO, 9, px + PW / 2, py + PH - 11, Profiles.statusBad() ? Gfx.RED2 : Gfx.SUB, foot, 1.4f);
        if (tip != null) tooltip(px);
    }

    private static void tooltip(float px) {
        List<String> tl = wrap(tip, Gfx.UI, 11, 300);
        float tipW = 0;
        for (String t : tl) tipW = Math.max(tipW, Gfx.width(Gfx.UI, 11, t));
        float bh = 12 + tl.size() * 16;
        float bx = Math.max(4, Math.min(L + 14, px + PW - tipW - 40)), by = T + 18;
        if (by + bh > H - 4) by = T - 10 - bh;
        Gfx.rect(bx, by, tipW + 24, bh, 6, 0xF2101016);
        Gfx.ring(bx, by, tipW + 24, bh, 6, 1, Gfx.LN2);
        for (int i = 0; i < tl.size(); i++) Gfx.text(Gfx.UI, 11, bx + 12, by + 14 + i * 16, Gfx.DIM, tl.get(i));
    }

    /** Profile-wide server-safe mode switch (header). */
    private static void safePill(float x, float y) {
        boolean on = Modules.safe(), hv = in(x, y, 176, 30);
        float t = an("safe", hv), o = an("safeon", on);
        Gfx.rect(x, y, 176, 30, 6, Gfx.a(0xFFFFFFFF, 0.035f + 0.04f * t));
        Gfx.ring(x, y, 176, 30, 6, 1, Gfx.mix(Gfx.mix(Gfx.LN, Gfx.LN2, t), Gfx.a(Gfx.RED, 0.7f), o));
        String why = on ? AutoSafe.reason() : null;
        Gfx.text(Gfx.SEMI, 10, x + 13, y + 15, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, o)), why == null ? "SERVER-SAFE" : "AUTO: " + (why.length() > 11 ? why.substring(0, 10) + ".." : why).toUpperCase(java.util.Locale.ROOT), false, 1.2f);
        float sx = x + 176 - 13 - 36, sy = y + 5;
        Gfx.rect(sx, sy, 36, 20, 10, Gfx.mix(Gfx.S3, Gfx.RED, o));
        Gfx.rect(sx + 3 + 16 * o, sy + 3, 14, 14, 7, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, o));
        if (hit(x, y, 176, 30)) { AutoSafe.manual(); Modules.setSafe(!on); }
        if (hv) tip = "Turns off every module marked CHECK RULES (sprint, sneak, zoom, fullbright, reach) for servers that forbid them. On by itself on strict servers (Auto Safe Mode).";
    }

    /** Category tabs on the left, the CONTROLS and CLIENT pages on the right. */
    private static void tabs(float px, float py) {
        float ty = py + 100, wl = 0, wr = 0;
        for (int i = 0; i < TABS.length; i++) { float tw = Gfx.width(Gfx.SEMI, 12, TABS[i], 1.4f); if (i < T_CONTROLS) wl += tw; else wr += tw; }
        float pad = Math.max(14, Math.min(28, (PW - 56 - 28 - wl - wr) / TABS.length));
        float tx = px + 28, rx = px + PW - 28 - wr - pad * (TABS.length - T_CONTROLS);
        Gfx.rect(rx - 12, ty - 9, 1, 18, 0, Gfx.LN2);
        for (int i = 0; i < TABS.length; i++) {
            float tw = Gfx.width(Gfx.SEMI, 12, TABS[i], 1.4f) + pad, x0 = i < T_CONTROLS ? tx : rx;
            boolean hv = in(x0, ty - 15, tw, 30);
            float t = an("tab" + i, hv || tab == i);
            Gfx.text(Gfx.SEMI, 12, x0 + pad / 2, ty, Gfx.mix(Gfx.DIM, Gfx.ICE, t), TABS[i], false, 1.4f);
            if (hit(x0, ty - 15, tw, 30)) { setTab(i); if (i >= T_CONTROLS) searchFocus = false; }
            if (tab == i) {
                if (tabX < 0) { tabX = x0; tabW = tw; }
                float k = Config.calm() ? 1 : Math.min(1, Gfx.dt * 16);
                tabX += (x0 - tabX) * k;
                tabW += (tw - tabW) * k;
            }
            if (i < T_CONTROLS) tx += tw; else rx += tw;
        }
        Gfx.rect(tabX + pad / 2, ty + 14, tabW - pad, 2, 1, Gfx.RED);
    }

    private static List<Module> filtered() {
        List<Module> l = new ArrayList<>();
        String q = search.toLowerCase(Locale.ROOT), c = TAB_CAT[tab];
        for (Module m : Modules.all) {
            if (!m.injected()) continue;
            if ("*on".equals(c) ? !m.enabled() : "*fav".equals(c) ? !m.fav() : c != null && !m.cat.equals(c)) continue;
            if (!q.isEmpty() && !(m.name.toLowerCase(Locale.ROOT).contains(q) || m.desc.toLowerCase(Locale.ROOT).contains(q) || m.cat.toLowerCase(Locale.ROOT).contains(q))) continue;
            l.add(m);
        }
        l.sort((a, b) -> Boolean.compare(b.fav(), a.fav())); // favourites first (stable: module order otherwise)
        return l;
    }

    private static void grid(float px, float py, float wheel) {
        navCols = 3;
        List<Module> list = filtered();
        float top = py + 132, viewH = PH - 132 - 24;
        int cols = 3;
        float gap = 14, cw = (PW - 56 - gap * (cols - 1)) / cols, ch = 94;
        int rows = (list.size() + cols - 1) / cols;
        float contentH = rows * (ch + gap) - gap;
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 46));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        beginClip(px + 8, top - 4, PW - 16, viewH + 8);
        if (list.isEmpty()) {
            boolean q = !search.isEmpty();
            String head = q ? "NO MODULES MATCH" : tab == 1 ? "NO FAVOURITES YET" : tab == 2 ? "NOTHING ENABLED YET" : "NO MODULES IN THIS TAB";
            String sub = q ? "ESC CLEARS THE SEARCH" : tab == 1 ? "STAR A MODULE TO PIN IT HERE AND ON TOP OF EVERY TAB" : tab == 2 ? "TURN A MODULE ON FROM THE ALL TAB" : "THIS INSTANCE LEAVES THEM OUT";
            Gfx.textC(Gfx.SEMI, 13, px + PW / 2, top + 60, Gfx.DIM, head, 2f);
            Gfx.textC(Gfx.MONO, 10, px + PW / 2, top + 84, Gfx.SUB, sub, 1.4f);
        }
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            float x = px + 28 + (i % cols) * (cw + gap), y = top - scroll + (i / cols) * (ch + gap);
            boolean f = focus(x, y, cw, ch);
            if (!f && (y > top + viewH || y + ch < top - 4)) continue;
            if (card(m, x, y, cw, ch, f)) break; // opened its settings
        }
        endClip();
        if (maxS > 0) {
            float th = Math.max(30, viewH * viewH / contentH);
            float ty2 = top + (viewH - th) * (scroll / maxS);
            Gfx.rect(px + PW - 12, ty2, 3, th, 1.5f, Gfx.a(0xFFFFFFFF, 0.16f));
        }
    }

    /** One module card; true when it opened the module's settings page. */
    private static boolean card(Module m, float x, float y, float w, float h, boolean f) {
        boolean on = m.enabled(), lock = m.locked(), avail = m.available();
        boolean hv = in(x, y, w, h);
        float t = an("c" + m.id, hv);
        float on1 = an("on" + m.id, on);
        Gfx.rect(x, y, w, h, 8, Gfx.mix(Gfx.CARD, Gfx.CARD2, lock ? t * 0.4f : t));
        Gfx.ring(x, y, w, h, 8, 1, Gfx.mix(Gfx.LN, on ? Gfx.a(Gfx.RED, 0.55f) : Gfx.LN2, Math.max(t, on1 * 0.7f)));
        if (on1 > 0.02f) Gfx.rect(x, y + 14, 3, h - 28, 1.5f, Gfx.a(Gfx.RED, on1));
        float a0 = Gfx.alpha;
        if (lock) Gfx.alpha = a0 * 0.55f;
        // icon tile (the item that stands for the module), Lunar-style
        float ix = x + 16, iy = y + 14;
        Gfx.rect(ix, iy, 40, 40, 8, Gfx.mix(Gfx.a(0xFFFFFFFF, 0.04f), Gfx.a(Gfx.RED, 0.16f), on1));
        Gfx.ring(ix, iy, 40, 40, 8, 1, Gfx.mix(Gfx.LN, Gfx.a(Gfx.RED, 0.5f), on1));
        // Nether's own glyph (ice, red accent); item texture only for ids without one
        float io = hv ? 1 : 0;
        if (!ModIcons.draw(m.id, ix + 8 - io, iy + 8 - io, 24 + 2 * io, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, on1)), Gfx.mix(Gfx.RED, Gfx.RED2, on1))) {
            int[] ipx = m.iconPx();
            if (ipx != null) Gfx.icon(ipx, ix + 8 - io, iy + 8 - io, 24 + 2 * io);
            else Gfx.textC(Gfx.HEAD, 18, ix + 20, iy + 20, Gfx.ICE, m.name.substring(0, 1), 0);
        }
        Gfx.text(Gfx.SEMI, 16, x + 66, y + 25, Gfx.ICE, fit(m.name, Gfx.SEMI, 16, w - 66 - 50, 0));
        List<String> lines = wrap(m.desc, Gfx.UI, 11, w - 66 - 58);
        if (lines.size() > 2) lines.set(1, fit(lines.get(1) + " " + lines.get(2), Gfx.UI, 11, w - 66 - 58, 0));
        for (int i = 0; i < Math.min(2, lines.size()); i++) Gfx.text(Gfx.UI, 11, x + 66, y + 45 + i * 14, Gfx.DIM, lines.get(i));
        if (lock) {
            Gfx.alpha = a0;
            Gfx.rect(x + 18, y + h - 19, 4, 4, 2, Gfx.a(avail ? Gfx.RED2 : Gfx.MUT, 0.9f));
            Gfx.text(Gfx.MONO, 9, x + 28, y + h - 16, avail ? Gfx.DIM : Gfx.SUB, m.lockNote(), false, 1.4f);
            Gfx.alpha = a0 * 0.55f;
        } else {
            float cwid = Gfx.text(Gfx.MONO, 9, x + 18, y + h - 16, Gfx.SUB, m.cat.toUpperCase(Locale.ROOT), false, 2f);
            badge(m, x + 18 + cwid + 12, y + h - 16);
        }

        // settings icon (two sliders)
        float gx = x + w - 18 - 24, gy = y + 12;
        boolean gh = in(gx, gy, 24, 24);
        float gt = an("g" + m.id, gh);
        Gfx.rect(gx, gy, 24, 24, 6, Gfx.a(0xFFFFFFFF, 0.06f * gt));
        int ic = Gfx.mix(Gfx.DIM, Gfx.ICE, gt);
        Gfx.rect(gx + 5, gy + 8, 14, 1.5f, 0.75f, ic);
        Gfx.rect(gx + 5, gy + 15, 14, 1.5f, 0.75f, ic);
        Gfx.rect(gx + 12, gy + 5.5f, 4, 6.5f, 2, ic);
        Gfx.rect(gx + 7, gy + 12.5f, 4, 6.5f, 2, ic);
        if (gh) tip = "Settings (or right-click the card)";
        // favourite star beside the switch: shown when starred, on hover or keyboard focus
        boolean fav = m.fav();
        float stx = lock ? gx - 26 : x + w - 18 - 36 - 32, sty = lock ? gy : y + h - 32; // locked cards keep that row for the reason
        boolean sth = in(stx, sty, 24, 24);
        float stt = an("st" + m.id, sth), vis = Math.max(fav ? 1 : 0, Math.max(t, f ? 1 : 0));
        if (vis > 0.02f) {
            float a1 = Gfx.alpha;
            Gfx.alpha = a1 * vis;
            Gfx.rect(stx, sty, 24, 24, 6, Gfx.a(0xFFFFFFFF, 0.06f * stt));
            drawStar(stx + 12, sty + 12, fav, fav ? Gfx.mix(Gfx.RED, Gfx.RED2, stt) : Gfx.mix(Gfx.SUB, Gfx.ICE, stt));
            Gfx.alpha = a1;
        }
        if (sth) tip = fav ? "Remove from favourites" : "Add to favourites: pinned on top of every tab";
        if (hit(stx, sty, 24, 24)) Config.set(m.id, "fav", !fav);
        // the gear, a right-click anywhere on the card, Enter, or a click on a locked card (it explains why) opens the settings
        int u = used(f);
        boolean rc = clickRt && in(x, y, w, h);
        if (hit(gx, gy, 24, 24) || rc || u == 257 || (lock && (hit(x, y, w, h) || u == 32))) {
            if (rc) clickRt = false;
            Gfx.alpha = a0;
            openPage(m.id);
            return true;
        }

        float sx = x + w - 18 - 36, sy = y + h - 30;
        if (avail) sw("sw" + m.id, sx, sy, on);
        Gfx.alpha = a0;
        if (!lock && (hit(sx - 6, sy - 4, 48, 28) || hit(x, y, w, h) || u == 32)) Config.set(m.id, "enabled", !on);
        return false;
    }

    // ---------------------------------------------------------------- settings page

    private static void settings(float px, float py, float wheel) {
        Module m = Modules.byId(page);
        if (m == null) { page = null; return; }
        navCols = 1;
        if (ColorPicker.id != null && !ColorPicker.id.equals(m.id)) ColorPicker.close();
        if (button("back", "BACK", px + 28, py + 88, 92, 30, false)) { commitEdit(); ColorPicker.close(); page = null; scroll = scrollT = gridScroll; return; }
        float tw = Gfx.text(Gfx.HEAD, 22, px + 138, py + 103, Gfx.ICE, m.name.toUpperCase(Locale.ROOT), false, 1.5f);
        float cw0 = Gfx.text(Gfx.MONO, 9, px + 138 + tw + 12, py + 105, Gfx.SUB, m.cat.toUpperCase(Locale.ROOT), false, 2f);
        badge(m, px + 138 + tw + 12 + cw0 + 12, py + 105);

        float top = py + 138, viewH = PH - 138 - 24, lx = px + 28, lw = 500, rowH = 46;
        String note = m.lockNote();
        if (note != null) {
            // why the module is off, above its settings (its own switch still shows and sets what you want)
            float bh = 34;
            Gfx.rect(lx, top, lw, bh, 0, Gfx.a(Gfx.RED, 0.12f));
            Gfx.rect(lx, top, 3, bh, 0, Gfx.RED2);
            Gfx.text(Gfx.SEMI, 11, lx + 16, top + bh / 2, Gfx.ICE, note, false, 1.2f);
            if (m.available()) {
                String why = "TURN SERVER-SAFE OFF IN THE HEADER";
                Gfx.text(Gfx.MONO, 9, lx + lw - 14 - Gfx.width(Gfx.MONO, 9, why, 1.2f), top + bh / 2, Gfx.DIM, why, false, 1.2f);
            }
            top += bh + 8;
            viewH -= bh + 8;
        }
        List<Setting> rows = new ArrayList<>();
        rows.add(Setting.bool("enabled", "Enabled", m.defOn));
        rows.addAll(m.settings);
        Waypoints wp = m instanceof Waypoints ? (Waypoints) m : null;
        if (wp == null && editWp >= 0) commitEdit();
        float contentH = wp != null ? wpHeight(wp) : 0;
        for (Setting s : rows) contentH += rowH + (s.t == Setting.COLOR && ColorPicker.isOpen(m.id, s.key) ? ColorPicker.H : 0);
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 46));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        beginClip(lx - 8, top, lw + 16, viewH);
        float y = top - scroll;
        for (Setting s : rows) {
            float xr = lx + lw, rh = rowH;
            if (s.t == Setting.HEADER) { // section title: small caps on the row's lower half, a stronger rule
                Gfx.text(Gfx.MONO, 9, lx, y + rowH - 14, Gfx.SUB, s.label.toUpperCase(Locale.ROOT), false, 2f);
                Gfx.rect(lx, y + rowH - 1, lw, 1, 0, Gfx.LN2);
                y += rowH;
                continue;
            }
            boolean f = focus(lx - 4, y + 3, lw + 8, rowH - 6);
            Gfx.text(Gfx.UI, 13, lx, y + rowH / 2 - (s.hint != null ? 7 : 0), Gfx.ICE, s.label);
            String id = m.id + "." + s.key, ek = m.id + ":" + s.key;
            float cw = 0; // width of the control on the right (the hint line stops before it)
            switch (s.t) {
                case Setting.BOOL: {
                    boolean v = s.key.equals("enabled") ? m.wantedOn() : Config.bool(m.id, s.key, (Boolean) s.def);
                    sw("s" + id, xr - 36, y + 13, v);
                    int a = adjust(f);
                    if (hit(xr - 44, y + 6, 52, 34) || used(f) != 0) Config.set(m.id, s.key, !v);
                    else if (a != 0 && (a > 0) != v) Config.set(m.id, s.key, a > 0);
                    cw = 52;
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
                        // the value: click (or Enter) to type an exact one
                        String vs = fmt(nv, s.step);
                        boolean vh = in(vx, y + 9, vw, 28);
                        float vt = an("sv" + id, vh);
                        if (vt > 0.02f) Gfx.ring(vx, y + 9, vw, 28, 6, 1, Gfx.a(Gfx.LN2, vt));
                        Gfx.text(Gfx.MONO, 12, vx + vw - 10 - Gfx.width(Gfx.MONO, 12, vs), y + rowH / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, vt), vs);
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
                    Gfx.rect(x, by, bw, 28, 6, Gfx.a(0xFFFFFFFF, 0.035f + 0.03f * t));
                    Gfx.ring(x, by, bw, 28, 6, 1, op ? Gfx.RED : Gfx.mix(Gfx.LN, Gfx.LN2, t));
                    ColorPicker.swatch(x + 7, by + 6, 16, 16, col);
                    Gfx.text(Gfx.MONO, 11, x + 31, by + 14, Gfx.ICE, ch ? "RAINBOW" : Config.hex(col));
                    chevron(x + bw - 15, by + 14, op, Gfx.mix(Gfx.DIM, Gfx.ICE, t));
                    if (hit(x, by, bw, 28) || used(f) != 0) ColorPicker.toggle(m, s.key);
                    cw = bw;
                    if (ColorPicker.isOpen(m.id, s.key)) { ColorPicker.draw(m, lx, y + rowH, lw); rh += ColorPicker.H; }
                    break;
                }
                case Setting.CHOICE: {
                    String cur = Config.str(m.id, s.key, (String) s.def);
                    float total = 0;
                    int ci = 0;
                    for (int o = 0; o < s.opts.length; o++) { total += Gfx.width(Gfx.SEMI, 11, s.opts[o], 0.6f) + 22; if (s.opts[o].equals(cur)) ci = o; }
                    int a = adjust(f), u = used(f);
                    if (a != 0) Config.set(m.id, s.key, s.opts[Math.max(0, Math.min(s.opts.length - 1, ci + Integer.signum(a)))]);
                    else if (u != 0) Config.set(m.id, s.key, s.opts[(ci + 1) % s.opts.length]);
                    cw = total + 10;
                    float x = xr - total, cy = y + rowH / 2;
                    Gfx.rect(x - 3, cy - 15, total + 6, 30, 8, Gfx.a(0xFFFFFFFF, 0.04f));
                    for (String o : s.opts) {
                        float ow = Gfx.width(Gfx.SEMI, 11, o, 0.6f) + 22;
                        boolean sel = o.equals(cur);
                        float t = an("ch" + id + o, sel);
                        if (t > 0.02f) Gfx.rect(x, cy - 12, ow, 24, 6, Gfx.a(Gfx.RED, t));
                        Gfx.textC(Gfx.SEMI, 11, x + ow / 2, cy, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, Math.max(t, an("hv" + id + o, in(x, cy - 12, ow, 24)) * 0.6f)), o, 0.6f);
                        if (hit(x, cy - 12, ow, 24)) Config.set(m.id, s.key, o);
                        x += ow;
                    }
                    break;
                }
                case Setting.KEY: {
                    int code = (int) Config.num(m.id, s.key, ((Number) s.def).doubleValue());
                    float bw = Math.max(110, Gfx.width(Gfx.SEMI, 11, keyName(code), 1f) + 28);
                    boolean[] hard = new boolean[1];
                    String clash = Controls.conflict(m, s, hard);
                    keyButton(ek, code, xr - bw, y + 9, bw, clash != null && hard[0], f);
                    cw = bw;
                    if (clash != null) {
                        int cc = hard[0] ? Gfx.RED2 : 0xFFFFB020;
                        Gfx.rect(xr - bw - 14, y + rowH / 2 - 2, 4, 4, 0, cc);
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
                    String bl = s.opts != null && s.opts.length > 0 ? s.opts[0] : "RUN";
                    float bw = Math.max(96, Gfx.width(Gfx.SEMI, 11, bl, 1.2f) + 32);
                    if ((button("a" + id, bl, xr - bw, y + 8, bw, 30, false) | used(f) != 0) && s.run != null) s.run.run();
                    cw = bw;
                    break;
                }
                default: break;
            }
            Gfx.rect(lx, y + rh - 1, lw, 1, 0, Gfx.LN);
            if (s.hint != null) {
                float hw = lw - cw - 20;
                String h = fit(s.hint, Gfx.UI, 10, hw, 0);
                Gfx.text(Gfx.UI, 10, lx, y + rowH / 2 + 9, Gfx.SUB, h);
                if (!h.equals(s.hint) && in(lx, y, hw, rowH)) tip = s.hint; // shortened: the whole text on hover
            }
            y += rh;
        }
        if (wp != null) wpList(wp, lx, y, lw);
        endClip();
        if (maxS > 0) {
            float th = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(lx + lw + 10, top + (viewH - th) * (scroll / maxS), 3, th, 1.5f, Gfx.a(0xFFFFFFFF, 0.16f));
        }

        // preview + description
        float rx = px + 572, rw = 280, rh = 190;
        Gfx.rect(rx, top, rw, rh, 8, 0xFF08080C);
        Gfx.ring(rx, top, rw, rh, 8, 1, Gfx.LN);
        Gfx.text(Gfx.MONO, 9, rx + 14, top + 16, Gfx.SUB, "PREVIEW", false, 2f);
        beginClip(rx + 1, top + 1, rw - 2, rh - 2);
        float s0 = Gfx.s;
        m.preview(rx + rw / 2, top + rh / 2 + 6);
        Gfx.s = s0;
        endClip();
        List<String> lines = wrap(m.desc, Gfx.UI, 12, rw);
        for (int i = 0; i < lines.size(); i++) Gfx.text(Gfx.UI, 12, rx, top + rh + 26 + i * 17, Gfx.DIM, lines.get(i));
        if (fbutton("reset", "RESET TO DEFAULT", rx, py + PH - 24 - 30, 136, 30, false)) { ColorPicker.close(); m.reset(); }
        // one look for the whole HUD: copy this module's colours/background/outline to every HUD module
        if (m.hud && fbutton("styleall", "STYLE TO ALL HUD", rx + 144, py + PH - 24 - 30, 136, 30, false)) m.styleToAll();
    }

    // ---------------------------------------------------------------- waypoint list (settings page of Waypoints)

    private static float wpHeight(Waypoints w) {
        List<Map<String, Object>> l = w.list(false);
        return 66 + Math.max(1, l == null ? 0 : l.size()) * 46;
    }

    private static void wpList(Waypoints w, float lx, float y, float lw) {
        float xr = lx + lw;
        String k = w.world();
        Gfx.text(Gfx.MONO, 9, lx, y + 33, Gfx.SUB, "THIS WORLD", false, 2f);
        String wn = k == null ? "NOT IN A WORLD" : k.substring(3).replace("|minecraft:", "  /  ").replace('|', '/').replace('_', ' ').toUpperCase(Locale.ROOT);
        float ww = xr - 176 - 16 - (lx + 96);
        while (wn.length() > 4 && Gfx.width(Gfx.SEMI, 11, wn, 0.8f) > ww) wn = wn.substring(0, wn.length() - 3) + "..";
        Gfx.text(Gfx.SEMI, 11, lx + 96, y + 33, Gfx.DIM, wn, false, 0.8f);
        if (k != null && Game.player() != null && fbutton("wpadd", "ADD AT MY POSITION", xr - 176, y + 18, 176, 30, true)) w.addHere();
        List<Map<String, Object>> l = w.list(false);
        float ry = y + 66;
        Gfx.rect(lx, ry - 1, lw, 1, 0, Gfx.LN);
        if (l == null || l.isEmpty()) {
            String msg = k == null ? "Join a world to add waypoints for it." : "No waypoints here yet. Press " + keyName((int) w.num("addKey")) + " in game or use the button.";
            Gfx.text(Gfx.UI, 12, lx, ry + 23, Gfx.SUB, msg);
            return;
        }
        for (int i = 0; i < l.size(); i++) {
            Map<String, Object> p = l.get(i);
            float top = ry + i * 46, cy = top + 23;
            Gfx.rect(lx, top + 45, lw, 1, 0, Gfx.LN);
            // colour: click cycles through the palette
            boolean sh = in(lx - 2, cy - 11, 22, 22);
            float st = an("wpc" + i, sh);
            Gfx.rect(lx + 1, cy - 8, 16, 16, 4, Waypoints.col(p));
            if (st > 0.02f) Gfx.ring(lx - 2, cy - 11, 22, 22, 6, 1.5f, Gfx.a(Gfx.ICE, st));
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
            Gfx.rect(nx - 8, cy - 14, nw, 28, 6, Gfx.a(0xFFFFFFFF, 0.045f * nt));
            Gfx.ring(nx - 8, cy - 14, nw, 28, 6, 1, ed ? Gfx.RED : Gfx.a(Gfx.LN2, nt));
            String nm = ed ? editBuf : Waypoints.str(p, "name", "Waypoint");
            float tw = Gfx.text(Gfx.UI, 13, nx, cy, Gfx.ICE, nm);
            if (ed && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(nx + tw + 1, cy - 8, 1.5f, 16, 0, Gfx.RED2);
            if ((hit(nx - 8, cy - 14, nw, 28) || used(f) != 0) && !ed) { commitEdit(); editWp = i; editKind = TXT; editBuf = Waypoints.str(p, "name", ""); }
            // position
            Gfx.text(Gfx.MONO, 11, lx + 238, cy, Gfx.DIM, String.format(Locale.ROOT, "%d / %d / %d",
                (long) Waypoints.num(p, "x"), (long) Waypoints.num(p, "y"), (long) Waypoints.num(p, "z")));
            // shown on screen
            boolean on = !Boolean.FALSE.equals(p.get("on"));
            sw("wpv" + i, xr - 36 - 40, cy - 10, on);
            if (hit(xr - 36 - 46, cy - 14, 48, 28)) { p.put("on", !on); w.changed(); }
            // delete
            boolean dh = in(xr - 26, cy - 13, 26, 26);
            float dt = an("wpd" + i, dh);
            Gfx.rect(xr - 26, cy - 13, 26, 26, 6, Gfx.a(Gfx.RED, 0.2f * dt));
            drawX(xr - 13, cy, 4f, Gfx.mix(Gfx.MUT, Gfx.RED2, dt));
            if (hit(xr - 26, cy - 13, 26, 26)) {
                if (editWp == i) editWp = -1; else if (editWp > i) editWp--;
                l.remove(i);
                w.changed();
                return;
            }
        }
    }
}
