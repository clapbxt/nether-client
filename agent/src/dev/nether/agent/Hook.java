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

    public static float fov(float v) { float d = fovDiv; return d > 1 ? v / d : v; }
    public static float hand(float v) { float s = handShow; return s < 1 ? v * Math.max(0, s) : v; }

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

    /** Draws a replacement for a held item (entity, stack, display context, pose stack, collector, light); true = drawn. */
    public interface Item { boolean draw(Object entity, Object stack, Object ctx, Object pose, Object sink, int light); }
    public static volatile Item item;
    private static int itemErrors;

    /** Start of ItemInHandRenderer.renderItem: true = the overlay drew a cosmetic instead (the game then skips the item). */
    public static boolean item(Object entity, Object stack, Object ctx, Object pose, Object sink, int light) {
        Item f = item;
        if (f == null) return false;
        try {
            return f.draw(entity, stack, ctx, pose, sink, light);
        } catch (Throwable e) {
            if (itemErrors++ == 0) { System.out.println("[nether-agent] held cosmetic error: " + e); e.printStackTrace(System.out); }
            if (itemErrors >= 200) item = null;
            return false;
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

    public static void frame(long window) {
        LongConsumer t = target;
        if (t == null) return;
        try {
            t.accept(window);
        } catch (Throwable e) {
            target = null;
            System.out.println("[nether-agent] hook disabled: " + e);
        }
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
