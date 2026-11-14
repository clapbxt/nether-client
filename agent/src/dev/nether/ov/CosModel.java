package dev.nether.ov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A worn cosmetic model (hat, headband, shield): a Blockbench "Java Block/Item" model JSON, the same rule as the
 * launcher's cosmetics.rs. Units are skin pixels, (8, 0, 8) is the attachment pivot (head/back: the neck; shield: its
 * centre), the player looks north (-Z). Elements: from/to, rotation {origin, axis, angle, rescale}, faces {uv (0..16),
 * rotation, texture}, light_emission (> 0 = glows). One texture per model; vertical frames = animated texture.
 * Blockbench free rotation (tools/cosmetics/import.mjs): element "rotation": {"x", "y", "z", "origin"} (degrees, Z*Y*X like
 * Blockbench) and group "rotation": [x, y, z] around the group origin. Groups (Blockbench outliner) can also move: "nether": {"anim": [{group, spin [x,y,z] deg/s, sway [x,y,z] deg, bob [x,y,z] px,
 * period s, phase 0..1} | {group, length s, rotation [[t, x, y, z]...] deg, position [[t, x, y, z]...] px} (looping
 * keyframes, linear: Blockbench animations)], "glow": all glow}. Quads come out in the game's model space (Y down, blocks) via {@link #pose}.
 */
final class CosModel {
    /** per quad: 4 corners (TL, BL, BR, TR seen from outside, i.e. counter-clockwise) x/y/z in model pixels */
    final float[] pos;
    /** per quad: 4 corners u/v, 0..1 inside one texture frame */
    final float[] uv;
    /** per quad: outward normal (model pixel space) */
    final float[] nrm;
    final int[] grp;
    final boolean[] glow;
    final int quads;
    final Group[] groups;
    final boolean moving, posed;
    /** texture_size (UV grid aspect: frame height = png width * th / tw) */
    final int tw, th;

    static final class Group {
        float ox, oy, oz;
        /** rest rotation (degrees), Blockbench bones */
        float rx, ry, rz;
        int parent = -1;
        final List<float[]> anims = new ArrayList<>(); // {kind 0 spin / 1 sway / 2 bob, x, y, z, period, phase}
        /** keyframe loops: {length, rotation keys (t, x, y, z)..., position keys...} */
        final List<float[][]> keys = new ArrayList<>();
    }

    private CosModel(List<float[]> q, List<Integer> g, List<Boolean> gl, Group[] groups, int tw, int th) {
        quads = q.size();
        pos = new float[quads * 12];
        uv = new float[quads * 8];
        nrm = new float[quads * 3];
        grp = new int[quads];
        glow = new boolean[quads];
        for (int i = 0; i < quads; i++) {
            float[] f = q.get(i);
            System.arraycopy(f, 0, pos, i * 12, 12);
            System.arraycopy(f, 12, uv, i * 8, 8);
            System.arraycopy(f, 20, nrm, i * 3, 3);
            grp[i] = g.get(i);
            glow[i] = gl.get(i);
        }
        this.groups = groups;
        boolean m = false;
        boolean p = false;
        for (Group x : groups) { if (!x.anims.isEmpty() || !x.keys.isEmpty()) m = true; if (x.rx != 0 || x.ry != 0 || x.rz != 0) p = true; }
        moving = m;
        posed = m || p;
        this.tw = tw;
        this.th = th;
    }

    private static float num(Object o, float d) { return o instanceof Number ? ((Number) o).floatValue() : d; }

    private static float[] vec(Object o, float d) {
        float[] v = {d, d, d};
        if (o instanceof List) for (int i = 0; i < 3 && i < ((List<?>) o).size(); i++) v[i] = num(((List<?>) o).get(i), d);
        return v;
    }

    private static final String[] DIRS = {"north", "south", "east", "west", "up", "down"};

    /** Parse a model (throws on anything malformed; limits keep a bad file cheap). */
    static CosModel parse(Object json) {
        if (!(json instanceof Map)) throw new IllegalArgumentException("model is not an object");
        Map<?, ?> m = (Map<?, ?>) json;
        Object els = m.get("elements");
        if (!(els instanceof List) || ((List<?>) els).isEmpty()) throw new IllegalArgumentException("model has no elements");
        List<?> list = (List<?>) els;
        if (list.size() > 2048) throw new IllegalArgumentException("model too large");
        float[] ts = vec(m.get("texture_size"), 16);
        int tw = Math.max(1, Math.round(ts[0])), th = Math.max(1, Math.round(ts[1]));
        Map<?, ?> nether = m.get("nether") instanceof Map ? (Map<?, ?>) m.get("nether") : new HashMap<>();
        boolean allGlow = Boolean.TRUE.equals(nether.get("glow"));
        // groups (outliner): element index -> innermost group
        List<Group> groups = new ArrayList<>();
        Map<String, Integer> byName = new HashMap<>();
        int[] owner = new int[list.size()];
        java.util.Arrays.fill(owner, -1);
        if (m.get("groups") instanceof List) for (Object g : (List<?>) m.get("groups")) group(g, -1, groups, byName, owner);
        if (nether.get("anim") instanceof List) {
            for (Object a : (List<?>) nether.get("anim")) {
                if (!(a instanceof Map)) continue;
                Map<?, ?> am = (Map<?, ?>) a;
                Object gn = am.get("group");
                Integer gi = gn instanceof String ? byName.get(gn) : null;
                if (gi == null) { // no group: the whole model moves (a group around everything)
                    Group all = new Group();
                    float[] o = vec(am.get("origin"), 8);
                    all.ox = o[0]; all.oy = o[1]; all.oz = o[2];
                    gi = groups.size();
                    groups.add(all);
                    for (Group x : groups) if (x != all && x.parent < 0) x.parent = gi;
                    for (int i = 0; i < owner.length; i++) if (owner[i] < 0) owner[i] = gi;
                }
                if (am.get("length") != null) {
                    groups.get(gi).keys.add(new float[][] {{Math.max(0.05f, num(am.get("length"), 1))}, keyList(am.get("rotation")), keyList(am.get("position"))});
                    continue;
                }
                float period = Math.max(0.05f, num(am.get("period"), 2)), phase = num(am.get("phase"), 0);
                if (am.get("spin") != null) { float[] v = vec(am.get("spin"), 0); groups.get(gi).anims.add(new float[] {0, v[0], v[1], v[2], period, phase}); }
                if (am.get("sway") != null) { float[] v = vec(am.get("sway"), 0); groups.get(gi).anims.add(new float[] {1, v[0], v[1], v[2], period, phase}); }
                if (am.get("bob") != null) { float[] v = vec(am.get("bob"), 0); groups.get(gi).anims.add(new float[] {2, v[0], v[1], v[2], period, phase}); }
            }
        }
        List<float[]> quads = new ArrayList<>();
        List<Integer> qg = new ArrayList<>();
        List<Boolean> qgl = new ArrayList<>();
        for (int ei = 0; ei < list.size(); ei++) {
            if (!(list.get(ei) instanceof Map)) continue;
            Map<?, ?> e = (Map<?, ?>) list.get(ei);
            float[] a = vec(e.get("from"), 0), b = vec(e.get("to"), 0);
            float x1 = Math.min(a[0], b[0]), y1 = Math.min(a[1], b[1]), z1 = Math.min(a[2], b[2]);
            float x2 = Math.max(a[0], b[0]), y2 = Math.max(a[1], b[1]), z2 = Math.max(a[2], b[2]);
            boolean gl = allGlow || num(e.get("light_emission"), 0) > 0;
            // element rotation: one axis around an origin (any angle), optional rescale like vanilla
            float[] rot = null, free = null;
            if (e.get("rotation") instanceof Map && ((Map<?, ?>) e.get("rotation")).get("angle") == null) { // Blockbench free rotation
                Map<?, ?> r = (Map<?, ?>) e.get("rotation");
                float[] o = vec(r.get("origin"), 8);
                float x = num(r.get("x"), 0), y = num(r.get("y"), 0), z = num(r.get("z"), 0);
                if (x != 0 || y != 0 || z != 0) free = around(o[0], o[1], o[2], (float) Math.toRadians(x), (float) Math.toRadians(y), (float) Math.toRadians(z));
            } else if (e.get("rotation") instanceof Map) {
                Map<?, ?> r = (Map<?, ?>) e.get("rotation");
                float ang = (float) Math.toRadians(num(r.get("angle"), 0));
                if (ang != 0) {
                    float[] o = vec(r.get("origin"), 8);
                    String ax = String.valueOf(r.get("axis"));
                    int axis = "x".equals(ax) ? 0 : "y".equals(ax) ? 1 : 2;
                    float sc = Boolean.TRUE.equals(r.get("rescale")) ? (float) (1 / Math.cos(Math.abs(ang) > Math.PI / 4 ? Math.PI / 4 : ang)) : 1;
                    rot = new float[] {o[0], o[1], o[2], axis, (float) Math.sin(ang), (float) Math.cos(ang), sc};
                }
            }
            if (!(e.get("faces") instanceof Map)) continue;
            Map<?, ?> faces = (Map<?, ?>) e.get("faces");
            for (int d = 0; d < 6; d++) {
                if (!(faces.get(DIRS[d]) instanceof Map)) continue;
                Map<?, ?> f = (Map<?, ?>) faces.get(DIRS[d]);
                if (f.get("texture") == null) continue; // Blockbench writes faces without a texture as {"uv": .., } only for unused faces
                float[] c = corners(d, x1, y1, z1, x2, y2, z2);
                float[] u = f.get("uv") instanceof List ? uv4((List<?>) f.get("uv")) : defaultUv(d, x1, y1, z1, x2, y2, z2);
                int k = ((Math.round(num(f.get("rotation"), 0)) / 90) % 4 + 4) % 4;
                // vanilla: corner i takes uv index (i + k) % 4; uv index 0 (u1,v1) 1 (u1,v2) 2 (u2,v2) 3 (u2,v1)
                float[] q = new float[23];
                for (int i = 0; i < 4; i++) {
                    float[] p = {c[i * 3], c[i * 3 + 1], c[i * 3 + 2]};
                    if (rot != null) p = rotate(p, rot);
                    if (free != null) p = new float[] {free[0] * p[0] + free[1] * p[1] + free[2] * p[2] + free[3], free[4] * p[0] + free[5] * p[1] + free[6] * p[2] + free[7], free[8] * p[0] + free[9] * p[1] + free[10] * p[2] + free[11]};
                    q[i * 3] = p[0]; q[i * 3 + 1] = p[1]; q[i * 3 + 2] = p[2];
                    int j = (i + k) % 4;
                    q[12 + i * 2] = (j < 2 ? u[0] : u[2]) / 16f;
                    q[13 + i * 2] = (j == 0 || j == 3 ? u[1] : u[3]) / 16f;
                }
                float[] n = NORMALS[d].clone();
                if (rot != null) n = rotate(n, new float[] {0, 0, 0, rot[3], rot[4], rot[5], 1});
                if (free != null) n = new float[] {free[0] * n[0] + free[1] * n[1] + free[2] * n[2], free[4] * n[0] + free[5] * n[1] + free[6] * n[2], free[8] * n[0] + free[9] * n[1] + free[10] * n[2]};
                q[20] = n[0]; q[21] = n[1]; q[22] = n[2];
                quads.add(q);
                qg.add(owner[ei]);
                qgl.add(gl);
            }
        }
        if (quads.isEmpty()) throw new IllegalArgumentException("model has no textured faces");
        return new CosModel(quads, qg, qgl, groups.toArray(new Group[0]), tw, th);
    }

    /** [[t, x, y, z], ...] -> flat t,x,y,z sorted by time (at most 256 keys) */
    private static float[] keyList(Object o) {
        if (!(o instanceof List)) return new float[0];
        List<float[]> k = new ArrayList<>();
        for (Object e : (List<?>) o) {
            if (!(e instanceof List) || ((List<?>) e).size() < 4 || k.size() >= 256) continue;
            List<?> l = (List<?>) e;
            k.add(new float[] {num(l.get(0), 0), num(l.get(1), 0), num(l.get(2), 0), num(l.get(3), 0)});
        }
        k.sort((a, b) -> Float.compare(a[0], b[0]));
        float[] out = new float[k.size() * 4];
        for (int i = 0; i < k.size(); i++) System.arraycopy(k.get(i), 0, out, i * 4, 4);
        return out;
    }

    /** linear value of flat keys at time t (out = x, y, z added) */
    private static void sample(float[] k, float t, float[] out) {
        int n = k.length / 4;
        if (n == 0) return;
        int i = 0;
        while (i + 1 < n && k[(i + 1) * 4] <= t) i++;
        float f = 0;
        int j = i;
        if (t > k[i * 4] && i + 1 < n) { j = i + 1; f = (t - k[i * 4]) / Math.max(1e-4f, k[j * 4] - k[i * 4]); }
        for (int c = 0; c < 3; c++) out[c] += k[i * 4 + 1 + c] + (k[j * 4 + 1 + c] - k[i * 4 + 1 + c]) * f;
    }

    private static void group(Object g, int parent, List<Group> out, Map<String, Integer> byName, int[] owner) {
        if (g instanceof Number) { int i = ((Number) g).intValue(); if (i >= 0 && i < owner.length) owner[i] = parent; return; }
        if (!(g instanceof Map) || out.size() > 512) return;
        Map<?, ?> m = (Map<?, ?>) g;
        Group x = new Group();
        float[] o = vec(m.get("origin"), 8);
        x.ox = o[0]; x.oy = o[1]; x.oz = o[2];
        float[] gr = vec(m.get("rotation"), 0);
        x.rx = gr[0]; x.ry = gr[1]; x.rz = gr[2];
        x.parent = parent;
        int id = out.size();
        out.add(x);
        if (m.get("name") instanceof String) byName.putIfAbsent((String) m.get("name"), id);
        if (m.get("children") instanceof List) for (Object c : (List<?>) m.get("children")) group(c, id, out, byName, owner);
    }

    private static final float[][] NORMALS = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    /** Corners TL, BL, BR, TR of a face seen from outside (vanilla FaceInfo order). */
    private static float[] corners(int d, float x1, float y1, float z1, float x2, float y2, float z2) {
        switch (d) {
            case 0: return new float[] {x2, y2, z1, x2, y1, z1, x1, y1, z1, x1, y2, z1}; // north
            case 1: return new float[] {x1, y2, z2, x1, y1, z2, x2, y1, z2, x2, y2, z2}; // south
            case 2: return new float[] {x2, y2, z2, x2, y1, z2, x2, y1, z1, x2, y2, z1}; // east
            case 3: return new float[] {x1, y2, z1, x1, y1, z1, x1, y1, z2, x1, y2, z2}; // west
            case 4: return new float[] {x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1}; // up
            default: return new float[] {x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2}; // down
        }
    }

    private static float[] uv4(List<?> l) {
        float[] u = new float[4];
        for (int i = 0; i < 4 && i < l.size(); i++) u[i] = num(l.get(i), 0);
        return u;
    }

    private static float[] defaultUv(int d, float x1, float y1, float z1, float x2, float y2, float z2) {
        switch (d) {
            case 0: return new float[] {16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case 1: return new float[] {x1, 16 - y2, x2, 16 - y1};
            case 2: return new float[] {16 - z2, 16 - y2, 16 - z1, 16 - y1};
            case 3: return new float[] {z1, 16 - y2, z2, 16 - y1};
            case 4: return new float[] {x1, z1, x2, z2};
            default: return new float[] {x1, 16 - z2, x2, 16 - z1};
        }
    }

    /** rot = {ox, oy, oz, axis, sin, cos, rescale} */
    private static float[] rotate(float[] p, float[] r) {
        float x = p[0] - r[0], y = p[1] - r[1], z = p[2] - r[2], s = r[4], c = r[5], k = r[6];
        float nx = x, ny = y, nz = z;
        if (r[3] == 0) { ny = y * c - z * s; nz = y * s + z * c; ny *= k; nz *= k; }
        else if (r[3] == 1) { nx = x * c + z * s; nz = -x * s + z * c; nx *= k; nz *= k; }
        else { nx = x * c - y * s; ny = x * s + y * c; nx *= k; ny *= k; }
        return new float[] {nx + r[0], ny + r[1], nz + r[2]};
    }

    // ---------------------------------------------------------------- posing

    /** Per group 3x4 matrices (row major) at time t (seconds), parents applied; null when nothing moves. */
    float[][] groupMats(float t) {
        if (!posed) return null;
        float[][] out = new float[groups.length][];
        for (int i = 0; i < groups.length; i++) mat(i, t, out);
        return out;
    }

    private float[] mat(int i, float t, float[][] done) {
        if (done[i] != null) return done[i];
        Group g = groups[i];
        float[] r = {g.rx, g.ry, g.rz}, p = {0, 0, 0};
        for (float[][] k : g.keys) { float tt = t % k[0][0]; sample(k[1], tt, r); sample(k[2], tt, p); }
        float[] m = r[0] != 0 || r[1] != 0 || r[2] != 0
            ? around(g.ox, g.oy, g.oz, (float) Math.toRadians(r[0]), (float) Math.toRadians(r[1]), (float) Math.toRadians(r[2]))
            : new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        m[3] += p[0]; m[7] += p[1]; m[11] += p[2];
        for (float[] a : g.anims) {
            float w = (float) Math.sin(2 * Math.PI * (t / a[4] + a[5]));
            if (a[0] == 2) { m[3] += a[1] * w; m[7] += a[2] * w; m[11] += a[3] * w; continue; }
            float k = a[0] == 0 ? t : w;
            float rx = (float) Math.toRadians(a[1] * k), ry = (float) Math.toRadians(a[2] * k), rz = (float) Math.toRadians(a[3] * k);
            m = mul(m, around(g.ox, g.oy, g.oz, rx, ry, rz));
        }
        if (g.parent >= 0 && g.parent != i) m = mul(mat(g.parent, t, done), m);
        return done[i] = m;
    }

    /** translate(o) * Rz * Ry * Rx * translate(-o) */
    private static float[] around(float ox, float oy, float oz, float rx, float ry, float rz) {
        float cx = (float) Math.cos(rx), sx = (float) Math.sin(rx), cy = (float) Math.cos(ry), sy = (float) Math.sin(ry), cz = (float) Math.cos(rz), sz = (float) Math.sin(rz);
        float[] r = {
            cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx, 0,
            sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx, 0,
            -sy, cy * sx, cy * cx, 0};
        r[3] = ox - (r[0] * ox + r[1] * oy + r[2] * oz);
        r[7] = oy - (r[4] * ox + r[5] * oy + r[6] * oz);
        r[11] = oz - (r[8] * ox + r[9] * oy + r[10] * oz);
        return r;
    }

    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                float v = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
                if (c == 3) v += a[r * 4 + 3];
                o[r * 4 + c] = v;
            }
        }
        return o;
    }

    /**
     * Corner positions (12 floats per quad) and normals (3 per quad) in the game's model space (blocks, Y down, front
     * -Z) at time t: x = (8 - px) / 16, y = -py / 16, z = (pz - 8) / 16. `out`/`outN` are reused when big enough.
     */
    void pose(float t, float[] out, float[] outN) {
        float[][] gm = groupMats(t);
        for (int q = 0; q < quads; q++) {
            float[] m = gm == null || grp[q] < 0 ? null : gm[grp[q]];
            for (int i = 0; i < 4; i++) {
                int o = q * 12 + i * 3;
                float x = pos[o], y = pos[o + 1], z = pos[o + 2];
                if (m != null) {
                    float nx = m[0] * x + m[1] * y + m[2] * z + m[3], ny = m[4] * x + m[5] * y + m[6] * z + m[7], nz = m[8] * x + m[9] * y + m[10] * z + m[11];
                    x = nx; y = ny; z = nz;
                }
                out[o] = (8 - x) / 16f;
                out[o + 1] = -y / 16f;
                out[o + 2] = (z - 8) / 16f;
            }
            float nx = nrm[q * 3], ny = nrm[q * 3 + 1], nz = nrm[q * 3 + 2];
            if (m != null) { float ax = m[0] * nx + m[1] * ny + m[2] * nz, ay = m[4] * nx + m[5] * ny + m[6] * nz, az = m[8] * nx + m[9] * ny + m[10] * nz; nx = ax; ny = ay; nz = az; }
            outN[q * 3] = -nx;
            outN[q * 3 + 1] = -ny;
            outN[q * 3 + 2] = nz;
        }
    }
}
