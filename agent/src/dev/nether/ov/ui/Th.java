package dev.nether.ov.ui;

import dev.nether.ov.framework.Config;

/**
 * Menu theme (v8: Nether Dark default, Nether Light) and accent, read from the global client.json
 * ({@code modules.client.theme / accent / accent2}, written by the launcher's Settings and by the in-game Settings tab).
 * One look in both: neutral charcoal / paper panels with small radii, Nether Pixel titles, Barlow text; the accent is the
 * only colour choice. Colours are menu/wardrobe/editor chrome only; HUD modules keep {@link Gfx}'s fixed colours so they
 * read over the world. Pre-v8 names (NetherGlass / NetherMinecraft Dark|Light, Ice) fold onto Dark / Light.
 */
public final class Th {
    private Th() {}

    public static final String[] NAMES = {"Nether Dark", "Nether Light"};
    /** Short labels for the in-game picker. */
    public static final String[] LABELS = {"Dark", "Light"};
    // bg, s1, s2, s3, ln, ln2, tx, dim, mut, overlay (hover / fills)
    private static final int[][] SET = {
        {0xFF0F0F11, 0xFF18181B, 0xFF202024, 0xFF2A2A30, 0x10FFFFFF, 0x21FFFFFF, 0xFFF4F4F5, 0xFFA1A1AA, 0xFF71717A, 0xFFFFFFFF},
        {0xFFEEEEF1, 0xFFFFFFFF, 0xFFF5F5F7, 0xFFE6E6EB, 0x12000000, 0x24000000, 0xFF18181B, 0xFF52525B, 0xFF8A8A93, 0xFF000000}};
    public static int bg, s1, s2, s3, ln, ln2, tx, dim, mut, ov, ac = Gfx.RED, ac2 = Gfx.RED2, theme = -1;
    private static String accKey = "";

    static { set(0); }

    private static void set(int i) {
        int[] c = SET[i];
        bg = c[0]; s1 = c[1]; s2 = c[2]; s3 = c[3]; ln = c[4]; ln2 = c[5]; tx = c[6]; dim = c[7]; mut = c[8]; ov = c[9];
        theme = i;
        Gfx.face(true);
    }

    /** Frame start: follow the config (cheap: a few string reads). Any older name with "Light" (or Ice) is Light. */
    public static void tick() {
        String n = Config.str("client", "theme", NAMES[0]);
        int i = n.toLowerCase(java.util.Locale.ROOT).contains("light") || "Ice".equalsIgnoreCase(n) ? 1 : 0;
        if (i != theme) set(i);
        String a = Config.str("client", "accent", "#F76A05"), b = Config.str("client", "accent2", "#FF9A2E");
        if (!(a + b).equals(accKey)) {
            accKey = a + b;
            ac = parse(a, Gfx.RED);
            ac2 = parse(b, Gfx.RED2);
        }
    }

    /** No square theme since v8 (kept for old call sites). */
    static boolean square() { return false; }
    /** What {@link Gfx#round} should be for menus, title screen and HUD. */
    public static boolean round() { return true; }
    public static boolean light() { return theme == 1; }
    /** Panel radius: small, Feather-like (between the old glass 14 and Minecraft 0). */
    public static final float R = 8;
    /** Text colour on top of the accent. */
    static int onAccent() { return 0xFFFFFFFF; }
    static int a(int c, float f) { return Gfx.a(c, f); }

    private static int parse(String h, int def) {
        try { return 0xFF000000 | Integer.parseInt(h.replace("#", "").trim(), 16); } catch (Throwable t) { return def; }
    }
}
