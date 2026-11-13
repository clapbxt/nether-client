package dev.nether.ov;

import java.util.Arrays;
import java.util.Locale;

/** Frame times of the last seconds (ring buffer, filled only while a module that needs it is on) and their lows. */
final class FrameStats {
    private FrameStats() {}

    /** set each frame by FPS (stats) / Frame Graph while they are shown; the overlay records the frame only then */
    static boolean want;
    private static final int N = 16384;
    private static final float[] ring = new float[N], tmp = new float[N];
    private static int n;
    private static long at;
    /** fps over the window (0 = not enough frames yet): average, worst frame, 1% low, 0.1% low */
    static float avg, min, low1, low01;
    /** worst frame time in ms per graph column, oldest first */
    static final float[] cols = new float[120];
    static int colsUsed;

    static void add(float dt) { ring[n++ & (N - 1)] = dt; }

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

/** Frame-time graph with average and 1% / 0.1% lows. */
final class FrameGraph extends Module {
    private static final float W = 168;

    FrameGraph() {
        super("framegraph", "Frame Graph", "HUD", "Frame times as a graph, with average and 1% / 0.1% lows.", true, false, 0.012, 0.3);
        add(Setting.slider("window", "Window (s)", 5, 60, 5, 10).hint("How many seconds the graph and the lows cover."));
        add(Setting.slider("height", "Graph height", 20, 80, 2, 36).hint("Height of the graph in GUI pixels."));
        add(Setting.choice("target", "Target line", "60", "Off", "30", "60", "120", "144", "240").hint("A line at the frame time of this FPS; bars above it turn amber, far above red."));
        add(Setting.bool("stats", "Show numbers", true).hint("Average, 1% low and 0.1% low under the graph."));
        add(Setting.bool("spikes", "Color spikes", true).hint("Bars change color by how far they are over the target."));
        needs(0);
    }

    private float tgtMs() { String t = str("target"); try { return 1000f / Integer.parseInt(t); } catch (NumberFormatException e) { return 1000f / 60; } }

    @Override void tick() { if (live()) FrameStats.want = true; }

    @Override void measure() { w = W; h = 14 + (float) num("height") + (bool("stats") ? 16 : 6); }

    @Override void draw(float x, float y) {
        panel(x, y, w, h);
        FrameStats.refresh((int) num("window"), 100);
        int n = 100;
        float gh = (float) num("height"), gx = x + 8, gw = w - 16, gy = y + 18;
        boolean sample = FrameStats.avg == 0 && Modules.sampling(this);
        float[] c = FrameStats.cols;
        float worst = 0;
        for (int i = 0; i < n; i++) worst = Math.max(worst, sample ? 7 + (float) (3 * Math.sin(i * 0.7) + (i % 23 == 0 ? 14 : 0)) : c[i]);
        float tm = tgtMs(), top = Math.max(worst * 1.1f, str("target").equals("Off") ? 20 : tm * 1.6f);
        float last = 0;
        for (int i = n - 1; i >= 0 && last == 0; i--) last = sample ? 8 : c[i];
        txt(Gfx.SEMI, 10, x + 8, y + 9, labelColor(), "FRAME TIME");
        String ms = String.format(Locale.ROOT, "%.1f ms", last);
        txt(Gfx.MONO, 11, x + w - 8 - Gfx.width(Gfx.MONO, 11, ms), y + 9, color(), ms);
        float bw = gw / n;
        float ref = str("target").equals("Off") ? 1000f / 60 : tm;
        for (int i = 0; i < n; i++) {
            float v = sample ? 7 + (float) (3 * Math.sin(i * 0.7) + (i % 23 == 0 ? 14 : 0)) : c[i];
            if (v <= 0) continue;
            float bh = Math.max(1, Math.min(1, v / top) * gh);
            int col = !bool("spikes") ? Gfx.a(color(), 0.85f) : v <= ref * 1.05f ? 0xFF3DDC84 : v <= ref * 2f ? 0xFFFFB020 : Gfx.RED2;
            Gfx.rect(gx + i * bw, gy + gh - bh, Math.max(1, bw - 0.4f), bh, 0, Gfx.a(col, 0.9f));
        }
        if (!str("target").equals("Off") && tm < top) Gfx.rect(gx, gy + gh - tm / top * gh, gw, 1, 0, Gfx.a(Gfx.ICE, 0.45f));
        if (bool("stats")) {
            float a = sample ? 142 : FrameStats.avg, l1 = sample ? 98 : FrameStats.low1, l2 = sample ? 61 : FrameStats.low01;
            String s = String.format(Locale.ROOT, "AVG %.0f   1%% %.0f   0.1%% %.0f", a, l1, l2);
            txt(Gfx.MONO, 10, x + 8, y + h - 9, a > 0 ? color() : Gfx.DIM, a > 0 ? s : "collecting...");
        }
    }
}
