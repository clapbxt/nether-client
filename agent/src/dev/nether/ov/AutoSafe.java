package dev.nether.ov;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns server-safe mode ON when you join a server on the strict list, and puts your own value back when you leave.
 * It never turns safe mode off on its own (if you switch it off while connected, it stays off) and never touches it when
 * it was already on. List = bundled names + the content file strict-servers.json under -Dnether.content (a base URL or a
 * folder; {"strict":["hypixel.net", ...]}) + your own names. Matching is by domain suffix: "hypixel.net" also covers
 * "mc.hypixel.net". Dev: -Dnether.fakeServer=<address> [-Dnether.fakeServerFor=<seconds>] pretends to be on that server.
 */
final class AutoSafe extends Module {
    /** used when the content file is missing or unreadable */
    private static final String[] BUNDLED = {"hypixel.net", "hivemc.com", "cubecraft.net", "mineplex.com", "minemen.club"};
    private static final String FAKE = System.getProperty("nether.fakeServer");
    private static final long FAKE_FOR = parse(System.getProperty("nether.fakeServerFor"));

    private volatile List<String> remote;
    private Net.Req req;
    private boolean asked, engaged, prior;
    private String handled, reason;
    private long worldSince;

    AutoSafe() {
        super("autosafe", "Auto Safe Mode", "Utility", "Turns server-safe mode on by itself on servers with strict rules, and back to your own setting when you leave.", false, true, 0, 0);
        add(Setting.bool("toast", "Show a message", true).hint("A short notice when safe mode is switched on for you."));
        add(Setting.text("extra", "Your own strict servers", "", 200).hint("Domains separated by commas, e.g. play.example.net. Subdomains match too."));
        needs(4);
    }

    private static long parse(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; } }

    /** Why safe mode is on right now when we switched it on (the server's domain), else null. */
    static String reason() { AutoSafe a = Modules.AUTOSAFE; return a.engaged ? a.reason : null; }

    /** The user flipped the switch themselves: from now on the value is theirs, we do not touch it again. */
    static void manual() { Modules.AUTOSAFE.engaged = false; }

    /** Game closing / overlay dead: your value goes back, so options saved to disk never keep ours. */
    void restore() {
        if (engaged && Modules.safe()) Modules.setSafe(prior);
        engaged = false;
        reason = null;
    }

    private String address() {
        String sv = Game.server();
        if (sv == null) { worldSince = 0; return null; }
        if (FAKE != null) {
            long now = System.currentTimeMillis();
            if (worldSince == 0) worldSince = now;
            if (FAKE_FOR <= 0 || now - worldSince < FAKE_FOR * 1000) return FAKE;
        }
        return sv;
    }

    /** "Mc.Hypixel.net:25565" -> "mc.hypixel.net"; null for singleplayer */
    private static String host(String a) {
        if (a == null || a.equals("Singleplayer")) return null;
        String h = a.trim().toLowerCase(Locale.ROOT);
        int c = h.lastIndexOf(':');
        if (c > 0 && h.indexOf(':') == c) h = h.substring(0, c);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }

    private void load() {
        asked = true;
        String c = System.getProperty("nether.content");
        if (c == null || c.trim().isEmpty()) return;
        c = c.trim();
        try {
            if (c.startsWith("http://") || c.startsWith("https://")) req = Net.get(c.replaceAll("/+$", "") + "/strict-servers.json", 6L * 3600 * 1000);
            else {
                Path p = Paths.get(c);
                if (Files.isDirectory(p)) p = p.resolve("strict-servers.json");
                if (Files.isRegularFile(p)) remote = names(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            System.out.println("[nether-agent] autosafe: content list unreadable (" + t + "), using the bundled one");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(String json) {
        List<String> out = new ArrayList<>();
        Object o = Json.parse(json);
        if (o instanceof Map<?, ?>) { Object l = ((Map<String, Object>) o).get("strict"); o = l != null ? l : ((Map<String, Object>) o).get("strictServers"); }
        if (o instanceof List<?>) for (Object e : (List<?>) o) if (e instanceof String && !((String) e).trim().isEmpty()) out.add(((String) e).trim().toLowerCase(Locale.ROOT));
        return out.isEmpty() ? null : out;
    }

    private boolean strict(String h) {
        List<String> l = remote;
        if (l == null) for (String b : BUNDLED) if (match(h, b)) return true;
        if (l != null) for (String b : l) if (match(h, b)) return true;
        for (String b : str("extra").split("[,;\\s]+")) if (!b.isEmpty() && match(h, b.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean match(String h, String d) { return h.equals(d) || h.endsWith("." + d); }

    @Override void tick() {
        if (!due(500)) return;
        if (!enabled()) { restore(); return; }
        if (!asked) load();
        if (req != null && req.done()) {
            try { if (req.ok()) remote = names(req.text()); } catch (Throwable t) { System.out.println("[nether-agent] autosafe: content list invalid (" + t + "), using the bundled one"); }
            req = null;
        }
        String h = host(address());
        boolean strict = h != null && strict(h);
        if (strict && !h.equals(handled)) {
            handled = h;
            if (Modules.safe()) { System.out.println("[nether-agent] autosafe: " + h + " is strict, safe mode already on (left alone)"); return; }
            prior = false;
            Modules.setSafe(true);
            engaged = true;
            reason = h;
            System.out.println("[nether-agent] autosafe: " + h + " is strict, safe mode ON");
            if (bool("toast")) Toast.show("Strict server: safe mode on");
        } else if (!strict && handled != null) {
            handled = null;
            if (engaged) {
                boolean still = Modules.safe();
                restore();
                System.out.println("[nether-agent] autosafe: left the strict server, safe mode " + (still ? "back to " + Modules.safe() : "left as you set it"));
            }
        }
    }
}
