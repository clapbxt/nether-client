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
        for (String f : new String[] {"NetherPixel-Bold", "NetherPixel-Regular"}) {
            try (InputStream in = Agent.class.getResourceAsStream("/fonts/" + f + ".ttf")) {
                if (in != null) Hook.res.put("fonts/" + f + ".ttf", readAll(in));
            } catch (Throwable t) {
                System.out.println("[nether-agent] font " + f + ": " + t);
            }
        }
        Hook.file = p -> { try { return read("/" + p); } catch (Throwable t) { return null; } };
        Hook.layerClass = Agent::layerClass;
        nameTargets();
        inst.addTransformer(new LwjglTransformer());
        System.out.println("[nether-agent] loaded (java " + System.getProperty("java.version") + ")");
    }

    /** Player class -> {name method, descriptor}: the nametag hook in every naming the game can run with. */
    private static final Map<String, String[]> NAME_HOOKS = new HashMap<>();

    /** Living entity renderer class -> {method, descriptor start}: its show-name test is filtered by Hook.label (own nametag). */
    private static final Map<String, String[]> LABEL_HOOKS = new HashMap<>();

    /**
     * Class -> {method, descriptor start, hook}: every float field of the class read inside that method goes through
     * Hook.<hook>(F)F. GameRenderer.getFov (its FOV multiplier: smooth zoom) and ItemInHandRenderer.renderHandsWithItems
     * (its equip heights: Zoom's hide-held-item ease). Vanilla code only, no Sodium/Iris class.
     */
    private static final Map<String, String[]> FLOAT_HOOKS = new HashMap<>();

    /** Item-in-hand renderer class -> {method, descriptor}: renderItem starts with `if (Hook.item(...)) return;` (shield cosmetics in first person). */
    private static final Map<String, String[]> ITEM_HOOKS = new HashMap<>();

    /** Chat HUD class -> chat component type: each of its add-message methods gets its message from Hook.chat first. */
    private static final Map<String, String> CHAT_HOOKS = new HashMap<>();

    private static void nameTargets() {
        CHAT_HOOKS.put("net/minecraft/class_338", "net/minecraft/class_2561"); // Fabric / Quilt
        CHAT_HOOKS.put("net/minecraft/client/gui/components/ChatComponent", "net/minecraft/network/chat/Component"); // Forge/NeoForge, 26.x
        CHAT_HOOKS.put("net/minecraft/client/gui/GuiNewChat", "net/minecraft/util/IChatComponent"); // Forge 1.8.9
        NAME_HOOKS.put("net/minecraft/class_1657", new String[] {"method_7299", "(Lnet/minecraft/class_5250;)Lnet/minecraft/class_5250;"}); // Fabric
        NAME_HOOKS.put("net/minecraft/world/entity/player/Player", new String[] {"decorateDisplayNameComponent", "(Lnet/minecraft/network/chat/MutableComponent;)Lnet/minecraft/network/chat/MutableComponent;"}); // Forge/NeoForge, 26.x
        NAME_HOOKS.put("net/minecraft/entity/player/EntityPlayer", new String[] {"func_145748_c_", "()Lnet/minecraft/util/IChatComponent;"}); // Forge 1.8.9 getDisplayName (SRG)
        LABEL_HOOKS.put("net/minecraft/class_922", new String[] {"method_4055", "(Lnet/minecraft/class_1309;"}); // Fabric hasLabel / shouldShowName
        LABEL_HOOKS.put("net/minecraft/client/renderer/entity/LivingEntityRenderer", new String[] {"shouldShowName", "(Lnet/minecraft/world/entity/LivingEntity;"}); // Forge/NeoForge, 26.x
        LABEL_HOOKS.put("net/minecraft/client/renderer/entity/RenderLivingBase", new String[] {"func_110813_b", "(Lnet/minecraft/entity/EntityLivingBase;"}); // Forge 1.12.2 canRenderName
        LABEL_HOOKS.put("net/minecraft/client/renderer/entity/RendererLivingEntity", new String[] {"func_110813_b", "(Lnet/minecraft/entity/EntityLivingBase;"}); // Forge 1.8.9 canRenderName
        FLOAT_HOOKS.put("net/minecraft/class_757", new String[] {"method_3196", "(Lnet/minecraft/class_4184;F", "fov"}); // Fabric getFov
        FLOAT_HOOKS.put("net/minecraft/client/renderer/GameRenderer", new String[] {"getFov", "(Lnet/minecraft/client/Camera;F", "fov"}); // Forge/NeoForge, 26.x
        FLOAT_HOOKS.put("net/minecraft/class_759", new String[] {"method_22976", "(F", "hand"}); // Fabric renderItem
        ITEM_HOOKS.put("net/minecraft/class_759", new String[] {"method_3233", "(Lnet/minecraft/class_1309;Lnet/minecraft/class_1799;Lnet/minecraft/class_811;Lnet/minecraft/class_4587;"}); // Fabric renderItem
        ITEM_HOOKS.put("net/minecraft/client/renderer/ItemInHandRenderer", new String[] {"renderItem", "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;"}); // Forge/NeoForge, 26.x
        FLOAT_HOOKS.put("net/minecraft/client/renderer/ItemInHandRenderer", new String[] {"renderHandsWithItems", "(F", "hand"}); // Forge/NeoForge, 26.x
        String[] a = String.valueOf(System.getProperty("sun.java.command")).split(" ");
        String v = null;
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals("--version")) v = a[i + 1];
        if (v == null) return;
        if (v.equals("1.8.9")) NAME_HOOKS.put("wn", new String[] {"f_", "()Leu;"}); // vanilla 1.8.9 EntityPlayer.getDisplayName
        try { // obfuscated vanilla with a name table (rows: intermediary, obfuscated, descriptor)
            String cls = null, m = null, d = null, chat = null, comp = null, lr = null, lm = null, ld = null, ir = null, im = null, id = null;
            for (String l : new String(read("/names/" + v + ".txt"), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String[] r = l.split("\t", -1);
                if (r.length < 3) continue;
                for (int i = 0; i < r.length; i++) r[i] = r[i].trim(); // a CRLF checkout must not leave \r in the descriptor
                if (r[0].equals("net.minecraft.class_1657")) cls = r[1];
                else if (r[0].equals("method_7299")) { m = r[1]; d = r[2]; }
                else if (r[0].equals("net.minecraft.class_338")) chat = r[1];
                else if (r[0].equals("net.minecraft.class_2561")) comp = r[1];
                else if (r[0].equals("net.minecraft.class_922")) lr = r[1];
                else if (r[0].equals("method_4055")) { lm = r[1]; ld = r[2]; }
                else if (r[0].equals("net.minecraft.class_759")) ir = r[1];
                else if (r[0].equals("method_3233")) { im = r[1]; id = r[2]; }
            }
            if (chat != null && comp != null) CHAT_HOOKS.put(chat.replace('.', '/'), comp.replace('.', '/'));
            if (cls != null && m != null && !d.isEmpty()) NAME_HOOKS.put(cls.replace('.', '/'), new String[] {m, d});
            if (lr != null && lm != null && ld.length() > 1) LABEL_HOOKS.put(lr.replace('.', '/'), new String[] {lm, ld.substring(0, ld.indexOf(';') + 1)});
            if (ir != null && im != null && id.length() > 1) ITEM_HOOKS.put(ir.replace('.', '/'), new String[] {im, id});
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

    private static int layers;

    /**
     * A subclass of the game's render layer (`sup`, abstract, one abstract method: RenderLayer.submit/render), defined in
     * `loader`: its constructor passes the renderer to super, its draw method boxes its arguments and calls
     * Hook.layer(this, args). Nothing of the game is patched; the overlay appends an instance to the player renderers.
     */
    private static synchronized Class<?> layerClass(Class<?> sup, ClassLoader loader) {
        try {
            java.lang.reflect.Method draw = null;
            for (java.lang.reflect.Method m : sup.getDeclaredMethods())
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && m.getReturnType() == void.class && m.getParameterCount() >= 4) {
                    if (draw != null) throw new IllegalStateException("two abstract draw methods on " + sup.getName());
                    draw = m;
                }
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> c : sup.getDeclaredConstructors())
                if (c.getParameterCount() == 1 && !java.lang.reflect.Modifier.isPrivate(c.getModifiers())) ctor = c;
            if (draw == null || ctor == null) throw new IllegalStateException("no draw method / constructor on " + sup.getName());
            String name = "dev/nether/gen/CosmeticLayer" + (layers++), superName = Type.getInternalName(sup);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
            String cd = Type.getConstructorDescriptor(ctor);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", cd, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", cd, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            Type[] args = Type.getArgumentTypes(draw);
            int acc = (draw.getModifiers() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 ? draw.getModifiers() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) : Opcodes.ACC_PUBLIC;
            mv = cw.visitMethod(acc, draw.getName(), Type.getMethodDescriptor(draw), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitIntInsn(Opcodes.BIPUSH, args.length);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            int slot = 1;
            for (int i = 0; i < args.length; i++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, i);
                Type t = args[i];
                mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                switch (t.getSort()) {
                    case Type.INT: mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false); break;
                    case Type.FLOAT: mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false); break;
                    case Type.DOUBLE: mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false); break;
                    case Type.LONG: mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false); break;
                    case Type.BOOLEAN: mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false); break;
                    case Type.BYTE: case Type.SHORT: case Type.CHAR: mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false); break;
                    default: break;
                }
                mv.visitInsn(Opcodes.AASTORE);
                slot += t.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "layer", "(Ljava/lang/Object;[Ljava/lang/Object;)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            byte[] b = cw.toByteArray();
            Method dc = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            dc.setAccessible(true);
            Class<?> c = (Class<?>) dc.invoke(loader, name.replace('/', '.'), b, 0, b.length);
            System.out.println("[nether-agent] cosmetics layer class " + c.getName() + " (" + sup.getName() + "." + draw.getName() + ")");
            return c;
        } catch (Throwable t) {
            System.out.println("[nether-agent] cosmetics layer class failed: " + t);
            return null;
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
            String[] lh = name == null ? null : LABEL_HOOKS.get(name);
            if (lh != null) return label(bytes, lh[0], lh[1]);
            String[] fh = name == null ? null : FLOAT_HOOKS.get(name);
            String[] ih = name == null ? null : ITEM_HOOKS.get(name);
            if (fh != null && ih != null) { byte[] b = floatReads(bytes, name, fh[0], fh[1], fh[2]); byte[] c = itemHook(b != null ? b : bytes, ih[0], ih[1]); return c != null ? c : b; }
            if (fh != null) return floatReads(bytes, name, fh[0], fh[1], fh[2]);
            if (ih != null) return itemHook(bytes, ih[0], ih[1]);
            String ct = name == null ? null : CHAT_HOOKS.get(name);
            if (ct != null) return chat(bytes, ct);
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

        /**
         * LivingEntityRenderer.shouldShowName(entity[, distance]) returns Hook.label(result, entity): the game draws your own
         * nametag in third person (as third-person-nametags does), so it moves with the rendered player. Only the result.
         */
        private static byte[] label(byte[] bytes, final String method, final String descStart) {
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final boolean[] done = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!method.equals(mname) || !desc.startsWith(descStart) || !desc.endsWith(")Z") || (access & Opcodes.ACC_STATIC) != 0) return mv;
                        done[0] = true;
                        // the entity is read at the start only: at a return its slot can be dead in the stack map (VerifyError)
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "labelArg", "(Ljava/lang/Object;)V", false);
                            }

                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.IRETURN) super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "label", "(Z)Z", false);
                                super.visitInsn(op);
                            }
                        };
                    }
                }, 0);
                if (!done[0]) return null;
                Hook.labelHooked = true;
                System.out.println("[nether-agent] hooked nametag visibility");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] nametag visibility hook failed: " + t);
                return null;
            }
        }

        /**
         * ItemInHandRenderer.renderItem(entity, stack, context, poseStack, collector, light) starts with
         * `if (Hook.item(...)) return;`: a held vanilla shield is drawn as the player's shield cosmetic in first person.
         */
        private static byte[] itemHook(byte[] bytes, final String method, final String descStart) {
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final boolean[] done = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!method.equals(mname) || !desc.startsWith(descStart) || !desc.endsWith("I)V") || (access & Opcodes.ACC_STATIC) != 0) return mv;
                        Type[] a = Type.getArgumentTypes(desc);
                        if (a.length != 6) return mv;
                        done[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                for (int i = 1; i <= 5; i++) super.visitVarInsn(Opcodes.ALOAD, i);
                                super.visitVarInsn(Opcodes.ILOAD, 6);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "item", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Z", false);
                                dev.nether.shaded.asm.Label go = new dev.nether.shaded.asm.Label();
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                }, 0);
                if (!done[0]) return null;
                System.out.println("[nether-agent] hooked held items (shield cosmetics)");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] held item hook failed: " + t);
                return null;
            }
        }

        /** In owner.method(descStart...): each read of a float field of owner is passed through Hook.<hook>(F)F. */
        private static byte[] floatReads(byte[] bytes, final String owner, final String method, final String descStart, final String hook) {
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final int[] n = {0};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!method.equals(mname) || !desc.startsWith(descStart) || (access & Opcodes.ACC_STATIC) != 0) return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitFieldInsn(int op, String fo, String fn, String fd) {
                                super.visitFieldInsn(op, fo, fn, fd);
                                if (op == Opcodes.GETFIELD && owner.equals(fo) && "F".equals(fd)) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, hook, "(F)F", false);
                                    n[0]++;
                                }
                            }
                        };
                    }
                }, 0);
                if (n[0] == 0) return null;
                if (hook.equals("fov")) Hook.fovHooked = true; else Hook.handHooked = true;
                System.out.println("[nether-agent] hooked " + hook + " (" + n[0] + " reads)");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] " + hook + " hook failed: " + t);
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
         * Chat HUD: every instance method returning void whose first parameter is the chat component starts with
         * arg1 = Hook.chat(arg1). Several of them call each other; the overlay's change is idempotent. HUD class only.
         */
        private static byte[] chat(byte[] bytes, final String comp) {
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final int[] n = {0};
                final String first = "(L" + comp + ";";
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0 || !desc.startsWith(first) || !desc.endsWith(")V") || mname.startsWith("<")) return mv;
                        n[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "chat", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, comp);
                                super.visitVarInsn(Opcodes.ASTORE, 1);
                            }
                        };
                    }
                }, 0);
                if (n[0] == 0) { System.out.println("[nether-agent] chat methods not found"); return null; }
                System.out.println("[nether-agent] hooked chat (" + n[0] + ")");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] chat hook failed: " + t);
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
