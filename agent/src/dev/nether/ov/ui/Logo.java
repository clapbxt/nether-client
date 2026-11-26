package dev.nether.ov.ui;

import java.awt.image.BufferedImage;

/**
 * The Nether logo: the pixel fireball (design/icon/fireball-pixel.json, 17 x 24; regenerate these rows with
 * `python design/icon/fireball-pixel.py`). Uploaded once as a nearest-filtered texture, drawn crisp at any size.
 */
public final class Logo {
    private Logo() {}

    static final int W = 17, H = 24;
    private static final int[] PAL = {0xFFFDCB33, 0xFFFD9207, 0xFFFD8605, 0xFFF76A05, 0xFFEF5B03, 0xFFDF3E01, 0xFFFD7C04, 0xFFE64E03, 0xFFFDA90E, 0xFFFDE04E, 0xFFFDFAA6, 0xFF232124, 0xFF393835, 0xFF94360B, 0xFF54514B, 0xFF2D2624, 0xFF847269, 0xFFDCBF6C, 0xFF1A1B20, 0xFF17161B};
    private static final String[] ROWS = {"................a", "................b", "...........bcd...", ".....ef....e.....", ".....edffffd.....", ".....eegbef...g..", "..hfhiiiigf......", "..ggciaaige......", ".hcdeedijkjch....", ".gagdiaaakkkaie..", ".hbaaaaakkkjkjb..", "hbkaiilllljijid..", "fgcgllmnomplbcg..", "ciglpoqfqrmpsg...", "ec.loqbqqbfol.ad.", "j.plqqjqbqqrfsc..", ".alnfrqjqqqnosa..", "kjloqbqqbrnqosk..", ".aspqqcrfqfoptej.", "..jsoqfqnooftha..", "..ispnoonoopte.k.", "...dttpfopttdaa..", "....edttttde.....", "......heeh......."};

    /** The fireball, h units tall (width = h * 17 / 24), top-left at x, y. */
    public static void draw(float x, float y, float h) {
        if (!Gfx.hasTexture("nether-logo")) {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            for (int j = 0; j < H; j++)
                for (int i = 0; i < W; i++) {
                    char c = ROWS[j].charAt(i);
                    if (c != '.') img.setRGB(i, j, PAL[c - 'a']);
                }
            Gfx.texture("nether-logo", img, false);
        }
        Gfx.image("nether-logo", x, y, h * W / H, h, 0xFFFFFFFF, 0);
    }

    public static float width(float h) { return h * W / H; }

    /** brand/logo.png (art/blank_square_logo.png cropped): the fireball over the NETHER CLIENT wordmark; {w, h}, 0 = missing */
    private static int bw = -1, bh;

    private static boolean brandReady() {
        if (bw < 0) {
            bw = 0;
            try {
                byte[] b = dev.nether.agent.Hook.file.apply("brand/logo.png");
                BufferedImage img = b == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
                if (img != null) { Gfx.texture("nether-brand", img, true); bw = img.getWidth(); bh = img.getHeight(); }
            } catch (Throwable t) {
                System.out.println("[nether-agent] brand logo: " + t);
            }
        }
        return bw > 0 && Gfx.hasTexture("nether-brand");
    }

    /** Width of the brand logo h units tall (the fireball's width when the image is missing). */
    public static float brandWidth(float h) { return brandReady() ? h * bw / bh : width(h); }

    /** The fireball + wordmark, h units tall, top-left at x, y (the fireball alone if the image is missing). */
    public static void brand(float x, float y, float h) {
        if (brandReady()) Gfx.image("nether-brand", x, y, h * bw / bh, h, 0xFFFFFFFF, 0);
        else draw(x, y, h);
    }
}
