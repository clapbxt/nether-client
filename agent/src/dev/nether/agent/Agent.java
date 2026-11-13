package dev.nether.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.nether.shaded.asm.ClassReader;
import dev.nether.shaded.asm.ClassVisitor;
import dev.nether.shaded.asm.ClassWriter;
import dev.nether.shaded.asm.MethodVisitor;
import dev.nether.shaded.asm.Opcodes;
import dev.nether.shaded.asm.Type;

/**
 * Nether agent entry point. Loaded with -javaagent so it works on vanilla, Fabric, Forge, NeoForge, etc. This class is
 * Java 8 bytecode (module-system calls are reflective), so it also runs on Minecraft 1.8.9's Java 8.
 *
 * Tier A only: the only game-adjacent classes we touch belong to LWJGL.
 * LWJGL3 (1.13+): we insert
 *  - a call at the start of GLFW.glfwSwapBuffers (overlay drawn on the finished frame, below every renderer mod),
 *  - a wrapper around the callback passed to glfwSet{Key,MouseButton,CursorPos,Scroll,Char,CharMods}Callback (our filter sees
 *    every event first and can swallow it while the menu is open),
 *  - a value filter on glfwSetInputMode and result filters on glfwGetKey / glfwGetMouseButton.
 * LWJGL2 (1.8.9, polled input, no GLFW): a call at the start of both Display.update overloads (frame), and result/argument
 *  filters on Keyboard.next, getEvent..., isKeyDown and Mouse.next, getEvent..., getDX, getDY, isButtonDown, setGrabbed (Hook.Legacy).
 * The overlay classes (dev.nether.ov.*) are defined INTO the class loader that owns the game's LWJGL (vanilla: app
 * loader, Fabric: Knot), so they share LWJGL's classes and natives instead of loading a second copy.
 */
public final class Agent {
    private Agent() {}

    private static Instrumentation inst;
    private static boolean overlayDefined;
    private static final String HOOK = "dev/nether/agent/Hook";

    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        openJavaLang();
        Hook.open = Agent::openModule;
        for (String f : new String[] {"ChakraPetch-Bold", "ChakraPetch-SemiBold", "ChakraPetch-Medium", "JetBrainsMono-Medium"}) {
            try (InputStream in = Agent.class.getResourceAsStream("/fonts/" + f + ".ttf")) {
                if (in != null) Hook.res.put("fonts/" + f + ".ttf", readAll(in));
            } catch (Throwable t) {
                System.out.println("[nether-agent] font " + f + ": " + t);
            }
        }
        Hook.file = p -> { try { return read("/" + p); } catch (Throwable t) { return null; } };
        nameTargets();
        inst.addTransformer(new LwjglTransformer());
        System.out.println("[nether-agent] loaded (java " + System.getProperty("java.version") + ")");
    }

    /** Player class -> {name method, descriptor}: the nametag hook in every naming the game can run with. */
    private static final Map<String, String[]> NAME_HOOKS = new HashMap<>();

    /** Gui class -> {render-crosshair method, exact descriptor or null = any}: Fabric/Quilt (intermediary), Mojang names (Forge, NeoForge, 26.x), vanilla 1.21.11. */
    private static final Map<String, String[]> CROSS_HOOKS = new HashMap<>();

    private static void nameTargets() {
        CROSS_HOOKS.put("net/minecraft/class_329", new String[] {"method_1736", null});
        CROSS_HOOKS.put("net/minecraft/client/gui/Gui", new String[] {"renderCrosshair", null});
        NAME_HOOKS.put("net/minecraft/class_1657", new String[] {"method_7299", "(Lnet/minecraft/class_5250;)Lnet/minecraft/class_5250;"}); // Fabric
        NAME_HOOKS.put("net/minecraft/world/entity/player/Player", new String[] {"decorateDisplayNameComponent", "(Lnet/minecraft/network/chat/MutableComponent;)Lnet/minecraft/network/chat/MutableComponent;"}); // Forge/NeoForge, 26.x
        NAME_HOOKS.put("net/minecraft/entity/player/EntityPlayer", new String[] {"func_145748_c_", "()Lnet/minecraft/util/IChatComponent;"}); // Forge 1.8.9 getDisplayName (SRG)
        String[] a = String.valueOf(System.getProperty("sun.java.command")).split(" ");
        String v = null;
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals("--version")) v = a[i + 1];
        if (v == null) return;
        if (v.equals("1.8.9")) NAME_HOOKS.put("wn", new String[] {"f_", "()Leu;"}); // vanilla 1.8.9 EntityPlayer.getDisplayName
        if (v.equals("1.21.11")) CROSS_HOOKS.put("giq", new String[] {"k", "(Lgir;Lgez;)V"}); // vanilla 1.21.11 Gui.renderCrosshair
        try { // obfuscated vanilla with a name table (rows: intermediary, obfuscated, descriptor)
            String cls = null, m = null, d = null;
            for (String l : new String(read("/names/" + v + ".txt"), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String[] r = l.split("\t", -1);
                if (r.length < 3) continue;
                for (int i = 0; i < r.length; i++) r[i] = r[i].trim(); // a CRLF checkout must not leave \r in the descriptor
                if (r[0].equals("net.minecraft.class_1657")) cls = r[1];
                else if (r[0].equals("method_7299")) { m = r[1]; d = r[2]; }
            }
            if (cls != null && m != null && !d.isEmpty()) NAME_HOOKS.put(cls.replace('.', '/'), new String[] {m, d});
        } catch (Throwable t) { } // no table for this version
    }

    /** Java 9+: open java.lang to us so ClassLoader.defineClass can be called reflectively. All reflective, so Java 8 can load this class. */
    private static void openJavaLang() {
        try {
            Class<?> modCls;
            try { modCls = Class.forName("java.lang.Module"); } catch (ClassNotFoundException e) { return; } // Java 8: no modules
            Method getModule = Class.class.getMethod("getModule");
            Object base = getModule.invoke(Object.class), self = getModule.invoke(Agent.class);
            Map<String, Set<Object>> opens = new HashMap<>();
            opens.put("java.lang", Collections.singleton(self));
            Method rm = Instrumentation.class.getMethod("redefineModule", modCls, Set.class, Map.class, Map.class, Set.class, Map.class);
            rm.invoke(inst, base, Collections.emptySet(), Collections.emptyMap(), opens, Collections.emptySet(), Collections.emptyMap());
        } catch (Throwable t) {
            System.out.println("[nether-agent] could not open java.lang: " + t);
        }
    }

    /** Java 9+: opens every package of the named module that holds `target` to the module of `to`. Reflective for Java 8. */
    private static void openModule(Class<?> target, Class<?> to) {
        try {
            Class<?> modCls;
            try { modCls = Class.forName("java.lang.Module"); } catch (ClassNotFoundException e) { return; }
            Method getModule = Class.class.getMethod("getModule");
            Object m = getModule.invoke(target), self = getModule.invoke(to);
            if (m == self || !(Boolean) modCls.getMethod("isNamed").invoke(m)) return;
            Map<String, Set<Object>> opens = new HashMap<>();
            for (Object p : (Set<?>) modCls.getMethod("getPackages").invoke(m)) opens.put((String) p, Collections.singleton(self));
            Method rm = Instrumentation.class.getMethod("redefineModule", modCls, Set.class, Map.class, Map.class, Set.class, Map.class);
            rm.invoke(inst, m, Collections.emptySet(), Collections.emptyMap(), opens, Collections.emptySet(), Collections.emptyMap());
        } catch (Throwable t) {
            System.out.println("[nether-agent] could not open the game module: " + t);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static byte[] read(String path) throws Exception {
        try (InputStream in = Agent.class.getResourceAsStream(path)) {
            return readAll(in);
        }
    }

    private static void defineOrdered(Method dc, ClassLoader loader, String n, List<String> all, Set<String> done) throws Exception {
        if (!done.add(n)) return;
        byte[] b = read("/" + n.replace('.', '/') + ".class");
        ClassReader cr = new ClassReader(b);
        List<String> deps = new ArrayList<>();
        if (cr.getSuperName() != null) deps.add(cr.getSuperName().replace('/', '.'));
        for (String i : cr.getInterfaces()) deps.add(i.replace('/', '.'));
        for (String d : deps) if (all.contains(d)) defineOrdered(dc, loader, d, all, done);
        dc.invoke(loader, n, b, 0, b.length);
    }

    /** Define every dev.nether.ov class inside `loader`, then let Overlay.install() register itself with Hook. */
    /** platform: 0 GLFW, 1 LWJGL2, 2 SDL3. */
    private static synchronized void installOverlay(ClassLoader loader, int platform) {
        if (overlayDefined || loader == null) return;
        overlayDefined = true;
        try {
            List<String> names = new ArrayList<>();
            for (String l : new String(read("/ov.list"), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String n = l.trim();
                // each platform only gets its own classes: the other one's supertypes (GLFW callbacks) would not resolve
                int only = n.startsWith("dev.nether.ov.InputGlfw") ? 0 : n.startsWith("dev.nether.ov.PlatLwjgl2") || n.startsWith("dev.nether.ov.GlCompat") ? 1
                    : n.startsWith("dev.nether.ov.PlatSdl") ? 2 : n.startsWith("dev.nether.ov.GlCore") ? (platform == 1 ? 0 : platform) : -1;
                if (n.length() > 0 && (only < 0 || only == platform)) names.add(n);
            }
            Method dc = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            dc.setAccessible(true);
            // define supertypes first, so the loader never resolves them from the class path on its own
            Set<String> done = new HashSet<>();
            for (String n : names) defineOrdered(dc, loader, n, names, done);
            Class<?> c = Class.forName("dev.nether.ov.Overlay", true, loader);
            c.getMethod("install", int.class).invoke(null, platform);
            System.out.println("[nether-agent] overlay defined in " + loader.getClass().getName() + " (" + names.size() + " classes, " + (platform == 1 ? "LWJGL2" : platform == 2 ? "SDL3" : "LWJGL3") + ")");
        } catch (Throwable t) {
            System.out.println("[nether-agent] could not install overlay: " + t);
            t.printStackTrace(System.out);
        }
    }

    /** Adds `call` before every IRETURN: the returned int is replaced by the hook's result (hook takes and returns the same type). */
    private static MethodVisitor retFilter(MethodVisitor mv, final String hook, final String desc, final boolean withArg0) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitInsn(int op) {
                if (op == Opcodes.IRETURN) {
                    if (withArg0) super.visitVarInsn(Opcodes.ILOAD, 0); // static method, first (int) argument
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, hook, desc, false);
                }
                super.visitInsn(op);
            }
        };
    }

    static final class LwjglTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
            if ("net/minecraft/client/main/Main".equals(name) && System.getProperty("nether.world") != null) return devArgs(bytes);
            String[] nh = name == null ? null : NAME_HOOKS.get(name);
            if (nh != null) return playerName(bytes, nh[0], nh[1]);
            String[] ch = name == null ? null : CROSS_HOOKS.get(name);
            if (ch != null) return crosshair(bytes, ch[0], ch[1]);
            if (name == null || !name.startsWith("org/lwjgl/")) return null;
            final boolean glfw = "org/lwjgl/glfw/GLFW".equals(name);
            final boolean display = "org/lwjgl/opengl/Display".equals(name);
            final boolean kb = "org/lwjgl/input/Keyboard".equals(name), ms = "org/lwjgl/input/Mouse".equals(name);
            final boolean sdl = name.startsWith("org/lwjgl/sdl/SDL") && (name.endsWith("/SDLVideo") || name.endsWith("/SDLEvents") || name.endsWith("/SDLKeyboard") || name.endsWith("/SDLMouse"));
            if (!glfw && !display && !kb && !ms && !sdl) return null;
            try {
                installOverlay(loader, glfw ? 0 : sdl ? 2 : 1);
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (glfw) return glfwMethod(mv, mname, desc);
                        if (sdl) return sdlMethod(mv, mname, desc);
                        if (display) return displayMethod(mv, mname, desc);
                        return kb ? keyboardMethod(mv, mname, desc) : mouseMethod(mv, mname, desc);
                    }
                }, 0);
                System.out.println("[nether-agent] hooked " + name.substring(name.lastIndexOf('/') + 1));
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] hook failed: " + t);
                return null;
            }
        }

        /** Player.decorateDisplayNameComponent (1.8.9: getDisplayName) returns Hook.name(this, result) (nametag icon). */
        private static byte[] playerName(byte[] bytes, final String method, final String mdesc) {
            final String ret = mdesc.substring(mdesc.indexOf(')') + 2, mdesc.length() - 1);
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final boolean[] done = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!method.equals(mname) || !mdesc.equals(desc)) return mv;
                        done[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "name", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, ret);
                                }
                                super.visitInsn(op);
                            }
                        };
                    }
                }, 0);
                if (!done[0]) { System.out.println("[nether-agent] player name method not found"); return null; }
                System.out.println("[nether-agent] hooked player name");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] name hook failed: " + t);
                return null;
            }
        }

        /**
         * Gui.renderCrosshair returns at once while Hook.crosshair() says the Nether crosshair module draws its own. The game's HUD
         * method only: no renderer class is touched.
         */
        private static byte[] crosshair(byte[] bytes, final String method, final String mdesc) {
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final boolean[] done = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!method.equals(mname) || (mdesc != null && !mdesc.equals(desc)) || !desc.endsWith(")V") || (access & Opcodes.ACC_STATIC) != 0) return mv;
                        done[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                dev.nether.shaded.asm.Label go = new dev.nether.shaded.asm.Label();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "crosshair", "()Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                }, 0);
                if (!done[0]) { System.out.println("[nether-agent] crosshair method not found"); return null; }
                System.out.println("[nether-agent] hooked crosshair");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] crosshair hook failed: " + t);
                return null;
            }
        }

        /** Dev only (-Dnether.world): main(String[]) starts with args = Hook.args(args), which adds the quick-play argument. */
        private static byte[] devArgs(byte[] bytes) {
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!"main".equals(mname) || !"([Ljava/lang/String;)V".equals(desc) || (access & Opcodes.ACC_STATIC) == 0) return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "args", "([Ljava/lang/String;)[Ljava/lang/String;", false);
                                super.visitVarInsn(Opcodes.ASTORE, 0);
                            }
                        };
                    }
                }, 0);
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] dev world args failed: " + t);
                return null;
            }
        }

        // ------------------------------------------------------------ LWJGL2

        private static MethodVisitor displayMethod(MethodVisitor mv, String mname, String desc) {
            if (!"update".equals(mname)) return mv;
            final boolean outer;
            if ("()V".equals(desc)) outer = true;           // update() calls update(true): the inner call is skipped by Hook
            else if ("(Z)V".equals(desc)) outer = false;
            else return mv;
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitInsn(outer ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "lframe", "(Z)V", false);
                }
            };
        }

        private static MethodVisitor keyboardMethod(MethodVisitor mv, String n, String d) {
            if ("next".equals(n) && "()Z".equals(d)) return retFilter(mv, "lkbNext", "(Z)Z", false);
            if ("getEventKey".equals(n) && "()I".equals(d)) return retFilter(mv, "lkbKey", "(I)I", false);
            if ("getEventKeyState".equals(n) && "()Z".equals(d)) return retFilter(mv, "lkbState", "(Z)Z", false);
            if ("getEventCharacter".equals(n) && "()C".equals(d)) return retFilter(mv, "lkbChar", "(C)C", false);
            if ("isKeyDown".equals(n) && "(I)Z".equals(d)) return retFilter(mv, "lkbDown", "(ZI)Z", true);
            return mv;
        }

        private static MethodVisitor mouseMethod(MethodVisitor mv, String n, String d) {
            if ("next".equals(n) && "()Z".equals(d)) return retFilter(mv, "lmsNext", "(Z)Z", false);
            if ("getEventButton".equals(n) && "()I".equals(d)) return retFilter(mv, "lmsButton", "(I)I", false);
            if ("getEventButtonState".equals(n) && "()Z".equals(d)) return retFilter(mv, "lmsState", "(Z)Z", false);
            if ("getEventDWheel".equals(n) && "()I".equals(d)) return retFilter(mv, "lmsWheel", "(I)I", false);
            if ("getDX".equals(n) && "()I".equals(d)) return retFilter(mv, "lmsDX", "(I)I", false);
            if ("getDY".equals(n) && "()I".equals(d)) return retFilter(mv, "lmsDY", "(I)I", false);
            if ("isButtonDown".equals(n) && "(I)Z".equals(d)) return retFilter(mv, "lmsDown", "(ZI)Z", true);
            if ("setGrabbed".equals(n) && "(Z)V".equals(d)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ILOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "lmsGrab", "(Z)Z", false);
                        super.visitVarInsn(Opcodes.ISTORE, 0);
                    }
                };
            }
            return mv;
        }

        // ------------------------------------------------------------ SDL3 (26.3+)

        private static MethodVisitor sdlMethod(MethodVisitor mv, String mname, String desc) {
            if ("SDL_GL_SwapWindow".equals(mname) && "(J)Z".equals(desc)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.LLOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "frame", "(J)V", false);
                    }
                };
            }
            if ("SDL_PollEvent".equals(mname) && "(Lorg/lwjgl/sdl/SDL_Event;)Z".equals(desc)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.IRETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "sdlPoll", "(ZLjava/lang/Object;)Z", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
            if ("SDL_GetKeyboardState".equals(mname) && "()Ljava/nio/ByteBuffer;".equals(desc)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "sdlKeys", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/nio/ByteBuffer");
                        }
                        super.visitInsn(op);
                    }
                };
            }
            if ("SDL_SetWindowRelativeMouseMode".equals(mname) && "(JZ)Z".equals(desc)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "sdlRel", "(Z)Z", false);
                        super.visitVarInsn(Opcodes.ISTORE, 2);
                    }
                };
            }
            return mv;
        }

        // ------------------------------------------------------------ LWJGL3

        private static MethodVisitor glfwMethod(MethodVisitor mv, String mname, String desc) {
            int kind = -1;
            if ("glfwSetKeyCallback".equals(mname)) kind = 0;
            else if ("glfwSetMouseButtonCallback".equals(mname)) kind = 1;
            else if ("glfwSetCursorPosCallback".equals(mname)) kind = 2;
            else if ("glfwSetScrollCallback".equals(mname)) kind = 3;
            else if ("glfwSetCharCallback".equals(mname)) kind = 4;
            else if ("glfwSetCharModsCallback".equals(mname)) kind = 5; // 1.21.x registers this one
            if (kind >= 0 && desc.startsWith("(JL")) {
                final String cbType = Type.getArgumentTypes(desc)[1].getInternalName();
                final int k = kind;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.LLOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitIntInsn(Opcodes.BIPUSH, k);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "wrap", "(JLjava/lang/Object;I)Ljava/lang/Object;", false);
                        super.visitTypeInsn(Opcodes.CHECKCAST, cbType);
                        super.visitVarInsn(Opcodes.ASTORE, 2);
                    }
                };
            }
            if ("glfwSetInputMode".equals(mname) && "(JII)V".equals(desc)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.LLOAD, 0);
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitVarInsn(Opcodes.ILOAD, 3);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mode", "(JII)I", false);
                        super.visitVarInsn(Opcodes.ISTORE, 3);
                    }
                };
            }
            if (("glfwGetKey".equals(mname) || "glfwGetMouseButton".equals(mname)) && "(JI)I".equals(desc)) {
                final String h = "glfwGetKey".equals(mname) ? "getKey" : "getBtn";
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.IRETURN) {
                            super.visitVarInsn(Opcodes.LLOAD, 0);
                            super.visitVarInsn(Opcodes.ILOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, h, "(IJI)I", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
            if ("glfwSwapBuffers".equals(mname) && "(J)V".equals(desc)) {
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.LLOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "frame", "(J)V", false);
                    }
                };
            }
            return mv;
        }
    }
}
