package dev.nether.ov;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** LWJGL3 backend: its own GL 150 core program + VAO; the whole touched state is saved and restored by hand. */
final class GlCore extends GlBackend {
    private static final String VS = "#version 150 core\n"
        + "in vec2 pos; in vec2 uv; in vec4 col; in vec2 loc; in vec4 par; uniform vec2 res;\n"
        + "out vec2 vuv; out vec4 vcol; out vec2 vloc; out vec4 vpar;\n"
        + "void main(){ vec2 p = pos / res * 2.0 - 1.0; gl_Position = vec4(p.x, -p.y, 0.0, 1.0); vuv = uv; vcol = col; vloc = loc; vpar = par; }";
    private static final String FS = "#version 150 core\n"
        + "in vec2 vuv; in vec4 vcol; in vec2 vloc; in vec4 vpar; uniform sampler2D tex; uniform sampler2D img; out vec4 o;\n"
        + "void main(){ float a; vec4 c = vcol;\n"
        + " if (vpar.z < 0.0) { a = texture(tex, vuv).r; }\n"
        + " else { vec2 q = abs(vloc) - (vpar.xy - vec2(vpar.z));\n"
        + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vpar.z;\n"
        + "  a = clamp(0.5 - d, 0.0, 1.0);\n"
        + "  if (vpar.w > 0.0) a *= clamp(0.5 + d + vpar.w, 0.0, 1.0);\n"
        + "  else if (vpar.w < 0.0) c *= texture(img, vuv); }\n" // image quad: RGBA texture on unit 1, tinted, SDF-rounded
        + " o = vec4(c.rgb, c.a * a); }";
    private static final int FLOATS = 14, CLAMP_TO_EDGE = 0x812F;

    private int program, vao, vbo, uRes, uTex, uImg, atlas;
    /** unit 1 (image textures): the game's binding there is saved on first use in a frame and put back by restore */
    private boolean unit1;
    private int prevTex1, prevSampler1;
    private final IntBuffer ib = BufferUtils.createIntBuffer(16);

    // saved state
    private int prevProg, prevVao, prevBuf, prevFb, prevActive, prevTex, prevSampler, align, polyF, polyB;
    private final int[] vp = new int[4], sb = new int[4], ps = new int[4];
    private final boolean[] cm = new boolean[4];
    /** sampler objects are GL 3.3; Minecraft only asks for a 3.2 core context */
    private boolean samplers;
    private boolean blend, depth, cull, scissor, stencil, srgb, depthMask;
    private int srcRgb, dstRgb, srcA, dstA, eqRgb, eqA;
    private int h;

    @Override String name() { return "core 150"; }

    private static int geti(int p) { return GL11.glGetInteger(p); }

    private static int shader(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(sh));
        return sh;
    }

    @Override void init(int size) {
        int vs = shader(GL20.GL_VERTEX_SHADER, VS), fs = shader(GL20.GL_FRAGMENT_SHADER, FS);
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        String[] attrs = {"pos", "uv", "col", "loc", "par"};
        for (int i = 0; i < attrs.length; i++) GL20.glBindAttribLocation(program, i, attrs[i]);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        uRes = GL20.glGetUniformLocation(program, "res");
        uTex = GL20.glGetUniformLocation(program, "tex");
        uImg = GL20.glGetUniformLocation(program, "img");
        samplers = GL.getCapabilities().OpenGL33;

        int pVao = geti(GL30.GL_VERTEX_ARRAY_BINDING), pBuf = geti(GL15.GL_ARRAY_BUFFER_BINDING);
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int[] sizes = {2, 2, 4, 2, 4};
        int off = 0;
        for (int i = 0; i < sizes.length; i++) {
            GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(i, sizes[i], GL11.GL_FLOAT, false, FLOATS * 4, off * 4L);
            off += sizes[i];
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, pBuf);
        GL30.glBindVertexArray(pVao);

        int pTex = geti(GL11.GL_TEXTURE_BINDING_2D), pAlign = geti(GL11.GL_UNPACK_ALIGNMENT);
        atlas = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, size, size, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, pAlign);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pTex);
    }

    @Override void save(int fw, int fh) {
        h = fh;
        unit1 = false;
        prevProg = geti(GL20.GL_CURRENT_PROGRAM);
        prevVao = geti(GL30.GL_VERTEX_ARRAY_BINDING);
        prevBuf = geti(GL15.GL_ARRAY_BUFFER_BINDING);
        prevFb = geti(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        prevActive = geti(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        prevTex = geti(GL11.GL_TEXTURE_BINDING_2D);
        prevSampler = samplers ? geti(GL33.GL_SAMPLER_BINDING) : 0;
        ib.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, ib);
        for (int i = 0; i < 4; i++) vp[i] = ib.get(i);
        ib.clear();
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, ib);
        for (int i = 0; i < 4; i++) sb[i] = ib.get(i);
        ib.clear();
        ib.put(0, GL11.GL_FILL).put(1, GL11.GL_FILL);
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, ib); // wireframe debug views must not turn our quads into lines
        polyF = ib.get(0);
        polyB = ib.get(1);
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        srcRgb = geti(0x80C9); dstRgb = geti(0x80C8); srcA = geti(0x80CB); dstA = geti(0x80CA);
        eqRgb = geti(0x8009); eqA = geti(0x883D);
        align = geti(GL11.GL_UNPACK_ALIGNMENT);
        ib.clear();
        GL11.glGetIntegerv(0x0C23, ib); // GL_COLOR_WRITEMASK
        for (int i = 0; i < 4; i++) cm[i] = ib.get(i) != 0;

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, fw, fh);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(0x8006, 0x8006);
        GL20.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (polyF != GL11.GL_FILL || polyB != GL11.GL_FILL) GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        if (samplers) GL33.glBindSampler(0, 0);
    }

    @Override void restore() {
        if (unit1) {
            unit1 = false;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
            if (samplers) GL33.glBindSampler(1, prevSampler1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, align);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProg);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFb);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (samplers) GL33.glBindSampler(0, prevSampler);
        if (polyF != GL11.GL_FILL || polyB != GL11.GL_FILL) GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polyF == polyB ? polyF : GL11.GL_FILL);
        GL13.glActiveTexture(prevActive);
        GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
        GL11.glScissor(sb[0], sb[1], sb[2], sb[3]);
        set(GL11.GL_BLEND, blend);
        set(GL11.GL_DEPTH_TEST, depth);
        set(GL11.GL_CULL_FACE, cull);
        set(GL11.GL_SCISSOR_TEST, scissor);
        set(GL11.GL_STENCIL_TEST, stencil);
        set(GL30.GL_FRAMEBUFFER_SRGB, srgb);
        GL11.glDepthMask(depthMask);
        GL11.glColorMask(cm[0], cm[1], cm[2], cm[3]);
        GL20.glBlendEquationSeparate(eqRgb, eqA);
        GL20.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
    }

    private static void set(int cap, boolean on) {
        if (on) GL11.glEnable(cap); else GL11.glDisable(cap);
    }

    @Override void begin(int w, int hh) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL20.glUseProgram(program);
        GL20.glUniform2f(uRes, w, hh);
        GL20.glUniform1i(uTex, 0);
        GL20.glUniform1i(uImg, 1);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
    }

    @Override void draw(FloatBuffer data, int verts) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
    }

    @Override void upload(int x, int y, int w, int hh, ByteBuffer px) {
        for (int i = 0; i < 4; i++) { ps[i] = geti(PS[i]); GL11.glPixelStorei(PS[i], i == 0 ? 1 : 0); }
        int prevPbo = geti(0x88EF);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, hh, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, px);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, prevPbo);
        for (int i = 0; i < 4; i++) GL11.glPixelStorei(PS[i], ps[i]);
    }

    /** Makes unit 1 active (saving the game's binding there once per frame); callers go back to unit 0. */
    private void unit1() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        if (unit1) return;
        unit1 = true;
        prevTex1 = geti(GL11.GL_TEXTURE_BINDING_2D);
        prevSampler1 = samplers ? geti(GL33.GL_SAMPLER_BINDING) : 0;
        if (samplers) GL33.glBindSampler(1, 0);
    }

    @Override int texCreate(int w, int hh, ByteBuffer rgba, boolean smooth) {
        unit1();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int f = smooth ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
        for (int i = 0; i < 4; i++) { ps[i] = geti(PS[i]); GL11.glPixelStorei(PS[i], i == 0 ? 1 : 0); }
        int prevPbo = geti(0x88EF);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, hh, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, prevPbo);
        for (int i = 0; i < 4; i++) GL11.glPixelStorei(PS[i], ps[i]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        return id;
    }

    @Override void texBind(int id) {
        unit1();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override void texDelete(int id) { GL11.glDeleteTextures(id); }

    private static final int[] PS = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS};

    @Override void clip(boolean on, int x, int y, int w, int hh) {
        if (on) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, w, hh);
        } else GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override void readBack(int w, int hh, ByteBuffer out) {
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, w, hh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
    }
}
