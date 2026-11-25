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
        double nx = (px / W * 2 - 1) * tanX;
        double ny = (1 - py / H * 2) * tanY;
        double x = fx + rx * nx + ux * ny, y = fy + uy * ny, z = fz + rz * nx + uz * ny;
        double n = Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-8) return null;
        return new double[] {x / n, y / n, z / n};
    }
}
