package dev.nether.ov;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * The GL surface the overlay needs, in two flavours: {@link GlCore} (LWJGL3, GL 3.2 core program with VAO, 1.13+) and
 * {@code GlCompat} (LWJGL2, compatibility context: GLSL 120 program, no VAO, attrib stacks, 1.8.9). Both draw the same
 * vertex stream ({@link Gfx}: pos2 uv2 col4 loc2 par4 per vertex, triangles) and leave every piece of game GL state as
 * they found it.
 */
abstract class GlBackend {
    static GlBackend cur;

    abstract String name();

    /** Creates program, buffers and the atlas texture (an atlas x atlas single-channel texture). */
    abstract void init(int atlas);

    /** Saves the game's GL state and sets up drawing into the default framebuffer of size w x h. */
    abstract void save(int w, int h);

    /** Restores everything {@link #save} touched. */
    abstract void restore();

    /** Per frame, after save: bind program, atlas and buffers. */
    abstract void begin(int w, int h);

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
