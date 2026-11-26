package dev.nether.ov.features.cosmetics;

import java.util.List;
import java.util.Map;

/**
 * An aura: pixel-texture particles floating around the player. Stateless (the same rule as the launcher's
 * cosmetics.rs): particle i is respawned every `life` seconds with fresh random values derived from (i, cycle), so its
 * whole flight is a function of the time; the game adds where the player stood when it spawned (trails when moving).
 * Units are skin pixels around the feet (Y up). Sprite sheet: square cells of `cell` px, columns = animation frames,
 * rows = variants. JSON: count, life [a,b] s, size [a,b] px, shape column|ring|feet, radius [a,b] px, height [a,b] px,
 * velocity [x,y,z] px/s, jitter [x,y,z] px/s, gravity px/s^2, orbit deg/s, wobble [px, Hz], fade [in, out] (life
 * fractions), grow [start, mid, end], frames life|loop|random, fps, glow, cell.
 */
final class CosAura {
    final int count;
    final float life0, life1, size0, size1, rad0, rad1, h0, h1, vx, vy, vz, jx, jy, jz, grav, orbit, wobA, wobF, fadeIn, fadeOut, g0, g1, g2, fps;
    final int shape; // 0 column, 1 ring, 2 feet
    final int frames; // 0 life, 1 loop, 2 random
    final boolean glow;
    final int cell;

    private static float num(Object o, float d) { return o instanceof Number ? ((Number) o).floatValue() : d; }
    private static float at(Object o, int i, float d) { return o instanceof List && ((List<?>) o).size() > i ? num(((List<?>) o).get(i), d) : (i == 0 || !(o instanceof List)) && o instanceof Number ? num(o, d) : d; }

    CosAura(Object json) {
        if (!(json instanceof Map)) throw new IllegalArgumentException("aura is not an object");
        Map<?, ?> m = (Map<?, ?>) json;
        count = Math.max(1, Math.min(64, Math.round(num(m.get("count"), 16))));
        life0 = Math.max(0.2f, at(m.get("life"), 0, 1.5f)); life1 = Math.max(life0, at(m.get("life"), 1, life0));
        size0 = Math.max(0.2f, at(m.get("size"), 0, 1.5f)); size1 = Math.max(size0, at(m.get("size"), 1, size0));
        rad0 = at(m.get("radius"), 0, 6); rad1 = Math.max(rad0, at(m.get("radius"), 1, rad0));
        h0 = at(m.get("height"), 0, 0); h1 = Math.max(h0, at(m.get("height"), 1, 30));
        vx = at(m.get("velocity"), 0, 0); vy = at(m.get("velocity"), 1, 4); vz = at(m.get("velocity"), 2, 0);
        jx = at(m.get("jitter"), 0, 0); jy = at(m.get("jitter"), 1, 0); jz = at(m.get("jitter"), 2, 0);
        grav = num(m.get("gravity"), 0);
        orbit = num(m.get("orbit"), 0);
        wobA = at(m.get("wobble"), 0, 0); wobF = at(m.get("wobble"), 1, 1);
        fadeIn = Math.max(0.01f, at(m.get("fade"), 0, 0.15f)); fadeOut = Math.max(0.01f, at(m.get("fade"), 1, 0.3f));
        g0 = at(m.get("grow"), 0, 1); g1 = at(m.get("grow"), 1, 1); g2 = at(m.get("grow"), 2, 1);
        String sh = String.valueOf(m.get("shape"));
        shape = "ring".equals(sh) ? 1 : "feet".equals(sh) ? 2 : 0;
        String fr = String.valueOf(m.get("frames"));
        frames = "loop".equals(fr) ? 1 : "random".equals(fr) ? 2 : 0;
        fps = Math.max(0.1f, num(m.get("fps"), 8));
        glow = !Boolean.FALSE.equals(m.get("glow"));
        cell = Math.max(1, Math.round(num(m.get("cell"), 0)));
    }

    /** [0, 1) from two integers (splitmix-style; the launcher uses the same). */
    static float rnd(int a, int b) {
        long z = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ 0x165667B19E3779F9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }

    /** Values per particle in {@link #eval}'s output. */
    static final int STRIDE = 8;

    /**
     * All particles at time t (seconds): per particle x, y, z (px from the feet, Y up), size (px), alpha, column (0..1
     * of the life for frame picking or a frame index), row pick (0..1), age (s). Returns the particle count.
     */
    int eval(double t, float[] out) {
        for (int i = 0; i < count; i++) {
            float ph = rnd(i, 1), life = life0 + (life1 - life0) * rnd(i, 2);
            double c = t / life + ph;
            long k = (long) Math.floor(c);
            float a = (float) (c - k), age = a * life;
            int s = (int) (k * 1000003L + i * 7919L);
            float r0 = rnd(s, 3), r1 = rnd(s, 4), r2 = rnd(s, 5), r3 = rnd(s, 6), r4 = rnd(s, 7), r5 = rnd(s, 8), r6 = rnd(s, 9), r7 = rnd(s, 10), r8 = rnd(s, 11);
            float ang = (float) (r0 * Math.PI * 2);
            float rad = shape == 1 ? rad1 : rad0 + (rad1 - rad0) * (shape == 0 ? (float) Math.sqrt(r1) : r1);
            float y = shape == 2 ? h0 : h0 + (h1 - h0) * r2;
            float x = (float) Math.cos(ang) * rad, z = (float) Math.sin(ang) * rad;
            x += (vx + (r3 * 2 - 1) * jx) * age;
            y += (vy + (r4 * 2 - 1) * jy) * age + 0.5f * grav * age * age;
            z += (vz + (r5 * 2 - 1) * jz) * age;
            if (orbit != 0) {
                double o = Math.toRadians(orbit * age);
                float cx = (float) Math.cos(o), sx = (float) Math.sin(o), nx = x * cx - z * sx;
                z = x * sx + z * cx;
                x = nx;
            }
            if (wobA != 0) {
                float w = (float) (wobA * Math.sin(2 * Math.PI * (wobF * age + r6)));
                x += w * (float) Math.cos(ang + 1.5708);
                z += w * (float) Math.sin(ang + 1.5708);
            }
            float g = a < 0.5f ? g0 + (g1 - g0) * a * 2 : g1 + (g2 - g1) * (a - 0.5f) * 2;
            int o = i * STRIDE;
            out[o] = x; out[o + 1] = y; out[o + 2] = z;
            out[o + 3] = (size0 + (size1 - size0) * r7) * Math.max(0, g);
            out[o + 4] = Math.max(0, Math.min(1, Math.min(a / fadeIn, (1 - a) / fadeOut)));
            out[o + 5] = frames == 0 ? a : frames == 1 ? (float) (age * fps + r8 * 64) : r8;
            out[o + 6] = rnd(s, 12);
            out[o + 7] = age;
        }
        return count;
    }

    /** Frame column for a particle's value 5 (see eval), with `cols` columns. */
    int col(float v, int cols) {
        if (cols <= 1) return 0;
        if (frames == 0) return Math.min(cols - 1, (int) (v * cols));
        if (frames == 1) return ((int) v % cols + cols) % cols;
        return Math.min(cols - 1, (int) (v * cols));
    }
}
