package dev.nether.ov.features.social;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import dev.nether.ov.game.Game;

/**
 * Discord Rich Presence from the game itself, so it works with the launcher closed. Only runs when the launcher passed
 * -Dnether.discord=<application id> (its Discord setting is on). Talks to the Discord app on this PC over its local
 * IPC pipe (Windows); shows the version, loader and play time, never the server or world. The launcher stops its own
 * "In the launcher" status while a game runs, so this one is the only one then.
 */
public final class Discord {
    private Discord() {}

    private static boolean started;
    private static final String PIPE = new String(new char[] { 92, 92, 46, 92, 112, 105, 112, 101, 92, 100, 105, 115, 99, 111, 114, 100, 45, 105, 112, 99, 45 }); // \\.\pipe\discord-ipc-

    private static volatile String mode = "In the menus";
    private static long checkNs;

    /** Render thread, every frame: cheap; re-reads the game state every 2 s. Only the kind of game is shared, never the address or world. */
    public static void tick() {
        if (!started) return;
        long now = System.nanoTime();
        if (now - checkNs < 2_000_000_000L) return;
        checkNs = now;
        try {
            String sv = Game.server();
            mode = sv == null ? "In the menus" : "Singleplayer".equals(sv) ? "Singleplayer" : "Multiplayer";
        } catch (Throwable t) { }
    }

    public static void start() {
        if (started) return;
        try {
            final String id = System.getProperty("nether.discord", "");
            if (!id.matches("[0-9]{15,25}") || !System.getProperty("os.name", "").toLowerCase().contains("win")) return;
            String v = System.getProperty("nether.discordVer", "");
            if (v.isEmpty()) v = String.valueOf(Game.version());
            final String ver = clean(v), loader = clean(System.getProperty("nether.discordLoader", "")), name = clean(System.getProperty("nether.discordName", ""));
            final long since = System.currentTimeMillis() / 1000;
            started = true;
            Thread t = new Thread(new Runnable() { public void run() { loop(id, ver, loader, name, since); } }, "nether-discord");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) { }
    }

    private static String clean(String s) { return s.replaceAll("[^A-Za-z0-9._+ -]", ""); }

    private static String activity(String m, String ver, String loader, String name, long since) {
        String state = (name.isEmpty() ? "" : name + " · ") + (loader.isEmpty() ? "Nether Client" : loader);
        return "{\"details\":\"" + m + " · " + ver + "\",\"state\":\"" + state + "\","
            + "\"timestamps\":{\"start\":" + since + "},\"assets\":{\"large_image\":\"nether\",\"large_text\":\"Nether Client\"}}";
    }

    private static void loop(String id, String ver, String loader, String name, long since) {
        long pid = pid();
        RandomAccessFile f = null;
        String sent = null;
        long sentAt = 0;
        while (true) {
            String m = mode;
            boolean due = f == null || !m.equals(sent) || System.currentTimeMillis() - sentAt > 30000; // also a heartbeat: notices a restarted Discord
            if (due) {
                try {
                    if (f == null) f = connect(id);
                    if (f != null) {
                        send(f, 1, "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid + ",\"activity\":" + activity(m, ver, loader, name, since) + "},\"nonce\":\"" + System.nanoTime() + "\"}");
                        int op = recv(f, new StringBuilder());
                        if (op == 2) throw new IOException("closed");
                        sent = m;
                    }
                } catch (Throwable t) {
                    if (f != null) try { f.close(); } catch (Throwable t2) { }
                    f = null;
                }
                sentAt = System.currentTimeMillis();
                if (f == null) { sleep(15000); continue; } // Discord not running (yet): try again later
            }
            sleep(1000);
        }
    }

    private static RandomAccessFile connect(String id) {
        for (int i = 0; i < 10; i++) {
            RandomAccessFile f = null;
            try {
                f = new RandomAccessFile(PIPE + i, "rw");
                send(f, 0, "{\"v\":1,\"client_id\":\"" + id + "\"}");
                StringBuilder b = new StringBuilder();
                if (recv(f, b) == 1 && !b.toString().contains("\"ERROR\"")) return f;
            } catch (Throwable t) { }
            if (f != null) try { f.close(); } catch (Throwable t) { }
        }
        return null;
    }

    private static void send(RandomAccessFile f, int op, String json) throws IOException {
        byte[] d = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + d.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(d.length).put(d);
        f.write(b.array());
    }

    private static int recv(RandomAccessFile f, StringBuilder out) throws IOException {
        byte[] h = new byte[8];
        f.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int op = b.getInt(), len = b.getInt();
        if (len < 0 || len > 65536) throw new IOException("oversized");
        byte[] d = new byte[len];
        f.readFully(d);
        out.append(new String(d, StandardCharsets.UTF_8));
        return op;
    }

    private static long pid() {
        try { return Long.parseLong(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]); }
        catch (Throwable t) { return 0; }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }
}
