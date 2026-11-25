package dev.nether.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Lives on the bootstrap class path (Boot-Class-Path), so every class loader can see it, including Fabric's Knot
 * loader, which refuses classes from jars it doesn't know. The agent registers the real overlay (defined inside the
 * LWJGL loader) here. Types are Object/primitive only: this class cannot see LWJGL. Any failure disables the hook
 * instead of crashing the game.
 */
public final class Hook {
    private Hook() {}

    /** Wraps a GLFW callback object; kind 0 key, 1 mouse button, 2 cursor pos, 3 scroll, 4 char. */
    public interface Wrap { Object wrap(int kind, long window, Object cb); }

    /** Polling filters (results of glfwGetKey / glfwGetMouseButton and the value passed to glfwSetInputMode). */
    public interface Poll {
        int key(int result, long window, int key);
        int btn(int result, long window, int button);
        int mode(long window, int mode, int value);
    }

    public static volatile LongConsumer target;
    public static volatile Wrap wrapper;
    public static volatile Poll poll;
    /** Resource bytes (fonts) read from the agent jar, for the overlay. */
    public static final Map<String, byte[]> res = new ConcurrentHashMap<>();
    /** Reads a resource of the agent jar (name tables), null when absent. */
    public static volatile java.util.function.Function<String, byte[]> file = p -> null;
    /** (class, caller): opens the named module of `class` to the caller's module for reflection (Forge keeps the game in one). */
    public static volatile java.util.function.BiConsumer<Class<?>, Class<?>> open;

    /** Dev only (-Dnether.world=<save folder>): the world is opened by quick play (set by args, 1.20+; 1.8.9/1.12.2 load it from Game). */
    public static volatile boolean quickPlay;

    /** (player, name component) -> name to show; set by the overlay (Nametag). */
    public static volatile java.util.function.BiFunction<Object, Object, Object> name;

    /** End of Player.decorateDisplayNameComponent: the name the game shows above the player (nametag). */
    public static Object name(Object player, Object comp) {
        java.util.function.BiFunction<Object, Object, Object> f = name;
        if (f == null || comp == null) return comp;
        try {
            Object r = f.apply(player, comp);
            return r != null ? r : comp;
        } catch (Throwable e) {
            name = null;
            System.out.println("[nether-agent] name hook disabled: " + e);
            return comp;
        }
    }

    /** The entity whose nametag the game must show (you, in third person, Own Nametag on), else null; set by the overlay. */
    public static volatile Object self;
    /** The show-name test was hooked (the overlay then leaves the tag to the game). */
    public static volatile boolean labelHooked;

    /** entity of the shouldShowName call in progress (render thread), taken at the method's start */
    private static Object labelOf;

    /** Start of LivingEntityRenderer.shouldShowName: its entity (the argument slot may be dead or reused by the returns). */
    public static void labelArg(Object entity) { labelOf = entity; }

    /** End of LivingEntityRenderer.shouldShowName: true for {@link #self}, else the game's answer. */
    public static boolean label(boolean shown) {
        Object e = labelOf;
        return shown || (e != null && e == self);
    }

    /** Zoom: GameRenderer's FOV multiplier reads (in getFov) are divided by this (1 = none); set by the overlay. */
    public static volatile float fovDiv = 1;
    /** Held items shown (1) .. eased below the screen (0); ItemInHandRenderer's equip-height reads are scaled by it. */
    public static volatile float handShow = 1;
    /** The FOV / held-item reads were hooked (else Zoom falls back to the FOV option and cannot hide the hand). */
    public static volatile boolean fovHooked, handHooked;

    /**
     * Freelook: while on, mouse turns of {@link #lookOf} (Entity.turn) go to lookYaw/lookPitch instead of the player,
     * and Camera.setRotation uses them. Set by the overlay; render thread.
     */
    public static volatile boolean look, lookInvert;
    public static volatile Object lookOf;
    public static volatile float lookYaw, lookPitch;
    /** Entity.turn / Camera.setRotation were hooked (Freelook needs both). */
    public static volatile boolean turnHooked, camHooked;
    private static boolean lookUsed;

    /** Start of Entity.turn(yRot, xRot): consumed by Freelook (returns 0) or passed on. Same scale as vanilla (0.15). */
    public static double look(Object e, double y, double x) {
        lookUsed = look && e != null && e == lookOf;
        if (!lookUsed) return y;
        lookYaw += (float) (y * 0.15);
        lookPitch = Math.max(-90f, Math.min(90f, lookPitch + (float) ((lookInvert ? -x : x) * 0.15)));
        return 0;
    }
    /** 1.8.9 / 1.12.2 Entity.setAngles(yaw, pitch): same 0.15 scale, but the game subtracts the pitch delta. */
    public static float lookL(Object e, float y, float x) {
        lookUsed = look && e != null && e == lookOf;
        if (!lookUsed) return y;
        lookYaw += y * 0.15f;
        lookPitch = Math.max(-90f, Math.min(90f, lookPitch - (lookInvert ? -x : x) * 0.15f));
        return 0;
    }
    public static float lookLX(float x) { return lookUsed ? 0 : x; }
    /** Entity.turn's xRot after {@link #look}. */
    public static double lookX(double x) { return lookUsed ? 0 : x; }
    /** Start of Camera.setRotation(yaw, pitch). */
    public static float camYaw(float v) { return look ? lookYaw : v; }
    public static float camPitch(float v) { return look ? lookPitch : v; }

    public static float fov(float v) { float d = fovDiv; return d > 1 ? v / d : v; }
    public static float hand(float v) { float s = handShow; return s < 1 ? v * Math.max(0, s) : v; }

    // ---------------------------------------------------------------- view editors (Shield / Fire / Ride, Tier B)

    /** PoseStack and item access for the view editors; set by the overlay (it owns the game's names). */
    public interface Pose {
        void push(Object ps);
        void pop(Object ps);
        void move(Object ps, float dy);
        boolean shield(Object stack);
        /** Hand view: the arm / held item moved by (x, y, z) and scaled by s (x mirrored for the offhand). */
        void hand(Object ps, Object stack, float x, float y, float z, float s);
    }
    public static volatile Pose pose;
    /** ItemInHandRenderer arm / ScreenEffectRenderer fire / entity renderers were hooked (each module needs its own). */
    public static volatile boolean shieldHooked, fireHooked, mountHooked;
    /** Shield: held shield moved up/down by this (0 = vanilla). Fire: overlay moved by fireDy, alpha fireAlpha (-1 = vanilla). */
    public static volatile float shieldDy, fireDy, fireAlpha = -1;
    /** Hand view: first-person arm and item offset (blocks) and scale; 0 / 1 = vanilla. */
    public static volatile float handX, handY, handZ, handS = 1;
    /** Hand view on 1.8.9 / 1.12.2: GL push + transform / pop around renderItemInFirstPerson (set by the overlay). */
    public static volatile Runnable handGlIn, handGlOut;
    public static volatile boolean handViewHooked;
    private static boolean handPushed;
    private static boolean firePushed;
    private static Object firePose, mountArg;
    /** Ride: the entity you ride while its opacity is below 255 (else null), and that alpha. */
    public static volatile Object mountOf;
    public static volatile int mountAlpha = 255;
    /** Identifier -> the game's translucent entity render type (armour and saddle layers of the mount). */
    public static volatile java.util.function.Function<Object, Object> translucent;
    private static Object mountState, mountId;
    private static boolean mountNow;
    private static int viewErrors;

    /** Right after the arm's first pushPose: a held shield moves by shieldDy. */
    public static void shield(Object ps, Object stack) {
        float d = shieldDy;
        Pose p = pose;
        if (p == null) return;
        try {
            if (handX != 0 || handY != 0 || handZ != 0 || handS != 1) p.hand(ps, stack, handX, handY, handZ, handS);
            if (d != 0 && p.shield(stack)) p.move(ps, d);
        } catch (Throwable t) { viewFail(t); }
    }

    /** 1.8.9 / 1.12.2 renderItemInFirstPerson: start (push + offset) and each return (pop). */
    public static void handIn() {
        handPushed = false;
        Runnable r = handGlIn;
        if (r == null || (handX == 0 && handY == 0 && handZ == 0 && handS == 1)) return;
        try { r.run(); handPushed = true; } catch (Throwable t) { viewFail(t); }
    }
    public static void handOut() {
        if (!handPushed) return;
        handPushed = false;
        Runnable r = handGlOut;
        try { if (r != null) r.run(); } catch (Throwable t) { viewFail(t); }
    }

    /** Start / each return of the fire overlay method: its own pose level moved by fireDy. */
    public static void fireIn(Object ps) {
        firePushed = false;
        firePose = ps;
        float d = fireDy;
        Pose p = pose;
        if (d == 0 || p == null) return;
        try { p.push(ps); firePushed = true; p.move(ps, d); } catch (Throwable t) { viewFail(t); }
    }
    public static void fireOut() {
        Object ps = firePose;
        firePose = null;
        if (!firePushed) return;
        firePushed = false;
        Pose p = pose;
        try { if (p != null && ps != null) p.pop(ps); } catch (Throwable t) { viewFail(t); }
    }
    /** The fire quads' alpha (float colour, or packed ARGB on 26.x). */
    /** The live fire editor shows the game's own fire overlay while it is open, burning or not. */
    public static volatile boolean fireForce;
    public static boolean fireShown(boolean burning) { return burning || fireForce; }
    public static float fireA(float a) { float f = fireAlpha; return f < 0 ? a : f; }
    public static int fireArgb(int c) { float f = fireAlpha; return f < 0 ? c : (c & 0xFFFFFF) | (Math.round(Math.max(0, Math.min(1, f)) * 255) << 24); }

    /** EntityRenderer.createRenderState(entity, partial): its entity at the start, its result at the return (remembers the ridden entity's state). */
    public static void mountArg(Object entity) { mountArg = entity; }
    public static void mountTag(Object state) { Object e = mountArg; mountArg = null; if (e != null && e == mountOf) mountState = state; }
    /** LivingEntityRenderer.submit's translucent flag for getRenderType: forced on for the faded mount. */
    public static boolean mountTranslucent(boolean t, Object state) {
        Object m = mountOf; // 1.21.2+ passes the render state, 1.21.1 the entity itself
        mountNow = state != null && m != null && (state == mountState || state == m) && mountAlpha < 255;
        return t || mountNow;
    }
    /** The model / equipment layer colour of the mount: alpha scaled by mountAlpha. */
    /** 1.15-1.20: the translucent model alpha (vanilla 0.15F for the invisible) for the faded mount. */
    public static float mountA(float a) { return mountNow ? mountAlpha / 255f : a; }
    public static int mountColor(int c) { return mountNow ? (c & 0xFFFFFF) | ((((c >>> 24) * mountAlpha) / 255) << 24) : c; }
    /** Equipment layer: the texture id of the next render type, then that render type (translucent for the mount). */
    public static void mountId(Object id) { mountId = id; }
    public static Object mountType(Object rt) {
        java.util.function.Function<Object, Object> f = translucent;
        if (!mountNow || f == null || mountId == null) return rt;
        try { Object r = f.apply(mountId); return r == null ? rt : r; } catch (Throwable t) { viewFail(t); return rt; }
    }

    /** 1.8.9 fire overlay: its translate y (-0.3) moved by fireDy. */
    public static float fireY(float y) { return y + fireDy; }

    /** 1.8.9 mount fade: GL blend + colour alpha around RendererLivingEntity.doRender of the ridden mob (overlay side). */
    public interface Tint {
        void begin(float alpha);
        void end();
        /** 1.12.2: inside renderItemInFirstPerson's matrix, a held shield moves up/down by dy. */
        void shield(Object stack, float dy);
    }
    public static volatile Tint tint;
    private static boolean tinted;
    /** 3D skin layers (1.8.9): around each living entity's doRender, after the game set a player's layer visibility */
    public static volatile java.util.function.Consumer<Object> layersIn;
    public static volatile Runnable layersOut;

    /** Custom sky (1.8.9 / 1.12.2): drawn at the end of RenderGlobal.renderSky with the game's camera matrices. */
    public static volatile java.util.function.Consumer<Float> skyDraw;
    public static volatile boolean skyHooked;
    /** LevelRenderer.renderLevel arguments of the current frame: view rotation, projection (1.21.x), CameraRenderState (26.1). */
    public static volatile Object levelView, levelProj, levelCam;
    public static volatile long levelFrame, levelNs;
    public static void levelMats(Object view, Object proj, Object cam) { levelView = view; levelProj = proj; levelCam = cam; levelFrame++; levelNs = System.nanoTime(); }
    /** Custom sky (1.21.2 - 26.1): drawn after SkyRenderer.renderSkyDisc, inside the world pass. */
    public static volatile Runnable skyModernDraw;
    public static void skyModern() {
        Runnable d = skyModernDraw;
        if (d != null) try { d.run(); } catch (Throwable x) { skyModernDraw = null; System.out.println("[nether-agent] custom sky off: " + x); }
    }
    public static void sky(float partial) {
        java.util.function.Consumer<Float> d = skyDraw;
        if (d != null) try { d.accept(partial); } catch (Throwable x) { skyDraw = null; System.out.println("[nether-agent] custom sky off: " + x); }
    }

    public static void tintIn(Object e) {
        java.util.function.Consumer<Object> li = layersIn;
        if (li != null) try { li.accept(e); } catch (Throwable x) { layersIn = null; System.out.println("[nether-agent] 3D skin layers hook: " + x); }
        tinted = false;
        Tint t = tint;
        Object m = mountOf;
        if (t == null || m == null || e != m) return;
        try { t.begin(mountAlpha / 255f); tinted = true; } catch (Throwable x) { viewFail(x); }
    }
    public static void lshield(Object stack) {
        float d = shieldDy;
        Tint t = tint;
        if (d == 0 || t == null) return;
        try { t.shield(stack, d); } catch (Throwable x) { viewFail(x); }
    }
    public static void tintOut() {
        Runnable lo = layersOut;
        if (lo != null) try { lo.run(); } catch (Throwable x) { layersOut = null; }
        if (!tinted) return;
        tinted = false;
        Tint t = tint;
        try { if (t != null) t.end(); } catch (Throwable x) { viewFail(x); }
    }

    /** A view hook threw: logged once; one that keeps failing switches the view editors off (the game draws vanilla). */
    private static synchronized void viewFail(Throwable e) {
        if (viewErrors++ == 0) { System.out.println("[nether-agent] view hook error: " + e); e.printStackTrace(System.out); }
        if (viewErrors >= 50) { System.out.println("[nether-agent] view hooks disabled after repeated errors"); pose = null; translucent = null; tint = null; }
    }

    /**
     * (super class, game loader) -> a generated subclass of the game's render layer whose one abstract draw method
     * passes (this, its arguments) to {@link #layer(Object, Object[])}. Set by the agent (it owns ASM); used by the
     * overlay's Cosmetics to append a layer to the player renderers (no game class is patched).
     */
    public static volatile java.util.function.BiFunction<Class<?>, ClassLoader, Class<?>> layerClass;
    /** (layer, draw arguments) -> draws the cosmetics; set by the overlay. */
    public static volatile java.util.function.BiConsumer<Object, Object[]> layerDraw;
    private static int layerErrors;

    /** The generated render layer's draw method (render thread). */
    public static void layer(Object self, Object[] args) {
        java.util.function.BiConsumer<Object, Object[]> f = layerDraw;
        if (f == null) return;
        try {
            f.accept(self, args);
        } catch (Throwable e) {
            if (layerErrors++ == 0) { System.out.println("[nether-agent] cosmetics layer error: " + e); e.printStackTrace(System.out); }
            if (layerErrors >= 200) { layerDraw = null; System.out.println("[nether-agent] cosmetics layer disabled after repeated errors"); }
        }
    }

    /** component -> chat line to show; set by the overlay (the Nether badge before Nether players' names). */
    public static volatile java.util.function.Function<Object, Object> chat;

    /** Start of the chat HUD's add-message methods: the message about to be shown. */
    public static Object chat(Object comp) {
        java.util.function.Function<Object, Object> f = chat;
        if (f == null || comp == null) return comp;
        try {
            Object r = f.apply(comp);
            return r != null ? r : comp;
        } catch (Throwable e) {
            chat = null;
            System.out.println("[nether-agent] chat hook disabled: " + e);
            return comp;
        }
    }

    public static String[] args(String[] a) {
        String w = System.getProperty("nether.world");
        if (w != null) w = w.replace('+', ' '); // JAVA_TOOL_OPTIONS cannot quote spaces on Java 8
        if (w == null || a == null) return a;
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals("--version")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|-)1\\.(\\d+)").matcher(a[i + 1]);
            if (m.find() && Integer.parseInt(m.group(1)) < 20) return a; // quick play is 1.20+
        }
        for (String s : a) if (s.startsWith("--quickPlay")) return a;
        String[] b = java.util.Arrays.copyOf(a, a.length + 2);
        b[a.length] = "--quickPlaySingleplayer";
        b[a.length + 1] = w;
        quickPlay = true;
        return b;
    }

    // ---------------------------------------------------------------- GL state shadow
    // The overlay draws on the game's finished frame and must leave the GL state exactly as it found it. Asking the
    // driver (glGet*) stalls the CPU until the driver's worker thread has drained the whole frame (NVIDIA "threaded
    // optimization"), which cost up to half the frame. Instead the agent wraps LWJGL's GL entry points (natives through a
    // method prefix on LWJGL3, Java methods on LWJGL2) and records, from the render thread only, the state the overlay
    // touches. The overlay seeds it with one real query and re-checks it now and then; a mismatch turns it off for good.

    /** Only calls from this thread are recorded (the game's render thread, set by the first frame). */
    public static volatile Thread glThread;
    public static final int G_PROG = 0, G_VAO = 1, G_ABUF = 2, G_DFB = 3, G_ACTIVE = 4, G_VP = 5, G_SB = 9, G_POLYF = 13, G_POLYB = 14,
        G_CAPS = 15, G_DMASK = 16, G_SRC_RGB = 17, G_DST_RGB = 18, G_SRC_A = 19, G_DST_A = 20, G_EQ_RGB = 21, G_EQ_A = 22, G_CMASK = 23,
        G_ALIGN = 24, G_ROW = 25, G_SKIP_ROWS = 26, G_SKIP_PIX = 27, G_UPBO = 28, G_N = 29;
    /** cap bits in gs[G_CAPS] */
    public static final int C_BLEND = 1, C_DEPTH = 2, C_CULL = 4, C_SCISSOR = 8, C_STENCIL = 16, C_SRGB = 32;
    public static final int[] gs = new int[G_N];
    /** TEXTURE_BINDING_2D and SAMPLER_BINDING per texture unit */
    public static final int[] gtex = new int[32], gsmp = new int[32];

    private static boolean onGl() { return Thread.currentThread() == glThread; }
    private static int unit() { int u = gs[G_ACTIVE] - 0x84C0; return u < 0 || u >= 32 ? 0 : u; }
    private static int capBit(int cap) {
        switch (cap) {
            case 0x0BE2: return C_BLEND;
            case 0x0B71: return C_DEPTH;
            case 0x0B44: return C_CULL;
            case 0x0C11: return C_SCISSOR;
            case 0x0B90: return C_STENCIL;
            case 0x8DB9: return C_SRGB;
            default: return 0;
        }
    }

    public static void glEnable(int cap) { if (onGl()) gs[G_CAPS] |= capBit(cap); }
    public static void glDisable(int cap) { if (onGl()) gs[G_CAPS] &= ~capBit(cap); }
    public static void glViewport(int x, int y, int w, int h) { if (onGl()) { gs[G_VP] = x; gs[G_VP + 1] = y; gs[G_VP + 2] = w; gs[G_VP + 3] = h; } }
    public static void glScissor(int x, int y, int w, int h) { if (onGl()) { gs[G_SB] = x; gs[G_SB + 1] = y; gs[G_SB + 2] = w; gs[G_SB + 3] = h; } }
    public static void glBindTexture(int target, int id) { if (target == 0x0DE1 && onGl()) gtex[unit()] = id; }
    public static void glActiveTexture(int u) { if (onGl()) gs[G_ACTIVE] = u; }
    public static void glActiveTextureARB(int u) { glActiveTexture(u); }
    public static void glBindSampler(int u, int s) { if (u >= 0 && u < 32 && onGl()) gsmp[u] = s; }
    public static void glDepthMask(boolean m) { if (onGl()) gs[G_DMASK] = m ? 1 : 0; }
    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) { if (onGl()) gs[G_CMASK] = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0); }
    public static void glBlendFunc(int s, int d) { glBlendFuncSeparate(s, d, s, d); }
    public static void glBlendFuncSeparate(int sr, int dr, int sa, int da) { if (onGl()) { gs[G_SRC_RGB] = sr; gs[G_DST_RGB] = dr; gs[G_SRC_A] = sa; gs[G_DST_A] = da; } }
    public static void glBlendEquation(int e) { glBlendEquationSeparate(e, e); }
    public static void glBlendEquationSeparate(int rgb, int a) { if (onGl()) { gs[G_EQ_RGB] = rgb; gs[G_EQ_A] = a; } }
    public static void glPolygonMode(int face, int mode) {
        if (!onGl()) return;
        if (face == 0x0408 || face == 0x0404) gs[G_POLYF] = mode;
        if (face == 0x0408 || face == 0x0405) gs[G_POLYB] = mode;
    }
    public static void glPixelStorei(int p, int v) {
        if (!onGl()) return;
        if (p == 0x0CF5) gs[G_ALIGN] = v; else if (p == 0x0CF2) gs[G_ROW] = v; else if (p == 0x0CF3) gs[G_SKIP_ROWS] = v; else if (p == 0x0CF4) gs[G_SKIP_PIX] = v;
    }
    public static void glBindBuffer(int target, int id) { if (!onGl()) return; if (target == 0x8892) gs[G_ABUF] = id; else if (target == 0x88EC) gs[G_UPBO] = id; }
    public static void glBindBufferARB(int target, int id) { glBindBuffer(target, id); }
    public static void glUseProgram(int p) { if (onGl()) gs[G_PROG] = p; }
    public static void glUseProgramObjectARB(int p) { glUseProgram(p); }
    public static void glBindVertexArray(int v) { if (onGl()) gs[G_VAO] = v; }
    public static void glBindFramebuffer(int target, int id) { if ((target == 0x8D40 || target == 0x8CA9) && onGl()) gs[G_DFB] = id; }
    public static void glBindFramebufferEXT(int target, int id) { glBindFramebuffer(target, id); }
    // deleting a bound object unbinds it
    public static void glDeleteTextures(int id) { if (id != 0 && onGl()) for (int i = 0; i < 32; i++) if (gtex[i] == id) gtex[i] = 0; }
    public static void glDeleteBuffers(int id) { if (id != 0 && onGl()) { if (gs[G_ABUF] == id) gs[G_ABUF] = 0; if (gs[G_UPBO] == id) gs[G_UPBO] = 0; } }
    public static void glDeleteVertexArrays(int id) { if (id != 0 && onGl() && gs[G_VAO] == id) gs[G_VAO] = 0; }
    public static void glDeleteFramebuffers(int id) { if (id != 0 && onGl() && gs[G_DFB] == id) gs[G_DFB] = 0; }
    public static void glDeleteFramebuffersEXT(int id) { glDeleteFramebuffers(id); }
    public static void glDeleteSamplers(int id) { if (id != 0 && onGl()) for (int i = 0; i < 32; i++) if (gsmp[i] == id) gsmp[i] = 0; }

    // ---------------------------------------------------------------- GPU error-check pairs (Performance module)

    /** Skip vanilla's clearGlErrors() + _getError() out-of-memory probe (each glGetError waits for the driver). Set by ov.Performance. */
    public static volatile boolean glErrSkip = true;
    private static boolean errPending;

    /** Start of GlStateManager.clearGlErrors(): true = return at once (and let the next _getError report "no error"). */
    public static boolean glErrClear() {
        if (!glErrSkip) return false;
        errPending = true;
        return true;
    }

    /** Start of GlStateManager._getError(): true = return 0 without asking the driver (only right after a skipped clear). */
    public static boolean glErrRead() {
        if (!errPending) return false;
        errPending = false;
        return true;
    }

    // ---------------------------------------------------------------- entity occlusion culling (Performance module)

    /** Answers "is this entity hidden behind opaque blocks?" from the overlay's culling worker; render thread. */
    public interface Cull {
        boolean hidden(Object entity, double camX, double camY, double camZ);
        /** Block entity behind opaque blocks (camera of this frame from the entity test). */
        boolean hiddenBlock(Object dispatcher, Object blockEntity);
    }
    /** The block entity render dispatcher's per-block-entity method was hooked. */
    public static volatile boolean beCullHooked;
    /** Block entity culling on (Performance "Skip hidden block entities"); Culling also needs cull set. */
    public static volatile boolean beCull;
    private static int beAsked, beHidden;

    /** Start of the block entity dispatcher's render / extract method: true = the game skips that block entity this frame. */
    public static boolean becull(Object dispatcher, Object be) {
        Cull c = cull;
        if (c == null || !beCull) return false;
        try {
            boolean h = c.hiddenBlock(dispatcher, be);
            if (BENCH) { beAsked++; if (h) beHidden++; }
            return h;
        } catch (Throwable t) {
            if (cullErrors++ == 0) { System.out.println("[nether-agent] block entity culling error: " + t); t.printStackTrace(System.out); }
            if (cullErrors >= 50) { cull = null; System.out.println("[nether-agent] culling disabled after repeated errors"); }
            return false;
        }
    }
    public static volatile Cull cull;
    /** EntityRenderDispatcher.shouldRender (RenderManager on 1.8.9 / 1.12.2) was hooked. */
    public static volatile boolean cullHooked;
    /** dev: -Dnether.benchCull=1 (with bench) turns culling off and on every other 5 s window. */
    private static final boolean BENCH_CULL = "1".equals(System.getProperty("nether.benchCull"));
    private static volatile boolean cullOff;
    private static int cullErrors, cullAsked, cullHidden;

    /** Start of EntityRenderDispatcher.shouldRender(entity, frustum, camX, camY, camZ[, partial]): true = the game skips the entity. */
    public static boolean cull(Object e, double x, double y, double z) {
        Cull c = cull;
        if (c == null || cullOff) return false;
        try {
            boolean h = c.hidden(e, x, y, z);
            if (BENCH) { cullAsked++; if (h) cullHidden++; }
            return h;
        } catch (Throwable t) {
            if (cullErrors++ == 0) { System.out.println("[nether-agent] culling error: " + t); t.printStackTrace(System.out); }
            if (cullErrors >= 50) { cull = null; System.out.println("[nether-agent] culling disabled after repeated errors"); }
            return false;
        }
    }

    /** dev: -Dnether.bench=1 prints frame-rate stats every 5 s from the swap hook itself, so it also measures -Dnether.bare=1 (overlay and game hooks off). */
    static final boolean BENCH = "1".equals(System.getProperty("nether.bench"));
    private static long benchAt, benchLast;
    private static final long[] benchDt = new long[200_000];
    private static int benchN;
    /** time spent in the overlay (all of Hook.target) this window, and its longest single frame */
    private static long benchOv, benchOvMax;
    /** dev: -Dnether.benchAB=1 (with bench) turns the overlay off and on every other window */
    private static final boolean BENCH_AB = "1".equals(System.getProperty("nether.benchAB"));
    private static LongConsumer benchSaved;
    /** dev: -Dnether.benchFeat=<hud|becull|...> (with bench): that Performance feature is off in every other window. */
    public static final String BENCH_FEAT = BENCH ? System.getProperty("nether.benchFeat") : null;
    public static volatile boolean featOff;

    /** One line per 5 s window: average FPS, 1 % and 0.1 % lows (FPS at the 99th / 99.9th percentile frame time). */
    private static void bench() {
        long now = System.nanoTime();
        if (benchLast != 0 && benchN < benchDt.length) benchDt[benchN++] = now - benchLast;
        benchLast = now;
        if (benchAt == 0) benchAt = now;
        if (now - benchAt < 5_000_000_000L || benchN == 0) return;
        long[] d = java.util.Arrays.copyOf(benchDt, benchN);
        java.util.Arrays.sort(d);
        double avg = benchN / ((now - benchAt) / 1e9);
        double p99 = 1e9 / d[Math.min(benchN - 1, (int) (benchN * 0.99))], p999 = 1e9 / d[Math.min(benchN - 1, (int) (benchN * 0.999))];
        String ab = "";
        if (BENCH_AB) {
            // the overlay alternates on / off every window, so both halves see the same world, clocks and load
            if (benchSaved == null) benchSaved = target;
            ab = target != null ? " ab on" : " ab off";
            target = target != null ? null : benchSaved;
        }
        if (cullHooked) ab += String.format(java.util.Locale.ROOT, " cull%s %.0f/%.0f", cullOff ? "off" : "on", cullHidden / (double) benchN, cullAsked / (double) benchN);
        if (BENCH_CULL) cullOff = !cullOff;
        if (BENCH_FEAT != null) { ab += " " + BENCH_FEAT + (featOff ? "off" : "on"); featOff = !featOff; }
        if (beCullHooked) ab += String.format(java.util.Locale.ROOT, " be %.0f/%.0f", beHidden / (double) benchN, beAsked / (double) benchN);
        if (waitN > 0) ab += String.format(java.util.Locale.ROOT, " oswait late avg %.0fus max %.0fus", lateSum / 1e3 / waitN, lateMax / 1e3);
        waitN = 0; lateSum = lateMax = 0;
        cullAsked = cullHidden = beAsked = beHidden = 0;
        System.out.println(String.format(java.util.Locale.ROOT, "[nether-bench] fps %.1f low1 %.1f low01 %.1f frames %d ov %.1fus ovmax %.1fus%s", avg, p99, p999, benchN, benchOv / 1e3 / benchN, benchOvMax / 1e3, ab));
        benchN = 0;
        benchOv = benchOvMax = 0;
        benchAt = now;
    }

    // ---------------------------------------------------------------- precise frame cap (Performance module)

    /** Performance "Precise frame cap": the game's limiter waits through glfwWaitEventsTimeout (1.13 - 26.2). */
    public static volatile boolean preciseWait;
    /** OS wait ends this long before the deadline; the rest is spun (Windows wakes a wait up to a timer tick late). */
    private static final double SPIN_S = Double.parseDouble(System.getProperty("nether.spinMs", "1.5")) / 1000;

    /**
     * glfwWaitEventsTimeout(timeout) starts with timeout = waitTimeout(timeout). The vanilla limiter calls it in a loop
     * until its deadline, so a wait ending early just comes back with the rest: the OS wait stops SPIN_S short and the
     * last call spins to the deadline (returns -1: no GLFW wait; the game polls events itself right after the frame).
     */
    private static long wakeAt, lateSum, lateMax;
    private static int waitN;

    public static double waitTimeout(double t) {
        if (!preciseWait || !(t > 0) || t > 1 || Thread.currentThread() != glThread) return t;
        if (BENCH && wakeAt != 0) { long late = System.nanoTime() - wakeAt; waitN++; lateSum += late; if (late > lateMax) lateMax = late; wakeAt = 0; }
        if (t > SPIN_S + 0.0005) { if (BENCH) wakeAt = System.nanoTime() + (long) ((t - SPIN_S) * 1e9); return t - SPIN_S; }
        long end = System.nanoTime() + (long) (t * 1e9);
        while (System.nanoTime() < end) { } // under ~2 ms: spin (Java 8 has no onSpinWait)
        return -1;
    }

    /** True once the agent wrapped LWJGL's GL calls (so the shadow above is being kept). */
    public static volatile boolean glShadowed;

    /** Swapped frames (render thread), counted even when the overlay is off. */
    public static long frames;

    public static void frame(long window) {
        frames++;
        if (glThread == null) glThread = Thread.currentThread();
        if (BENCH) bench();
        LongConsumer t = target;
        if (t == null) return;
        long t0 = BENCH ? System.nanoTime() : 0;
        try {
            t.accept(window);
        } catch (Throwable e) {
            target = null;
            System.out.println("[nether-agent] hook disabled: " + e);
        }
        if (BENCH) { long d = System.nanoTime() - t0; benchOv += d; if (d > benchOvMax) benchOvMax = d; }
    }

    public static Object wrap(long window, Object cb, int kind) {
        Wrap w = wrapper;
        if (w == null || cb == null) return cb;
        try {
            return w.wrap(kind, window, cb);
        } catch (Throwable e) {
            wrapper = null;
            System.out.println("[nether-agent] input wrap disabled: " + e);
            return cb;
        }
    }

    public static int getKey(int r, long window, int key) {
        Poll p = poll;
        if (p == null) return r;
        try { return p.key(r, window, key); } catch (Throwable e) { if (bad(e)) poll = null; return r; }
    }

    public static int getBtn(int r, long window, int button) {
        Poll p = poll;
        if (p == null) return r;
        try { return p.btn(r, window, button); } catch (Throwable e) { if (bad(e)) poll = null; return r; }
    }

    public static int mode(long window, int mode, int value) {
        Poll p = poll;
        if (p == null) return value;
        try { return p.mode(window, mode, value); } catch (Throwable e) { if (bad(e)) poll = null; return value; }
    }

    // ---------------------------------------------------------------- SDL3 (26.3+): polled events, keyboard state, relative mouse

    /** Filters on SDL calls: SDL_PollEvent result (event = the SDL_Event), SDL_GetKeyboardState buffer, SDL_SetWindowRelativeMouseMode argument. */
    public interface Sdl {
        boolean poll(boolean result, Object event);
        Object keys(Object state);
        boolean relative(boolean on);
    }

    public static volatile Sdl sdl;

    public static boolean sdlPoll(boolean r, Object event) {
        Sdl s = sdl;
        if (s == null) return r;
        try { return s.poll(r, event); } catch (Throwable e) { if (bad(e)) sdl = null; return r; }
    }

    public static Object sdlKeys(Object state) {
        Sdl s = sdl;
        if (s == null) return state;
        try { return s.keys(state); } catch (Throwable e) { if (bad(e)) sdl = null; return state; }
    }

    public static boolean sdlRel(boolean on) {
        Sdl s = sdl;
        if (s == null) return on;
        try { return s.relative(on); } catch (Throwable e) { if (bad(e)) sdl = null; return on; }
    }

    // ---------------------------------------------------------------- LWJGL2 (1.8.9): polled input + Display.update

    /** Filters on LWJGL2's polled Keyboard/Mouse statics. Every method gets the game's real result and returns what the game should see. */
    public interface Legacy {
        boolean kbNext(boolean r);
        int kbKey(int r);
        boolean kbState(boolean r);
        char kbChar(char r);
        boolean kbDown(boolean r, int key);
        boolean msNext(boolean r);
        int msButton(int r);
        boolean msState(boolean r);
        int msWheel(int r);
        int msDX(int r);
        int msDY(int r);
        boolean msDown(boolean r, int button);
        boolean msGrab(boolean grab);
    }

    public static volatile Legacy legacy;
    private static boolean outerSeen;

    /** Start of Display.update(): outer = the no-arg overload, which calls update(true) (that inner call must not draw twice). */
    public static void lframe(boolean outer) {
        if (!outer) {
            if (outerSeen) { outerSeen = false; return; }
        } else outerSeen = true;
        frame(1L);
    }

    public static boolean lkbNext(boolean r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.kbNext(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static int lkbKey(int r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.kbKey(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static boolean lkbState(boolean r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.kbState(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static char lkbChar(char r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.kbChar(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static boolean lkbDown(boolean r, int k) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.kbDown(r, k); } catch (Throwable e) { fail(e); return r; }
    }
    public static boolean lmsNext(boolean r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msNext(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static int lmsButton(int r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msButton(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static boolean lmsState(boolean r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msState(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static int lmsWheel(int r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msWheel(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static int lmsDX(int r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msDX(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static int lmsDY(int r) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msDY(r); } catch (Throwable e) { fail(e); return r; }
    }
    public static boolean lmsDown(boolean r, int b) {
        Legacy l = legacy;
        if (l == null) return r;
        try { return l.msDown(r, b); } catch (Throwable e) { fail(e); return r; }
    }
    public static boolean lmsGrab(boolean g) {
        Legacy l = legacy;
        if (l == null) return g;
        try { return l.msGrab(g); } catch (Throwable e) { fail(e); return g; }
    }

    private static void fail(Throwable e) {
        if (bad(e)) legacy = null;
    }

    private static int errors;

    /**
     * An input filter threw (it already guards every event itself, so this is rare): logged once, and only a filter that
     * keeps failing is switched off, so one bad event never leaves the game without its input hooks.
     */
    private static synchronized boolean bad(Throwable e) {
        if (errors++ == 0) { System.out.println("[nether-agent] input hook error: " + e); e.printStackTrace(System.out); }
        if (errors < 50) return false;
        System.out.println("[nether-agent] input hook disabled after repeated errors: " + e);
        errors = 0;
        return true;
    }
}
