package dev.nether.ov;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Shared HTTPS GET for modules (lyrics, album art, content lists). Never runs on the render thread: requests queue to
 * one daemon worker, one in flight, at least 300 ms apart (polite to small community APIs). 429/503 honour Retry-After
 * (plus jitter, two retries). Answers are cached on disk under {@code <client>/cache/net} for the caller's TTL; a 404 is
 * remembered for 7 days so a missing item is not asked for again. Bodies are capped (default 2 MB) and the cache folder
 * is trimmed to 64 MB, oldest first. Java 8 runtimes (1.8.9) get an explicit TLS 1.2 context.
 * <p>Use from the render thread: {@code Net.Req r = Net.get(url, ttl)}, then poll {@code r.done()} on later frames.
 */
final class Net {
    private Net() {}

    static final String UA = "NetherClient/" + System.getProperty("nether.version", "0.1.0") + " (https://github.com/clapbxt/nether-client)"; // LRCLIB asks for app, version and a homepage
    private static final long NEG_TTL = 7L * 24 * 3600 * 1000, CACHE_CAP = 64L << 20, SPACING = 300;
    private static final int DEF_MAX = 2 << 20;

    /** One request; fields are written by the worker, read by anyone once {@link #done} is true. */
    static final class Req {
        final String url;
        final long ttl;
        final int maxBytes;
        /** 200 ok, 404 not found (also from the negative cache), other HTTP codes, -1 network error / too large */
        volatile int status;
        volatile byte[] body;
        volatile boolean done;
        /** answer came from the disk cache */
        volatile boolean cached;

        Req(String url, long ttl, int maxBytes) { this.url = url; this.ttl = ttl; this.maxBytes = maxBytes; }

        boolean done() { return done; }
        boolean ok() { return done && status == 200 && body != null; }
        String text() { return body == null ? null : new String(body, StandardCharsets.UTF_8); }
    }

    private static final ArrayDeque<Req> queue = new ArrayDeque<>();
    /** queued or running, by url: the same url asked twice shares one request */
    private static final Map<String, Req> live = new HashMap<>();
    private static Thread worker;
    private static long lastAt;

    static Req get(String url, long ttlMs) { return get(url, ttlMs, DEF_MAX); }

    /** Queues a GET of an https url (others fail at once). ttlMs = how long a cached answer is reused (0 = always fetch). */
    static synchronized Req get(String url, long ttlMs, int maxBytes) {
        Req r = live.get(url);
        if (r != null) return r;
        r = new Req(url, ttlMs, maxBytes);
        if (!url.startsWith("https://")) { r.status = -1; r.done = true; return r; }
        live.put(url, r);
        queue.add(r);
        if (worker == null) {
            worker = new Thread(Net::run, "nether-net");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        Net.class.notifyAll();
        return r;
    }

    private static void run() {
        while (true) {
            Req r;
            synchronized (Net.class) {
                while (queue.isEmpty()) { try { Net.class.wait(); } catch (InterruptedException e) { return; } }
                r = queue.poll();
            }
            try { serve(r); } catch (Throwable t) { r.status = -1; }
            synchronized (Net.class) { live.remove(r.url); }
            r.done = true;
        }
    }

    // ---------------------------------------------------------------- cache

    private static Path dir;

    private static Path cacheDir() {
        if (dir == null) dir = Data.dir("cache/net");
        return dir;
    }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    private static void serve(Req r) throws Exception {
        Path d = cacheDir();
        String h = hash(r.url);
        File body = d == null ? null : d.resolve(h).toFile(), miss = d == null ? null : d.resolve(h + ".404").toFile();
        long now = System.currentTimeMillis();
        if (miss != null && miss.exists() && now - miss.lastModified() < NEG_TTL) { r.status = 404; r.cached = true; return; }
        // an empty answer ("[]", "{}": nothing found yet) is asked again after a day, not the caller's long TTL
        long ttl = body != null && body.length() <= 2 ? Math.min(r.ttl, 24L * 3600 * 1000) : r.ttl;
        if (body != null && ttl > 0 && body.exists() && now - body.lastModified() < ttl && body.length() <= r.maxBytes) {
            r.body = Files.readAllBytes(body.toPath());
            r.status = 200;
            r.cached = true;
            return;
        }
        for (int attempt = 0; ; attempt++) {
            long wait = lastAt + SPACING - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastAt = System.currentTimeMillis();
            HttpURLConnection c = open(r.url);
            int code;
            try {
                code = c.getResponseCode();
                if ((code == 429 || code == 503) && attempt < 2) {
                    long after = 5000;
                    try { after = Math.min(60_000, Long.parseLong(c.getHeaderField("Retry-After").trim()) * 1000); } catch (Throwable ignored) { }
                    Thread.sleep(after + (long) (Math.random() * 1000));
                    continue;
                }
                r.status = code;
                if (code == 404) {
                    if (miss != null) Files.write(miss.toPath(), new byte[0]);
                    return;
                }
                if (code != 200) return;
                byte[] b = read(c.getInputStream(), r.maxBytes);
                if (b == null) { r.status = -1; return; } // larger than allowed
                r.body = b;
            } finally {
                c.disconnect();
            }
            if (body != null && r.ttl > 0) store(d, body, r.body);
            return;
        }
    }

    private static void store(Path d, File f, byte[] b) {
        try {
            Path tmp = d.resolve(f.getName() + ".tmp");
            Files.write(tmp, b);
            Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            trim(d);
        } catch (Throwable ignored) { }
    }

    /** Keeps the cache folder under its cap, oldest files first. */
    private static void trim(Path d) {
        File[] all = d.toFile().listFiles();
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= CACHE_CAP) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : all) {
            if (total <= CACHE_CAP * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    // ---------------------------------------------------------------- transport

    private static SSLContext tls12;

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && System.getProperty("java.specification.version", "").startsWith("1.")) {
            // Java 8 (1.8.9's runtime) may still offer TLS 1.0/1.1 first; the APIs we use require 1.2+
            if (tls12 == null) { tls12 = SSLContext.getInstance("TLSv1.2"); tls12.init(null, null, null); }
            ((HttpsURLConnection) c).setSSLSocketFactory(tls12.getSocketFactory());
        }
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    /** Whole stream, or null when it is longer than max. */
    private static byte[] read(InputStream in, int max) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = s.read(b)) > 0; ) {
                if (o.size() + n > max) return null;
                o.write(b, 0, n);
            }
            return o.toByteArray();
        }
    }
}
