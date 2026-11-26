package dev.nether.ov.framework;

import java.util.Arrays;
import java.util.Locale;

/** Frame times of the last seconds (ring buffer, filled only while a module that needs it is on) and their lows. */
public final class FrameStats {
    private FrameStats() {}

    /** set each frame by FPS (stats) while it is shown; the overlay records the frame only then */
    public static boolean want;
    private static final int N = 16384;
    private static final float[] ring = new float[N], tmp = new float[N];
    private static int n;
    private static long at;
    /** fps over the window (0 = not enough frames yet): average, worst frame, 1% low, 0.1% low */
    static float avg, min, low1, low01;
    /** worst frame time in ms per graph column, oldest first */
    static final float[] cols = new float[120];
    static int colsUsed;

    public static void add(float dt) { ring[n++ & (N - 1)] = dt; }

    /** Recomputes the numbers at most 4 times a second. */
    static void refresh(int windowS, int columns) {
        long now = System.currentTimeMillis();
        if (now - at < 250) return;
        at = now;
        columns = Math.max(1, Math.min(columns, cols.length));
        int k = 0;
        double sum = 0;
        while (k < N && k < n && sum < windowS) { float d = ring[(n - 1 - k) & (N - 1)]; tmp[k++] = d; sum += d; }
        colsUsed = columns;
        Arrays.fill(cols, 0, columns, 0f);
        if (k < 2 || sum <= 0) { avg = min = low1 = low01 = 0; return; }
        double t = 0;
        for (int i = 0; i < k; i++) {
            int c = columns - 1 - Math.min(columns - 1, (int) (t / sum * columns));
            cols[c] = Math.max(cols[c], tmp[i] * 1000f);
            t += tmp[i];
        }
        avg = (float) (k / sum);
        Arrays.sort(tmp, 0, k);
        min = 1f / Math.max(tmp[k - 1], 1e-4f);
        low1 = 1f / Math.max(tmp[Math.min(k - 1, (int) (k * 0.99))], 1e-4f);
        low01 = 1f / Math.max(tmp[Math.min(k - 1, (int) (k * 0.999))], 1e-4f);
    }
}
