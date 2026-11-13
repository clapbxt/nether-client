package dev.nether.ov;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Game assets as the player sees them: the enabled resource packs from options.txt (top pack first; a zip or a folder in
 * resourcepacks/), then the game's class loader (client jar, mod jars). No game code is called, so it works the same on
 * every version and loader. {@link #gen()} changes when the pack list does, so callers can drop what they cached.
 */
final class Res {
    private Res() {}

    private static List<Path> packs = Collections.emptyList();
    private static long mtime = Long.MIN_VALUE, checked;
    private static int gen;

    /** Bumps whenever the enabled packs change (options.txt is re-read at most every 2 s). Render thread. */
    static int gen() {
        long now = System.currentTimeMillis();
        if (now - checked < 2000) return gen;
        checked = now;
        try {
            Path opt = Paths.get("options.txt").toAbsolutePath();
            long t = Files.exists(opt) ? Files.getLastModifiedTime(opt).toMillis() : 0;
            if (t == mtime) return gen;
            mtime = t;
            List<Path> out = new ArrayList<>();
            if (t != 0) for (String l : Files.readAllLines(opt, StandardCharsets.UTF_8)) {
                if (!l.startsWith("resourcePacks:")) continue;
                for (String p : l.substring(14).replaceAll("[\\[\\]\"]", "").split(",")) {
                    String n = p.trim();
                    if (n.startsWith("file/")) n = n.substring(5);
                    if (n.isEmpty() || n.contains("..")) continue;
                    Path f = Paths.get("resourcepacks", n).toAbsolutePath();
                    if (Files.exists(f)) out.add(f); // vanilla / fabric / mod packs are not files: the class loader has those
                }
            }
            Collections.reverse(out); // options.txt lists the top pack last
            if (!out.equals(packs)) { packs = out; gen++; }
        } catch (Throwable ignored) { }
        return gen;
    }

    /** Bytes of an asset ("assets/minecraft/textures/...png"), top pack first; null when no pack or jar has it. */
    static byte[] read(String path) { return read(path, null); }

    /** As {@link #read(String)}, leaving out the pack named skip (our own pack, when we build from what lies under it). */
    static byte[] read(String path, String skip) {
        gen();
        for (Path p : packs) {
            if (skip != null && p.getFileName().toString().equals(skip)) continue;
            try {
                if (Files.isDirectory(p)) {
                    Path f = p.resolve(path);
                    if (Files.isRegularFile(f)) return Files.readAllBytes(f);
                } else {
                    try (ZipFile z = new ZipFile(p.toFile())) {
                        ZipEntry e = z.getEntry(path);
                        if (e != null) try (InputStream in = z.getInputStream(e)) { return all(in); }
                    }
                }
            } catch (Throwable ignored) { }
        }
        try (InputStream in = Game.loader().getResourceAsStream(path)) { return in == null ? null : all(in); } catch (Throwable t) { return null; }
    }

    /** The asset decoded as an image, or null. */
    static java.awt.image.BufferedImage image(String path) { return image(path, null); }

    static java.awt.image.BufferedImage image(String path, String skip) {
        byte[] b = read(path, skip);
        if (b == null) return null;
        try { return Icons.decode(new java.io.ByteArrayInputStream(b)); } catch (Throwable t) { return null; }
    }

    private static byte[] all(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
        return o.toByteArray();
    }
}
