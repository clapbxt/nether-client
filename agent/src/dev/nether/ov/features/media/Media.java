package dev.nether.ov.features.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import dev.nether.ov.framework.Json;
import dev.nether.ov.game.Data;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Icons;

/**
 * Now-playing bridge for {@link MediaPlayer} and {@link Lyrics}: runs {@code nether-media.exe} (crates/media, Windows
 * media controls) while either module wants it, reads its line-JSON states on a daemon thread and sends commands on its
 * stdin. The helper exits when our stdin closes or this JVM's process ends; it is also killed on stop and at JVM exit.
 * Positions are extrapolated ({@code pos + (now - ts) * rate}) so progress bars and lyrics move smoothly between the
 * player's coarse timeline updates; a play/pause click shows its result at once and holds it for 1.5 s (idea from Velo
 * Client's NowPlaying, MIT) so the button does not flicker while the player catches up.
 * Helper path: -Dnether.media, else nether-media.exe beside the -javaagent jar. Dev: -Dnether.mediaFake=<json file or
 * inline json> plays a fake track (no helper).
 */
public final class Media {
    private Media() {}

    /** One immutable now-playing snapshot. */
    public static final class State {
        public final String app, title, artist, album, art;
        public final boolean playing, canPlay, canNext, canPrev, canSeek;
        public final double pos, dur, rate;
        final long ts;

        public State(String app, String title, String artist, String album, String art, boolean playing, double pos, double dur, double rate, long ts,
              boolean canPlay, boolean canNext, boolean canPrev, boolean canSeek) {
            this.app = app; this.title = title; this.artist = artist; this.album = album; this.art = art; this.playing = playing;
            this.pos = pos; this.dur = dur; this.rate = rate; this.ts = ts;
            this.canPlay = canPlay; this.canNext = canNext; this.canPrev = canPrev; this.canSeek = canSeek;
        }

        /** Position in seconds now (extrapolated while playing, clamped to the duration). */
        public double position() {
            double p = playing ? pos + Math.max(0, System.currentTimeMillis() - ts) / 1000.0 * rate : pos;
            return dur > 0 ? Math.min(p, dur) : p;
        }

        public boolean timeline() { return dur > 0; }
        /** same track (title/artist/album/source) */
        boolean same(State o) { return o != null && title.equals(o.title) && artist.equals(o.artist) && album.equals(o.album) && app.equals(o.app); }

        State with(boolean play, double p) { return new State(app, title, artist, album, art, play, p, dur, rate, System.currentTimeMillis(), canPlay, canNext, canPrev, canSeek); }
    }

    /** current track, null = nothing playing (or helper not running) */
    public static volatile State cur;
    /** why there is no data: null ok, else a short upper-case note for the module's card */
    static volatile String problem;

    private static Process proc;
    private static OutputStream stdin;
    private static long keepAt, retryAt, holdUntil;
    private static int fails;
    private static State held;
    private static String prefer = "auto";
    private static Path artDir;

    // album art: decoded off the render thread, uploaded on it
    public static final String ART = "media:art";
    private static volatile BufferedImage artImg;
    private static volatile String artFor = "";
    /** path of the art now uploaded as {@link #ART} ("" = none), and when it arrived (fade in) */
    public static String artShown = "";
    static long artAt;
    /** average colour of the cover (album accent), ARGB */
    public static volatile int artColor = Gfx.RED;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(Media::kill));
    }

    public static boolean supported() { return System.getProperty("os.name", "").startsWith("Windows") || fake() != null; }

    /** A module needs now-playing data this frame (helper started lazily, stopped 2 s after the last call). */
    public static void keep() { keepAt = System.currentTimeMillis(); }

    /** Render thread, every frame (from MediaPlayer.tick): start/stop the helper, upload decoded art. */
    static void poll() {
        long now = System.currentTimeMillis();
        boolean want = now - keepAt < 2000;
        String f = fake();
        if (f != null) {
            if (want) fakeTick(f, now);
        } else if (want && proc == null && now >= retryAt && supported()) start(now);
        else if (!want && proc != null) stop();
        if (proc != null && !proc.isAlive()) { // crashed or blocked: back off, then give up after three tries
            proc = null;
            stdin = null;
            cur = null;
            fails++;
            retryAt = now + 5000L * fails;
            if (fails >= 3) { problem = "MEDIA HELPER UNAVAILABLE"; retryAt = Long.MAX_VALUE; }
        }
        BufferedImage img = artImg;
        if (img != null) {
            artImg = null;
            Gfx.texture(ART, img, true);
            artShown = artFor;
            artAt = now;
        }
        State s = cur;
        if (s == null || s.art.isEmpty()) { if (!artShown.isEmpty()) { Gfx.dropTexture(ART); artShown = ""; } }
        else if (!s.art.equals(artFor)) loadArt(s.art);
    }

    // ---------------------------------------------------------------- helper process

    private static File exe() {
        String p = System.getProperty("nether.media");
        if (p != null && !p.isEmpty()) return new File(p);
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (!a.startsWith("-javaagent:")) continue;
                String jar = a.substring(11);
                int eq = jar.indexOf('=');
                if (eq > 0) jar = jar.substring(0, eq);
                File f = new File(new File(jar).getAbsoluteFile().getParentFile(), "nether-media.exe");
                if (f.isFile()) return f;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void start(long now) {
        File exe = exe();
        if (exe == null || !exe.isFile() || exe.length() == 0) { problem = "MEDIA HELPER MISSING"; retryAt = now + 30_000; return; }
        try {
            if (artDir == null) artDir = Data.dir("cache/media");
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "--parent", pid, "--art-dir", artDir == null ? "" : artDir.toString());
            pb.redirectError(ProcessBuilder.Redirect.appendTo(log()));
            final Process p = pb.start();
            proc = p;
            stdin = p.getOutputStream();
            problem = null;
            Thread t = new Thread(() -> read(p), "nether-media");
            t.setDaemon(true);
            t.start();
            if (!"auto".equals(prefer)) send("prefer " + prefer);
        } catch (Throwable e) {
            System.out.println("[nether-agent] media helper: " + e);
            proc = null;
            fails++;
            retryAt = now + 10_000;
            problem = "MEDIA HELPER UNAVAILABLE";
        }
    }

    private static File log() {
        Path d = Data.dir("cache/media");
        return d == null ? new File(System.getProperty("java.io.tmpdir"), "nether-media.log") : d.resolve("helper.log").toFile();
    }

    private static void stop() {
        kill();
        proc = null;
        stdin = null;
        cur = null;
    }

    private static void kill() {
        Process p = proc;
        if (p == null) return;
        try { p.getOutputStream().close(); } catch (Throwable ignored) { } // helper exits on stdin EOF
        p.destroy();
    }

    private static void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (proc != p) break;
                try { handle(line); } catch (Throwable t) { System.out.println("[nether-agent] media line: " + t); }
            }
        } catch (Throwable ignored) { }
        if (proc == p) cur = null;
    }

    @SuppressWarnings("unchecked")
    private static void handle(String line) {
        Object o = Json.parse(line);
        if (!(o instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) o;
        String t = s(m, "t");
        if ("hello".equals(t)) { fails = 0; return; }
        if ("error".equals(t)) { problem = "MEDIA CONTROLS UNAVAILABLE"; System.out.println("[nether-agent] media helper: " + s(m, "msg")); return; }
        if ("none".equals(t)) { set(null); return; }
        if (!"state".equals(t)) return;
        String art = s(m, "art");
        if (!art.isEmpty() && !insideArtDir(art)) art = ""; // only ever read covers the helper wrote into our folder
        set(new State(s(m, "app"), s(m, "title"), s(m, "artist"), s(m, "album"), art, "playing".equals(s(m, "status")),
            d(m, "pos"), d(m, "dur"), d(m, "rate") > 0 ? d(m, "rate") : 1, (long) d(m, "ts"),
            b(m, "canPlay"), b(m, "canNext"), b(m, "canPrev"), b(m, "canSeek")));
    }

    private static void set(State s) {
        // optimistic hold: a state that contradicts the button the user just pressed waits until the hold ends
        if (System.currentTimeMillis() < holdUntil && held != null && s != null && s.same(held) && s.playing != held.playing) return;
        cur = s;
    }

    private static boolean insideArtDir(String p) {
        try {
            if (artDir == null) return false;
            Path f = Paths.get(p).toRealPath(), d = artDir.toRealPath();
            return f.startsWith(d) && f.getFileName().toString().startsWith("art-");
        } catch (Throwable t) { return false; }
    }

    private static String s(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof String ? (String) o : ""; }
    private static double d(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof Number ? ((Number) o).doubleValue() : 0; }
    private static boolean b(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }

    // ---------------------------------------------------------------- commands

    private static void send(String cmd) {
        OutputStream o = stdin;
        if (o == null) return;
        try { o.write((cmd + "\n").getBytes(StandardCharsets.UTF_8)); o.flush(); } catch (Throwable ignored) { }
    }

    public static void toggle() {
        State s = cur;
        if (s == null) return;
        State n = s.with(!s.playing, s.position());
        held = n;
        holdUntil = System.currentTimeMillis() + 1500;
        cur = n;
        if (fake() != null) { fakeState = n; return; }
        send("toggle");
    }

    public static void next() { if (fake() == null) send("next"); }
    public static void prev() { if (fake() == null) send("prev"); }

    public static void seek(double sec) {
        State s = cur;
        if (s == null || !s.canSeek) return;
        sec = Math.max(0, Math.min(sec, s.dur));
        cur = s.with(s.playing, sec);
        if (fake() != null) { fakeState = cur; return; }
        send("seek " + String.format(java.util.Locale.ROOT, "%.2f", sec));
    }

    /** Source preference: "auto" or lower-case AppUserModelId fragments ("spotify"). */
    static void prefer(String p) {
        if (p.equals(prefer)) return;
        prefer = p;
        send("prefer " + p);
    }

    // ---------------------------------------------------------------- album art

    private static void loadArt(final String path) {
        artFor = path;
        Thread t = new Thread(() -> {
            try {
                BufferedImage src; // album art can come from web pages (media sessions): size-checked before decoding
                try (java.io.InputStream in = new java.io.FileInputStream(path)) { src = Icons.decode(in, 4L << 20); }
                if (src == null || !path.equals(artFor)) return; // unsupported format (e.g. WebP): no cover, no crash
                int n = 128;
                BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                // centre-crop to a square (video thumbnails are 16:9)
                int w = src.getWidth(), h = src.getHeight(), side = Math.min(w, h);
                g.drawImage(src, 0, 0, n, n, (w - side) / 2, (h - side) / 2, (w - side) / 2 + side, (h - side) / 2 + side, null);
                g.dispose();
                artColor = accent(out);
                if (path.equals(artFor)) artImg = out;
            } catch (Throwable e) {
                System.out.println("[nether-agent] media art: " + e);
            }
        }, "nether-media-art");
        t.setDaemon(true);
        t.start();
    }

    /** Most saturated of the cover's average colours, brightened enough to read on the dark card. */
    private static int accent(BufferedImage img) {
        float best = -1;
        int pick = Gfx.RED;
        for (int by = 0; by < 4; by++) for (int bx = 0; bx < 4; bx++) {
            long r = 0, g = 0, b = 0;
            for (int y = by * 32; y < by * 32 + 32; y += 4) for (int x = bx * 32; x < bx * 32 + 32; x += 4) {
                int p = img.getRGB(x, y);
                r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255;
            }
            float[] hsb = java.awt.Color.RGBtoHSB((int) (r / 64), (int) (g / 64), (int) (b / 64), null);
            float score = hsb[1] * (0.3f + hsb[2]);
            if (score > best) { best = score; pick = java.awt.Color.HSBtoRGB(hsb[0], Math.min(1, hsb[1] * 1.1f), Math.max(0.75f, hsb[2])); }
        }
        return 0xFF000000 | (pick & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- dev: fake track

    private static String fakeSrc;
    private static State fakeState;

    private static String fake() {
        if (fakeSrc == null) { String p = System.getProperty("nether.mediaFake"); fakeSrc = p == null ? "" : p.trim(); }
        return fakeSrc.isEmpty() ? null : fakeSrc;
    }

    @SuppressWarnings("unchecked")
    private static void fakeTick(String src, long now) {
        if (fakeState == null) {
            try {
                String json = src.startsWith("{") ? src : new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
                Map<String, Object> m = (Map<String, Object>) Json.parse(json);
                fakeState = new State(s(m, "app"), s(m, "title"), s(m, "artist"), s(m, "album"), s(m, "art"), !"paused".equals(s(m, "status")),
                    d(m, "pos"), d(m, "dur"), 1, now, true, true, true, true);
            } catch (Throwable t) {
                System.out.println("[nether-agent] mediaFake: " + t);
                fakeSrc = "";
                return;
            }
        }
        State s = fakeState;
        if (s.dur > 0 && s.position() >= s.dur) fakeState = s = s.with(s.playing, 0); // loop
        if (cur != s && System.currentTimeMillis() >= holdUntil) cur = s;
    }
}
