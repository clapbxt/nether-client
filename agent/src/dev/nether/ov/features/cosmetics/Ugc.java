package dev.nether.ov.features.cosmetics;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.nether.ov.framework.Json;

/**
 * Capes and cosmetics, all public community items (ids "u-" + 12 [a-z0-9]): players' uploads, screened by the service,
 * and Nether's own, credited to "Nether". What an id is comes from the player API (GET /v1/ugc/(id): type + file
 * sha256s, public items only), the files from /v1/ugc/blob/(sha256) and are checked against it. Your own items (also
 * private ones) come in the launcher's sums files with their hashes. Worker threads only.
 */
final class Ugc {
    private Ugc() {}

    static boolean is(String id) { return id != null && id.length() == 14 && id.matches("u-[a-z0-9]{12}"); }

    /** id -> {type, model sha or null, png sha, ms} ({} = not public / gone), kept for the session */
    private static final Map<String, String[]> META = new ConcurrentHashMap<>();
    private static final String[] NONE = new String[0];

    /** What a public community item is, or null when it isn't one (removed, private, unknown). */
    static String[] meta(String id) throws Exception {
        String[] m = META.get(id);
        if (m != null) return m.length == 0 ? null : m;
        if (Capes.API == null || !is(id)) return null;
        byte[] b;
        try { b = Capes.get(Capes.API + "/v1/ugc/" + id, 16 * 1024); }
        catch (IllegalStateException e) { if ("HTTP 404".equals(e.getMessage())) { META.put(id, NONE); return null; } throw e; }
        Object o = Json.parse(new String(b, StandardCharsets.UTF_8));
        if (!(o instanceof Map)) throw new IllegalStateException("bad item");
        Map<?, ?> j = (Map<?, ?>) o;
        String type = str(j.get("type")), model = str(j.get("model")), png = str(j.get("png"));
        Object ms = j.get("ms");
        if (type == null || !type.matches("cape|hat|headband|mask|back|wings|aura|pet") || !sha(png) || (model != null && !sha(model))
            || !id.equals(j.get("id")) || !"live".equals(j.get("status"))) { META.put(id, NONE); return null; }
        int frame = ms instanceof Number ? Math.max(20, Math.min(2000, ((Number) ms).intValue())) : 100;
        m = new String[] {type, model, png, Integer.toString(frame)};
        META.put(id, m);
        return m;
    }

    /** A community file by its sha256, checked. */
    static byte[] blob(String sha, int max) throws Exception {
        if (Capes.API == null || !sha(sha)) throw new IllegalStateException("no file");
        byte[] d = Capes.get(Capes.API + "/v1/ugc/blob/" + sha, max);
        StringBuilder sb = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(d)) sb.append(String.format("%02x", x & 255));
        if (!sb.toString().equals(sha)) throw new IllegalStateException("hash mismatch");
        return d;
    }

    private static boolean sha(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
    private static String str(Object o) { return o instanceof String ? (String) o : null; }
}
