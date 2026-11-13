package dev.nether.ov;

/**
 * Clickable HUD widgets (media controls, buttons on HUD cards). A widget registers its box every frame it is drawn
 * ({@link #box}); a mouse press is matched against the boxes of the previous frame (what the user saw) and delivered on
 * the next draw through {@link #clicked}. Presses only land while the cursor is free: our menu is open and its panel did
 * not use the click, or a game screen is up (chat, inventory). A press that lands is swallowed, its release too, so the
 * game never sees it. In plain gameplay (cursor grabbed) nothing is routed: a widget can never take an attack or use
 * click. Frames without widgets cost one comparison.
 */
final class Hits {
    private Hits() {}

    private static final int MAX = 64;
    private static final String[] ids = new String[MAX], seen = new String[MAX];
    private static final float[] cur = new float[MAX * 4], last = new float[MAX * 4];
    private static int n, lastN;
    private static String pending;
    private static int pendingBtn;
    private static long pendingAt;

    /** Overlay, once per frame before drawing: this frame's boxes become the ones presses are matched against. */
    static void frame() {
        if (n == 0 && lastN == 0 && pending == null) return;
        System.arraycopy(ids, 0, seen, 0, n);
        System.arraycopy(cur, 0, last, 0, n * 4);
        lastN = n;
        n = 0;
        if (pending != null && Game.frame - pendingAt > 3) pending = null; // nobody drew that widget any more
    }

    /** The cursor may interact with HUD widgets: our menu or a game screen holds it. */
    static boolean free() { return Input.menuOpen || Game.screenOpen(); }

    /** Cursor position in framebuffer pixels. */
    private static float mx() { return (float) (Input.mx * Gfx.fbW / Input.ww); }
    private static float my() { return (float) (Input.my * Gfx.fbH / Input.wh); }

    /**
     * Registers the clickable box of widget id for this frame (logical units at the current {@link Gfx#s}); returns true
     * while the free cursor is over it (hover look). Ids must be unique per frame ("media.play").
     */
    static boolean box(String id, float x, float y, float w, float h) {
        float s = Gfx.s, px = x * s, py = y * s, pw = w * s, ph = h * s;
        if (n < MAX) {
            ids[n] = id;
            int i = n++ * 4;
            cur[i] = px; cur[i + 1] = py; cur[i + 2] = pw; cur[i + 3] = ph;
        }
        if (!free()) return false;
        float cx = mx(), cy = my();
        return cx >= px && cy >= py && cx < px + pw && cy < py + ph;
    }

    /** Mouse press b (0 left, 1 right, 2 middle) at the cursor; true = it hit a widget and is swallowed. */
    static boolean press(int b) {
        if (lastN == 0 || !free()) return false;
        float cx = mx(), cy = my();
        for (int k = lastN - 1; k >= 0; k--) { // topmost (drawn last) first
            int i = k * 4;
            if (cx >= last[i] && cy >= last[i + 1] && cx < last[i] + last[i + 2] && cy < last[i + 1] + last[i + 3]) {
                pending = seen[k];
                pendingBtn = b;
                pendingAt = Game.frame;
                return true;
            }
        }
        return false;
    }

    /** Button (0 left, 1 right, 2 middle) that clicked widget id since its last draw, else -1; consumes the click. */
    static int clicked(String id) {
        if (pending == null || !pending.equals(id)) return -1;
        pending = null;
        return pendingBtn;
    }
}
