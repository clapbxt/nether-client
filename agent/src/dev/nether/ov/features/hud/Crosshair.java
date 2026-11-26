package dev.nether.ov.features.hud;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.game.Res;
import dev.nether.ov.ui.Gfx;

import static dev.nether.ov.framework.Modules.*;

/**
 * Crosshair the way resource packs do it: the design is drawn into the built-in "Nether Client" pack (always the top
 * pack) as the game's own crosshair texture (1.20.2+ gui/sprites/hud/crosshair, before that the crosshair cell of
 * gui/icons copied from the packs below), then the game reloads its resources once our menu is closed. The game
 * draws it itself, so it shows exactly where and when vanilla's would, with vanilla's invert blending (white reads
 * on any background; colours would come out inverted, so there is no colour setting).
 */
public final class Crosshair extends Module {
    public Crosshair() {
        super("crosshair", "Crosshair", "Visual", "Your own crosshair shape, drawn by the game like a resource pack's.", false, false, 0, 0);
        add(Setting.header("Shape"));
        add(Setting.choice("style", "Style", "Cross", "Cross", "Dot", "Circle", "Square").hint("Cross with gap 0 is a plus."));
        add(Setting.slider("size", "Size", 2, 12, 1, 5));
        add(Setting.slider("thickness", "Thickness", 0.5, 3, 0.5, 1));
        add(Setting.slider("gap", "Gap", 0, 8, 1, 3).when(() -> !str("style").equals("Dot")));
        add(Setting.bool("dot", "Center dot", false).when(() -> !str("style").equals("Dot")));
    }

    /** texels per GUI pixel of the texture we write (the game draws the crosshair 15 GUI px square) */
    private static final int R = 4;
    private static final String SPRITE = "assets/minecraft/textures/gui/sprites/hud/crosshair.png", ICONS = "assets/minecraft/textures/gui/icons.png";
    private String seen;
    private int seenGen = -1;
    private boolean reload;
    private long changedAt, checkedAt;

    private static java.nio.file.Path pack() { return java.nio.file.Paths.get("resourcepacks", "Nether Client").toAbsolutePath(); }
    private static boolean sprite() { return Game.loader().getResource(SPRITE) != null; }

    private String key() { return str("style") + num("size") + "/" + num("thickness") + "/" + num("gap") + bool("dot"); }

    /** The design, white on clear, n texels square with the crosshair's centre in the middle (1 unit = half a GUI px). */
    private java.awt.image.BufferedImage design(int n) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
        String st = str("style");
        float size = (float) num("size"), th = (float) num("thickness"), gap = (float) num("gap");
        float reach = st.equals("Dot") ? Math.max(th * 2, size / 2) / 2 : st.equals("Plus") ? size + gap
            : st.equals("Circle") || st.equals("Square") ? size + gap / 2 + th / 2 : gap + size;
        float u = n / 30f * Math.min(1, 14.5f / Math.max(1, reach)); // texels per unit, shrunk so the design fits the 15 px cell
        float c = n / 2f;
        int t = Math.max(1, Math.round(th * u)), ci = Math.round(c);
        switch (st) {
            case "Cross": case "Plus": {
                boolean plus = st.equals("Plus");
                int g0 = plus ? 0 : Math.round(gap * u), len = Math.max(1, Math.round((plus ? size + gap : size) * u));
                int a = Math.round(c - t / 2f);
                g.fillRect(ci - g0 - len, a, len, t);
                g.fillRect(ci + g0, a, len, t);
                g.fillRect(a, ci - g0 - len, t, len);
                g.fillRect(a, ci + g0, t, len);
                if (plus) g.fillRect(a, a, t, t);
                break;
            }
            case "Dot": {
                int d = Math.max(1, Math.round(Math.max(th * 2, size / 2) * u));
                g.fillOval(Math.round(c - d / 2f), Math.round(c - d / 2f), d, d);
                break;
            }
            default: {
                float r = (size + gap / 2) * u;
                boolean circle = st.equals("Circle");
                int o0 = Math.round(c - r - t / 2f), os = Math.round(2 * r + t);
                java.awt.Shape outer = circle ? new java.awt.geom.Ellipse2D.Float(c - r - t / 2f, c - r - t / 2f, 2 * r + t, 2 * r + t) : new java.awt.Rectangle(o0, o0, os, os);
                java.awt.Shape inner = circle ? new java.awt.geom.Ellipse2D.Float(c - r + t / 2f, c - r + t / 2f, 2 * r - t, 2 * r - t) : new java.awt.Rectangle(o0 + t, o0 + t, os - 2 * t, os - 2 * t);
                java.awt.geom.Area ring = new java.awt.geom.Area(outer);
                ring.subtract(new java.awt.geom.Area(inner));
                g.fill(ring);
            }
        }
        if (bool("dot") && !st.equals("Dot")) {
            int d = Math.max(1, Math.round(Math.max(th, 1) * u));
            g.fillRect(Math.round(c - d / 2f), Math.round(c - d / 2f), d, d);
        }
        g.dispose();
        return img;
    }

    /** 1.20.2+: the sprite itself. Before: icons copied from the packs below ours (upscaled), the crosshair cell replaced. */
    private byte[] png() throws Exception {
        java.awt.image.BufferedImage out;
        if (sprite()) out = design(15 * R);
        else {
            java.awt.image.BufferedImage base = Res.image(ICONS, "Nether Client");
            if (base == null || base.getWidth() < 256) return null;
            int k = base.getWidth() / 256, s = Math.max(k, R) / k * k; // texels per GUI px in the file
            out = new java.awt.image.BufferedImage(256 * s, base.getHeight() / k * s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(base, 0, 0, out.getWidth(), out.getHeight(), null);
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(0, 0, 15 * s, 15 * s);
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.drawImage(design(15 * s), 0, 0, null);
            g.dispose();
        }
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        javax.imageio.stream.MemoryCacheImageOutputStream mo = new javax.imageio.stream.MemoryCacheImageOutputStream(b);
        javax.imageio.ImageIO.write(out, "png", mo);
        mo.close();
        return b.toByteArray();
    }

    /** Keeps the pack file in step with the settings (checked 2x a second); a changed file asks the game to reload once our menu is closed. */
    public void sync() {
        long now = System.currentTimeMillis();
        if ("Plus".equals(Config.raw(id, "style"))) { Config.set(id, "style", "Cross"); Config.set(id, "gap", 0.0); } // Plus was Cross at gap 0
        if (reload && !Input.menuOpen && now - changedAt > 400) { reload = false; reloadGame(); }
        if (now - checkedAt < 500) return;
        checkedAt = now;
        boolean on = wantedOn() && available();
        String key = on ? key() : "off";
        boolean legacy = !sprite();
        int gen = legacy ? Res.gen() : 0; // before 1.20.2 our icons copy follows the packs under it
        if (key.equals(seen) && gen == seenGen) return;
        seen = key;
        seenGen = gen;
        try {
            if (!java.nio.file.Files.isDirectory(pack())) return; // the launcher creates the pack; without it there is nothing to override
            java.nio.file.Path f = pack().resolve(legacy ? ICONS : SPRITE);
            if (!on) {
                if (java.nio.file.Files.deleteIfExists(f)) { reload = true; changedAt = now; }
                return;
            }
            byte[] b = png();
            if (b == null) return;
            if (java.nio.file.Files.exists(f) && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(f), b)) return;
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            java.nio.file.Files.write(tmp, b);
            java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            reload = true;
            changedAt = now;
        } catch (Throwable t) {
            System.out.println("[nether-agent] crosshair texture: " + t);
        }
    }

    /**
     * The game's own resource reload, found by shape (no names): the public no-argument method of the game class that
     * returns a future (1.14+ reloadResourcePacks / delayTextureReload, 1.8.9 scheduleResourcesRefresh; each reloads).
     */
    private static void reloadGame() {
        Object mc = Game.mc();
        if (mc == null) return;
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(m.getModifiers()) || !java.util.concurrent.Future.class.isAssignableFrom(m.getReturnType())) continue;
            try { m.invoke(mc); System.out.println("[nether-agent] crosshair: resources reloading"); }
            catch (Throwable t) { System.out.println("[nether-agent] crosshair reload: " + t); }
            return;
        }
    }

    private String previewKey;

    @Override public void preview(float cx, float cy) {
        String key = key();
        if (!key.equals(previewKey) || !Gfx.hasTexture("xhair")) { previewKey = key; Gfx.texture("xhair", design(15 * R), false); }
        float s = 30 * 1.8f;
        Gfx.image("xhair", cx - s / 2, cy - s / 2, s, s, 0xFFFFFFFF, 0);
    }
}
