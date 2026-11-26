package dev.nether.ov;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import dev.nether.agent.Hook;

/**
 * The GL surface the overlay needs, in two flavours: {@link GlCore} (LWJGL3, GL 3.2 core program with VAO, 1.13+) and
 * {@code GlCompat} (LWJGL2, compatibility context: GLSL 120 program, no VAO, attrib stacks, 1.8.9). Both draw the same
 * vertex stream ({@link Gfx}: pos2 uv2 col4 loc2 par4 per vertex, triangles) and leave every piece of game GL state as
 * they found it.
 */
abstract class GlBackend {
    static GlBackend cur;
    /** non-zero while drawing inside the game's world pass (custom sky): the framebuffer to draw into instead of the window */
    static int targetFb;

    // ---------------------------------------------------------------- GL shadow (dev.nether.agent.Hook): no driver stall per frame

    /** Frames between checks of the shadow against a real query (one stall each). */
    private static final int CHECK = 2000;
    private static boolean seeded, shadowDead;
    private static int checkN;

    /** This frame reads the game's state from Hook.gs / gtex / gsmp (true) or queries the driver and passes it to {@link #queried}. */
    static boolean useShadow() {
        if (!Hook.glShadowed || shadowDead || !seeded) return false;
        return ++checkN % CHECK != 0;
    }

    /** A real query of the state (q indexed like Hook.gs; tex / smp for units 0 and 1): seeds the shadow, or checks it. */
    static void queried(int[] q, int[] tex, int[] smp) {
        if (!Hook.glShadowed || shadowDead) return;
        if (!seeded) {
            System.arraycopy(q, 0, Hook.gs, 0, q.length);
            for (int u = 0; u < 2; u++) { Hook.gtex[u] = tex[u]; Hook.gsmp[u] = smp[u]; }
            seeded = true;
            System.out.println("[nether-agent] GL state shadow on");
            return;
        }
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < q.length; i++) if (q[i] != Hook.gs[i]) bad.append(" s").append(i).append('=').append(Hook.gs[i]).append("/gl ").append(q[i]);
        for (int u = 0; u < 2; u++) {
            if (tex[u] != Hook.gtex[u]) bad.append(" tex").append(u).append('=').append(Hook.gtex[u]).append("/gl ").append(tex[u]);
            if (smp[u] != Hook.gsmp[u]) bad.append(" smp").append(u).append('=').append(Hook.gsmp[u]).append("/gl ").append(smp[u]);
        }
        if (bad.length() > 0) { shadowDead = true; System.out.println("[nether-agent] GL state shadow off (mismatch:" + bad + ")"); }
    }

    abstract String name();

    /** Creates program, buffers and the atlas texture (an atlas x atlas single-channel texture). */
    abstract void init(int atlas);

    /** Saves the game's GL state and sets up drawing into the default framebuffer of size w x h. */
    abstract void save(int w, int h);

    /** Restores everything {@link #save} touched. */
    abstract void restore();

    /** Per frame, after save: bind program, atlas and buffers. */
    abstract void begin(int w, int h);

    /** Maps screen-local overlay coordinates through a projective transform. */
    abstract void map(float[] matrix);

    /** Uploads and draws `verts` vertices (the buffer is already flipped). */
    abstract void draw(FloatBuffer data, int verts);

    /** Writes a w x h block of coverage bytes into the atlas at (x, y); the atlas is bound. */
    abstract void upload(int x, int y, int w, int h, ByteBuffer px);

    /** Creates an RGBA8 texture (w x h, rows top first) on unit 1 and returns its id; inside a frame (after save). */
    abstract int texCreate(int w, int h, ByteBuffer rgba, boolean smooth);

    /** Binds an image texture on unit 1 (the program's "img" sampler); inside a frame. */
    abstract void texBind(int id);

    /** Frees a texture made by {@link #texCreate} (needs no saved state: ours are never left bound). */
    abstract void texDelete(int id);

    /** Scissor rectangle in framebuffer pixels (origin bottom-left) or off. */
    abstract void clip(boolean on, int x, int y, int w, int h);

    /** Reads the back buffer as RGBA bytes (bottom-up). */
    abstract void readBack(int w, int h, ByteBuffer out);
}
