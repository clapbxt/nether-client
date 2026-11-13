package dev.nether.ov;

/**
 * Menu theme (OLED default, Obsidian, Slate, Ice) and accent, read from the global client.json
 * ({@code modules.client.theme / accent / accent2}, written by the launcher's Settings and by the in-game Settings tab).
 * Only the menu, wardrobe and editor chrome use these; HUD modules keep {@link Gfx}'s fixed colours so they read over the world.
 */
final class Th {
    private Th() {}

    static final String[] NAMES = {"OLED", "Obsidian", "Slate", "Ice"};
    // bg, s1, s2, s3, ln, ln2, tx, dim, mut, overlay (hover / fills), ink (text on accent)
    private static final int[][] SET = {
        {0xFF000000, 0xFF0B0B0D, 0xFF141418, 0xFF1D1D23, 0x14FFFFFF, 0x29FFFFFF, 0xFFF1F2F5, 0xFFA3A8B3, 0xFF6B707B, 0xFFFFFFFF},
        {0xFF14151A, 0xFF1B1D23, 0xFF23262E, 0xFF2D313B, 0x12FFFFFF, 0x24FFFFFF, 0xFFE9ECF1, 0xFFA0A7B4, 0xFF6D7584, 0xFFFFFFFF},
        {0xFF1A2130, 0xFF202839, 0xFF283245, 0xFF334057, 0x14BED2FF, 0x29BED2FF, 0xFFE6ECF7, 0xFF9EABC2, 0xFF6C7A93, 0xFFBED2FF},
        {0xFFEEF1F5, 0xFFFFFFFF, 0xFFF4F6F9, 0xFFE4E8EE, 0x17141E32, 0x2E141E32, 0xFF171A21, 0xFF545C6B, 0xFF8790A0, 0xFF141E32}};
    static int bg, s1, s2, s3, ln, ln2, tx, dim, mut, ov, ac = Gfx.RED, ac2 = Gfx.RED2, theme = -1;
    private static String accKey = "";

    static { set(0); }

    private static void set(int i) {
        int[] c = SET[i];
        bg = c[0]; s1 = c[1]; s2 = c[2]; s3 = c[3]; ln = c[4]; ln2 = c[5]; tx = c[6]; dim = c[7]; mut = c[8]; ov = c[9];
        theme = i;
    }

    /** Frame start: follow the config (cheap: a few string reads). */
    static void tick() {
        String n = Config.str("client", "theme", "OLED");
        int i = 0;
        for (int k = 0; k < NAMES.length; k++) if (NAMES[k].equalsIgnoreCase(n)) i = k;
        if (i != theme) set(i);
        String a = Config.str("client", "accent", "#E5372D"), b = Config.str("client", "accent2", "#FF6157");
        if (!(a + b).equals(accKey)) {
            accKey = a + b;
            ac = parse(a, Gfx.RED);
            ac2 = parse(b, Gfx.RED2);
        }
    }

    static boolean light() { return theme == 3; }
    /** Text colour on top of the accent. */
    static int onAccent() { return 0xFFFFFFFF; }
    static int a(int c, float f) { return Gfx.a(c, f); }

    private static int parse(String h, int def) {
        try { return 0xFF000000 | Integer.parseInt(h.replace("#", "").trim(), 16); } catch (Throwable t) { return def; }
    }
}
