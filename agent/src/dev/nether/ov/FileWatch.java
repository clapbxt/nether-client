package dev.nether.ov;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A file the launcher writes and the overlay reads (bridge state.json files): checked on one background thread
 * ("nether-io"), so the render thread never waits on the disk (a stat can take milliseconds with antivirus or a
 * sleeping drive). The render thread only picks up new contents with {@link #take}.
 */
final class FileWatch {
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nether-io");
        t.setDaemon(true);
        return t;
    });

    private final Supplier<Path> file;
    private volatile boolean exists;
    private volatile byte[] fresh, last;
    private long mtime = Long.MIN_VALUE; // IO thread only

    /** Watches file (asked again each time: its folder can appear later) every `ms` milliseconds. */
    FileWatch(Supplier<Path> file, long ms) {
        this.file = file;
        IO.scheduleWithFixedDelay(this::check, 0, ms, TimeUnit.MILLISECONDS);
    }

    private void check() {
        try {
            Path f = file.get();
            if (f == null || !Files.isRegularFile(f)) { exists = false; mtime = Long.MIN_VALUE; return; }
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m != mtime) { last = fresh = Files.readAllBytes(f); mtime = m; }
            exists = true;
        } catch (Throwable t) {
            exists = false;
        }
    }

    /** Runs `poll` on the nether-io thread every `ms` milliseconds (stats and other small disk checks off the render thread). */
    static void every(Runnable poll, long ms) {
        IO.scheduleWithFixedDelay(() -> { try { poll.run(); } catch (Throwable ignored) { } }, 0, ms, TimeUnit.MILLISECONDS);
    }

    /** File modification time in ms, 0 when missing or unreadable. */
    static long mtime(Path p) {
        try { return p != null && Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : 0; } catch (Throwable t) { return 0; }
    }

    /** False while the file is missing. */
    boolean exists() { return exists; }

    /** Hands the last contents to the next {@link #take} again (state that depends on them changed). */
    void again() { if (last != null) fresh = last; }

    /** The contents read since the last call, or null when nothing changed. */
    byte[] take() {
        byte[] b = fresh;
        if (b != null) fresh = null;
        return b;
    }
}
