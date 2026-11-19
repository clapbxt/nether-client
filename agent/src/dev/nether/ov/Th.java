package dev.nether.ov;

/**
 * Menu theme (NetherGlass Dark default, NetherGlass Light, NetherMinecraft Dark, NetherMinecraft Light) and accent, read from the
 * global client.json ({@code modules.client.theme / accent / accent2}, written by the launcher's Settings and by the in-game Settings tab).
 * Glass = rounded, see-through panels and Barlow type; Minecraft = square corners and Nether Pixel (menus and HUD alike).
 * Colours are menu/wardrobe/editor chrome only; HUD modules keep {@link Gfx}'s fixed colours so they read over the world.
 */
final class Th {
    private Th() {}

    static final String[] NAMES = {"NetherGlass Dark", "NetherGlass Light", "NetherMinecraft Dark", "NetherMinecraft Light"};
    /** Short labels for the in-game picker. */
    static final String[] LABELS = {"Glass Dark", "Glass Light", "MC Dark", "MC Light"};
    // bg, s1, s2, s3, ln, ln2, tx, dim, mut, overlay (hover / fills)
    private static final int[][] SET = {
        {0xFF0D1015, 0xFF161A21, 0xFF1C222B, 0xFF252C37, 0x12FFFFFF, 0x26FFFFFF, 0xFFE9EDF3, 0xFF9BA5B4, 0xFF677183, 0xFFFFFFFF},
        {0xFFE8ECF2, 0xFFFFFFFF, 0xFFF6F8FB, 0xFFE3E8EF, 0x14141E32, 0x29141E32, 0xFF151922, 0xFF4F5868, 0xFF8590A2, 0xFF141E32},
        {0xFF161616, 0xFF222222, 0xFF2C2C2C, 0xFF383838, 0x1AFFFFFF, 0x33FFFFFF, 0xFFFFFFFF, 0xFFB0B0B0, 0xFF7A7A7A, 0xFFFFFFFF},
        {0xFFB4B4B4, 0xFFC6C6C6, 0xFFD0D0D0, 0xFFA8A8A8, 0x2E000000, 0x59000000, 0xFF1E1E1E, 0xFF3F3F3F, 0xFF666666, 0xFF000000}};
    static int bg, s1, s2, s3, ln, ln2, tx, dim, mut, ov, ac = Gfx.RED, ac2 = Gfx.RED2, theme = -1;
    private static String accKey = "";

    static { set(0); }

    private static void set(int i) {
        int[] c = SET[i];
        bg = c[0]; s1 = c[1]; s2 = c[2]; s3 = c[3]; ln = c[4]; ln2 = c[5]; tx = c[6]; dim = c[7]; mut = c[8]; ov = c[9];
        theme = i;
        Gfx.face(square());
    }

    /** Frame start: follow the config (cheap: a few string reads). Pre-v7 names: Ice was light, the rest dark glass. */
    static void tick() {
        String n = Config.str("client", "theme", NAMES[0]);
        int i = "Ice".equalsIgnoreCase(n) ? 1 : 0;
        for (int k = 0; k < NAMES.length; k++) if (NAMES[k].equalsIgnoreCase(n)) i = k;
        if (i != theme) set(i);
        String a = Config.str("client", "accent", "#F76A05"), b = Config.str("client", "accent2", "#FF9A2E");
        if (!(a + b).equals(accKey)) {
            accKey = a + b;
            ac = parse(a, Gfx.RED);
            ac2 = parse(b, Gfx.RED2);
        }
    }

    /** Minecraft themes: square corners and Nether Pixel. */
    static boolean square() { return theme >= 2; }
    /** What {@link Gfx#round} should be for menus, title screen and HUD. */
    static boolean round() { return theme < 2; }
    static boolean light() { return theme == 1 || theme == 3; }
    /** Text colour on top of the accent. */
    static int onAccent() { return 0xFFFFFFFF; }
    static int a(int c, float f) { return Gfx.a(c, f); }

    private static int parse(String h, int def) {
        try { return 0xFF000000 | Integer.parseInt(h.replace("#", "").trim(), 16); } catch (Throwable t) { return def; }
    }
}
