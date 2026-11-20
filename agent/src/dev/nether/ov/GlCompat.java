package dev.nether.ov;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import dev.nether.agent.Hook;

/**
 * LWJGL2 / compatibility-context backend (Minecraft 1.8.9 draws with fixed function). Compiled against LWJGL2, in a
 * separate javac pass. GLSL 120 program, plain vertex attribute arrays on a VBO (no VAO in 2.1), alpha-only atlas
 * (GL_ALPHA8 works in every context; R8 needs 3.0). The game's state is protected with push/pop attrib (server and
 * client, all bits) plus the few bindings those stacks do not cover (program, array buffer, active unit, FBO).
 */
final class GlCompat extends GlBackend {
    private static final String VS = "#version 120\n"
        + "attribute vec2 pos; attribute vec2 uv; attribute vec4 col; attribute vec2 loc; attribute vec4 par; uniform vec2 res;\n"
        + "varying vec2 vuv; varying vec4 vcol; varying vec2 vloc; varying vec4 vpar;\n"
        + "void main(){ vec2 p = pos / res * 2.0 - 1.0; gl_Position = vec4(p.x, -p.y, 0.0, 1.0); vuv = uv; vcol = col; vloc = loc; vpar = par; }";
    private static final String FS = "#version 120\n"
        + "varying vec2 vuv; varying vec4 vcol; varying vec2 vloc; varying vec4 vpar; uniform sampler2D tex; uniform sampler2D img;\n"
        + "void main(){ float a; vec4 c = vcol;\n"
        + " if (vpar.z < 0.0) { a = texture2D(tex, vuv).a; }\n"
        + " else { vec2 q = abs(vloc) - (vpar.xy - vec2(vpar.z));\n"
        + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vpar.z;\n"
        + "  a = clamp(0.5 - d, 0.0, 1.0);\n"
        + "  if (vpar.w > 0.0) a *= clamp(0.5 + d + vpar.w, 0.0, 1.0);\n"
        + "  else if (vpar.w < 0.0) c *= texture2D(img, vuv); }\n" // image quad: RGBA texture on unit 1, tinted, SDF-rounded
        + " gl_FragColor = vec4(c.rgb, c.a * a); }";
    private static final int FLOATS = 14, CLAMP_TO_EDGE = 0x812F, ALPHA8 = 0x803C, FRAMEBUFFER_BINDING = 0x8CA6;

    private int program, vbo, uRes, uTex, uImg, atlas;
    /** unit 1 (image textures): its binding is saved on first use in a frame and put back before the attrib pop */
    private boolean unit1;
    private int prevTex1;
    private boolean fbo30, fboExt;
    private int prevProg, prevBuf, prevActive, prevFb;

    @Override String name() { return "compat GLSL 120 (" + GL11.glGetString(GL11.GL_VERSION) + ")"; }

    private static int shader(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShader(sh, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(sh, 4096));
        return sh;
    }

    @Override void init(int size) {
        ContextCapabilities caps = GLContext.getCapabilities();
        fbo30 = caps.OpenGL30;
        fboExt = !fbo30 && caps.GL_EXT_framebuffer_object;
        int vs = shader(GL20.GL_VERTEX_SHADER, VS), fs = shader(GL20.GL_FRAGMENT_SHADER, FS);
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        String[] attrs = {"pos", "uv", "col", "loc", "par"};
        for (int i = 0; i < attrs.length; i++) GL20.glBindAttribLocation(program, i, attrs[i]);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgram(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program, 4096));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        uRes = GL20.glGetUniformLocation(program, "res");
        uTex = GL20.glGetUniformLocation(program, "tex");
        uImg = GL20.glGetUniformLocation(program, "img");

        int pBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, pBuf);

        int pActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int pTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), pAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        atlas = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, ALPHA8, size, size, 0, GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, pAlign);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pTex);
        GL13.glActiveTexture(pActive);
    }

    private void bindFb(int id) {
        if (fbo30) GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
        else if (fboExt) EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, id);
    }

    /** this frame's state source: Hook's shadow (no driver stall) or a real query */
    private boolean shadow;
    private final int[] q = new int[Hook.G_N], qt = new int[2], qs = new int[2];

    /** The bindings the overlay reads, from the driver (a stall): seeds / checks the shadow. Fields not asked keep the shadow's values. */
    private void query() {
        System.arraycopy(Hook.gs, 0, q, 0, q.length);
        q[Hook.G_PROG] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        q[Hook.G_ABUF] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int act = q[Hook.G_ACTIVE] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        q[Hook.G_DFB] = fbo30 || fboExt ? GL11.glGetInteger(FRAMEBUFFER_BINDING) : 0;
        // unit 0's binding comes back with the attrib pop (behind the shadow's back), so it is neither read nor checked
        qt[0] = Hook.gtex[0];
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        qt[1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(act);
        int[] ps = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS};
        for (int i = 0; i < 4; i++) q[Hook.G_ALIGN + i] = GL11.glGetInteger(ps[i]);
        q[Hook.G_UPBO] = GL11.glGetInteger(0x88EF);
        queried(q, qt, qs);
    }

    @Override void save(int fw, int fh) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(0xFFFFFFFF); // GL_CLIENT_ALL_ATTRIB_BITS
        unit1 = false;
        // bindings the attrib stacks do not cover
        shadow = useShadow();
        if (!shadow) query();
        int[] s = shadow ? Hook.gs : q;
        prevProg = s[Hook.G_PROG];
        prevBuf = s[Hook.G_ABUF];
        prevActive = s[Hook.G_ACTIVE];
        prevFb = s[Hook.G_DFB];
        prevTex1 = shadow ? Hook.gtex[1] : qt[1];

        if (prevFb != 0) bindFb(0);
        GL11.glViewport(0, 0, fw, fh);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST); // still applies after a fragment shader
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(0x8006, 0x8006);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override void restore() {
        if (unit1) {
            unit1 = false;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        GL20.glUseProgram(prevProg);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
        GL11.glPopClientAttrib();
        GL11.glPopAttrib();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
        GL13.glActiveTexture(prevActive);
        if (prevFb != 0) bindFb(prevFb);
    }

    @Override void begin(int w, int h) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL20.glUseProgram(program);
        GL20.glUniform2f(uRes, w, h);
        GL20.glUniform1i(uTex, 0);
        GL20.glUniform1i(uImg, 1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int[] sizes = {2, 2, 4, 2, 4};
        int off = 0;
        for (int i = 0; i < sizes.length; i++) {
            GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(i, sizes[i], GL11.GL_FLOAT, false, FLOATS * 4, off * 4L);
            off += sizes[i];
        }
    }

    @Override void draw(FloatBuffer data, int verts) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
    }

    @Override void upload(int x, int y, int w, int h, ByteBuffer px) {
        int[] ps = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS};
        int[] old = new int[4];
        int prevPbo = unpack(ps, old);
        for (int i = 0; i < 4; i++) GL11.glPixelStorei(ps[i], i == 0 ? 1 : 0);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, px);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, prevPbo);
        for (int i = 0; i < 4; i++) GL11.glPixelStorei(ps[i], old[i]);
    }

    private void unit1() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        unit1 = true; // prevTex1 was taken by save
    }

    /** unpack state (alignment, row length, skip rows, skip pixels) into old; returns the bound pixel-unpack buffer */
    private int unpack(int[] ps, int[] old) {
        for (int i = 0; i < 4; i++) old[i] = shadow ? Hook.gs[Hook.G_ALIGN + i] : GL11.glGetInteger(ps[i]);
        return shadow ? Hook.gs[Hook.G_UPBO] : GL11.glGetInteger(0x88EF);
    }

    @Override int texCreate(int w, int h, ByteBuffer rgba, boolean smooth) {
        unit1();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int f = smooth ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
        int[] ps = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS};
        int[] old = new int[4];
        int prevPbo = unpack(ps, old);
        for (int i = 0; i < 4; i++) GL11.glPixelStorei(ps[i], i == 0 ? 1 : 0);
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, 0x8058, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba); // GL_RGBA8
        if (prevPbo != 0) GL15.glBindBuffer(0x88EC, prevPbo);
        for (int i = 0; i < 4; i++) GL11.glPixelStorei(ps[i], old[i]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        return id;
    }

    @Override void texBind(int id) {
        unit1();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override void texDelete(int id) { GL11.glDeleteTextures(id); }

    @Override void clip(boolean on, int x, int y, int w, int h) {
        if (on) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, w, h);
        } else GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override void readBack(int w, int h, ByteBuffer out) {
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
    }
}
