package dev.nether.ov;

/**
 * World to screen projection done by the overlay itself, from the camera position, rotation and FOV of the last frame
 * ({@link Game#camera}): nothing is drawn into the world and no renderer code is touched. No depth test exists, so
 * projected things show through walls. Usage per frame: {@link #begin}, then {@link #to} for every point.
 */
final class Project {
    private Project() {}

    /** Result of the last {@link #to}: nx/ny in -1..1 on screen (y up), depth (blocks along the view), dist (blocks). */
    static double nx, ny, depth, dist;

    private static double cx, cy, cz, fx, fy, fz, rx, rz, ux, uy, uz, tanY, tanX;

    /**
     * Reads the camera for this frame; false when it is unknown (no world, or 1.8.9 in third person, where only the
     * player's eyes are known and projections would sit in the wrong place). W/H = screen size in any unit (aspect).
     */
    static boolean begin(float W, float H) {
        double[] c = Game.legacy() && !Game.firstPerson() ? null : Game.camera();
        if (c == null) return false;
        cx = c[0]; cy = c[1]; cz = c[2];
        // 1.21.2+: the frame's own view rotation and projection (bobbing, hurt tilt, FOV effects), so projected things
        // stay on the world while walking; the camera model below is the fallback (and the legacy path)
        real = !MODEL && System.nanoTime() - dev.nether.agent.Hook.levelNs < 250_000_000L && matrices(VIEW, PROJ);
        if (real) {
            mul(PROJ, VIEW, MVP);
            real = invert(MVP, INV);
            if (real) return true;
        }
        double yaw = Math.toRadians(c[3]), pitch = Math.toRadians(c[4]);
        fx = -Math.sin(yaw) * Math.cos(pitch); fy = -Math.sin(pitch); fz = Math.cos(yaw) * Math.cos(pitch);
        rx = -Math.cos(yaw); rz = -Math.sin(yaw); // right, ry = 0
        ux = -fy * rz; uy = rz * fx - rx * fz; uz = fy * rx; // right x forward
        tanY = Math.tan(Math.toRadians(c[5]) / 2);
        tanX = tanY * W / H;
        return true;
    }

    /**
     * Projects world point (x, y, z). True when it is in front of the camera (nx/ny valid, possibly off screen when
     * |n| > 1); false when behind: nx/ny then hold the raw sideways/up offsets, so callers can push a marker to the edge
     * on the side to turn to.
     */
    static boolean to(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (real) {
            float[] m = MVP;
            double px = m[0] * dx + m[4] * dy + m[8] * dz + m[12], py = m[1] * dx + m[5] * dy + m[9] * dz + m[13], w = m[3] * dx + m[7] * dy + m[11] * dz + m[15];
            depth = w;
            if (w > 0.05) { nx = px / w; ny = py / w; return true; }
            nx = px; ny = py;
            return false;
        }
        depth = dx * fx + dy * fy + dz * fz;
        double sx = dx * rx + dz * rz, sy = dx * ux + dy * uy + dz * uz;
        if (depth > 0.05) {
            nx = sx / (depth * tanX);
            ny = sy / (depth * tanY);
            return true;
        }
        nx = sx;
        ny = sy;
        return false;
    }

    /** Clip-space x, y and w of world point (x, y, z) into out (w > 0 in front of the camera; linear in the point). */
    static void clip(double x, double y, double z, double[] out) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        if (real) {
            float[] m = MVP;
            out[0] = m[0] * dx + m[4] * dy + m[8] * dz + m[12];
            out[1] = m[1] * dx + m[5] * dy + m[9] * dz + m[13];
            out[2] = m[3] * dx + m[7] * dy + m[11] * dz + m[15];
            return;
        }
        out[0] = (dx * rx + dz * rz) / tanX;
        out[1] = (dx * ux + dy * uy + dz * uz) / tanY;
        out[2] = dx * fx + dy * fy + dz * fz;
    }

    /**
     * The overlay map ({@link Gfx#map}) drawing plane point (u, v) at world o + u * du + v * dv, built from the camera
     * matrices themselves rather than from projected corners: its third row is the true clip w, so the GPU clips what
     * is behind the camera and a plane partly behind you (a panel you turn away from) still draws its visible part.
     * W x H = framebuffer px. Call after {@link #begin}.
     */
    static float[] planeMap(double[] o, double[] du, double[] dv, float W, float H) {
        double[] c0 = new double[3], cu = new double[3], cv = new double[3];
        clip(o[0], o[1], o[2], c0);
        clip(o[0] + du[0], o[1] + du[1], o[2] + du[2], cu);
        clip(o[0] + dv[0], o[1] + dv[1], o[2] + dv[2], cv);
        for (int i = 0; i < 3; i++) { cu[i] -= c0[i]; cv[i] -= c0[i]; }
        return new float[] {
            (float) ((cu[0] + cu[2]) / 2 * W), (float) ((cu[2] - cu[1]) / 2 * H), (float) cu[2],
            (float) ((cv[0] + cv[2]) / 2 * W), (float) ((cv[2] - cv[1]) / 2 * H), (float) cv[2],
            (float) ((c0[0] + c0[2]) / 2 * W), (float) ((c0[2] - c0[1]) / 2 * H), (float) c0[2]};
    }

    /** Screen x / y of the last projection in a W x H space (same units as {@link #begin}). */
    static float sx(float W) { return (float) ((nx + 1) / 2 * W); }
    static float sy(float H) { return (float) ((1 - ny) / 2 * H); }

    /** Screen pixel and forward depth for a world point; null when it is behind the camera. */
    static float[] screen(double x, double y, double z, float W, float H) {
        if (!to(x, y, z) || depth <= 0.05) return null;
        return new float[] {sx(W), sy(H), (float) depth};
    }

    /** World-space ray through a framebuffer pixel. The camera basis and FOV come from the last begin call. */
    static double[] ray(float px, float py, float W, float H) {
        if (real) { // unproject the pixel on the near and far planes
            double x = px / W * 2 - 1, y = 1 - py / H * 2;
            double[] a = unproject(x, y, -1), b = unproject(x, y, 1);
            if (a == null || b == null) return null;
            double rx0 = b[0] - a[0], ry0 = b[1] - a[1], rz0 = b[2] - a[2], n = Math.sqrt(rx0 * rx0 + ry0 * ry0 + rz0 * rz0);
            return n < 1e-8 ? null : new double[] {rx0 / n, ry0 / n, rz0 / n};
        }
        double nx = (px / W * 2 - 1) * tanX;
        double ny = (1 - py / H * 2) * tanY;
        double x = fx + rx * nx + ux * ny, y = fy + uy * ny, z = fz + rz * nx + uz * ny;
        double n = Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-8) return null;
        return new double[] {x / n, y / n, z / n};
    }

    // ---------------------------------------------------------------- the game's matrices (Hook.levelMats)

    private static boolean real;
    private static final boolean MODEL = Boolean.getBoolean("nether.projModel"); // dev: the camera model only (compare with the real matrices)
    private static final float[] VIEW = new float[16], PROJ = new float[16], MVP = new float[16], INV = new float[16];
    private static java.lang.reflect.Method matGet;
    private static java.lang.reflect.Field camProj, camView;

    /** This frame's LevelRenderer.renderLevel view rotation and projection (column-major, as JOML) into view / proj. */
    static boolean matrices(float[] view, float[] proj) {
        try {
            Object v = dev.nether.agent.Hook.levelView, pr = dev.nether.agent.Hook.levelProj, c = dev.nether.agent.Hook.levelCam;
            if (c != null) { // 26.1: CameraRenderState.viewRotationMatrix / projectionMatrix
                if (camProj == null) { camProj = c.getClass().getField("projectionMatrix"); camView = c.getClass().getField("viewRotationMatrix"); }
                v = camView.get(c); pr = camProj.get(c);
            }
            if (v == null || pr == null) return false;
            if (matGet == null) matGet = Class.forName("org.joml.Matrix4fc", false, v.getClass().getClassLoader()).getMethod("get", float[].class);
            matGet.invoke(v, (Object) view);
            matGet.invoke(pr, (Object) proj);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** out = a * b (column-major 4x4). */
    private static void mul(float[] a, float[] b, float[] out) {
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++)
            out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
    }

    /** General 4x4 inverse (cofactors); false when singular. */
    private static boolean invert(float[] m, float[] inv) {
        double[] t = new double[16];
        t[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        t[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        t[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        t[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        t[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        t[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        t[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        t[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        t[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        t[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        t[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        t[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        t[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        t[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        t[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        t[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        double det = m[0] * t[0] + m[1] * t[4] + m[2] * t[8] + m[3] * t[12];
        if (Math.abs(det) < 1e-12) return false;
        for (int i = 0; i < 16; i++) inv[i] = (float) (t[i] / det);
        return true;
    }

    /** Camera-relative world point at normalized device coordinates (x, y, z). */
    private static double[] unproject(double x, double y, double z) {
        float[] m = INV;
        double px = m[0] * x + m[4] * y + m[8] * z + m[12], py = m[1] * x + m[5] * y + m[9] * z + m[13], pz = m[2] * x + m[6] * y + m[10] * z + m[14], w = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (Math.abs(w) < 1e-9) return null;
        return new double[] {px / w, py / w, pz / w};
    }
}
