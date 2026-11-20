package dev.nether.ov;

/** dev (-Dnether.bench=1): names the part of an overlay frame that took longer than 2 ms (render-thread hitches). */
final class Slow {
    private Slow() {}

    private static final boolean ON = "1".equals(System.getProperty("nether.bench"));
    private static long t0, last, worst;
    private static String worstName = "";

    static void start() { if (ON) { t0 = last = System.nanoTime(); worst = 0; } }

    static void mark(String name) {
        if (!ON || t0 == 0) return;
        long now = System.nanoTime(), d = now - last;
        last = now;
        if (d > worst) { worst = d; worstName = name; }
    }

    static void mark2(String a, String b) { if (ON && t0 != 0) mark(a + b); }

    static void end() {
        if (!ON || t0 == 0) return;
        long total = System.nanoTime() - t0;
        if (total > 2_000_000L) System.out.println(String.format(java.util.Locale.ROOT, "[nether-bench] slow overlay frame %.1fms, most in %s %.1fms", total / 1e6, worstName, worst / 1e6));
        t0 = 0;
    }
}
