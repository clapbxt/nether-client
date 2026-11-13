package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WARDROBE tab of the in-game menu: the player (software-rendered, drag to spin), a grid of capes (None, Nether capes,
 * account capes) with Equipped / Wear state, and the nametag icon switch. The launcher owns the accounts and the cape
 * service, so this page only talks to it through files in {@code <client>/wardrobe/}: the launcher writes
 * {@code state.json} (+ the skin and cape PNGs) whenever they change, a click writes {@code request.json} that the
 * running launcher applies and answers with a fresh state.json. No token ever reaches the game.
 */
final class Wardrobe {
    private Wardrobe() {}

    private static final class Cape { String id, name, png, key; boolean account; BufferedImage img; }

    private static final List<Cape> nether = new ArrayList<>(), account = new ArrayList<>();
    private static String netherWorn = "", accountWorn = "", uuid = "";
    private static boolean slim, loaded;
    private static BufferedImage skin;
    private static long mtime = -1, nextCheck, seq;
    /** a click waits for the launcher's answer (state.json newer than this) */
    private static long pendingAt;
    private static float yaw = 0.6f;
    private static boolean dragging;
    private static float dragX;

    private static Path dir() { return Data.dir("wardrobe"); }

    // ---------------------------------------------------------------- state from the launcher

    private static void reload() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 700;
        try {
            Path f = dir().resolve("state.json");
            if (!Files.isRegularFile(f)) { loaded = false; return; }
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m == mtime) return;
            mtime = m;
            Object o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            Map<?, ?> st = (Map<?, ?>) o;
            nether.clear();
            account.clear();
            uuid = str(st.get("uuid"));
            slim = Boolean.TRUE.equals(st.get("slim"));
            netherWorn = str(st.get("netherWorn"));
            accountWorn = str(st.get("accountWorn"));
            skin = read(f.resolveSibling(str(st.get("skin"))));
            fill(st.get("nether"), nether, false);
            fill(st.get("account"), account, true);
            loaded = true;
            if (m >= pendingAt) pendingAt = 0;
        } catch (Throwable t) {
            loaded = false;
        }
    }

    private static String str(Object o) { return o instanceof String ? (String) o : ""; }

    /**
     * The Nether cape the signed-in player wears, from the launcher's state (dashless uuid; null = not this player
     * or no state yet). Capes draws your own cape from this, so it shows the moment you pick it, API or not.
     */
    static String ownNetherCape(String uuidNoDash) {
        reload();
        if (!loaded || uuidNoDash == null || !uuidNoDash.equalsIgnoreCase(uuid.replace("-", ""))) return null;
        return netherWorn;
    }

    private static BufferedImage read(Path p) {
        try { return Files.isRegularFile(p) ? javax.imageio.ImageIO.read(p.toFile()) : null; } catch (Throwable t) { return null; }
    }

    private static void fill(Object list, List<Cape> out, boolean acct) {
        if (!(list instanceof List)) return;
        for (Object e : (List<?>) list) {
            if (!(e instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) e;
            String png = str(m.get("png"));
            if (png.isEmpty() || png.contains("/") || png.contains("\\") || png.contains("..")) continue; // plain file names in our folder only
            Cape c = new Cape();
            c.id = str(m.get("id"));
            c.name = str(m.get("name"));
            c.png = png;
            c.account = acct;
            c.key = "wd.cape." + (acct ? "a." : "n.") + c.id + "." + mtime;
            c.img = read(dir().resolve(png));
            if (c.img != null) out.add(c);
        }
    }

    private static void send(String nid, String aid) {
        try {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq", (double) ++seq);
            if (nid != null) r.put("nether", nid);
            if (aid != null) r.put("account", aid);
            Path tmp = dir().resolve("request.tmp"), f = dir().resolve("request.json");
            Files.write(tmp, Json.write(r).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            pendingAt = System.currentTimeMillis();
            if (nid != null) netherWorn = nid;
            if (aid != null) accountWorn = aid;
        } catch (Throwable t) {
            System.out.println("[nether-agent] wardrobe request failed: " + t);
        }
    }

    // ---------------------------------------------------------------- the player

    private static final int PW = 220, PH = 330;
    private static BufferedImage prev;
    private static float prevYaw = 99;
    private static BufferedImage prevCape;
    private static boolean prevSlim;

    private static void renderPlayer(BufferedImage capeImg) {
        if (skin == null) return;
        if (prev != null && prevYaw == yaw && prevCape == capeImg && prevSlim == slim) return;
        prevYaw = yaw; prevCape = capeImg; prevSlim = slim;
        prev = Player3D.render(skin, slim, capeImg, yaw, PW, PH);
        Gfx.texture("wd.player", prev, true);
    }

    // ---------------------------------------------------------------- the tab

    private static float gridScroll, gridScrollT;

    static void draw(float px, float py, float wheel) {
        reload();
        Menu.navCols = 1;
        float lx = px + 28, top = py + 124, bottom = py + Menu.PH - 24;
        if (!loaded) {
            Gfx.text(Gfx.SEMI, 16, lx, top + 30, Th.tx, "WARDROBE NEEDS THE LAUNCHER");
            for (int i = 0; i < 3; i++) {
                String[] t = {"Capes and your player are managed by the Nether launcher.", "Keep it open, sign in with your Microsoft account, and start the game from it.", "Your capes then appear here."};
                Gfx.text(Gfx.UI, 12, lx, top + 58 + i * 20, Th.dim, t[i]);
            }
            return;
        }
        // hovered / worn cape drives the preview
        Cape worn = null;
        for (Cape c : nether) if (c.id.equals(netherWorn)) worn = c;
        if (worn == null) for (Cape c : account) if (c.id.equals(accountWorn)) worn = c;
        BufferedImage shown = worn == null ? null : worn.img;

        // ---- left: player
        float pw = 250, ph = 336;
        Gfx.rect(lx, top, pw, ph, 8, Th.s1);
        Gfx.ring(lx, top, pw, ph, 8, 1, Th.ln2);
        boolean over = Menu.in(lx, top, pw, ph);
        if (over && Input.btn[0] && !dragging) { dragging = true; dragX = Menu.L; }
        if (!Input.btn[0]) dragging = false;
        if (dragging) { yaw += (Menu.L - dragX) * 0.012f; dragX = Menu.L; }
        else if (!Config.calm()) yaw += Gfx.dt * 0.45f;
        // a hovered tile previews its cape; the preview is rebuilt only when the angle, cape or model changed
        BufferedImage pc = hover != null ? hover : shown;
        if (skin != null) {
            renderPlayer(pc);
            if (Gfx.hasTexture("wd.player")) Gfx.image("wd.player", lx + (pw - PW) / 2f, top + (ph - PH) / 2f, PW, PH, 0xFFFFFFFF, 0);
        } else Gfx.textC(Gfx.SEMI, 11, lx + pw / 2, top + ph / 2, Th.mut, "SKIN NOT SHARED YET", 1.2f);
        Gfx.text(Gfx.MONO, 9, lx + 12, top + 16, Th.mut, "DRAG TO SPIN", false, 1.4f);

        // nametag icon switch
        float sy = top + ph + 12;
        boolean on = Config.bool("client", "nametagIcon", true), hv = Menu.in(lx, sy, pw, 30);
        float t = Menu.an("wdicon", hv), o = Menu.an("wdiconon", on);
        Gfx.rect(lx, sy, pw, 30, 6, Gfx.a(Th.ov, 0.035f + 0.04f * t));
        Gfx.ring(lx, sy, pw, 30, 6, 1, Gfx.mix(Gfx.mix(Th.ln, Th.ln2, t), Gfx.a(Th.ac, 0.7f), o));
        Gfx.text(Gfx.SEMI, 10, lx + 13, sy + 15, Gfx.mix(Th.dim, Th.tx, Math.max(t, o)), "NAMETAG ICON", false, 1.2f);
        float sx = lx + pw - 13 - 36, ssy = sy + 5;
        Gfx.rect(sx, ssy, 36, 20, 10, Gfx.mix(Th.s3, Th.ac, o));
        Gfx.rect(sx + 3 + 16 * o, ssy + 3, 14, 14, 7, Gfx.mix(Th.dim, 0xFFFFFFFF, o));
        if (Menu.hit(lx, sy, pw, 30)) Config.set("client", "nametagIcon", !on);
        if (hv) Menu.tip = "Shows the Nether icon next to other Nether players' names. Your own icon is controlled by the launcher.";

        // ---- right: capes
        float gx = lx + pw + 32, gw = px + Menu.PW - 28 - gx, tw = (gw - 3 * 12) / 4, th = 150, gap = 12;
        hover = null;
        int total = 1 + nether.size() + account.size();
        // rows: None + nether first, then a header, then account capes
        int nRow = (1 + nether.size() + 3) / 4, aRow = (account.size() + 3) / 4;
        float contentH = 26 + nRow * (th + gap) + (account.isEmpty() ? 0 : 30 + aRow * (th + gap));
        float viewH = bottom - top;
        float maxS = Math.max(0, contentH - viewH);
        gridScrollT = Math.max(0, Math.min(maxS, gridScrollT - wheel * 46));
        gridScroll = Config.calm() ? gridScrollT : gridScroll + (gridScrollT - gridScroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(gx - 6, top, gw + 12, viewH);
        float y = top - gridScroll;
        Gfx.text(Gfx.MONO, 9, gx, y + 8, Th.mut, "NETHER CAPES", false, 1.6f);
        y += 26;
        boolean noneWorn = netherWorn.isEmpty() && accountWorn.isEmpty();
        tile(0, null, gx, y, tw, th, noneWorn);
        for (int i = 0; i < nether.size(); i++) {
            int slot = i + 1;
            tile(slot, nether.get(i), gx + (slot % 4) * (tw + gap), y + (slot / 4) * (th + gap), tw, th, nether.get(i).id.equals(netherWorn));
        }
        y += nRow * (th + gap);
        if (!account.isEmpty()) {
            Gfx.text(Gfx.MONO, 9, gx, y + 8, Th.mut, "ACCOUNT CAPES", false, 1.6f);
            y += 30;
            for (int i = 0; i < account.size(); i++)
                tile(100 + i, account.get(i), gx + (i % 4) * (tw + gap), y + (i / 4) * (th + gap), tw, th, account.get(i).id.equals(accountWorn) && !account.get(i).id.isEmpty());
        }
        Menu.endClip();
        if (maxS > 0) {
            float bh = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(px + Menu.PW - 12, top + (viewH - bh) * (gridScroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
        if (total == 1 && account.isEmpty()) Gfx.text(Gfx.UI, 12, gx, top + 40 + th, Th.dim, "No capes yet. Nether capes load in the launcher.");
    }

    private static BufferedImage hover;

    /** One cape tile; c == null is the NONE tile. */
    private static void tile(int slot, Cape c, float x, float y, float w, float h, boolean equipped) {
        boolean hv = Menu.in(x, y, w, h);
        float t = Menu.an("wdt" + slot, hv), o = Menu.an("wdte" + slot, equipped);
        Gfx.rect(x, y, w, h, 8, Gfx.mix(Th.s1, Th.s2, Math.max(t, o * 0.6f)));
        Gfx.ring(x, y, w, h, 8, 1, Gfx.mix(Gfx.mix(Th.ln, Th.ln2, t), Th.ac2, o));
        float iw = 60, ih = 96, ix = x + (w - iw) / 2, iy = y + 14;
        if (c == null) {
            Gfx.ring(ix, iy, iw, ih, 4, 1, Th.ln2);
            Menu.drawX(ix + iw / 2, iy + ih / 2, 9, Th.mut);
        } else {
            if (!Gfx.hasTexture(c.key)) Gfx.texture(c.key, c.img, false);
            // front of a cape: 10 x 16 texels at (1, 1) in the 64 x 32 layout
            Gfx.image(c.key, ix, iy, iw, ih, 1f / 64, 1f / 32, 11f / 64, 17f / 32, 0xFFFFFFFF, 3);
            if (hv) hover = c.img;
        }
        String name = c == null ? "NONE" : Menu.fit(c.name.toUpperCase(java.util.Locale.ROOT), Gfx.SEMI, 10, w - 16, 1f);
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h - 34, equipped ? Th.tx : Th.dim, name, 1f);
        float bw = w - 20, by = y + h - 24;
        Gfx.rect(bx(x, w, bw), by, bw, 18, 5, equipped ? Gfx.a(Th.ac, 0.22f) : Gfx.a(Th.ov, 0.04f + 0.05f * t));
        Gfx.textC(Gfx.MONO, 9, x + w / 2, by + 9, equipped ? Th.ac2 : Gfx.mix(Th.mut, Th.tx, t), equipped ? "EQUIPPED" : "WEAR", 1.4f);
        if (equipped) { // check mark badge
            Gfx.rect(x + w - 24, y + 8, 16, 16, 8, Th.ac);
            Gfx.rect(x + w - 20.5f, y + 15, 3, 1.6f, 0.8f, 0xFFFFFFFF);
            Gfx.rect(x + w - 18.2f, y + 13.6f, 1.6f, 5, 0.8f, 0xFFFFFFFF);
        }
        if (Menu.hit(x, y, w, h)) choose(c);
    }

    private static float bx(float x, float w, float bw) { return x + (w - bw) / 2; }

    private static void choose(Cape c) {
        if (c == null) send("", "");                       // NONE: no Nether cape, account cape hidden
        else if (c.account) send(null, c.id);              // an account cape
        else send(c.id.equals(netherWorn) ? "" : c.id, null); // a Nether cape (again = take it off)
    }
}
