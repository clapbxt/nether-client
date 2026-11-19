package dev.nether.ov;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Background FPS (2026-11-20, vault: research/FPS boosters research): caps the frame rate while another window is in
 * front or the game is minimized, on every version and with or without OptiFine. It only ever lowers the frame rate,
 * so it sits fine next to vanilla's own "Reduce FPS when" (1.21.2+: the lower cap wins). When the launcher reports
 * Dynamic FPS (-Dnether.dynfps=1) it steps aside so that mod's settings rule. Runs at the start of every buffer
 * swap: waiting there paces the whole game loop, as the game's own frame limiter does. Only in a world or on the
 * title screen, never while the game is still loading. Minimized defaults to 5 FPS, not lower: the game runs at
 * most 10 ticks per frame, so 5 FPS still keeps its 20 ticks a second.
 */
final class BackgroundFps extends Module {
    static final BackgroundFps M = new BackgroundFps();

    private BackgroundFps() {
        super("bgfps", "Background FPS", "Utility", "Lowers the frame rate while the game is in the background, so your PC runs cooler and quieter.", false, true, 0, 0);
        add(Setting.slider("behind", "Another window in front", 5, 60, 5, 30).hint("FPS while you use another window. The game stays watchable."));
        add(Setting.slider("min", "Minimized", 1, 30, 1, 5).hint("FPS while the game is minimized. 5 and up keep the game running at full speed."));
    }

    private static final boolean DYNFPS = "1".equals(System.getProperty("nether.dynfps"));
    /** dev: -Dnether.bgfpsTest=1|2 acts as if the window were behind another one / minimized (measuring) */
    private static final int TEST = Integer.getInteger("nether.bgfpsTest", 0);
    /** dev: -Dnether.fpsLog=1 prints frames per second and the window state every 5 s */
    private static final boolean LOG = "1".equals(System.getProperty("nether.fpsLog"));

    @Override boolean available() { return !DYNFPS; }
    @Override String lockNote() { return DYNFPS ? "DYNAMIC FPS IS INSTALLED: IT SETS THE BACKGROUND FPS" : super.lockNote(); }

    private static long next, logAt;
    private static int logFrames;

    static void limit(long window) {
        long now = System.nanoTime();
        int away = TEST > 0 ? TEST : Plat.cur.away(window);
        if (LOG) log(now, away);
        int cap = 0;
        if (away > 0 && M.enabled() && (Game.hasPlayer() || MainMenu.title)) cap = (int) M.num(away == 2 ? "min" : "behind");
        if (cap <= 0) { next = 0; return; }
        long gap = 1_000_000_000L / cap;
        if (next == 0 || next - now > gap) next = now; // first capped frame, or the cap was raised
        if (next > now) LockSupport.parkNanos(next - now);
        next = Math.max(next, now) + gap; // a slow frame never banks time for a burst after it
    }

    private static void log(long now, int away) {
        logFrames++;
        if (logAt == 0) logAt = now;
        if (now - logAt < 5_000_000_000L) return;
        double fps = logFrames * 1e9 / (now - logAt);
        System.out.println(String.format(Locale.ROOT, "[nether-agent] fps %.1f away %d world %b capped %b", fps, away, Game.hasPlayer(), M.enabled() && away > 0));
        logFrames = 0;
        logAt = now;
    }
}
