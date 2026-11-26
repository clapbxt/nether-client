package dev.nether.ov;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HUD layout editor. Drag a box (it snaps to the screen edges, the centre lines and the other boxes' edges and centres,
 * shown by red guides; hold Alt to place it freely); Shift/Ctrl-click or drag a frame on empty space to select several
 * and move them together; arrow keys nudge 1 GUI pixel (Shift: 10); the wheel scales; right-click opens Reset position /
 * Hide / Settings; Ctrl+Z / Ctrl+Y undo and redo every move, scale, hide and reset (50 steps). A moved box is pinned to
 * the screen anchor nearest its centre ({@link Modules#setPos}); a reset box goes back to its corner's auto layout.
 * Box positions here are framebuffer pixels; the toolbar and menus use the menu's logical units (Menu.sc).
 */
final class HudEditor {
    private HudEditor() {}

    private static final String[] KEYS = {"anchor", "ox", "oy", "x", "y", "scale", "enabled"};
    private static final int MAX_UNDO = 50;
    private static final ArrayDeque<Map<String, Object>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private static final Set<String> sel = new LinkedHashSet<>();
    private static final Map<String, float[]> start = new HashMap<>();
    /** box being dragged, the right-click menu's box, box under the mouse, the last merged undo step */
    private static String dragMod, ctx, hovId, merge;
    /** layout before the drag (becomes an undo step once something moves) */
    private static Map<String, Object> pending;
    private static boolean marquee, pushed, devHold;
    private static float mx0, my0, ctxX, ctxY, nudgeX, nudgeY, guideX = -1, guideY = -1;
    private static long mergeAt, guideUntil;
    private static String dev = System.getProperty("nether.editDrag");
    private static String[] devParts;

    /** Editor closed: selection and gestures end (undo history stays for the session). */
    static void reset() {
        sel.clear();
        dragMod = ctx = null;
        pending = null;
        marquee = devHold = false;
        nudgeX = nudgeY = 0;
    }

    /** Opens with module id selected (a settings page's EDIT LAYOUT). */
    static void select(String id) { reset(); sel.add(id); }

    /** The layout was replaced (profile applied): old undo steps would bring it back piecemeal. */
    static void forget() { undo.clear(); redo.clear(); merge = null; reset(); }

    // ---------------------------------------------------------------- undo / redo

    private static Map<String, Object> snap() {
        Map<String, Object> s = new HashMap<>();
        for (Module m : Modules.all) if (m.hud) for (String k : KEYS) s.put(m.id + '\0' + k, Config.raw(m.id, k));
        return s;
    }

    private static void restore(Map<String, Object> s) {
        for (Map.Entry<String, Object> e : s.entrySet()) {
            int i = e.getKey().indexOf('\0');
            String id = e.getKey().substring(0, i), k = e.getKey().substring(i + 1);
            Object now = Config.raw(id, k), v = e.getValue();
            if (v == null ? now != null : !v.equals(now)) Config.set(id, k, v);
        }
    }

    /** Remember the layout before a change; repeated nudges / wheel steps on the same boxes merge into one step. */
    private static void push(String mergeKey) {
        long now = System.currentTimeMillis();
        boolean same = mergeKey != null && mergeKey.equals(merge) && now - mergeAt < 900;
        merge = mergeKey;
        mergeAt = now;
        if (!same) push(snap());
    }

    private static void push(Map<String, Object> s) {
        undo.push(s);
        while (undo.size() > MAX_UNDO) undo.removeLast();
        redo.clear();
    }

    private static void undo() { if (undo.isEmpty()) return; redo.push(snap()); restore(undo.pop()); merge = null; }
    private static void redo() { if (redo.isEmpty()) return; undo.push(snap()); restore(redo.pop()); merge = null; }

    // ---------------------------------------------------------------- keys

    static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (ctrl) {
            if (!press) return;
            if (k == 90) { if (shift) redo(); else undo(); }                                              // Ctrl+Z / Ctrl+Shift+Z
            else if (k == 89) redo();                                                                     // Ctrl+Y
            else if (k == 65) { sel.clear(); for (Module m : Modules.all) if (m.hud && m.enabled()) sel.add(m.id); } // Ctrl+A
            return;
        }
        float step = shift ? 10 : 1;
        if (k == 263) nudgeX -= step;
        else if (k == 262) nudgeX += step;
        else if (k == 265) nudgeY -= step;
        else if (k == 264) nudgeY += step;
    }

    /** Esc inside the editor: closes the menu, cancels a drag, clears the selection; false = nothing left (leave the editor). */
    static boolean esc() {
        if (ctx != null) { ctx = null; return true; }
        if (dragMod != null || marquee) {
            if (dragMod != null && pushed && !undo.isEmpty()) restore(undo.pop()); // put the boxes back where the drag began
            dragMod = null;
            marquee = devHold = false;
            return true;
        }
        if (!sel.isEmpty()) { sel.clear(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- snapping

    /** {dx, dy, guide x, guide y} pulling a w x h box at (x, y) onto the nearest target within thr pixels (guide -1: none). */
    private static float[] snapTo(List<Module> els, float x, float y, float w, float h, int fbW, int fbH, float thr) {
        float e = Modules.edge(), g = Gfx.gui;
        List<Float> tx = new ArrayList<>(), ty = new ArrayList<>();
        tx.add(0f); tx.add(e); tx.add(fbW / 2f); tx.add(fbW - e); tx.add((float) fbW);
        ty.add(0f); ty.add(e); ty.add(fbH / 2f); ty.add(fbH - e); ty.add((float) fbH);
        tx.add(fbW / 2f - 91 * g); tx.add(fbW / 2f + 91 * g); ty.add(fbH - 22 * g); // the hotbar's sides and top
        for (Module m : els) {
            if (sel.contains(m.id)) continue;
            float bw = m.w * m.lk, bh = m.h * m.lk;
            tx.add(m.bx); tx.add(m.bx + bw / 2); tx.add(m.bx + bw);
            ty.add(m.by); ty.add(m.by + bh / 2); ty.add(m.by + bh);
        }
        float[] r = {0, 0, -1, -1};
        float bx = thr + 1, by = thr + 1;
        for (float t : tx) for (float off : new float[] {0, w / 2, w}) { float d = t - (x + off); if (Math.abs(d) < Math.abs(bx)) { bx = d; r[2] = t; } }
        for (float t : ty) for (float off : new float[] {0, h / 2, h}) { float d = t - (y + off); if (Math.abs(d) < Math.abs(by)) { by = d; r[3] = t; } }
        if (Math.abs(bx) <= thr) r[0] = bx; else r[2] = -1;
        if (Math.abs(by) <= thr) r[1] = by; else r[3] = -1;
        return r;
    }

    /** Moves every box in ids by (dx, dy) pixels from its start position, as one group kept on screen. */
    private static void moveGroup(List<Module> els, Map<String, float[]> from, float dx, float dy, int fbW, int fbH) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Module m : els) {
            float[] p = from.get(m.id);
            if (p == null) continue;
            x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0] + m.w * m.lk); y1 = Math.max(y1, p[1] + m.h * m.lk);
        }
        if (x0 == Float.MAX_VALUE) return;
        dx = Modules.clamp(dx, -x0, fbW - x1);
        dy = Modules.clamp(dy, -y0, fbH - y1);
        for (Module m : els) { float[] p = from.get(m.id); if (p != null) Modules.setPos(m, p[0] + dx, p[1] + dy, fbW, fbH, true); }
    }

    // ---------------------------------------------------------------- frame

    static void draw(int fbW, int fbH, float wheel, float e) {
        float W = Menu.W, H = Menu.H, sc = Menu.sc;
        boolean shift = Input.keys[340] || Input.keys[344], ctrl = Input.keys[341] || Input.keys[345], alt = Input.keys[342] || Input.keys[346];
        Gfx.s = 1;
        Gfx.rect(0, 0, fbW, fbH, 0, Gfx.a(0xFF050507, 0.55f * e));
        Gfx.rect(fbW / 2f, 0, 1, fbH, 0, Gfx.a(Gfx.ICE, 0.07f));
        Gfx.rect(0, fbH / 2f, fbW, 1, 0, Gfx.a(Gfx.ICE, 0.07f));

        // toolbar first so it wins clicks
        Gfx.s = sc;
        Gfx.minPx = Menu.MIN_PX;
        String h1 = "DRAG TO MOVE  -  SHIFT-CLICK OR DRAG A FRAME TO SELECT SEVERAL  -  ARROWS NUDGE (SHIFT x10)";
        String h2 = "SCROLL TO SCALE  -  HOLD ALT TO PLACE FREELY  -  RIGHT-CLICK FOR OPTIONS  -  CTRL+Z / CTRL+Y";
        float hw = Math.max(Gfx.width(Gfx.MONO, 10, h1, 1.2f), Gfx.width(Gfx.MONO, 10, h2, 1.2f));
        if (hw > W - 64) {
            h1 = "DRAG  -  SHIFT-CLICK SELECTS  -  ARROWS NUDGE";
            h2 = "SCROLL SCALES  -  ALT FREE  -  RIGHT-CLICK MENU";
            hw = Math.max(Gfx.width(Gfx.MONO, 10, h1, 1.2f), Gfx.width(Gfx.MONO, 10, h2, 1.2f));
        }
        float tbw = Math.min(W - 16, Math.max(460, hw + 48)), by = H - 52, tx = W / 2 - tbw / 2;
        Gfx.rect(tx, by - 54, tbw, 100, 10, Gfx.a(Gfx.BG, 0.92f));
        Gfx.ring(tx, by - 54, tbw, 100, 10, 1, Gfx.LN);
        Gfx.textC(Gfx.MONO, 10, W / 2, by - 36, Gfx.DIM, h1, 1.2f);
        Gfx.textC(Gfx.MONO, 10, W / 2, by - 20, Gfx.DIM, h2, 1.2f);
        float bx0 = W / 2 - 214;
        boolean un = tool("hundo", "UNDO", bx0, by, 84, !undo.isEmpty()), re = tool("hredo", "REDO", bx0 + 92, by, 84, !redo.isEmpty());
        boolean done = Menu.button("done", "DONE", bx0 + 196, by, 110, 32, true);
        boolean resetAll = Menu.button("hreset", "RESET ALL", bx0 + 314, by, 110, 32, false);
        if (Menu.in(bx0 + 314, by, 110, 32)) Menu.tip = "Every box back to its corner at scale 1 (undo brings it back)";
        boolean overBar = Menu.L > tx && Menu.L < tx + tbw && Menu.T > by - 54 && Menu.T < by + 46;
        if (un) undo();
        if (re) redo();
        if (done) { Menu.hudEdit = false; reset(); return; }
        if (resetAll) {
            push((String) null);
            for (Module m : Modules.all) if (m.hud) { Config.set(m.id, "anchor", Modules.AUTO); Config.set(m.id, "scale", 1.0); }
        }

        // this frame's boxes
        Modules.place(fbW, fbH);
        List<Module> els = new ArrayList<>();
        for (Module m : Modules.all) if (m.hud && m.placed) els.add(m);
        sel.retainAll(ids(els));
        float mxp = (float) (Input.mx * fbW / Input.ww), myp = (float) (Input.my * fbH / Input.wh);
        devDrag(els, fbW);
        if (devHold) {
            Module dm = Modules.byId(devParts[0]);
            if (dm != null) { mxp = mx0 + devOff(devParts[1], dm, fbW) * Gfx.gui; myp = my0 + Float.parseFloat(devParts[2]) * Gfx.gui; }
        }

        // right-click menu: its own clicks first
        float[] cm = ctx == null ? null : ctxRect();
        boolean overCtx = cm != null && Menu.L >= cm[0] && Menu.L < cm[0] + cm[2] && Menu.T >= cm[1] && Menu.T < cm[1] + cm[3];
        if (cm != null) {
            String[] items = ctxItems();
            for (int i = 0; i < items.length; i++) if (Menu.hit(cm[0], cm[1] + 6 + i * 28, cm[2], 28)) { ctxRun(i, items[i], els, fbW, fbH); break; }
            if (Menu.click || Menu.clickRt) { ctx = null; Menu.click = Menu.clickRt = false; }
        }

        int hov = -1;
        if (!overBar && !overCtx) for (int i = els.size() - 1; i >= 0; i--) {
            Module m = els.get(i);
            if (mxp >= m.bx - 4 && mxp < m.bx + m.w * m.lk + 4 && myp >= m.by - 4 && myp < m.by + m.h * m.lk + 4) { hov = i; break; }
        }
        hovId = hov >= 0 ? els.get(hov).id : null;
        if (Menu.click && !overBar) {
            Menu.click = false;
            if (hov >= 0) {
                String id = hovId;
                if (shift || ctrl) { if (!sel.remove(id)) sel.add(id); }
                else {
                    if (!sel.contains(id)) { sel.clear(); sel.add(id); }
                    beginDrag(id, els, mxp, myp);
                }
            } else {
                marquee = true;
                mx0 = mxp;
                my0 = myp;
                if (!shift && !ctrl) sel.clear();
            }
        }
        if (Menu.clickRt && !overBar && hov >= 0 && dragMod == null) {
            Menu.clickRt = false;
            if (!sel.contains(hovId)) { sel.clear(); sel.add(hovId); }
            ctx = hovId;
            ctxX = Menu.L;
            ctxY = Menu.T;
        }

        // drag (the grabbed box snaps; the rest of the selection follows it)
        float gx = -1, gy = -1;
        if (dragMod != null) {
            Module dm = Modules.byId(dragMod);
            float[] s0 = start.get(dragMod);
            if (dm == null || s0 == null || !dm.placed || (!Input.btn[0] && !devHold)) { dragMod = null; pending = null; }
            else {
                float dx = mxp - mx0, dy = myp - my0;
                if (!pushed && (Math.abs(dx) >= 1 || Math.abs(dy) >= 1)) { push(pending); merge = null; pushed = true; }
                if (pushed) {
                    if (!alt) {
                        float[] sn = snapTo(els, s0[0] + dx, s0[1] + dy, dm.w * dm.lk, dm.h * dm.lk, fbW, fbH, 4 * Gfx.gui);
                        dx += sn[0];
                        dy += sn[1];
                        gx = sn[2];
                        gy = sn[3];
                    }
                    moveGroup(els, start, dx, dy, fbW, fbH);
                }
            }
        }

        // frame selection
        if (marquee && !Input.btn[0]) {
            float x0 = Math.min(mx0, mxp), y0 = Math.min(my0, myp), x1 = Math.max(mx0, mxp), y1 = Math.max(my0, myp);
            if (x1 - x0 > 2 || y1 - y0 > 2)
                for (Module m : els) if (m.bx < x1 && m.bx + m.w * m.lk > x0 && m.by < y1 && m.by + m.h * m.lk > y0) sel.add(m.id);
            marquee = false;
        }

        // arrow keys: the selection (or the box under the mouse) by whole GUI pixels; guides show what it lines up with
        if (nudgeX != 0 || nudgeY != 0) {
            Collection<String> ids = !sel.isEmpty() ? sel : hovId != null ? Collections.singleton(hovId) : Collections.<String>emptySet();
            Map<String, float[]> from = new HashMap<>();
            for (Module m : els) if (ids.contains(m.id)) from.put(m.id, new float[] {m.bx, m.by});
            if (!from.isEmpty() && dragMod == null) {
                push("nudge" + ids);
                moveGroup(els, from, nudgeX * Gfx.gui, nudgeY * Gfx.gui, fbW, fbH);
                Module f = Modules.byId(ids.iterator().next());
                if (f != null) {
                    Set<String> keep = new LinkedHashSet<>(sel);
                    sel.addAll(ids);
                    float[] sn = snapTo(els, f.bx, f.by, f.w * f.lk, f.h * f.lk, fbW, fbH, 0.5f);
                    sel.retainAll(keep);
                    guideX = sn[2];
                    guideY = sn[3];
                    guideUntil = System.currentTimeMillis() + 900;
                }
            }
            nudgeX = nudgeY = 0;
        }
        if (gx < 0 && gy < 0 && System.currentTimeMillis() < guideUntil) { gx = guideX; gy = guideY; }

        // wheel: scale the box under the mouse (and the rest of the selection with it)
        if (wheel != 0 && hovId != null) {
            Collection<String> ids = sel.contains(hovId) ? sel : Collections.singleton(hovId);
            push("scale" + ids);
            for (String id : ids) {
                Module m = Modules.byId(id);
                if (m != null) Config.set(id, "scale", Math.max(0.5, Math.min(2.5, Math.round((m.scale() + wheel * 0.05) / 0.05) * 0.05)));
            }
        }

        // the HUD at its real size, then guides and frames over it
        Gfx.s = 1;
        Gfx.minPx = 0;
        Modules.drawHud(fbW, fbH);
        Gfx.minPx = Menu.MIN_PX;
        Gfx.s = 1;
        if (gx >= 0) Gfx.rect(gx - 0.5f, 0, 1, fbH, 0, Gfx.a(Gfx.RED2, 0.9f));
        if (gy >= 0) Gfx.rect(0, gy - 0.5f, fbW, 1, 0, Gfx.a(Gfx.RED2, 0.9f));
        for (Module m : els) {
            boolean s = sel.contains(m.id), act = m.id.equals(dragMod) || (dragMod == null && m.id.equals(hovId));
            float t = Menu.an("box" + m.id, act || s), bw = m.w * m.lk, bh = m.h * m.lk;
            Gfx.s = 1;
            if (s) Gfx.rect(m.bx - 3, m.by - 3, bw + 6, bh + 6, 0, Gfx.a(Gfx.RED, 0.10f));
            Gfx.ring(m.bx - 3, m.by - 3, bw + 6, bh + 6, 5, s ? 2f : 1.5f, s ? Gfx.RED2 : Gfx.mix(Gfx.a(Gfx.ICE, 0.35f), Gfx.RED2, t));
            String a = Modules.anchor(m);
            boolean auto = Modules.AUTO.equals(a);
            if ((act || s) && !auto && a != null) { // the corner this box keeps when it or the window changes size
                float ax = m.bx - 3 + Modules.af(a, true) * (bw + 6), ay = m.by - 3 + Modules.af(a, false) * (bh + 6);
                Gfx.rect(ax - 3, ay - 3, 6, 6, 0, Gfx.RED2);
            }
            Gfx.s = sc * 0.9f;
            String label = m.name.toUpperCase(Locale.ROOT);
            if (t > 0.5f) label += "   " + String.format(Locale.ROOT, "%.2fx", m.scale()) + "   " + (auto ? "AUTO" : a == null ? "" : Modules.ANCHOR_NAMES[Modules.anchorIdx(a)]);
            float lw = Gfx.width(Gfx.MONO, 9, label, 1.2f) + 14;
            // name tag above the box; at the top edge beside it (so it never sits on the module below), else under it
            float lx = m.bx - 3, ly = m.by - 3 - 18 * Gfx.s;
            if (ly < 4) {
                if (m.bx + bw + 8 + lw * Gfx.s < fbW) { lx = m.bx + bw + 8; ly = m.by - 3; }
                else if (m.bx - 8 - lw * Gfx.s > 0) { lx = m.bx - 8 - lw * Gfx.s; ly = m.by - 3; }
                else ly = m.by + bh + 6;
            }
            Gfx.rect(lx / Gfx.s, ly / Gfx.s, lw, 15, 4, Gfx.mix(Gfx.a(0xFF0B0B0F, 0.9f), Gfx.RED, t));
            Gfx.text(Gfx.MONO, 9, lx / Gfx.s + 7, ly / Gfx.s + 7.5f, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, t), label, false, 1.2f);
        }
        if (marquee) {
            Gfx.s = 1;
            float x0 = Math.min(mx0, mxp), y0 = Math.min(my0, myp);
            Gfx.rect(x0, y0, Math.abs(mxp - mx0), Math.abs(myp - my0), 0, Gfx.a(Gfx.ICE, 0.06f));
            Gfx.ring(x0, y0, Math.abs(mxp - mx0), Math.abs(myp - my0), 0, 1, Gfx.a(Gfx.ICE, 0.5f));
        }
        Gfx.s = sc;
        if (els.isEmpty()) Gfx.textC(Gfx.SEMI, 13, W / 2, H / 2, Gfx.DIM, "NO HUD MODULES ENABLED - TURN SOME ON IN THE MENU", 1.6f);
        if (cm != null && ctx != null) drawCtx(cm);
        if (Menu.tip != null && !overCtx) {
            float tw = Gfx.width(Gfx.UI, 11, Menu.tip) + 24, bx = Math.min(Menu.L + 14, W - tw - 8), ty = Menu.T - 34;
            Gfx.rect(bx, ty, tw, 26, 6, 0xF2101016);
            Gfx.ring(bx, ty, tw, 26, 6, 1, Gfx.LN2);
            Gfx.text(Gfx.UI, 11, bx + 12, ty + 13, Gfx.DIM, Menu.tip);
        }
    }

    private static Set<String> ids(List<Module> els) {
        Set<String> s = new LinkedHashSet<>();
        for (Module m : els) s.add(m.id);
        return s;
    }

    private static void beginDrag(String id, List<Module> els, float mx, float my) {
        dragMod = id;
        mx0 = mx;
        my0 = my;
        start.clear();
        for (Module m : els) if (sel.contains(m.id)) start.put(m.id, new float[] {m.bx, m.by});
        pending = snap();
        pushed = false;
    }

    /** Toolbar button that greys out when it has nothing to do. */
    private static boolean tool(String id, String label, float x, float y, float w, boolean on) {
        float a0 = Gfx.alpha;
        if (!on) Gfx.alpha = a0 * 0.4f;
        boolean r = Menu.button(id, label, x, y, w, 32, false);
        Gfx.alpha = a0;
        return r && on;
    }

    // ---------------------------------------------------------------- right-click menu

    private static String[] ctxItems() {
        return sel.size() > 1 ? new String[] {"RESET POSITION", "HIDE"} : new String[] {"RESET POSITION", "HIDE", "SETTINGS"};
    }

    private static float[] ctxRect() {
        float w = 168, h = 12 + ctxItems().length * 28;
        return new float[] {Math.min(ctxX, Menu.W - w - 4), Math.min(ctxY, Menu.H - h - 4), w, h};
    }

    private static void ctxRun(int i, String item, List<Module> els, int fbW, int fbH) {
        Collection<String> ids = sel.isEmpty() ? Collections.singleton(ctx) : new ArrayList<>(sel);
        if (i == 2) { // SETTINGS: leave the editor on that module's page
            Menu.hudEdit = false;
            Menu.page = ctx;
            reset();
            return;
        }
        push((String) null);
        for (String id : ids) {
            if (i == 0) { Config.set(id, "anchor", Modules.AUTO); Config.set(id, "scale", 1.0); }
            else Config.set(id, "enabled", false);
        }
        if (i == 1) sel.clear();
        ctx = null;
    }

    private static void drawCtx(float[] r) {
        Gfx.rect(r[0], r[1], r[2], r[3], 8, 0xF50D0D11);
        Gfx.ring(r[0], r[1], r[2], r[3], 8, 1, Gfx.LN2);
        String[] items = ctxItems();
        for (int i = 0; i < items.length; i++) {
            float y = r[1] + 6 + i * 28;
            float t = Menu.an("ctx" + i, Menu.in(r[0], y, r[2], 28));
            if (t > 0.02f) Gfx.rect(r[0] + 4, y, r[2] - 8, 28, 4, Gfx.a(Gfx.RED, 0.85f * t));
            Gfx.text(Gfx.SEMI, 11, r[0] + 16, y + 14, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, t), items[i] + (i < 2 && sel.size() > 1 ? " (" + sel.size() + ")" : ""), false, 1.2f);
        }
    }

    // ---------------------------------------------------------------- dev

    /** Dev: -Dnether.editDrag=<id>:<dx|c>:<dy> grabs that box and holds it moved by GUI pixels (c = its centre onto the
     *  screen's, 2 px off so the snap shows), for snapshots of the guides. */
    private static void devDrag(List<Module> els, int fbW) {
        if (dev == null || devHold) return;
        String[] d = dev.split(":");
        if (d.length < 3) { dev = null; return; }
        for (Module m : els) if (m.id.equals(d[0])) {
            sel.clear();
            sel.add(m.id);
            beginDrag(m.id, els, m.bx + 4, m.by + 4);
            devHold = true;
            devParts = d;
            dev = null;
            return;
        }
    }

    private static float devOff(String v, Module m, int fbW) {
        if (!v.equals("c")) return Float.parseFloat(v);
        float[] s0 = start.get(m.id);
        return s0 == null ? 0 : (fbW / 2f - (s0[0] + m.w * m.lk / 2)) / Gfx.gui + 2;
    }
}
