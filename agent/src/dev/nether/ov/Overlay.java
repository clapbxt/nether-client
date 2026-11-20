package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;

import dev.nether.agent.Hook;

/**
 * Entry of the overlay. {@link #frame} runs at the start of every glfwSwapBuffers (LWJGL2: Display.update): it draws the HUD and menu on the
 * finished frame with the whole touched GL state saved and restored; any error disables the overlay and hands the game
 * back exactly as vanilla has it (input released, menu closed, temporary options restored, title screen rebuilt).
 */
public final class Overlay {
    private Overlay() {}

    static int fps;
    private static boolean inited, dead, shot;
    private static int shots;
    private static long lastNs, fpsNs, menuAt;
    private static int frames, total;
    /** The game's render thread (the one that swaps buffers): game state is only touched from it. */
    static volatile Thread renderThread;
    private static final int[] sz = new int[4];

    /** Called once by the agent after all overlay classes are defined in the LWJGL class loader. platform: 0 GLFW, 1 LWJGL2, 2 SDL3 (each one only has its own classes defined). */
    public static void install(int platform) throws Exception {
        boolean legacy = platform == 1;
        Plat.cur = (Plat) Class.forName(platform == 1 ? "dev.nether.ov.PlatLwjgl2" : platform == 2 ? "dev.nether.ov.PlatSdl" : "dev.nether.ov.InputGlfw").newInstance();
        GlBackend.cur = (GlBackend) Class.forName(legacy ? "dev.nether.ov.GlCompat" : "dev.nether.ov.GlCore").newInstance();
        Plat.cur.install();
        Hook.target = Overlay::frame;
        Hook.name = Capes::name;
        Hook.chat = Capes::chat;
    }

    private static void init() {
        Modules.all.size();
        Config.init();
        Gfx.init();
        inited = true;
        Discord.start();
        System.out.println("[nether-agent] overlay ready (" + GlBackend.cur.name() + ")");
    }

    public static void frame(long window) {
        if (dead) return;
        try {
            renderThread = Thread.currentThread();
            Game.frame++;
            if (!inited) init();
            BackgroundFps.limit(window); // before the size check: a minimized window has no size
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
            lastNs = now;
            frames++;
            total++;
            if (now - fpsNs >= 1_000_000_000L) { fps = frames; frames = 0; fpsNs = now; }

            Plat.cur.size(window, sz);
            int fw = sz[0], fh = sz[1];
            if (fw <= 0 || fh <= 0) return;
            Input.window = window;
            Input.ww = Math.max(1, sz[2]);
            Input.wh = Math.max(1, sz[3]);
            Plat.cur.tick();
            Discord.tick();

            // dev: open the menu / HUD editor after -Dnether.menuAt seconds (default at once), so in-world shots come first
            if (menuAt == 0) menuAt = now + Long.getLong("nether.menuAt", 0L) * 1_000_000_000L;
            if (total >= 6 && now >= menuAt) {
                menuAt = Long.MAX_VALUE;
                if ("1".equals(System.getProperty("nether.menuOpen")) || "1".equals(System.getProperty("nether.hudEdit"))) {
                    Menu.hudEdit = "1".equals(System.getProperty("nether.hudEdit"));
                    String pg = System.getProperty("nether.page");
                    if (pg != null && Modules.byId(pg) != null) Menu.page = pg;
                    String tb = System.getProperty("nether.tab");
                    if (tb != null) Menu.tab = Math.max(0, Math.min(Menu.TABS.length - 1, Integer.parseInt(tb)));
                    // -Dnether.picker=<module>:<colour key> opens that module's page with the colour picker open
                    String pk = System.getProperty("nether.picker");
                    Module pm = pk == null || pk.indexOf(':') < 0 ? null : Modules.byId(pk.substring(0, pk.indexOf(':')));
                    if (pm != null) { Menu.page = pm.id; ColorPicker.toggle(pm, pk.substring(pk.indexOf(':') + 1)); }
                    Input.setMenu(true);
                    if (Menu.hudEdit) Menu.hudEdit = true;
                    if ("1".equals(System.getProperty("nether.friends"))) { Menu.hub = false; Menu.tab = Menu.T_FRIENDS; } // dev: the Friends screen
                    if ("1".equals(System.getProperty("nether.splashes"))) { Menu.hub = false; Menu.tab = Menu.TABS.length - 1; Menu.page = "menu"; Splashes.show(); } // dev: the splash texts dialog
                    if ("0".equals(System.getProperty("nether.hub"))) { Menu.hub = false; if (tb != null) Menu.tab = Math.max(0, Math.min(Menu.TABS.length - 1, Integer.parseInt(tb))); } // dev: skip the hub (with -Dnether.tab)
                }
            }
            Config.tick();
            Th.tick();
            Keybind.tick();
            Capes.tick();
            Cosmetics.tick();
            try { Game.devTick(); Game.devInput(); } catch (Throwable t) { System.out.println("[nether-agent] dev hooks: " + t); } // a bad dev property never kills the overlay
            if (Plat.cur.closing(window)) { Modules.shutdown(); Gfx.freeTextures(); } // game is quitting: it saves options right after this frame

            Gfx.begin(fw, fh); // GL state is saved on the first draw only
            // HUD unit = half a GUI pixel: the HUD follows the game's GUI scale (and so window size / DPI) like vanilla's own
            Gfx.gui = Game.guiScale(fw, fh);
            Gfx.base = Gfx.gui / 2f;
            Gfx.s = Gfx.base;
            Gfx.alpha = 1;
            if (FrameStats.want) { FrameStats.want = false; FrameStats.add(dt); }
            Gfx.dt = dt;
            Hits.frame();
            Menu.frame(fw, fh, dt);
            devImage();
            Gfx.minPx = 0;
            Gfx.flush();
            snapshot(fw, fh);
            Gfx.end();
        } catch (Throwable t) {
            Gfx.abort();
            System.out.println("[nether-agent] overlay disabled: " + t);
            t.printStackTrace(System.out);
            panic();
        }
    }

    /** The overlay died: give the game everything back so vanilla stays fully usable for the rest of the session. */
    private static void panic() {
        dead = true;
        Input.dead = true; // every input filter passes events through from now on
        try {
            if (Input.menuOpen) { Input.menuOpen = false; Plat.cur.closed(); } // cursor mode / grab / key repeat back to the game's
        } catch (Throwable t) { System.out.println("[nether-agent] panic: cursor " + t); }
        try { Modules.release(); } catch (Throwable t) { System.out.println("[nether-agent] panic: options " + t); }
        try { MainMenu.restoreVanilla(); } catch (Throwable t) { System.out.println("[nether-agent] panic: title " + t); }
        try { Config.flush(); } catch (Throwable t) { }
    }

    private static int devImg; // 0 not tried, 1 loaded, -1 failed

    /** Dev: -Dnether.devImage=<png/jpg> draws that image as a rounded, textured quad in the top-left corner (image pipeline check). */
    private static void devImage() {
        String p = System.getProperty("nether.devImage");
        if (p == null || devImg < 0) return;
        if (devImg == 0) {
            try { Gfx.texture("dev", ImageIO.read(new File(p)), true); devImg = 1; } catch (Throwable t) { devImg = -1; System.out.println("[nether-agent] dev image: " + t); return; }
        }
        Gfx.s = Gfx.base;
        Gfx.image("dev", 20, 20, 160, 160, 0xFFFFFFFF, 18);
        Gfx.image("dev", 190, 20, 80, 80, 0xFFE10600, 0);
        Gfx.flush();
    }

    /** Debug: -Dnether.snapshot=path.png writes the back buffer (game + overlay) after ~5s of frames. */
    private static void snapshot(int w, int h) {
        String path = System.getProperty("nether.snapshot");
        if (path == null) return;
        String due = Game.devShot();
        if (due != null) path = path.replace(".png", "-" + due + ".png");
        else if (shot) {
            // later shots on request: create <snapshot>.req, get <snapshot>-N.png
            if (total % 20 != 0) return;
            File req = new File(path + ".req");
            if (!req.exists()) return;
            req.delete();
            path = path.replace(".png", "-" + (++shots) + ".png");
        } else if (total < 400) return;
        shot = true;
        try {
            Gfx.ensure();
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            GlBackend.cur.readBack(w, h, buf);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 4;
                    img.setRGB(x, y, ((buf.get(i) & 255) << 16) | ((buf.get(i + 1) & 255) << 8) | (buf.get(i + 2) & 255));
                }
            ImageIO.write(img, "png", new File(path));
            System.out.println("[nether-agent] snapshot written: " + path + " | " + Game.devState(Game.devT()));
        } catch (Throwable t) {
            System.out.println("[nether-agent] snapshot failed: " + t);
        }
    }
}
