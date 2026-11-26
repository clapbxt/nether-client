package dev.nether.ov.features.hud;

import dev.nether.ov.game.Data;
import dev.nether.ov.game.Game;

/**
 * Shared by Reach and Combo: your own attack clicks (Input hook) read against the game's crosshair hit result and your
 * own hurt flash. No packets, no other player's state. Switched on per frame by the modules (flags below), so with both
 * off nothing is stored and a click costs one boolean check.
 */
public final class HitTracker {
    private HitTracker() {}

    /** set every frame by Reach / Combo while enabled */
    static boolean reachOn, comboOn;
    /** distance of your last attack on an entity in blocks (-1 none) and when */
    static double reach = -1;
    static long reachAt;
    /** hits in a row, best of the session, time of the last counted hit */
    static int combo, best;
    static long hitAt;
    /** combo reset time in seconds (set by Combo) */
    static double timeout = 2.5;

    private static boolean pending;
    private static int prevHurt;
    private static long lastCount, lastPoll;
    private static final double[] pos = new double[3];
    private static java.lang.ref.WeakReference<Object> target = new java.lang.ref.WeakReference<>(null);

    /** Attack click (left button in the world), from {@link Input}. */
    public static void attack() { if (reachOn || comboOn) pending = true; }

    /** Once per frame from the modules' tick (a second call in the same frame returns at once). */
    static void poll() {
        long ns = System.nanoTime();
        if (ns - lastPoll < 200_000L) return;
        lastPoll = ns;
        long now = System.currentTimeMillis();
        if (!reachOn && !comboOn) { pending = false; combo = 0; prevHurt = 0; return; }
        if (pending) {
            pending = false;
            if (Data.hitType() == 2) {
                if (reachOn && Data.entityHit(pos)) {
                    double[] c = Game.firstPerson() ? Game.camera() : null;
                    if (c == null) { double[] p = Game.player(); if (p != null) c = new double[] {p[0], p[1] + 1.62, p[2]}; }
                    if (c != null) {
                        double dx = pos[0] - c[0], dy = pos[1] - c[1], dz = pos[2] - c[2];
                        reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        reachAt = now;
                    }
                }
                // living targets only (no item frames, boats); a hit is counted at most every 0.5 s, the 10-tick hurt
                // immunity: the target ignores faster clicks (no damage), so they are no hit. A new target starts over.
                Object e = comboOn ? Data.hitLiving() : null;
                if (e != null && now - lastCount >= 500) {
                    if (target.get() != e) { combo = 0; target = new java.lang.ref.WeakReference<>(e); }
                    combo++;
                    best = Math.max(best, combo);
                    hitAt = lastCount = now;
                }
            }
        }
        if (comboOn && combo > 0) {
            int h = Data.hurtTime();
            if (h > prevHurt && h > 0) combo = 0; // you were hit
            prevHurt = Math.max(h, 0);
            if (now - hitAt > timeout * 1000) combo = 0;
        } else prevHurt = 0;
    }

    static void reset() { reach = -1; combo = 0; best = 0; }
}
