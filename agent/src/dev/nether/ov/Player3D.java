package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny software renderer for the wardrobe preview (same model and shading as the launcher's player preview): a textured
 * Steve/Alex built from boxes, optional cape, perspective-correct, depth buffered, drawn into a transparent image that is
 * then uploaded like any other overlay texture. Nothing here touches the game's renderer.
 */
final class Player3D {
    private Player3D() {}

    private static final class Quad { float[][] p; float[] uv; boolean cape, cut; }

    private static float[] rotX(float[] p, float a) { float s = (float) Math.sin(a), c = (float) Math.cos(a); return new float[] {p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c}; }
    private static float[] rotY(float[] p, float a) { float s = (float) Math.sin(a), c = (float) Math.cos(a); return new float[] {p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c}; }
    private static float[] rotZ(float[] p, float a) { float s = (float) Math.sin(a), c = (float) Math.cos(a); return new float[] {p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]}; }

    private interface Xf { float[] f(float[] p); }

    private static void cuboid(List<Quad> out, float[] min, float[] size, float grow, float u, float v, Xf xf, boolean cape, boolean cut) {
        float w = size[0], h = size[1], d = size[2];
        float x0 = min[0] - grow, y0 = min[1] - grow, z0 = min[2] - grow, x1 = min[0] + w + grow, y1 = min[1] + h + grow, z1 = min[2] + d + grow;
        float[][][] f = {
            {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}},
            {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}},
            {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}},
            {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}},
            {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
            {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}};
        float[][] t = {
            {u + d, v + d, u + d + w, v + d + h}, {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h}, {u, v + d, u + d, v + d + h},
            {u + d + w, v + d, u + 2 * d + w, v + d + h}, {u + d, v, u + d + w, v + d}, {u + d + w, v, u + d + 2 * w, v + d}};
        for (int i = 0; i < 6; i++) {
            Quad q = new Quad();
            q.p = new float[4][];
            for (int k = 0; k < 4; k++) q.p[k] = xf.f(f[i][k]);
            q.uv = t[i];
            q.cape = cape;
            q.cut = cut;
            out.add(q);
        }
    }

    private static List<Quad> model(boolean slim, boolean hasCape) {
        List<Quad> q = new ArrayList<>();
        Xf id = p -> p;
        float aw = slim ? 3 : 4;
        Xf ra = arm(-5, 22, -0.06f), la = arm(5, 22, 0.06f);
        float[][][] uvs = {{{0, 0}, {16, 16}, {40, 16}, {32, 48}, {0, 16}, {16, 48}}, {{32, 0}, {16, 32}, {40, 32}, {48, 48}, {0, 32}, {0, 48}}};
        for (int l = 0; l < 2; l++) {
            boolean cut = l == 1;
            float gh = cut ? 0.5f : 0, gb = cut ? 0.25f : 0;
            float[][] uv = uvs[l];
            cuboid(q, new float[] {-4, 24, -4}, new float[] {8, 8, 8}, gh, uv[0][0], uv[0][1], id, false, cut);
            cuboid(q, new float[] {-4, 12, -2}, new float[] {8, 12, 4}, gb, uv[1][0], uv[1][1], id, false, cut);
            cuboid(q, new float[] {-4 - aw, 12, -2}, new float[] {aw, 12, 4}, gb, uv[2][0], uv[2][1], ra, false, cut);
            cuboid(q, new float[] {4, 12, -2}, new float[] {aw, 12, 4}, gb, uv[3][0], uv[3][1], la, false, cut);
            cuboid(q, new float[] {-4, 0, -2}, new float[] {4, 12, 4}, gb, uv[4][0], uv[4][1], id, false, cut);
            cuboid(q, new float[] {0, 0, -2}, new float[] {4, 12, 4}, gb, uv[5][0], uv[5][1], id, false, cut);
        }
        if (hasCape) {
            Xf xf = p -> { float[] r = rotX(rotY(p, (float) Math.PI), 0.14f); return new float[] {r[0], r[1] + 24, r[2] - 2.9f}; };
            cuboid(q, new float[] {-5, -16, -0.5f}, new float[] {10, 16, 1}, 0, 0, 0, xf, true, true);
        }
        return q;
    }

    private static Xf arm(float px, float py, float a) {
        return p -> { float[] r = rotZ(new float[] {p[0] - px, p[1] - py, p[2]}, a); return new float[] {r[0] + px, r[1] + py, r[2]}; };
    }

    /** The player turned by yaw radians, W x H, transparent background. skin is 64x64 (64x32 old skins are upgraded by the launcher). */
    static BufferedImage render(BufferedImage skin, boolean slim, BufferedImage cape, float yaw, int W, int H) {
        int[] px = new int[W * H];
        float[] depth = new float[W * H];
        float dist = 90, k = H * 0.86f / 34f, cx = W / 2f, cy = H / 2f;
        float[] light = {-0.45f, 0.6f, 0.75f};
        float ln = (float) Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
        for (int i = 0; i < 3; i++) light[i] /= ln;
        for (Quad quad : model(slim, cape != null)) {
            BufferedImage tex = quad.cape ? cape : skin;
            if (tex == null) continue;
            int tw = tex.getWidth(), th = tex.getHeight();
            float scale = tw / 64f;
            float[][] cam = new float[4][];
            for (int i = 0; i < 4; i++) cam[i] = rotX(rotY(new float[] {quad.p[i][0], quad.p[i][1] - 16.5f, quad.p[i][2]}, yaw), 0.12f);
            float[] e1 = sub(cam[1], cam[0]), e2 = sub(cam[2], cam[1]), n = cross(e1, e2);
            float nl = Math.max(1e-6f, (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]));
            float nd = -(n[0] * light[0] + n[1] * light[1] + n[2] * light[2]) / nl;
            float shade = 0.5f + 0.5f * Math.max(0, nd);
            float[][] sp = new float[4][];
            for (int i = 0; i < 4; i++) { float iw = 1f / (dist - cam[i][2]); sp[i] = new float[] {cx + cam[i][0] * dist * iw * k, cy - cam[i][1] * dist * iw * k, iw}; }
            float u0 = quad.uv[0], v0 = quad.uv[1], u1 = quad.uv[2], v1 = quad.uv[3];
            float[][] uvs = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            int umin = (int) Math.floor(u0 * scale), umax = (int) Math.ceil(u1 * scale) - 1, vmin = (int) Math.floor(v0 * scale), vmax = (int) Math.ceil(v1 * scale) - 1;
            int[][] tris = {{0, 1, 2}, {0, 2, 3}};
            for (int[] tri : tris) {
                float[] a = sp[tri[0]], b = sp[tri[1]], c = sp[tri[2]];
                float area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1]);
                if (area <= 0) continue;
                float[] ta = uvs[tri[0]], tb = uvs[tri[1]], tc = uvs[tri[2]];
                int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), x1 = Math.min(W - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
                int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), y1 = Math.min(H - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
                for (int y = y0; y <= y1; y++) {
                    float fy = y + 0.5f;
                    for (int x = x0; x <= x1; x++) {
                        float fx = x + 0.5f;
                        float wa = (b[0] - fx) * (c[1] - fy) - (c[0] - fx) * (b[1] - fy);
                        float wb = (c[0] - fx) * (a[1] - fy) - (a[0] - fx) * (c[1] - fy);
                        float wc = area - wa - wb;
                        if (wa < 0 || wb < 0 || wc < 0) continue;
                        float la = wa / area, lb = wb / area, lc = wc / area;
                        float iw = la * a[2] + lb * b[2] + lc * c[2];
                        int i = y * W + x;
                        if (iw <= depth[i]) continue;
                        float u = (la * ta[0] * a[2] + lb * tb[0] * b[2] + lc * tc[0] * c[2]) / iw;
                        float v = (la * ta[1] * a[2] + lb * tb[1] * b[2] + lc * tc[1] * c[2]) / iw;
                        int tx = Math.max(0, Math.min(tw - 1, Math.max(umin, Math.min(umax, (int) (u * scale)))));
                        int ty = Math.max(0, Math.min(th - 1, Math.max(vmin, Math.min(vmax, (int) (v * scale)))));
                        int t = tex.getRGB(tx, ty);
                        if (quad.cut && (t >>> 24) < 128) continue;
                        depth[i] = iw;
                        int r = (int) (((t >> 16) & 255) * shade), g = (int) (((t >> 8) & 255) * shade), bl = (int) ((t & 255) * shade);
                        px[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                    }
                }
            }
        }
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, W, H, px, 0, W);
        return out;
    }

    private static float[] sub(float[] a, float[] b) { return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static float[] cross(float[] a, float[] b) { return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
}
