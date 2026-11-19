package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player head faces for item icons. The owner comes from the stack's data text ({@link Data#extra}): the skin texture
 * the head carries (component profile or NBT SkullOwner), else its UUID (Mojang session server), else its name (Mojang
 * profile lookup, then the session server) — the lookups the game itself makes to draw the head. Everything goes
 * through {@link Net} (background thread, disk cache), so a head shows as Steve until its skin has loaded.
 */
final class Heads {
    private Heads() {}

    private static final Pattern TEX = Pattern.compile("(?:value=|Value:\")([A-Za-z0-9+/]{60,}={0,2})"),
        UUID = Pattern.compile("\\b([0-9a-fA-F]{8})-([0-9a-fA-F]{4})-([0-9a-fA-F]{4})-([0-9a-fA-F]{4})-([0-9a-fA-F]{12})\\b"),
        IDS = Pattern.compile("Id:\\[I;\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\]"),
        NAME = Pattern.compile("(?:Name:\"|SkullOwner:\"|name=Optional\\[|name=|Optional\\[)([A-Za-z0-9_]{3,16})[\"\\],]");

    /** state: 0 new, 1 profile by uuid, 2 skin download, 3 done, 4 uuid by name */
    private static final class H { int state; Net.Req req; int[] face; }
    private static final Map<String, H> heads = new HashMap<>();

    /** The head's face (8x8 or larger, n*n ARGB), or null while it loads / when it has no owner. Call from the render or tick thread. */
    static int[] face(String extra) {
        String owner = owner(extra);
        if (owner == null) return null;
        H h = heads.get(owner);
        if (h == null) {
            if (heads.size() > 256) heads.clear();
            heads.put(owner, h = new H());
        }
        try { step(h, owner); } catch (Throwable t) { h.state = 3; }
        return h.face;
    }

    /** "tex:<base64>", "id:<uuid without dashes>" or "name:<name>" from the profile part of a stack's data text; null = none. */
    private static String owner(String x) {
        if (x == null) return null;
        int k = x.indexOf("minecraft:profile");
        if (k < 0) k = x.indexOf("SkullOwner");
        if (k < 0) return null;
        String p = x.substring(k, Math.min(x.length(), k + 8192));
        Matcher m = TEX.matcher(p);
        if (m.find()) return "tex:" + m.group(1);
        m = UUID.matcher(p);
        // a version-3 UUID is an offline one ("OfflinePlayer:<name>", e.g. a name-only head the game filled in): Mojang
        // doesn't know it, so the name is asked instead
        if (m.find() && m.group(3).charAt(0) != '3') return "id:" + (m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5)).toLowerCase(java.util.Locale.ROOT);
        m = IDS.matcher(p);
        if (m.find()) {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= 4; i++) b.append(String.format("%08x", Integer.parseInt(m.group(i))));
            return "id:" + b;
        }
        m = NAME.matcher(p);
        return m.find() ? "name:" + m.group(1) : null;
    }

    private static void step(H h, String owner) throws Exception {
        if (h.state == 0) {
            if (owner.startsWith("tex:")) skin(h, skinUrl(new String(java.util.Base64.getDecoder().decode(owner.substring(4)), StandardCharsets.UTF_8)));
            else if (owner.startsWith("id:")) { h.req = Net.get("https://sessionserver.mojang.com/session/minecraft/profile/" + owner.substring(3), 6 * 3600_000L, 64 << 10); h.state = 1; }
            else { h.req = Net.get("https://api.mojang.com/users/profiles/minecraft/" + owner.substring(5), 24 * 3600_000L, 16 << 10); h.state = 4; }
        } else if (h.state == 4 && h.req.done()) {
            Object id = h.req.ok() ? ((Map<?, ?>) Json.parse(h.req.text())).get("id") : null;
            if (id instanceof String && ((String) id).matches("[0-9a-f]{32}")) { h.req = Net.get("https://sessionserver.mojang.com/session/minecraft/profile/" + id, 6 * 3600_000L, 64 << 10); h.state = 1; }
            else h.state = 3;
        } else if (h.state == 1 && h.req.done()) {
            String url = null;
            if (h.req.ok()) {
                Object props = ((Map<?, ?>) Json.parse(h.req.text())).get("properties");
                if (props instanceof List) for (Object o : (List<?>) props) {
                    Map<?, ?> e = (Map<?, ?>) o;
                    if ("textures".equals(e.get("name")) && e.get("value") instanceof String)
                        url = skinUrl(new String(java.util.Base64.getDecoder().decode((String) e.get("value")), StandardCharsets.UTF_8));
                }
            }
            skin(h, url);
        } else if (h.state == 2 && h.req.done()) {
            h.state = 3;
            BufferedImage img = h.req.ok() ? Icons.decode(new java.io.ByteArrayInputStream(h.req.body), 1 << 20) : null;
            h.face = Icons.face(img, 8, 8, 8, 40, 8);
        }
    }

    private static void skin(H h, String url) {
        // only Mojang's texture server: a head's data is written by whoever made the item
        if (url == null || !url.matches("https?://textures\\.minecraft\\.net/texture/[0-9a-f]+")) { h.state = 3; return; }
        h.req = Net.get(url.replace("http://", "https://"), 7 * 86400_000L, 1 << 20);
        h.state = 2;
    }

    /** textures.SKIN.url of a decoded "textures" property, or null. */
    private static String skinUrl(String json) {
        Object t = ((Map<?, ?>) Json.parse(json)).get("textures");
        Object sk = t instanceof Map ? ((Map<?, ?>) t).get("SKIN") : null;
        Object u = sk instanceof Map ? ((Map<?, ?>) sk).get("url") : null;
        return u instanceof String ? (String) u : null;
    }
}
