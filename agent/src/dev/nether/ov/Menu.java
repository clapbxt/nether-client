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
    static final int T_WARDROBE = 6, T_HUDED = 7, T_SETTINGS = 8, T_FRIENDS = 9;
    private static int sub;

    static boolean hudEdit;
    /** Live view editor: the menu steps aside so the real game shows the module (fire, shield, hand, mount); null = off. */
    static Module live;
    /** The menu key opens on the hub (logo + settings / MODS / wardrobe); a hub button opens the panel on that tab. */
    static boolean hub = true;
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
    static boolean textFocus() { return WpDialog.open || Splashes.open || WorldShare.open || PackBrowser.open || PackMixer.open || (!hub && tab == T_FRIENDS && Friends.typing) || fieldFocus() || (!hudEdit && !hub && tab < T_WARDROBE && (searchFocus || !search.isEmpty())); }
    /** A waypoint name, a text setting, a slider value, a hex colour or a profile name is being typed into. */
    static boolean fieldFocus() { return editWp >= 0 || editSet != null; }

    /** Smallest text in the menu, in pixels: small windows / GUI scale 1 never get unreadable labels. */
    static final int MIN_PX = 9;


    static float tS = 99, gridT = 99, pageT = 99, pillX = -1, pillW;
    static int tDir; // screen swipe: -1 from the left (Settings), 1 from the right (Wardrobe), 0 rises (Mods / hub)

    /** Eased 0..1 for an animation of dur seconds starting delay seconds into timer t. */
    private static float k(float t, float delay, float dur) {
        float x = Math.max(0, Math.min(1, (t - delay) / dur));
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    static void opened() {
        Sfx.open();
        tS = gridT = 0; tDir = 0; pillX = -1; hub = true; drag = capture = null; searchFocus = false; scroll = scrollT = 0; fCur = -1; kbNav = false;
        // the title screen shows the hub's actions itself (MainMenu.actions): the menu key goes straight to the mods
        if (MainMenu.title) { hub = false; page = null; if (tab != lastCat) setTab(lastCat); }
    }

    /** Open the menu straight on screen t (the title screen's actions row). */
    static void openAt(int t) {
        Input.setMenu(true);
        if (Input.menuOpen) go(t);
    }
    static void closed() {
        commitEdit();
        drag = capture = null;
        searchFocus = false;
        hudEdit = false;
        live = null;
        dev.nether.agent.Hook.fireForce = false;
        WpDialog.open = false;
        Splashes.open = false;
        WorldShare.open = false;
        PackBrowser.open = false;
        Screenshots.open = false;
        PackMixer.open = false;
        Wardrobe.ask = false;
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
                if (k == Input.MOUSE + 2) return "MIDDLE CLICK";
                if (k > Input.MOUSE + 2 && k < Input.MOUSE + 8) return "MOUSE " + (k - Input.MOUSE + 1);
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

    /** Starts (click inside) and keeps (button held) a drag with this id; false once released. For custom editors. */
    static boolean dragging(String id, float x, float y, float w, float h) {
        if (click && in(x, y, w, h)) { drag = id; click = false; }
        if (!id.equals(drag)) return false;
        if (!Input.btn[0]) { drag = null; return false; }
        return true;
    }

    static boolean hit(float x, float y, float w, float h) {
        if (click && in(x, y, w, h)) { click = false; Sfx.click(); return true; }
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

    static int lastCat = 1;

    private static void setTab(int i) {
        tab = i;
        gridT = 0;
        if (i < T_WARDROBE) lastCat = i;
        scroll = scrollT = 0;
        fCur = -1;
        capture = null;
    }

    private static void openPage(String id) {
        commitEdit();
        gridScroll = scrollT;
        gridFocus = fCur;
        page = id;
        pageT = 0;
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
            if (k == 70) { commitEdit(); ColorPicker.close(); hub = false; page = null; if (tab >= T_WARDROBE) setTab(1); searchFocus = true; return; } // Ctrl+F
            if (k == 69) { commitEdit(); ColorPicker.close(); page = null; hudEdit = true; return; }                                   // Ctrl+E
            if (k == 86) { typedSearch(Plat.cur.clipboard()); return; }                                                                // Ctrl+V
            return;
        }
        if (hub) { // arrows move in the 2x2 grid (0 MODS, 1 WARDROBE, 2 FRIENDS, 3 SETTINGS), Tab cycles, Enter / Space press one (MODS when none is focused)
            if (k == 262 || k == 263 || k == 258 || k == 264 || k == 265) {
                int d = k == 264 || k == 265 ? (k == 264 ? 2 : -2) : k == 263 || (k == 258 && shift) ? -1 : 1;
                fCur = fCur < 0 ? (d > 0 ? 0 : 3) : Math.floorMod(fCur + d, 4);
                kbNav = true;
            } else if (press && (k == 257 || k == 335 || k == 32)) { if (fCur < 0) fCur = 0; act = 257; kbNav = true; }
            return;
        }
        if (k == 259) {
            if (!search.isEmpty() && tab < T_WARDROBE) { search = search.substring(0, search.length() - 1); searchFocus = true; scroll = scrollT = 0; fCur = -1; }
            return;
        }
        switch (k) {
            case 258:
                if (page != null || tab == T_WARDROBE) nav(shift ? -1 : 1);
                else if (tab == T_SETTINGS) { commitEdit(); sub = Math.floorMod(sub + (shift ? -1 : 1), 3); scroll = scrollT = 0; fCur = -1; }
                else { commitEdit(); ColorPicker.close(); setTab(Math.floorMod(tab + (shift ? -1 : 1), T_WARDROBE)); }
                kbNav = true;
                break;
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

    /** Esc would close the menu (nothing to step back from). */
    static boolean escCloses() { return hub && !hudEdit; }

    /** Esc steps back one level: typing -> layout editor selection -> layout editor -> colour picker -> search -> page -> hub -> close. */
    private static void esc() {
        if (Wardrobe.ask) { Wardrobe.ask = false; return; }
        if (fieldFocus()) { editWp = -1; editSet = null; return; }
        if (hudEdit) { if (!HudEditor.esc()) { hudEdit = false; HudEditor.reset(); } return; }
        if (live != null) { live = null; return; }
        if (ColorPicker.id != null) { ColorPicker.close(); return; }
        if (page == null && (searchFocus || !search.isEmpty())) { search = ""; searchFocus = false; scroll = scrollT = 0; fCur = -1; return; }
        if (page != null) { commitEdit(); page = null; gridT = 0; scroll = scrollT = gridScroll; fCur = kbNav ? gridFocus : -1; return; }
        if (!hub) { toHub(); return; }
        Input.setMenu(false);
    }

    private static void typedSearch(String t) {
        if (hudEdit || capture != null || t.isEmpty() || (!hub && tab >= T_WARDROBE)) return;
        String q = (search + t.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')).replaceFirst("^\\s+", "");
        q = q.substring(0, Math.min(24, q.length()));
        if (q.equals(search)) return; // a lone space: nothing to search for (Space toggles the focused card)
        search = q;
        searchFocus = true;
        scroll = scrollT = 0;
        fCur = -1;
        if (tab >= T_WARDROBE) setTab(lastCat); // typing on the hub opens Mods with the search
        hub = false;
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
    private static final java.awt.Shape X_SHAPE, STAR, STAR_O, CHEV_D, CHEV_U, BACK;
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
        java.awt.geom.Path2D.Float b = new java.awt.geom.Path2D.Float();
        b.moveTo(13, 8); b.lineTo(3, 8); b.moveTo(7.5f, 3.5f); b.lineTo(3, 8); b.lineTo(7.5f, 12.5f);
        BACK = st.createStrokedShape(b);
    }

    /** Small chevron, up when open (MainMenu's PLAY mode card). */
    static void drawChev(float cx, float cy, boolean up, int col) {
        Gfx.mask(up ? "chevu" : "chevd", up ? CHEV_U : CHEV_D, 16, cx - 8, cy - 8, 16, col);
    }

    static void drawX(float cx, float cy, float r, int col) {
        float size = r * 2 * 16 / 12f;
        Gfx.mask("x", X_SHAPE, 16, cx - size / 2, cy - size / 2, size, col);
    }

    static void drawStar(float cx, float cy, boolean filled, int col) {
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

    /** Open the Nether menu at a tab (the party lobby's CHANGE OUTFIT). */
    static void openTab(int t) {
        Input.setMenu(true);
        // Opening the menu runs opened(), which resets it to the hub. Navigate only after that reset.
        go(t);
    }

    /** Title screen with the top bar: every Menu screen is a page under it (TitleNav). */
    static boolean pageMode() { return MainMenu.navOn() && !hudEdit; }

    private static int fbW0 = 1, fbH0 = 1;

    /** Bottom of the title bar in menu units. */
    static float navH() { return TitleNav.bottomPx(fbW0, fbH0) / sc; }

    /** A screen's panel height, kept under the title bar in page mode. */
    private static float pageH(float ph) { return pageMode() ? Math.min(ph, H - navH() - 30) : ph; }

    static void frame(int fbW, int fbH, float dt) {
        fbW0 = fbW;
        fbH0 = fbH;
        Party.tick();
        Slow.mark("Party");
        MainMenu.frame(fbW, fbH);
        Slow.mark("MainMenu");
        WorldShare.tick();
        Slow.mark("WorldShare");
        Friends.tick();
        Slow.mark("Friends");
        Gfx.minPx = 0;
        Gfx.s = Gfx.base;
        boolean want = Input.menuOpen;
        open = Config.calm() ? (want ? 1 : 0) : Math.max(0, Math.min(1, open + (want ? 1 : -1) * dt * 7));
        if (open <= 0) {
            Input.clickL = Input.clickR = false;
            Gfx.alpha = 1;
            Modules.drawHud(fbW, fbH);
            Party.hud(fbW, fbH);
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
        if (Screenshots.open) { // the screenshot browser takes every key while it is up
            for (int[] ev : evs) Screenshots.key(ev[0], ev[1] == 1, shift, ctrl);
            evs.clear();
            typed = "";
        }
        if (PackBrowser.open) { // the pack browser takes every key while it is up
            for (int[] ev : evs) PackBrowser.key(ev[0], ev[1] == 1, shift, ctrl);
            if (!typed.isEmpty() && PackBrowser.open) PackBrowser.type(typed);
            evs.clear();
            typed = "";
        }
        if (PackMixer.open) { // the pack combiner takes every key while it is up
            for (int[] ev : evs) PackMixer.key(ev[0], ev[1] == 1, shift, ctrl);
            if (!typed.isEmpty() && PackMixer.open) PackMixer.type(typed);
            evs.clear();
            typed = "";
        }
        if (WorldShare.open) { // the Invite to world dialog takes every key while it is up
            for (int[] ev : evs) WorldShare.key(ev[0], ev[1] == 1, shift, ctrl);
            if (!typed.isEmpty() && WorldShare.open) WorldShare.type(typed);
            evs.clear();
            typed = "";
        }
        if (Splashes.open) { // the splash texts dialog takes every key while it is up
            for (int[] ev : evs) Splashes.key(ev[0], ev[1] == 1, shift, ctrl);
            if (!typed.isEmpty() && Splashes.open) Splashes.type(typed);
            evs.clear();
            typed = "";
        }
        if (WpDialog.open) { // the waypoint dialog takes every key while it is up
            for (int[] ev : evs) WpDialog.key(ev[0], ev[1] == 1, shift, ctrl);
            if (!typed.isEmpty() && WpDialog.open) WpDialog.type(typed);
            evs.clear();
            typed = "";
        }
        if (!hub && tab == T_FRIENDS && Friends.typing) { // the Friends name field takes the keys while it is focused
            List<int[]> rest = new ArrayList<>();
            for (int[] ev : evs) if (!Friends.key(ev[0], ev[1] == 1, ctrl)) rest.add(ev);
            evs = rest;
            if (!typed.isEmpty()) Friends.type(typed);
            typed = "";
        }
        for (int[] ev : evs) key(ev[0], ev[1] == 1, shift, ctrl);
        if (!typed.isEmpty()) { if (fieldFocus()) typeField(typed); else typedSearch(typed); }

        float e = 1 - (float) Math.pow(1 - open, 3);
        if (Config.calm()) tS = gridT = pageT = 99;
        else { tS += dt; gridT += dt; pageT += dt; }
        fIdx = 0;
        if (hudEdit && want) {
            Gfx.alpha = 1;
            HudEditor.draw(fbW, fbH, wheel, e);
        } else {
            Gfx.alpha = 1;
            if (!MainMenu.title) Modules.drawHud(fbW, fbH); // the title screen has no game to put a HUD over
            Party.hud(fbW, fbH);
            Gfx.alpha = e;
            Gfx.s = sc;
            Gfx.minPx = MIN_PX;
            Gfx.round = Th.round(); // rounded panel, rows and controls (HUD modules stay square)
            try {
                if (want && pageMode()) { // the title bar stays over every page (its own scale and input)
                    if (TitleNav.draw(fbW, fbH, L * sc, T * sc, click, false)) click = false;
                    Gfx.s = sc;
                    Gfx.alpha = e;
                }
                if (live != null && !liveOk(live)) live = null;
                dev.nether.agent.Hook.fireForce = live instanceof Views.Fire;
                if (live != null) liveEdit(e, wheel);
                else if (Screenshots.open) Screenshots.drawBrowser(e, wheel);
                else if (PackBrowser.open) PackBrowser.draw(e, wheel);
                else if (PackMixer.open) PackMixer.draw(e, wheel);
                else if (WorldShare.open) WorldShare.draw(e, wheel);
                else if (Splashes.open) { // over the Main Menu settings page, which gets no clicks
                    boolean c0 = click, c1 = clickRt;
                    click = clickRt = false;
                    if (hub) hub(e); else panel(0, e);
                    click = c0;
                    clickRt = c1;
                    Splashes.draw(e, wheel);
                } else if (WpDialog.open) {
                    if (!WpDialog.fromWorld()) { // opened from the menu: it stays behind the dialog, without clicks
                        boolean c0 = click, c1 = clickRt;
                        click = clickRt = false;
                        if (hub) hub(e); else panel(0, e);
                        click = c0;
                        clickRt = c1;
                    }
                    WpDialog.draw(e);
                } else if (Wardrobe.ask && !hub && tab == T_WARDROBE) { // unsaved-changes dialog over the Wardrobe, which gets no clicks
                    boolean c0 = click, c1 = clickRt;
                    float l0 = L, t0 = T;
                    click = clickRt = false;
                    L = T = -1e6f; // no hover either
                    panel(0, e);
                    L = l0;
                    T = t0;
                    click = c0;
                    clickRt = c1;
                    Wardrobe.askDraw(e);
                } else if (hub) hub(e); else panel(wheel, e);
                if (want && pageMode()) { TitleNav.drawOver(fbW, fbH); Gfx.s = sc; }
            } finally { Gfx.round = false; }
            if (click && fieldFocus()) commitEdit(); // clicked somewhere else
            if (click) Hits.press(0); // a click the panel did not use may land on a HUD widget
        }
        fCount = fIdx;
        if (fCur >= fCount) fCur = fCount - 1;
        Gfx.minPx = 0;
        Gfx.alpha = 1;
        Gfx.s = sc;
    }

    private static final int TOP = 56, FOOT = 28, ROW = 44;

    // ---------------------------------------------------------------- hub (menu key): fireball logo, settings / MODS / wardrobe

    private static void hub(float e) {
        // over the title screen the hub is the only thing to look at: its buttons and logo recede further
        Gfx.rect(0, 0, W, H, 0, Gfx.a(0xFF000000, (Th.light() ? 0.18f : 0.34f) + (MainMenu.title ? 0.26f : 0)));
        // 2x2 grid: MODS / WARDROBE over FRIENDS / SETTINGS, equal tiles (icon + label)
        float lh = 150, bw0 = 196, bh = 56, gap = 10, rw = bw0 * 2 + gap, gh = bh * 2 + gap;
        float y = (H - (lh + 24 + gh)) / 2 + (1 - e) * 14, x = (W - rw) / 2, by = y + lh + 24;
        float a0 = Gfx.alpha, kl = k(tS, 0, 0.34f), k0 = k(tS, 0.07f, 0.3f), k1 = k(tS, 0.12f, 0.3f);
        Gfx.alpha = a0 * kl;
        Logo.brand(W / 2 - Logo.brandWidth(lh) / 2, y - (1 - kl) * 18, lh);
        Gfx.alpha = a0 * k0;
        boolean bm = hubBtn("hm", x, by + (1 - k0) * 14, bw0, bh, "all", "MODS");
        boolean bw = hubBtn("hw", x + bw0 + gap, by + (1 - k0) * 14, bw0, bh, "wardrobe", "WARDROBE");
        Gfx.alpha = a0 * k1;
        float fx = x, fy = by + bh + gap + (1 - k1) * 14;
        boolean bf = hubBtn("hf", fx, fy, bw0, bh, "friends", "FRIENDS");
        boolean bs = hubBtn("hs", x + bw0 + gap, fy, bw0, bh, "settings", "SETTINGS");
        float ks = k1;
        int badge = Friends.badge();
        if (badge > 0) { // requests + asks to join
            String b = badge > 9 ? "9+" : String.valueOf(badge);
            float bw1 = Math.max(16, Gfx.width(Gfx.UI, 10, b) + 8);
            Gfx.rect(fx + bw0 - bw1 + 4, fy - 4, bw1, 16, 8, Th.ac);
            Gfx.textC(Gfx.UI, 10, fx + bw0 - bw1 / 2 + 4, fy + 4, 0xFFFFFFFF, b, 0);
        }
        int n = Friends.everyone;
        if (n > 0) { // live count under the buttons: green dot + "1,284 players online"
            String t = String.format(java.util.Locale.US, "%,d", n), u = n == 1 ? "  player online" : "  players online";
            float tw = Gfx.width(Gfx.SEMI, 12, t), uw = Gfx.width(Gfx.UI, 12, u), lx = W / 2 - (14 + tw + uw) / 2, ly = by + gh + 22 + (1 - ks) * 14;
            Gfx.alpha = a0 * ks * 0.92f;
            Gfx.rect(lx, ly - 5, 10, 10, 5, Gfx.a(0xFF3FB950, 0.2f));
            Gfx.rect(lx + 2, ly - 3, 6, 6, 3, 0xFF3FB950);
            Gfx.text(Gfx.SEMI, 12, lx + 14, ly, Th.tx, t);
            Gfx.text(Gfx.UI, 12, lx + 14 + tw, ly, Th.dim, u);
        }
        Gfx.alpha = a0;
        if (bs) go(T_SETTINGS);
        else if (bm) go(lastCat);
        else if (bw) go(T_WARDROBE);
        else if (bf) go(T_FRIENDS);
    }

    /** Hub button: glass radius 6, hairline brightening on hover; an icon or a Nether Pixel label. */
    private static boolean hubBtn(String id, float x, float y, float w, float h, String icon, String label) {
        boolean f = focus(x, y, w, h), hv = in(x, y, w, h), down = hv && Input.btn[0];
        float t = an(id, hv || f && kbNav);
        Gfx.rect(x, y, w, h, 6, down ? Th.s3 : Gfx.mix(Gfx.a(Th.s1, 0.86f), Th.s2, t));
        Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Th.ln2, Gfx.a(Th.tx, 0.4f), t));
        int c = Gfx.mix(Th.dim, Th.tx, 0.4f + 0.6f * t);
        float dy = down ? 1 : 0;
        if (icon != null && label != null) { // icon + label, centred as a pair
            float lw = Gfx.width(Gfx.SEMI, 15, label), ix = x + (w - (22 + 10 + lw)) / 2;
            ModIcons.draw(icon, ix, y + h / 2 - 11 + dy, 22, c);
            Gfx.text(Gfx.SEMI, 15, ix + 32, y + h / 2 + dy, c, label);
        } else if (icon != null) ModIcons.draw(icon, x + w / 2 - 11, y + h / 2 - 11 + dy, 22, c);
        else Gfx.textC(Gfx.SEMI, 16, x + w / 2, y + h / 2 + dy, c, label, 0);
        return hit(x, y, w, h) | used(f) != 0;
    }

    /** Back to the hub, focusing the button of the screen just left. */
    /** Leaving the current screen is fine now (false: the Wardrobe holds it for its unsaved-changes dialog, then runs r). */
    static boolean leaveOk(Runnable r) { return hub || tab != T_WARDROBE || !Wardrobe.guard(r); }

    private static void toHub() {
        if (!leaveOk(Menu::toHub)) return;
        // over the title screen there is no hub (its actions are on the screen): back closes the menu
        if (MainMenu.title) { commitEdit(); ColorPicker.close(); page = null; search = ""; searchFocus = false; capture = null; Input.setMenu(false); return; }
        commitEdit();
        ColorPicker.close();
        page = null;
        search = "";
        searchFocus = false;
        capture = null;
        scroll = scrollT = 0;
        hub = true;
        tS = 0;
        tDir = 0;
        fCur = kbNav ? (tab == T_SETTINGS ? 3 : tab == T_WARDROBE ? 1 : tab == T_FRIENDS ? 2 : 0) : -1;
    }

    /** Leave the hub for the screen of tab t. */
    private static void go(int t) {
        hub = false;
        commitEdit();
        ColorPicker.close();
        page = null;
        if (t != tab) setTab(t);
        fCur = -1;
        tS = gridT = 0;
        pillX = -1;
        tDir = t == T_SETTINGS ? -1 : t == T_WARDROBE || t == T_FRIENDS ? 1 : 0;
    }

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

    // ---------------------------------------------------------------- screens: Mods, Settings and Wardrobe are separate GUIs

    private static float sx, sy, sw, sh; // the open screen's panel

    private static void panel(float wheel, float e) {
        if (tab == T_SETTINGS) settingsScreen(wheel, e);
        else if (tab == T_WARDROBE) wardrobeScreen(wheel, e);
        else if (tab == T_FRIENDS) friendsScreen(wheel, e);
        else { if (tab >= T_WARDROBE) setTab(lastCat); modsScreen(wheel, e); }
        if (tip != null) tooltip(sx);
    }

    /** Mods: logo + MODS, search, HUD editor; category chips; the card grid. */
    private static void modsScreen(float wheel, float e) {
        float pw = Math.max(760, Math.min(1180, W * 0.86f)), ph = pageH(Math.max(500, Math.min(640, H * 0.86f)));
        float hx = header(pw, ph, e, null, "MODS"), cy = sy + TOP / 2f;
        Gfx.text(Gfx.UI, 12, hx, cy, Th.mut, onCount(tab) + " of " + count(tab) + " on");
        float ex = sx + sw - 16 - 28 - 8 - 28;
        boolean eh = in(ex, cy - 14, 28, 28);
        float et = an("hed", eh);
        Gfx.rect(ex, cy - 14, 28, 28, 4, Gfx.a(Th.ov, 0.07f * et));
        ModIcons.draw("hudeditor", ex + 6, cy - 8, 16, Gfx.mix(Th.mut, Th.tx, et));
        if (eh) tip = "Edit HUD layout  (Ctrl E)";
        if (hit(ex, cy - 14, 28, 28)) { commitEdit(); ColorPicker.close(); page = null; hudEdit = true; }
        searchBox(ex - 12 - 250, cy);
        float x = sx + 16, y = sy + TOP + 10, a0 = Gfx.alpha;
        float[] cxs = new float[T_WARDROBE], cws = new float[T_WARDROBE];
        for (int i = 0, xx = 0; i < T_WARDROBE; i++) {
            cws[i] = 37 + Gfx.width(Gfx.UI, 12, TABS[i]) + 8 + Gfx.width(Gfx.UI, 11, String.valueOf(count(i))) + 14;
            cxs[i] = xx;
            xx += (int) Math.ceil(cws[i] + 6);
        }
        for (int i = 0; i < T_WARDROBE; i++) {
            if (tab != i) {
                Gfx.alpha = a0 * k(tS, 0.05f + i * 0.03f, 0.24f);
                float h = an("chh" + i, in(x + cxs[i], y, cws[i], 28));
                Gfx.rect(x + cxs[i], y, cws[i], 28, 14, Gfx.mix(Gfx.a(Th.s2, 0.7f), Th.s3, h));
                Gfx.ring(x + cxs[i], y, cws[i], 28, 14, 1, Th.ln2);
            }
        }
        Gfx.alpha = a0;
        if (tab < T_WARDROBE) {
            if (pillX < 0 || Config.calm()) { pillX = cxs[tab]; pillW = cws[tab]; }
            float f0 = Math.min(1, Gfx.dt * 14);
            pillX += (cxs[tab] - pillX) * f0;
            pillW += (cws[tab] - pillW) * f0;
            Gfx.rect(x + pillX, y, pillW, 28, 14, Th.ac);
        }
        for (int i = 0; i < T_WARDROBE; i++) {
            String c = String.valueOf(count(i));
            float w = cws[i];
            x = sx + 16 + cxs[i];
            boolean sel = tab == i;
            Gfx.alpha = a0 * k(tS, 0.05f + i * 0.03f, 0.24f);
            float t = an("ch" + i, sel), h = an("chh" + i, in(x, y, w, 28));
            int col = Gfx.mix(Gfx.mix(Th.dim, Th.tx, h), 0xFFFFFFFF, t);
            ModIcons.draw(TAB_ICON[i], x + 14, y + 6.5f, 15, col);
            float lw = Gfx.text(Gfx.UI, 12, x + 37, y + 14, col, TABS[i]);
            Gfx.text(Gfx.UI, 11, x + 37 + lw + 8, y + 14, Gfx.mix(Th.mut, 0xC0FFFFFF, t), c);
            if (hit(x, y, w, 28) && i != tab) { commitEdit(); ColorPicker.close(); page = null; setTab(i); searchFocus = false; }
        }
        Gfx.alpha = a0;
        float top = sy + TOP + 48;
        list(sx, top, sw, sh - (top - sy) - FOOT, wheel);
        footer(page != null ? "Esc back to modules" : "Arrows move  \u00b7  Space toggle  \u00b7  Enter settings  \u00b7  Esc back");
    }

    /** Settings: gear + SETTINGS; General / Controls / Profiles. */
    private static void settingsScreen(float wheel, float e) {
        float pw = Math.max(720, Math.min(940, W * 0.72f)), ph = pageH(Math.max(500, Math.min(640, H * 0.86f)));
        header(pw, ph, e, "settings", "SETTINGS");
        PW = pw + 16;
        PH = ph + 62;
        settingsPage(sx - 8, sy - 68, wheel, sx, sy, pw, ph);
        footer(page != null ? "Esc back to settings" : "Tab next page  \u00b7  Esc back");
    }

    /** Wardrobe: hanger + WARDROBE; player preview and cosmetics. */
    private static void wardrobeScreen(float wheel, float e) {
        float pw = Math.max(760, Math.min(1100, W * 0.82f)), ph = pageH(Math.max(500, Math.min(640, H * 0.86f)));
        header(pw, ph, e, "wardrobe", pageMode() ? "LOCKER" : "WARDROBE");
        PW = pw + 16; // Wardrobe draws at (px + 28, py + 124) inside PW x PH
        PH = ph + 62;
        Wardrobe.draw(sx - 8, sy - 68, wheel);
        footer("Esc back");
    }

    /** Friends: people + FRIENDS; status, requests, join / invite. */
    private static void friendsScreen(float wheel, float e) {
        float pw = Math.max(640, Math.min(820, W * 0.7f)), ph = pageH(Math.max(480, Math.min(620, H * 0.86f)));
        float hx = header(pw, ph, e, "friends", pageMode() ? "SOCIAL" : "FRIENDS");
        Friends.header(hx, sx, sw, sy + TOP / 2f);
        Friends.draw(sx, sy, sw, sh, TOP, FOOT, wheel);
        footer(Friends.typing ? "Enter sends the request  ·  Esc stops typing" : "Esc back");
    }

    /** Screen frame: backdrop, panel, back arrow (to the hub), mark + title, close. Returns the x after the title. */
    private static float header(float pw, float ph, float e, String icon, String title) {
        boolean pg = pageMode();
        float nh = pg ? navH() : 0;
        // over the title screen the hub is the only thing to look at: its buttons and logo recede further
        // (page mode: the bar stays clear; the page dims the lobby under it)
        Gfx.rect(0, nh, W, H - nh, 0, Gfx.a(0xFF000000, (Th.light() ? 0.18f : 0.34f) + (MainMenu.title ? 0.26f : 0)));
        float kk = k(tS, 0, 0.32f);
        Gfx.alpha *= kk;
        sw = pw;
        sh = ph;
        sx = (W - pw) / 2 + tDir * (1 - kk) * 70;
        sy = pg ? nh + 14 + Math.max(0, (H - nh - 30 - ph) / 2) * 0.35f + (1 - e) * 10 + (tDir == 0 ? (1 - kk) * 16 : 0)
            : (H - ph) / 2 + (1 - e) * 14 + (tDir == 0 ? (1 - kk) * 26 : 0);
        // v8: a near-solid charcoal / paper panel (Feather), small radius, faint top sheen and a soft drop shadow
        Gfx.rect(sx - 2, sy + 4, pw + 4, ph + 6, Th.R + 4, Gfx.a(0xFF000000, Th.light() ? 0.10f : 0.30f));
        Gfx.rect(sx, sy, pw, ph, Th.R, Gfx.a(Th.s1, Th.light() ? 0.94f : 0.90f));
        Gfx.rectG(sx, sy, pw, Math.min(ph, 120), Th.R, Gfx.a(0xFFFFFFFF, Th.light() ? 0.25f : 0.03f), 0);
        Gfx.ring(sx, sy, pw, ph, Th.R, 1, Th.ln2);
        float cy = sy + TOP / 2f;
        float tx = sx + 50;
        if (pg) tx = sx + 20; // the bar is the way back: no arrow, no close
        else {
            float bt = an("back", in(sx + 12, cy - 14, 28, 28));
            Gfx.rect(sx + 12, cy - 14, 28, 28, 4, Gfx.a(Th.ov, 0.07f * bt));
            Gfx.mask("back", BACK, 16, sx + 18, cy - 8, 16, Gfx.mix(Th.mut, Th.tx, bt));
            if (hit(sx + 12, cy - 14, 28, 28)) toHub();
        }
        if (icon == null) { Logo.draw(tx, cy - 12, 24); tx += Logo.width(24) + 10; }
        else { if (!ModIcons.drawItem(icon, tx, cy - 9, 18)) ModIcons.draw(icon, tx, cy - 9, 18, Th.ac2); tx += 28; }
        float tw = Gfx.text(Gfx.HEAD, 16, tx, cy, Th.tx, title);
        float cx = sx + pw - 16 - 28;
        if (!pg) {
            float xt = an("close", in(cx, cy - 14, 28, 28));
            Gfx.rect(cx, cy - 14, 28, 28, 4, Gfx.a(Th.ov, 0.07f * xt));
            drawX(cx + 14, cy, 4.2f, Gfx.mix(Th.mut, Th.tx, xt));
            if (hit(cx, cy - 14, 28, 28) && leaveOk(() -> Input.setMenu(false))) Input.setMenu(false);
        }
        Gfx.rect(sx, sy + TOP, pw, 1, 0, Th.ln);
        return tx + tw + 12;
    }

    private static void footer(String hint) {
        float fy = sy + sh - FOOT;
        Gfx.rect(sx, fy, sw, 1, 0, Th.ln);
        String st = Profiles.status();
        boolean bad = st != null && Profiles.statusBad();
        Gfx.text(Gfx.UI, 11, sx + 20, fy + FOOT / 2f, bad ? Th.ac2 : Th.mut, st != null ? st : hint);
    }

    private static void searchBox(float sx0, float cy) {
        boolean sh0 = in(sx0, cy - 15, 250, 30);
        float qx = sx0 + 250 - 40;
        if (!search.isEmpty() && hit(qx, cy - 12, 22, 24)) { search = ""; searchFocus = true; scroll = scrollT = 0; }
        if (click && !sh0) searchFocus = false;
        if (hit(sx0, cy - 15, 250, 30)) searchFocus = true;
        float st = an("search", searchFocus || sh0);
        Gfx.rect(sx0, cy - 15, 250, 30, 4, Gfx.a(Th.s1, 0.5f));
        Gfx.ring(sx0, cy - 15, 250, 30, 4, 1, Gfx.mix(Th.ln2, Th.ac, searchFocus ? st : 0));
        ModIcons.draw("zoom", sx0 + 9, cy - 7, 14, Th.mut);
        if (search.isEmpty() && !searchFocus) {
            Gfx.text(Gfx.UI, 12, sx0 + 30, cy, Th.mut, "Search modules");
            Gfx.text(Gfx.UI, 10, sx0 + 250 - 12 - Gfx.width(Gfx.UI, 10, "Ctrl F"), cy, Th.mut, "Ctrl F");
        } else {
            String shown = search;
            while (shown.length() > 1 && Gfx.width(Gfx.UI, 12, shown) > 250 - 30 - 40) shown = shown.substring(1);
            float w = Gfx.text(Gfx.UI, 12, sx0 + 30, cy, Th.tx, shown);
            if (searchFocus && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(sx0 + 31 + w, cy - 7, 1.5f, 14, 0, Th.ac2);
        }
        if (!search.isEmpty()) drawX(qx + 11, cy, 3.4f, Gfx.mix(Th.mut, Th.tx, an("sclear", in(qx, cy - 12, 22, 24))));
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

    private static final float CARD_H = 132, GAP = 10, CARD_MIN = 172;

    /** Scrolls the view to the wheel within [0, contentH - viewH]; returns the max scroll. */
    private static float scrollTo(float contentH, float viewH, float wheel) {
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 46));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        return maxS;
    }

    private static void scrollBar(float lx, float top, float lw, float viewH, float contentH, float maxS) {
        if (maxS <= 0) return;
        float th = Math.max(30, viewH * viewH / contentH);
        Gfx.rect(lx + lw - 8, top + (viewH - th) * (scroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
    }

    /**
     * Modules as a card grid (Lunar / Feather style): name + star, big icon, settings cog + ENABLED bar. A card's settings
     * open as their own page (its row as the header, the settings under it); Esc or the header goes back to the grid.
     */
    private static void list(float lx, float top, float lw, float viewH, float wheel) {
        float x = lx + 16, w = lw - 32;
        if (page != null) {
            Module pm = null;
            for (Module m : Modules.all) if (m.id.equals(page)) pm = m;
            if (pm != null) {
                navCols = 1;
                float contentH = 16 + ROW + expH(pm);
                float maxS = scrollTo(contentH, viewH, wheel);
                beginClip(lx, top, lw, viewH);
                float y = top + 8 - scroll, a0 = Gfx.alpha, pk = k(pageT, 0, 0.26f);
                Gfx.alpha = a0 * pk;
                x += (1 - pk) * 40;
                row(pm, x, y, w, focus(x, y, w, ROW), true);
                expand(pm, x, y + ROW, w);
                Gfx.alpha = a0;
                endClip();
                scrollBar(lx, top, lw, viewH, contentH, maxS);
                return;
            }
            page = null;
        }
        List<Module> list = filtered();
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
        int cols = Math.max(2, (int) ((w + GAP) / (CARD_MIN + GAP)));
        float cw = (w - GAP * (cols - 1)) / cols;
        navCols = cols;
        float contentH = 8;
        for (String s : secs) {
            int n = 0;
            for (Module m : list) if ((tab == 1 ? m.cat : TABS[tab]).equals(s)) n++;
            contentH += 30 + ((n + cols - 1) / cols) * (CARD_H + GAP);
        }
        float maxS = scrollTo(contentH, viewH, wheel);
        beginClip(lx, top, lw, viewH);
        float y = top + 8 - scroll, a0 = Gfx.alpha;
        int ci = 0;
        for (String s : secs) {
            int n = 0;
            for (Module m : list) if ((tab == 1 ? m.cat : TABS[tab]).equals(s)) n++;
            float tw = Gfx.text(Gfx.SEMI, 12, x + 4, y + 12, Th.dim, s);
            String c = String.valueOf(n);
            float bw0 = Gfx.width(Gfx.UI, 10, c) + 12;
            Gfx.rect(x + 4 + tw + 10, y + 4, bw0, 16, 8, Th.s3);
            Gfx.textC(Gfx.UI, 10, x + 4 + tw + 10 + bw0 / 2, y + 12, Th.dim, c, 0);
            y += 30;
            int i = 0;
            for (Module m : list) {
                if (!(tab == 1 ? m.cat : TABS[tab]).equals(s)) continue;
                float ck = k(gridT, Math.min(ci++, 14) * 0.025f, 0.26f);
                float cx = x + (i % cols) * (cw + GAP), cy = y + (i / cols) * (CARD_H + GAP) + (1 - ck) * 14;
                boolean f = focus(cx, cy, cw, CARD_H);
                Gfx.alpha = a0 * ck;
                if (f || (cy < top + viewH && cy + CARD_H > top - 4)) card(m, cx, cy, cw, f);
                Gfx.alpha = a0;
                i++;
            }
            y += ((i + cols - 1) / cols) * (CARD_H + GAP);
        }
        endClip();
        scrollBar(lx, top, lw, viewH, contentH, maxS);
    }

    /** One module card: name + star, big icon (faded when off), settings cog, ENABLED / DISABLED bar. Click = toggle, cog / right-click / Enter = settings, Space = toggle. */
    private static void card(Module m, float x, float y, float w, boolean f) {
        boolean on = m.enabled(), lock = m.locked(), hv = in(x, y, w, CARD_H);
        float t = an("r" + m.id, hv || f && kbNav), on1 = an("on" + m.id, on);
        y -= t * 2;
        Gfx.rect(x, y, w, CARD_H, Th.R, Gfx.mix(Th.s2, Th.s3, t * 0.7f));
        Gfx.ring(x, y, w, CARD_H, Th.R, 1, Gfx.mix(Th.ln, Gfx.mix(Th.ln2, Th.ac, on1 * 0.35f), Math.max(t, on1 * 0.6f)));
        float a0 = Gfx.alpha;
        if (lock) Gfx.alpha = a0 * 0.55f;
        // name + star
        boolean fav = m.fav();
        float sx = x + w - 28, sy = y + 6;
        boolean sh = in(sx, sy, 22, 22);
        Gfx.text(Gfx.SEMI, 13, x + 12, y + 17, Gfx.mix(Th.dim, Th.tx, Math.max(t, on1)), fit(m.name, Gfx.SEMI, 13, w - 46, 0));
        if (fav || hv) drawStar(sx + 11, sy + 11, fav, fav ? 0xFFFFB020 : Gfx.mix(Th.mut, Th.tx, an("star" + m.id, sh)));
        boolean starHit = (fav || hv) && hit(sx, sy, 22, 22);
        // icon
        float ic = 32, iy = y + 32 + (CARD_H - 32 - 38 - ic) / 2;
        float a1 = Gfx.alpha;
        Gfx.alpha = a1 * (0.5f + 0.5f * on1);
        boolean item = ModIcons.drawItem(m.id, x + w / 2 - ic / 2, iy, ic);
        Gfx.alpha = a1;
        if (!item && !ModIcons.draw(m.id, x + w / 2 - ic / 2, iy, ic, Gfx.mix(Th.mut, Th.tx, on1)))
            Gfx.textC(Gfx.HEAD, 24, x + w / 2, iy + ic / 2, Th.dim, m.name.substring(0, 1), 0);
        // bottom: cog + status bar
        float by = y + CARD_H - 34, bh = 24, gx = x + 10;
        boolean gh = in(gx, by, bh, bh);
        float gt = an("cog" + m.id, gh);
        Gfx.rect(gx, by, bh, bh, 5, Gfx.a(Th.ov, 0.06f + 0.08f * gt));
        ModIcons.draw("settings", gx + 5, by + 5, 14, Gfx.mix(Th.dim, Th.tx, gt));
        boolean cogHit = hit(gx, by, bh, bh);
        float bx = gx + bh + 6, bw = x + w - 10 - bx;
        float bt = an("tg" + m.id, in(bx, by, bw, bh));
        // ENABLED sits on a darker bevel lip (Minecraft button edge); DISABLED is a flat neutral plate
        if (on1 > 0.01f) Gfx.rect(bx, by + 2, bw, bh, 5, Gfx.a(Gfx.mix(Th.ac, 0xFF000000, 0.45f), on1));
        Gfx.rect(bx, by, bw, bh, 5, Gfx.mix(Gfx.a(Th.ov, 0.06f + 0.05f * bt), Gfx.mix(Th.ac, Th.ac2, bt * 0.6f), on1));
        Gfx.textC(Gfx.SEMI, 10, bx + bw / 2, by + bh / 2, Gfx.mix(Th.dim, Th.onAccent(), on1), lock ? "UNAVAILABLE" : on ? "ENABLED" : "DISABLED", 0.6f);
        Gfx.alpha = a0;
        if (lock && hv && m.lockNote() != null) tip = m.lockNote();
        int u = used(f);
        if (starHit) Config.set(m.id, "fav", !fav);
        else if (cogHit || u == 257 || (clickRt && hv)) { if (clickRt && hv) clickRt = false; ColorPicker.close(); openPage(m.id); }
        else if (!lock && (u == 32 || hit(x, y, w, CARD_H))) { Config.set(m.id, "enabled", !on); Sfx.toggle(!on); }
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
        String why = lock ? m.lockNote() : null; // locked: the reason replaces the description
        if (dr > 60) Gfx.text(Gfx.UI, 11, nx + nw + 12, y + ROW / 2f, why != null ? Th.ac2 : Th.mut, fit(why != null ? why : m.desc, Gfx.UI, 11, dr, 0));
        Gfx.alpha = a0;
        if (lock && in(x, y, w, ROW) && m.lockNote() != null) tip = m.lockNote();
        int u = used(f), a = adjust(f);
        if (!lock && (pillHit || u != 0)) { Config.set(m.id, "enabled", !on); Sfx.toggle(!on); }
        else if (hit(x, y, w, ROW) || a > 0) { commitEdit(); ColorPicker.close(); page = open && a <= 0 ? null : m.id; }
        else if (a < 0 && open) { commitEdit(); ColorPicker.close(); page = null; }
    }

    // ---------------------------------------------------------------- live view editor (fire, shield, hand, mount)

    /** A view module that can be edited over the running game (in a world, its hook in place). */
    static boolean liveOk(Module m) {
        return (m instanceof Views.Fire || m instanceof Views.Shield || m instanceof Views.Hand || m instanceof Views.Ride)
            && Game.player0() != null && !MainMenu.title && m.available();
    }

    private static boolean liveDrag;
    private static float liveX0, liveY0;
    private static double liveA, liveB;
    private static float liveScroll;

    /**
     * The game shows through (no dim, no panel over the middle): a settings sheet on the left, a status chip on top.
     * Dragging anywhere else moves the effect (fire / shield height; hand sideways + height). The fire overlay is
     * forced on while this is open (Hook.fireForce), so it is the game's own overlay, not a picture of it.
     */
    private static void liveEdit(float e, float wheel) {
        Module m = live;
        float pw = 392, px = 20, py = 20, top = 64, foot = 54;
        float rows = 0;
        for (int i = 0; i < m.settings.size(); i++) if (m.row(i)) rows += SROW;
        float ph = Math.min(H - 40, top + rows + foot), view = ph - top - foot;
        float a0 = Gfx.alpha;
        Gfx.rect(px, py + 4, pw, ph, 12, Gfx.a(Gfx.BLACK, 0.35f * e));
        Gfx.rect(px, py, pw, ph, 12, Gfx.a(Th.s1, 0.94f));
        Gfx.ring(px, py, pw, ph, 12, 1, Th.ln2);
        Gfx.rect(px + 18, py + 22, 7, 7, 4, Th.ac);
        Gfx.text(Gfx.UI, 10, px + 32, py + 26, Th.ac2, "LIVE", false, 1.4f);
        Gfx.text(Gfx.SEMI, 15, px + 18, py + 46, Th.tx, m.name);
        boolean on = m.enabled();
        String tl = on ? "ON" : "OFF";
        pill("lv" + m.id, px + pw - 52, py + 38, on);
        Gfx.text(Gfx.UI, 10, px + pw - 60 - Gfx.width(Gfx.UI, 10, tl, 1.2f), py + 47, on ? Th.tx : Th.mut, tl, false, 1.2f);
        if (hit(px + pw - 62, py + 30, 56, 34)) { Config.set(m.id, "enabled", !on); Sfx.toggle(!on); }
        // the rows scroll when they are taller than the screen (Ride View Editor)
        float maxS = Math.max(0, rows - view);
        if (in(px, py, pw, ph)) liveScroll = Math.max(0, Math.min(maxS, liveScroll - wheel * 40));
        liveScroll = Math.min(liveScroll, maxS);
        Gfx.clip(px, py + top, pw, view);
        settingRows(m, px + 20, py + top - liveScroll, pw - 40);
        Gfx.unclip();
        float fy = py + ph - foot + 12;
        Gfx.rect(px + 1, py + ph - foot, pw - 2, 1, 0, Th.ln2);
        if (fbutton("livedone", "DONE", px + 20, fy, 96, 30, true)) live = null;
        if (fbutton("livereset", "RESET", px + 124, fy, 86, 30, false)) { ColorPicker.close(); m.reset(); }

        // status chip, top centre of the game view
        String note = !on ? "Turn it on to see your changes"
            : m instanceof Views.Fire ? "This is the game's own fire  \u00b7  drag up or down to move it"
            : m instanceof Views.Shield ? "Hold a shield to see it  \u00b7  drag up or down to move it"
            : m instanceof Views.Hand ? "Drag to move your hand and held item"
            : "Ride a horse, pig, camel or other mount to see it";
        float cx = px + pw + (W - px - pw) / 2, nw = Gfx.width(Gfx.UI, 12, note) + 36;
        Gfx.rect(cx - nw / 2, 18, nw, 32, 16, 0xD9101014);
        Gfx.ring(cx - nw / 2, 18, nw, 32, 16, 1, Gfx.a(0xFFFFFFFF, 0.12f));
        Gfx.textC(Gfx.UI, 12, cx, 34, 0xF2FFFFFF, note, 0);

        // drag on the game view (outside the sheet) moves the effect, relative to where the drag began
        boolean movable = m instanceof Views.Fire || m instanceof Views.Shield || m instanceof Views.Hand;
        if (movable && click && !in(px, py, pw, ph) && !in(cx - nw / 2, 18, nw, 32)) {
            liveDrag = true; liveX0 = L; liveY0 = T; click = false;
            if (m instanceof Views.Hand) { liveA = m.num("x"); liveB = m.num("y"); }
            else liveA = m.num("height");
        }
        if (liveDrag && !Input.btn[0]) liveDrag = false;
        if (liveDrag) {
            if (m instanceof Views.Hand) {
                Config.set(m.id, "x", Math.round(Math.max(-0.6, Math.min(0.6, liveA + (L - liveX0) / W * 1.2)) * 100) / 100.0);
                Config.set(m.id, "y", Math.round(Math.max(-0.6, Math.min(0.6, liveB - (T - liveY0) / H * 1.2)) * 100) / 100.0);
            } else Config.set(m.id, "height", Math.round(Math.max(-0.5, Math.min(0, liveA - (T - liveY0) / H)) * 100) / 100.0);
        }
        Gfx.alpha = a0;
    }

    // ---------------------------------------------------------------- settings of one module, inline under its row

    private static final float SROW = 46;

    private static float expH(Module m) {
        float h = 10;
        if (m.lockNote() != null) h += 42;
        for (int i = 0; i < m.settings.size(); i++) { Setting s = m.settings.get(i); if (m.row(i)) h += SROW + (s.t == Setting.COLOR && ColorPicker.isOpen(m.id, s.key) ? ColorPicker.H : 0); }
        h += SROW; // the Enabled-free footer: reset buttons
        if (m instanceof Waypoints) h += wpHeight((Waypoints) m);
        if (ViewEditor.has(m)) h += ViewEditor.H + 12;
        if (liveOk(m)) h += 40;
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
        if (ViewEditor.has(m)) { ViewEditor.draw(m, lx, yy + 4, lw, "ve" + m.id); yy += ViewEditor.H + 12; }
        if (liveOk(m)) {
            if (fbutton("live" + m.id, "EDIT LIVE IN GAME", lx, yy + 4, Math.min(lw, 220), 30, true)) { commitEdit(); ColorPicker.close(); live = m; liveDrag = false; }
            yy += 40;
        }
        yy = settingRows(m, lx, yy, lw);
        if (wp != null) { wpList(wp, lx, yy, lw); yy += wpHeight(wp); }
        String rl = "Reset to default";
        float rw = Gfx.width(Gfx.SEMI, 11, rl, 1.2f) + 32;
        if (fbutton("reset" + m.id, rl, lx, yy + 6, rw, 28, false)) { ColorPicker.close(); m.reset(); }
    }

    /** The setting rows of module m from y; returns the y after them. */
    private static float settingRows(Module m, float lx, float y, float lw) {
        float rowH = SROW;
        for (int si = 0; si < m.settings.size(); si++) {
            Setting s = m.settings.get(si);
            if (!m.row(si)) continue;
            float xr = lx + lw, rh = rowH;
            if (s.t == Setting.HEADER) {
                if (s.fold != null) { // folding group: the whole row opens / closes it
                    boolean f = focus(lx - 4, y + 3, lw + 8, rowH - 6), op = Setting.open.contains(s.fold), hv = in(lx - 4, y + 3, lw + 8, rowH - 6);
                    Gfx.text(Gfx.UI, 10, lx, y + rowH - 14, hv ? Th.tx : Th.mut, s.label.toUpperCase(Locale.ROOT), false, 1.2f);
                    chevron(xr - 8, y + rowH - 14, op, hv ? Th.tx : Th.mut);
                    if (hit(lx - 4, y + 3, lw + 8, rowH - 6) || used(f) != 0) { if (op) Setting.open.remove(s.fold); else Setting.open.add(s.fold); }
                } else Gfx.text(Gfx.UI, 10, lx, y + rowH - 14, Th.mut, s.label.toUpperCase(Locale.ROOT), false, 1.2f);
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
                    if (hit(xr - 40, y + 6, 44, 34) || used(f) != 0) { Config.set(m.id, s.key, !v); Sfx.toggle(!v); }
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
    private static final String[][] ACC = {{"#F76A05", "#FF9A2E"}, {"#E5372D", "#FF6157"}, {"#22A845", "#3DDC6A"}, {"#1F6FEB", "#4C8DFF"}, {"#8B3DFF", "#A66BFF"}};

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
        float cx = lx + lw - Th.NAMES.length * 96;
        Gfx.text(Gfx.UI, 10, lx, yy + 31, Th.mut,
            fit("Dark or Light. Your accent is the only colour that changes. Also the launcher.", Gfx.UI, 10, cx - lx - 12, 0));
        for (int i = 0; i < Th.NAMES.length; i++) {
            boolean sel = Th.theme == i, hv = in(cx, yy + 8, 90, 28);
            float t = an("th" + i, sel);
            Gfx.rect(cx, yy + 8, 90, 28, 4, Gfx.a(Th.ac, 0.16f * t));
            Gfx.ring(cx, yy + 8, 90, 28, 4, 1, Gfx.mix(Th.ln2, Th.ac, t));
            Gfx.textC(Gfx.UI, 11, cx + 45, yy + 22, Gfx.mix(Th.dim, Th.tx, Math.max(t, hv ? 1 : 0)), Th.LABELS[i], 0);
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
            boolean sel = ACC[i][0].equalsIgnoreCase(Config.str("client", "accent", "#F76A05"));
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
        if (k != null && Game.player() != null && fbutton("wpadd", "ADD AT MY POSITION", xr - 176, y + 18, 176, 30, true)) w.addHere(false);
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
            float pw0 = Gfx.text(Gfx.MONO, 11, lx + 238, cy, Th.dim, String.format(Locale.ROOT, "%d / %d / %d",
                (long) Waypoints.num(p, "x"), (long) Waypoints.num(p, "y"), (long) Waypoints.num(p, "z")));
            double[] me = Game.reducedDebug() ? null : Game.player();
            if (me != null) {
                double dx = Waypoints.num(p, "x") + 0.5 - me[0], dy = Waypoints.num(p, "y") - me[1], dz = Waypoints.num(p, "z") + 0.5 - me[2];
                long d = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                Gfx.text(Gfx.MONO, 10, lx + 238 + pw0 + 12, cy, Th.mut, d >= 10000 ? (d / 1000) + "k m" : d + " m");
            }
            // edit: name, colour and position in the dialog
            float ex = xr - 36 - 46 - 58;
            boolean eh = in(ex, cy - 12, 48, 24);
            float et = an("wpe" + i, eh);
            Gfx.rect(ex, cy - 12, 48, 24, 6, Gfx.a(Th.ov, 0.035f + 0.05f * et));
            Gfx.ring(ex, cy - 12, 48, 24, 6, 1, Gfx.mix(Th.ln, Th.ln2, et));
            Gfx.textC(Gfx.SEMI, 10, ex + 24, cy, Gfx.mix(Th.dim, Th.tx, et), "EDIT", 1.2f);
            if (hit(ex, cy - 12, 48, 24)) { commitEdit(); WpDialog.edit(p, i); return; }
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
