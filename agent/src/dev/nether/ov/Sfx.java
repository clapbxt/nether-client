package dev.nether.ov;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Interface sounds for the Nether menus: the launcher's earcon set (crates/app/src/sfx.rs, same tones), synthesized
 * and played with Java Sound on its own thread, so it works on every game version and never touches the game's
 * sound engine. Level = the launcher's "Interface sounds" (config launcher.sounds: 0 off, 1 quiet, 2 normal).
 */
final class Sfx {
    private Sfx() {}

    /** Priority order: when two land together (a toggle's own click), the higher one plays. */
    static final int CLICK = 0, OPEN = 1, ON = 2, OFF = 3, ERROR = 4;

    private static final int RATE = 44100;
    private static final LinkedBlockingQueue<Integer> Q = new LinkedBlockingQueue<>();
    private static volatile boolean started;

    //                              at     f0      f1      len    atk     tau    amp   h2    h3    noise
    private static final float[][] CLICK_T = {{0, 1650, 1500, .040f, .0006f, .009f, .50f, .10f, 0, .22f}};
    private static final float[][] ON_T = {{0, 1180, 1180, .050f, .0008f, .016f, .40f, .12f, 0, .08f}, {.045f, 1580, 1580, .070f, .0008f, .024f, .40f, .12f, 0, 0}};
    private static final float[][] OFF_T = {{0, 1580, 1580, .050f, .0008f, .016f, .36f, .12f, 0, .08f}, {.045f, 1180, 1180, .070f, .0008f, .024f, .36f, .12f, 0, 0}};
    private static final float[][] OPEN_T = {{0, 520, 860, .150f, .012f, .045f, .30f, .15f, 0, 0}};
    private static final float[][] ERROR_T = {{0, 440, 430, .110f, .002f, .050f, .34f, 0, .22f, 0}, {.090f, 349.2f, 340, .160f, .002f, .070f, .34f, 0, .22f, 0}};

    static void click() { play(CLICK); }
    static void open() { play(OPEN); }
    static void toggle(boolean nowOn) { play(nowOn ? ON : OFF); }
    static void error() { play(ERROR); }

    static void play(int k) {
        if (gain() <= 0) return;
        if (!started) start();
        Q.offer(k);
    }

    private static float gain() {
        int l = Config.launcherSounds();
        return l <= 0 ? 0 : l == 1 ? 0.22f : 0.45f;
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(Sfx::run, "nether-sfx");
        t.setDaemon(true);
        t.start();
    }

    /** Opens the speakers on demand and lets go after a quiet minute (an open line keeps Windows "playing audio"). */
    private static void run() {
        SourceDataLine line = null;
        int seed = 0x12345678;
        while (true) {
            try {
                Integer first = Q.poll(60, TimeUnit.SECONDS);
                if (first == null) {
                    if (line != null) { line.close(); line = null; }
                    continue;
                }
                int k = first;
                Integer more;
                while ((more = Q.poll(4, TimeUnit.MILLISECONDS)) != null) k = Math.max(k, more);
                if (line == null) {
                    AudioFormat f = new AudioFormat(RATE, 16, 1, true, false);
                    line = AudioSystem.getSourceDataLine(f);
                    line.open(f, RATE / 10 * 2); // ~100 ms buffer: low latency
                }
                seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                float pitch = k == CLICK ? 0.97f + 0.06f * ((seed >>> 1) / (float) Integer.MAX_VALUE) : 1f;
                byte[] pcm = pcm(render(k, pitch), gain());
                line.flush();
                line.start();
                line.write(pcm, 0, pcm.length);
                // A started line that runs dry replays its ring buffer on Windows (DirectSound): the last sound looped
                // with nobody touching anything. Pad with a full buffer of silence, let it play out, then stop the line.
                byte[] quiet = new byte[line.getBufferSize()];
                line.write(quiet, 0, quiet.length);
                line.drain();
                line.stop();
            } catch (Throwable e) {
                // no speakers, device gone, line busy: stay quiet and try again next time
                if (line != null) { try { line.close(); } catch (Throwable ignored) { } line = null; }
            }
        }
    }

    private static float[][] tones(int k) {
        switch (k) {
            case ON: return ON_T;
            case OFF: return OFF_T;
            case OPEN: return OPEN_T;
            case ERROR: return ERROR_T;
            default: return CLICK_T;
        }
    }

    static float[] render(int k, float pitch) {
        float[][] ts = tones(k);
        float end = 0;
        for (float[] t : ts) end = Math.max(end, t[0] + t[3]);
        float[] out = new float[(int) (end * RATE) + 1];
        int seed = 0x9E3779B9;
        for (float[] t : ts) {
            int start = (int) (t[0] * RATE), len = (int) (t[3] * RATE);
            double phase = 0;
            for (int i = 0; i < len && start + i < out.length; i++) {
                float s = i / (float) RATE;
                float f = (t[1] + (t[2] - t[1]) * (s / t[3])) * pitch;
                phase += 2 * Math.PI * f / RATE;
                double env = (1 - Math.exp(-s / t[4])) * Math.exp(-s / t[5]);
                float tail = Math.min(1f, (len - i) / (0.002f * RATE));
                double v = Math.sin(phase) + t[7] * Math.sin(2 * phase) + t[8] * Math.sin(3 * phase);
                if (t[9] > 0 && s < 0.004f) {
                    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                    v += t[9] * (((seed >>> 1) / (float) Integer.MAX_VALUE) * 2 - 1) * (1 - s / 0.004f);
                }
                out[start + i] += (float) (v * env * tail * t[6]);
            }
        }
        return out;
    }

    private static byte[] pcm(float[] f, float g) {
        byte[] b = new byte[f.length * 2];
        for (int i = 0; i < f.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, f[i] * g)) * 32767);
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }
}
