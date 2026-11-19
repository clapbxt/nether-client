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
 * WARDROBE tab of the in-game menu: the player (software-rendered with the picked cape and cosmetics, drag to spin), a
 * grid of capes (None, Nether capes, account capes) and of every cosmetic slot. Clicks only pick (the preview shows the
 * picks); APPLY sends the whole look at once, REFRESH asks for new capes / cosmetics, UPLOAD hands files picked here to the
 * launcher, which converts and uploads them as a community item (same as its own UPLOAD). The launcher owns the accounts and
 * the services, so this page only talks to it through files in {@code <client>/wardrobe/}: the launcher writes
 * {@code state.json} (+ the skin and cape PNGs, and an "answer" to the last request), a button writes {@code request.json}
 * that the running launcher applies. No token ever reaches the game.
 */
final class Wardrobe {
    private Wardrobe() {}

    private static final class Cape { String id, name, png, key; boolean account; BufferedImage img; }
    /** a Nether cosmetic card (the launcher renders the picture) */
    private static final class Cos { String id, name, type, key; BufferedImage img; }
    private static final List<Cos> cosmetics = new ArrayList<>();
    /** shown category: 0 capes, 1.. = Cosmetics.SLOTS + 1 */
    private static int cat;
    private static final String[] CATS = {"CAPES", "HATS", "HEADBANDS", "MASKS", "BACK", "WINGS", "AURAS", "PETS"};
    private static final String[] KINDS = {"Hat", "Headband", "Mask", "Back item", "Wings", "Aura", "Pet"};

    private static final List<Cape> nether = new ArrayList<>(), account = new ArrayList<>();
    /** applied (from the launcher) */
    private static String netherWorn = "", accountWorn = "", uuid = "";
    private static final String[] worn = {"", "", "", "", "", "", ""};
    /** picked here, not applied yet */
    private static String pNether = "", pAccount = "";
    private static final String[] picked = {"", "", "", "", "", "", ""};
    private static boolean slim, loaded;
    private static BufferedImage skin;
    private static long mtime = -1, nextCheck;
    /** the request waiting for the launcher (seq, 0 = none), when it was sent; refresh waits for a new state.json */
    private static double pendingSeq;
    private static long pendingAt, refreshAt, pendingLimit = 20000;
    /** UPLOAD: files picked in the dialog (waiting for the rules confirm), the dialog being open */
    private static volatile List<String> upFiles;
    private static volatile boolean upPicking;
    private static String note = "";
    private static boolean noteOk;
    private static long noteAt;
    private static float yaw = 0.6f;
    private static boolean dragging;
    private static float dragX;

    private static Path dir() { return Data.dir("wardrobe"); }

    private static boolean dirty() {
        if (!pNether.equals(netherWorn) || !pAccount.equals(accountWorn)) return true;
        for (int i = 0; i < worn.length; i++) if (!picked[i].equals(worn[i])) return true;
        return false;
    }

    private static boolean capeDirty() { return !pNether.equals(netherWorn) || !pAccount.equals(accountWorn); }

    /** picks that differ from what is applied (the cape counts once) */
    private static int changes() {
        int n = capeDirty() ? 1 : 0;
        for (int i = 0; i < worn.length; i++) if (!picked[i].equals(worn[i])) n++;
        return n;
    }

    private static void pickApplied() {
        pNether = netherWorn;
        pAccount = accountWorn;
        System.arraycopy(worn, 0, picked, 0, worn.length);
    }

    private static void say(String s, boolean ok) { note = s; noteOk = ok; noteAt = System.currentTimeMillis(); }

    // ---------------------------------------------------------------- state from the launcher

    private static void reload() {
        long now = System.currentTimeMillis();
        if (pendingSeq != 0 && now - pendingAt > pendingLimit) { pendingSeq = 0; say("The launcher didn't answer. Is it still open?", false); }
        if (refreshAt != 0 && now - refreshAt > 20000) refreshAt = 0;
        if (now < nextCheck) return;
        nextCheck = now + 500;
        try {
            Path f = dir().resolve("state.json");
            if (!Files.isRegularFile(f)) { loaded = false; return; }
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m == mtime) return;
            mtime = m;
            Object o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            Map<?, ?> st = (Map<?, ?>) o;
            boolean clean = !loaded || !dirty();
            nether.clear();
            account.clear();
            uuid = str(st.get("uuid"));
            slim = Boolean.TRUE.equals(st.get("slim"));
            netherWorn = str(st.get("netherWorn"));
            accountWorn = str(st.get("accountWorn"));
            Map<?, ?> cw = st.get("cosmetics") instanceof Map ? (Map<?, ?>) st.get("cosmetics") : null;
            for (int i = 0; i < worn.length; i++) worn[i] = cw == null ? "" : str(cw.get(Cosmetics.SLOTS[i]));
            loadCosmetics(st.get("items"));
            skin = read(f.resolveSibling(str(st.get("skin"))));
            fill(st.get("nether"), nether, false);
            fill(st.get("account"), account, true);
            loaded = true;
            if (refreshAt != 0 && m >= refreshAt) { refreshAt = 0; say("Up to date", true); }
            Map<?, ?> an = st.get("answer") instanceof Map ? (Map<?, ?>) st.get("answer") : null;
            if (an != null && pendingSeq != 0 && an.get("seq") instanceof Number && ((Number) an.get("seq")).doubleValue() == pendingSeq) {
                pendingSeq = 0;
                boolean ok = Boolean.TRUE.equals(an.get("ok"));
                String msg = str(an.get("msg"));
                say(msg.isEmpty() ? (ok ? "Look applied" : "Couldn't apply") : msg, ok);
                if (ok) clean = true;
            }
            if (clean) pickApplied();
        } catch (Throwable t) {
            loaded = false;
        }
    }

    private static String str(Object o) { return o instanceof String ? (String) o : ""; }

    /**
     * The Nether cape the signed-in player wears, from the launcher's state (dashless uuid; null = not this player
     * or no state yet). Capes draws your own cape from this, so it shows the moment it is applied, API or not.
     */
    static String ownNetherCape(String uuidNoDash) {
        reload();
        if (!loaded || uuidNoDash == null || !uuidNoDash.equalsIgnoreCase(uuid.replace("-", ""))) return null;
        return netherWorn;
    }

    /** Your worn cosmetics (dashless uuid) from the launcher's state, null = not this player / no state. */
    static String[] ownLook(String uuidNoDash) {
        reload();
        if (!loaded || uuidNoDash == null || !uuidNoDash.equalsIgnoreCase(uuid.replace("-", ""))) return null;
        String[] l = new String[worn.length];
        boolean any = false;
        for (int i = 0; i < worn.length; i++) { l[i] = worn[i].isEmpty() ? null : worn[i]; any |= l[i] != null; }
        return any ? l : null;
    }

    private static BufferedImage read(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try (java.io.InputStream in = Files.newInputStream(p)) { return Icons.decode(in); } catch (Throwable t) { return null; }
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

    private static void loadCosmetics(Object list) {
        cosmetics.clear();
        if (!(list instanceof List)) return;
        for (Object e : (List<?>) list) {
            if (!(e instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) e;
            String png = str(m.get("png"));
            if (png.isEmpty() || png.contains("/") || png.contains("\\") || png.contains("..")) continue; // plain file names in our folder only
            Cos c = new Cos();
            c.id = str(m.get("id"));
            c.name = str(m.get("name"));
            c.type = str(m.get("type"));
            c.key = "wd.cos." + c.id + "." + mtime;
            c.img = read(dir().resolve(png));
            if (c.img != null) cosmetics.add(c);
        }
    }

    // ---------------------------------------------------------------- requests to the launcher

    private static boolean request(Map<String, Object> r) {
        try {
            Path tmp = dir().resolve("request.tmp"), f = dir().resolve("request.json");
            Files.write(tmp, Json.write(r).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable t) {
            System.out.println("[nether-agent] wardrobe request failed: " + t);
            say("Couldn't reach the launcher", false);
            return false;
        }
    }

    /** APPLY: the whole picked look in one request (the launcher sends only what changed). */
    private static void apply() {
        Map<String, Object> look = new LinkedHashMap<>();
        look.put("nether", pNether);
        look.put("account", pAccount);
        Map<String, Object> c = new LinkedHashMap<>();
        for (int i = 0; i < picked.length; i++) c.put(Cosmetics.SLOTS[i], picked[i]);
        look.put("cosmetics", c);
        Map<String, Object> r = new LinkedHashMap<>();
        double seq = System.currentTimeMillis();
        r.put("seq", seq);
        r.put("look", look);
        if (request(r)) { pendingSeq = seq; pendingAt = System.currentTimeMillis(); pendingLimit = 20000; note = ""; }
    }

    /** the shown category as a community type: cape, or the cosmetic slot */
    private static String catType() { return cat == 0 ? "cape" : Cosmetics.SLOTS[cat - 1]; }

    /** UPLOAD: the file dialog runs on its own thread (it blocks); the picked files then wait for the rules confirm. */
    private static void pickUpload() {
        if (upPicking || pendingSeq != 0) return;
        if (uuid.isEmpty()) { say("Sign in with Microsoft in the launcher to upload", false); return; }
        upPicking = true;
        Thread t = new Thread(() -> {
            try {
                List<String> f = FilePick.pick("Upload to Nether: model + texture, an image, or a zip");
                if (f == null) say("No file dialog here: use UPLOAD in the launcher's Wardrobe", false);
                else if (!f.isEmpty()) upFiles = f.size() > 20 ? new ArrayList<>(f.subList(0, 20)) : f;
            } finally { upPicking = false; }
        }, "nether-upload-pick");
        t.setDaemon(true);
        t.start();
    }

    /** The rules were confirmed: the launcher converts, checks and uploads the files, then answers. */
    private static void sendUpload() {
        List<String> f = upFiles;
        upFiles = null;
        if (f == null || f.isEmpty()) return;
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("type", catType());
        up.put("files", new ArrayList<Object>(f));
        Map<String, Object> r = new LinkedHashMap<>();
        double seq = System.currentTimeMillis();
        r.put("seq", seq);
        r.put("upload", up);
        if (request(r)) { pendingSeq = seq; pendingAt = System.currentTimeMillis(); pendingLimit = 150000; say("Uploading... the launcher is converting and checking it", true); }
    }

    private static void refresh() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("seq", (double) System.currentTimeMillis());
        r.put("refresh", true);
        if (request(r)) { refreshAt = System.currentTimeMillis(); note = ""; }
    }

    /** An outlined button with an icon and a label (dimmed while off). */
    private static boolean smallBtn(String id, String icon, String label, float x, float y, float w, boolean on) {
        boolean hv = on && Menu.in(x, y, w, 30);
        float t = Menu.an(id, hv);
        Gfx.rect(x, y, w, 30, 6, Gfx.a(Th.ov, 0.035f + 0.05f * t));
        Gfx.ring(x, y, w, 30, 6, 1, Gfx.mix(Th.ln, Th.ln2, t));
        int c = on ? Gfx.mix(Th.dim, Th.tx, t) : Th.mut;
        float lw = Gfx.width(Gfx.SEMI, 11, label, 1.2f), ix = x + (w - lw - 22) / 2;
        if (ModIcons.draw(icon, ix, y + 8, 14, c)) Gfx.text(Gfx.SEMI, 11, ix + 22, y + 15, c, label, false, 1.2f);
        else Gfx.textC(Gfx.SEMI, 11, x + w / 2, y + 15, c, label, 1.2f);
        return on && Menu.hit(x, y, w, 30);
    }

    private static final String[] UP_AS = {"A CAPE", "A HAT", "A HEADBAND", "A MASK", "A BACK ITEM", "WINGS", "AN AURA", "A PET"};

    /** The rules card over the player: what is about to go up and the confirm. */
    private static void confirmUpload(float x, float y, float w, float h) {
        List<String> f = upFiles;
        if (f == null) return;
        Gfx.rect(x, y, w, h, 8, Gfx.a(Th.s1, 0.97f));
        Gfx.ring(x, y, w, h, 8, 1, Gfx.a(Th.ac2, 0.55f));
        float tx = x + 16, ty = y + 26, tw = w - 32;
        Gfx.text(Gfx.SEMI, 12, tx, ty, Th.tx, "UPLOAD AS " + UP_AS[cat], false, 1.2f);
        String first = new java.io.File(f.get(0)).getName();
        Gfx.text(Gfx.UI, 11, tx, ty + 22, Th.dim, Menu.fit(f.size() == 1 ? first : f.size() + " files: " + first + ", ...", Gfx.UI, 11, tw, 0));
        String[] rules = {"Everyone can see and wear it.", "No nudity, hate symbols or gore,", "and only things you may share.", "Breaking the rules removes it and", "can turn uploading off."};
        for (int i = 0; i < rules.length; i++) Gfx.text(Gfx.UI, 11, tx, ty + 50 + i * 17, Th.mut, rules[i]);
        float byy = y + h - 46, ow = tw * 0.62f;
        if (Menu.button("wdupok", "I AGREE, UPLOAD", tx, byy, ow, 30, true)) sendUpload();
        if (Menu.button("wdupno", "CANCEL", tx + ow + 8, byy, tw - ow - 8, 30, false)) upFiles = null;
    }

    // ---------------------------------------------------------------- the player

    private static final int PW = 176, PH = 254;
    private static BufferedImage prev;
    private static String prevKey = "";

    /** The preview, rebuilt only when the angle, cape, model or cosmetics changed (or the cosmetics move). */
    private static void renderPlayer(BufferedImage capeImg, Cosmetics.Item[] cos) {
        if (skin == null) return;
        StringBuilder k = new StringBuilder().append(yaw).append(System.identityHashCode(capeImg)).append(slim);
        for (Cosmetics.Item it : cos) k.append(',').append(it == null ? "" : it.e.id);
        boolean moving = !Config.calm() && Player3D.animated(cos);
        String key = k.toString();
        if (prev != null && !moving && key.equals(prevKey)) return;
        prevKey = key;
        prev = Player3D.render(skin, slim, capeImg, cos, Cosmetics.clock(), yaw, PW, PH);
        Gfx.texture("wd.player", prev, true);
    }

    // ---------------------------------------------------------------- the tab

    private static float gridScroll, gridScrollT;
    private static BufferedImage hover;
    /** hovered cosmetic card: slot and id ("" = the None card), -1 = none */
    private static int hoverSlot = -1;
    private static String hoverCos = "";

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
        // the picked cape (a hovered tile previews its own)
        Cape pc = null;
        for (Cape c : nether) if (c.id.equals(pNether)) pc = c;
        if (pc == null && pNether.isEmpty()) for (Cape c : account) if (c.id.equals(pAccount)) pc = c;
        BufferedImage shownCape = hover != null ? hover : pc == null ? null : pc.img;
        // the picked cosmetics, a hovered card replacing its slot
        Cosmetics.Item[] cos = new Cosmetics.Item[picked.length];
        for (int i = 0; i < picked.length; i++) cos[i] = Cosmetics.preview(hoverSlot == i ? hoverCos : picked[i], Cosmetics.SLOTS[i]);

        // ---- left: player
        float pw = 250, ph = 258;
        boolean dirty = dirty();
        float dt = Menu.an("wddirty", dirty);
        Gfx.rectG(lx, top, pw, ph, 8, Th.s2, Th.s1);
        Gfx.ring(lx, top, pw, ph, 8, 1, Gfx.mix(Th.ln2, Gfx.a(Th.ac2, 0.55f), dt));
        boolean over = Menu.in(lx, top, pw, ph);
        if (over && Input.btn[0] && !dragging) { dragging = true; dragX = Menu.L; }
        if (!Input.btn[0]) dragging = false;
        if (dragging) { yaw += (Menu.L - dragX) * 0.012f; dragX = Menu.L; }
        else if (!Config.calm()) yaw += Gfx.dt * 0.45f;
        if (skin != null) {
            renderPlayer(shownCape, cos);
            if (Gfx.hasTexture("wd.player")) Gfx.image("wd.player", lx + (pw - PW) / 2f, top + (ph - PH) / 2f, PW, PH, 0xFFFFFFFF, 0);
        } else Gfx.textC(Gfx.SEMI, 11, lx + pw / 2, top + ph / 2, Th.mut, "SKIN NOT SHARED YET", 1.2f);
        if (dirty) {
            int n = changes();
            String u = n + (n == 1 ? " UNSAVED CHANGE" : " UNSAVED CHANGES");
            float uw = Gfx.width(Gfx.MONO, 9, u, 1.4f) + 24;
            Gfx.rect(lx + 10, top + 8, uw, 18, 9, Gfx.a(Th.ac, 0.22f));
            Gfx.rect(lx + 17, top + 15, 4, 4, 2, Th.ac2);
            Gfx.text(Gfx.MONO, 9, lx + 26, top + 17, Th.ac2, u, false, 1.4f);
        } else Gfx.text(Gfx.MONO, 9, lx + 12, top + 16, Th.mut, "DRAG TO SPIN", false, 1.4f);
        long age = System.currentTimeMillis() - noteAt;
        if (!note.isEmpty() && age < 5000) {
            float a = Config.calm() ? 1 : Math.min(1, (5000 - age) / 400f);
            String n = Menu.fit(note, Gfx.UI, 11, pw - 24, 0);
            Gfx.text(Gfx.UI, 11, lx + 12, top + ph - 14, Gfx.a(noteOk ? 0xFF3DDC84 : Th.ac2, a), n);
        } else if (Game.mc() != null && !Cosmetics.renders()) { // the game cannot draw them: say so instead of failing quietly
            String v = Game.version();
            Gfx.text(Gfx.UI, 11, lx + 12, top + ph - 14, Th.ac2, Menu.fit("Cosmetics can't render on " + (v != null ? "Minecraft " + v : "this version") + ", capes still show", Gfx.UI, 11, pw - 24, 0));
        }

        // the Nether badge (always on: shown before Nether players' names above their heads and in chat)
        float sy = top + ph + 10;
        boolean hv = Menu.in(lx, sy, pw, 30);
        Gfx.rect(lx, sy, pw, 30, 6, Gfx.a(Th.ov, 0.035f));
        Gfx.ring(lx, sy, pw, 30, 6, 1, Th.ln);
        float bx = lx + 12;
        if (Capes.badge(bx, sy + 7, 16)) bx += Capes.badgeWidth(16) + 8;
        Gfx.text(Gfx.SEMI, 10, bx, sy + 15, Th.tx, "NETHER BADGE", false, 1.2f);
        String bn = "NAMETAG + CHAT";
        Gfx.text(Gfx.MONO, 9, lx + pw - 12 - Gfx.width(Gfx.MONO, 9, bn, 0.8f), sy + 15, Th.mut, bn, false, 0.8f);
        if (hv) Menu.tip = "Every Nether player gets the badge before their name, above their head and in chat.";

        // APPLY (dimmed while nothing changed) + REVERT, then REFRESH + UPLOAD
        boolean busy = pendingSeq != 0, can = dirty && !busy;
        float ay = sy + 40, vw = can ? 74 : 0, aw = pw - (can ? vw + 8 : 0);
        String al = busy ? (pendingLimit > 20000 ? "UPLOADING..." : "APPLYING...") : can ? "APPLY" : "APPLIED";
        if (can) { if (Menu.button("wdapply", al, lx, ay, aw, 30, true)) apply(); }
        else {
            Gfx.rect(lx, ay, aw, 30, 6, Gfx.a(Th.ac, 0.16f));
            Gfx.textC(Gfx.SEMI, 11, lx + aw / 2, ay + 15, Gfx.a(0xFFFFFFFF, 0.45f), al, 1.2f);
        }
        if (can) {
            if (Menu.button("wdrev", "REVERT", lx + aw + 8, ay, vw, 30, false)) { pickApplied(); say("Changes reverted", true); }
            if (Menu.in(lx + aw + 8, ay, vw, 30)) Menu.tip = "Go back to the look you have on now";
        }
        float by2 = ay + 38, hw = (pw - 8) / 2;
        boolean refreshing = refreshAt != 0;
        if (smallBtn("wdref", "refresh", refreshing ? "CHECKING..." : "REFRESH", lx, by2, hw, !refreshing)) refresh();
        if (Menu.in(lx, by2, hw, 30)) Menu.tip = refreshing ? "Checking for new capes and cosmetics..." : "Check for new capes, cosmetics and your uploads";
        boolean upBusy = upPicking || busy || upFiles != null;
        if (smallBtn("wdup", "upload", upPicking ? "CHOOSING..." : "UPLOAD", lx + hw + 8, by2, hw, !upBusy)) pickUpload();
        if (Menu.in(lx + hw + 8, by2, hw, 30)) Menu.tip = "Upload your own " + (cat == 0 ? "cape" : KINDS[cat - 1].toLowerCase()) + " for everyone: Blockbench, Bedrock, Lunar files, .obj, PNG or a zip";
        if (upFiles != null) confirmUpload(lx, top, pw, ph);

        // ---- right: category tabs, then capes or cosmetic cards
        float gx = lx + pw + 32, gw = px + Menu.PW - 28 - gx, tw = (gw - 3 * 12) / 4, th = 150, gap = 12;
        hover = null;
        hoverSlot = -1;
        float tx0 = gx, natural = 0;
        for (String c : CATS) natural += Gfx.width(Gfx.SEMI, 10, c, 1.2f) + 30;
        float pad = Math.max(10, 24 - Math.max(0, natural - gw) / CATS.length); // tabs tighten to fit the width
        for (int i = 0; i < CATS.length; i++) {
            float w = Gfx.width(Gfx.SEMI, 10, CATS[i], 1.2f) + pad;
            boolean on = cat == i, hv2 = Menu.in(tx0, top, w, 24);
            float a = Menu.an("wdc" + i, on), hh = Menu.an("wdch" + i, hv2);
            Gfx.rect(tx0, top, w, 24, 12, on ? Gfx.a(Th.ac, 0.2f + 0.05f * hh) : Gfx.a(Th.ov, 0.03f + 0.05f * hh));
            if (on) Gfx.ring(tx0, top, w, 24, 12, 1, Gfx.a(Th.ac2, 0.6f));
            Gfx.textC(Gfx.SEMI, 10, tx0 + w / 2, top + 12, Gfx.mix(Gfx.mix(Th.mut, Th.tx, hh), Th.ac2, a), CATS[i], 1.2f);
            if (i == 0 ? capeDirty() : !picked[i - 1].equals(worn[i - 1])) Gfx.rect(tx0 + w - 9, top + 4, 5, 5, 2.5f, Th.ac2); // unsaved pick here
            if (Menu.hit(tx0, top, w, 24) && cat != i) { cat = i; gridScrollT = gridScroll = 0; }
            tx0 += w + 6;
        }
        top += 36;
        if (cat > 0) { cosGrid(gx, gw, top, bottom, wheel); return; }
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
        tile(0, null, gx, y, tw, th, pNether.isEmpty() && pAccount.isEmpty(), netherWorn.isEmpty() && accountWorn.isEmpty());
        for (int i = 0; i < nether.size(); i++) {
            int slot = i + 1;
            Cape c = nether.get(i);
            tile(slot, c, gx + (slot % 4) * (tw + gap), y + (slot / 4) * (th + gap), tw, th, c.id.equals(pNether), c.id.equals(netherWorn));
        }
        y += nRow * (th + gap);
        if (!account.isEmpty()) {
            Gfx.text(Gfx.MONO, 9, gx, y + 8, Th.mut, "ACCOUNT CAPES", false, 1.6f);
            y += 30;
            for (int i = 0; i < account.size(); i++) {
                Cape c = account.get(i);
                tile(100 + i, c, gx + (i % 4) * (tw + gap), y + (i / 4) * (th + gap), tw, th, pNether.isEmpty() && c.id.equals(pAccount), c.id.equals(accountWorn));
            }
        }
        Menu.endClip();
        if (maxS > 0) {
            float bh = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(px + Menu.PW - 12, top + (viewH - bh) * (gridScroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
        if (total == 1 && account.isEmpty()) Gfx.text(Gfx.UI, 12, gx, top + 40 + th, Th.dim, "No capes yet. Nether capes load in the launcher.");
    }

    /** Cards of one cosmetic slot: None first, then every cosmetic of that type (picture rendered by the launcher). */
    private static void cosGrid(float gx, float gw, float top, float bottom, float wheel) {
        int si = cat - 1;
        String slot = Cosmetics.SLOTS[si];
        List<Cos> list = new ArrayList<>();
        for (Cos c : cosmetics) if (slot.equals(c.type)) list.add(c);
        float gap = 12, tw = (gw - 3 * gap) / 4, th = tw * 1.3f + 34;
        int rows = (list.size() + 1 + 3) / 4;
        float viewH = bottom - top, contentH = rows * (th + gap);
        float maxS = Math.max(0, contentH - viewH);
        gridScrollT = Math.max(0, Math.min(maxS, gridScrollT - wheel * 46));
        gridScroll = Config.calm() ? gridScrollT : gridScroll + (gridScrollT - gridScroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(gx - 6, top, gw + 12, viewH);
        for (int i = 0; i <= list.size(); i++) {
            Cos c = i == 0 ? null : list.get(i - 1);
            float x = gx + (i % 4) * (tw + gap), y = top - gridScroll + (i / 4) * (th + gap);
            if (y > bottom || y + th < top) continue;
            String cid = c == null ? "" : c.id;
            boolean eq = cid.equals(picked[si]), on = cid.equals(worn[si]);
            boolean hv = Menu.in(x, y, tw, th) && Menu.in(gx - 6, top, gw + 12, viewH);
            if (hv) { hoverSlot = si; hoverCos = cid; }
            float t = Menu.an("wdk" + cat + "." + i, hv), o = Menu.an("wdke" + cat + "." + i, eq);
            Gfx.rect(x, y, tw, th, 8, Gfx.mix(Th.s1, Th.s2, Math.max(t, o * 0.6f)));
            Gfx.ring(x, y, tw, th, 8, 1, Gfx.mix(Gfx.mix(Th.ln, Th.ln2, t), Th.ac2, o));
            float ih = th - 40;
            if (c == null) {
                Menu.drawX(x + tw / 2, y + ih / 2 + 4, 10, Th.mut);
            } else {
                if (!Gfx.hasTexture(c.key)) Gfx.texture(c.key, c.img, true);
                float iw = Math.min(tw - 8, ih * c.img.getWidth() / (float) c.img.getHeight());
                Gfx.image(c.key, x + (tw - iw) / 2, y + 4, iw, ih, 0xFFFFFFFF, 6);
            }
            String name = c == null ? "NONE" : Menu.fit(c.name, Gfx.SEMI, 11, tw - 16, 0.4f);
            Gfx.text(Gfx.SEMI, 11, x + 9, y + th - 26, eq ? Th.tx : Gfx.mix(Th.dim, Th.tx, t), name, false, 0.4f);
            String sub = eq ? (on ? "Equipped" : "Selected") : on ? "Equipped now" : c == null ? "Nothing in this slot" : KINDS[si];
            Gfx.text(Gfx.UI, 10, x + 9, y + th - 10.5f, eq ? Th.ac2 : Th.mut, sub);
            if (eq) {
                Gfx.rect(x + tw - 24, y + 8, 16, 16, 8, Th.ac);
                Gfx.rect(x + tw - 20.5f, y + 15, 3, 1.6f, 0.8f, 0xFFFFFFFF);
                Gfx.rect(x + tw - 18.2f, y + 13.6f, 1.6f, 5, 0.8f, 0xFFFFFFFF);
            } else if (hv) {
                String w = c == null ? "TAKE OFF" : "SELECT";
                float bw = Gfx.width(Gfx.MONO, 9, w, 1.4f) + 14;
                Gfx.rect(x + tw - bw - 8, y + 8, bw, 16, 8, Gfx.a(Th.ac, 0.85f));
                Gfx.textC(Gfx.MONO, 9, x + tw - bw / 2 - 8, y + 16, 0xFFFFFFFF, w, 1.4f);
            }
            if (Menu.hit(x, y, tw, th)) picked[si] = c == null || eq ? "" : c.id;
        }
        Menu.endClip();
        if (maxS > 0) {
            float bh = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(gx + gw + 8, top + (viewH - bh) * (gridScroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
        if (list.isEmpty()) Gfx.text(Gfx.UI, 12, gx, top + th + 30, Th.dim, "Nothing here yet. Press refresh to check for new ones.");
    }

    /** One cape tile; c == null is the NONE tile. picked = selected here, on = what the account wears. */
    private static void tile(int slot, Cape c, float x, float y, float w, float h, boolean picked, boolean on) {
        boolean hv = Menu.in(x, y, w, h);
        float t = Menu.an("wdt" + slot, hv), o = Menu.an("wdte" + slot, picked);
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
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h - 34, picked ? Th.tx : Th.dim, name, 1f);
        float bw = w - 20, by = y + h - 24;
        Gfx.rect(x + (w - bw) / 2, by, bw, 18, 5, picked ? Gfx.a(Th.ac, 0.22f) : Gfx.a(Th.ov, 0.04f + 0.05f * t));
        String lab = picked ? (on ? "EQUIPPED" : "SELECTED") : on ? "EQUIPPED NOW" : "SELECT";
        Gfx.textC(Gfx.MONO, 9, x + w / 2, by + 9, picked ? Th.ac2 : Gfx.mix(Th.mut, Th.tx, t), lab, 1.4f);
        if (picked) { // check mark badge
            Gfx.rect(x + w - 24, y + 8, 16, 16, 8, Th.ac);
            Gfx.rect(x + w - 20.5f, y + 15, 3, 1.6f, 0.8f, 0xFFFFFFFF);
            Gfx.rect(x + w - 18.2f, y + 13.6f, 1.6f, 5, 0.8f, 0xFFFFFFFF);
        }
        if (Menu.hit(x, y, w, h)) choose(c);
    }

    // ---------------------------------------------------------------- leaving with unsaved picks

    /** the "unsaved changes" dialog is up; then = what leaving would have done */
    static boolean ask;
    private static Runnable then;
    private static float askT;

    /** Leaving the Wardrobe (back, close, menu key): true = held for the dialog, which runs leave once answered. */
    static boolean guard(Runnable leave) {
        if (!loaded || pendingSeq != 0 || !dirty()) return false;
        ask = true;
        then = leave;
        askT = 0;
        return true;
    }

    private static void leave() {
        ask = false;
        Runnable r = then;
        then = null;
        if (r != null) r.run();
    }

    static void askDraw(float e) {
        askT = Config.calm() ? 1 : Math.min(1, askT + Math.min(Gfx.dt, 0.1f) * 6);
        float k = 1 - (float) Math.pow(1 - askT, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.45f * e));
        float w = 420, h = 168, x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y + 4, w, h, 10, Gfx.a(0xFF000000, 0.35f));
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);
        Gfx.rect(x + 20, y + 22, 3, 14, 1.5f, Th.ac);
        Gfx.text(Gfx.SEMI, 13, x + 30, y + 29, Th.tx, "UNSAVED CHANGES", false, 1.4f);
        String hint = "ESC KEEPS EDITING";
        Gfx.text(Gfx.MONO, 9, x + w - 20 - Gfx.width(Gfx.MONO, 9, hint, 1.2f), y + 29, Th.mut, hint, false, 1.2f);
        int n = changes();
        Gfx.text(Gfx.UI, 12, x + 20, y + 66, Th.tx, n == 1 ? "You have a change that isn't applied yet." : "You have " + n + " changes that aren't applied yet.");
        Gfx.text(Gfx.UI, 12, x + 20, y + 86, Th.dim, "Close without applying and they're lost.");
        float by = y + h - 50, bw = (w - 40 - 16) / 3;
        if (Menu.button("wdaskk", "KEEP EDITING", x + 20, by, bw, 30, false)) ask = false;
        else if (Menu.button("wdaskd", "DISCARD", x + 20 + bw + 8, by, bw, 30, false)) { pickApplied(); leave(); }
        else if (Menu.button("wdaska", "APPLY & CLOSE", x + 20 + 2 * (bw + 8), by, bw, 30, true)) { apply(); leave(); }
        Gfx.alpha = a0;
    }

    private static void choose(Cape c) {
        if (c == null) { pNether = ""; pAccount = ""; }                        // NONE: no Nether cape, account cape hidden
        else if (c.account) { pNether = ""; pAccount = c.id; }                // an account cape shows only without a Nether one
        else pNether = c.id.equals(pNether) ? "" : c.id;                      // a Nether cape (again = take it off)
    }
}
