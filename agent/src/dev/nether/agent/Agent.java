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
    /** dev (benchmarks): -Dnether.bare=1 keeps only the LWJGL hooks, which pass everything through; no overlay, no game-class hook. */
    private static final boolean BARE = "1".equals(System.getProperty("nether.bare"));

    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        openJavaLang();
        Hook.open = Agent::openModule;
        for (String f : new String[] {"NetherPixel-Bold", "NetherPixel-Regular", "Barlow-SemiBold", "Barlow-Medium"}) {
            try (InputStream in = Agent.class.getResourceAsStream("/fonts/" + f + ".ttf")) {
                if (in != null) Hook.res.put("fonts/" + f + ".ttf", readAll(in));
            } catch (Throwable t) {
                System.out.println("[nether-agent] font " + f + ": " + t);
            }
        }
        Hook.file = p -> { try { return read("/" + p); } catch (Throwable t) { return null; } };
        Hook.layerClass = Agent::layerClass;
        nameTargets();
        LwjglTransformer tf = new LwjglTransformer();
        inst.addTransformer(tf);
        // GL shadow (see Hook): LWJGL3's GL natives are renamed with this prefix and wrapped
        if (!BARE && !"0".equals(System.getProperty("nether.glShadow")) && inst.isNativeMethodPrefixSupported()) {
            try { inst.setNativeMethodPrefix(tf, GL_PREFIX); Hook.glShadowed = true; } catch (Throwable t) { System.out.println("[nether-agent] GL shadow off: " + t); }
        }
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

    /**
     * Freelook (Tier B): class -> {method, descriptor, kind}. "turn" = Entity.turn(DD)V gets its deltas from Hook.look/lookX
     * (mouse turns go to the free camera), "cam" = Camera.setRotation(FF)V gets Hook.camYaw/camPitch. Vanilla classes only.
     */
    private static final Map<String, String[]> LOOK_HOOKS = new HashMap<>();

    /**
     * View editors (Tier B, OverlayTweaks / MountOpacity behaviour): class -> {kind, methods ("a|b", "" = any), exact
     * descriptor ("" = any), helper names, helper owner}. "shield" = arm-with-item: after its first PoseStack push
     * (helpers = push names), Hook.shield(pose, item). "fire" = fire overlay: Hook.fireIn/fireOut around the method, its
     * alpha constants through Hook.fireA/fireArgb (helpers = extra alpha-only methods). "tag" = EntityRenderer.
     * createRenderState(entity, partial) result to Hook.mountTag. "mount" = LivingEntityRenderer: getRenderType's
     * translucent flag and the model colour (ARGB.multiply). "armor" = EquipmentLayerRenderer: layer colour and the
     * armour render type (helpers = its names on the helper owner). Vanilla classes only, never a renderer mod's.
     */
    private static final Map<String, String[]> VIEW_HOOKS = new HashMap<>();
    /** PoseStack in every naming (the fire method's pose argument is found by type: 1.21.1 passes it second). */
    private static final Set<String> POSES = new HashSet<>(java.util.Arrays.asList("net/minecraft/class_4587", "com/mojang/blaze3d/vertex/PoseStack"));

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
        LABEL_HOOKS.put("net/minecraft/client/renderer/entity/RendererLivingEntity", new String[] {"func_177070_b", "(Lnet/minecraft/entity/EntityLivingBase;)Z"}); // Forge 1.8.9 canRenderName (func_110813_b is 1.12's name)
        // Freelook on 1.8.9 / 1.12.2 (Forge SRG; same names in both): mouse deltas of Entity.setAngles/turn, camera rotation reads
        LOOK_HOOKS.put("net/minecraft/entity/Entity", new String[] {"func_70082_c", "(FF)V", "lturn"});
        String lyaw = "field_70177_z|field_70126_B", lpitch = "field_70125_A|field_70127_C";
        LOOK_HOOKS.put("net/minecraft/client/renderer/EntityRenderer", new String[] {"func_78467_g", "(F)V", "lcam", "net/minecraft/entity/Entity", lyaw, lpitch}); // orientCamera
        LOOK_HOOKS.put("net/minecraft/client/renderer/entity/RenderManager", new String[] {"func_180597_a", "", "lcam", "net/minecraft/entity/Entity", lyaw, lpitch}); // cacheActiveRenderInfo (nametags face the camera)
        FLOAT_HOOKS.put("net/minecraft/class_757", new String[] {"method_3196", "(Lnet/minecraft/class_4184;F", "fov"}); // Fabric getFov
        FLOAT_HOOKS.put("net/minecraft/client/renderer/GameRenderer", new String[] {"getFov", "(Lnet/minecraft/client/Camera;F", "fov"}); // Forge/NeoForge, 26.x
        FLOAT_HOOKS.put("net/minecraft/class_759", new String[] {"method_22976", "(F", "hand"}); // Fabric renderItem
        FLOAT_HOOKS.put("net/minecraft/client/renderer/ItemInHandRenderer", new String[] {"renderHandsWithItems", "(F", "hand"}); // Forge/NeoForge, 26.1
        FLOAT_HOOKS.put("net/minecraft/client/Camera", new String[] {"calculateFov", "(F", "fov"}); // 26.x: the FOV modifier moved to Camera
        FLOAT_HOOKS.put("net/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer", new String[] {"submitHandsWithItems", "(F", "hand", // 26.3: heights on a render state
            "net/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState", "oldMainHandHeight|mainHandHeight|oldOffHandHeight|offHandHeight"});
        LOOK_HOOKS.put("net/minecraft/class_1297", new String[] {"method_5872", "(DD)V", "turn"}); // Fabric Entity.changeLookDirection
        LOOK_HOOKS.put("net/minecraft/world/entity/Entity", new String[] {"turn", "(DD)V", "turn"}); // Forge/NeoForge, 26.x
        LOOK_HOOKS.put("net/minecraft/class_4184", new String[] {"method_19325", "(FF)V", "cam"}); // Fabric Camera.setRotation
        LOOK_HOOKS.put("net/minecraft/client/Camera", new String[] {"setRotation", "(FF)V", "cam"}); // Forge/NeoForge, 26.x
        VIEW_HOOKS.put("net/minecraft/class_759", new String[] {"shield", "method_3228", "", "method_22903", ""}); // Fabric renderArmWithItem
        VIEW_HOOKS.put("net/minecraft/client/renderer/ItemInHandRenderer", new String[] {"shield", "renderArmWithItem", "", "pushPose", ""});
        VIEW_HOOKS.put("net/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer", new String[] {"shield", "submitArmWithItem", "", "pushPose", ""}); // 26.3+
        VIEW_HOOKS.put("net/minecraft/class_4603", new String[] {"fire", "method_23070", "", "", ""}); // Fabric ScreenEffectRenderer.renderFire
        VIEW_HOOKS.put("net/minecraft/client/renderer/ScreenEffectRenderer", new String[] {"fire", "renderFire|submitFire", "", "buildFireQuad", ""});
        VIEW_HOOKS.put("net/minecraft/class_897", new String[] {"tag", "method_62425", "", "", ""}); // Fabric EntityRenderer.createRenderState
        VIEW_HOOKS.put("net/minecraft/client/renderer/entity/EntityRenderer", new String[] {"tag", "createRenderState", "", "", ""});
        VIEW_HOOKS.put("net/minecraft/class_922", new String[] {"mount", "", "", "", ""}); // Fabric LivingEntityRenderer
        VIEW_HOOKS.put("net/minecraft/client/renderer/entity/LivingEntityRenderer", new String[] {"mount", "", "", "", ""});
        VIEW_HOOKS.put("net/minecraft/class_10197", new String[] {"armor", "", "", "method_75966|method_25448", "net/minecraft/class_12249|net/minecraft/class_1921"}); // Fabric EquipmentLayerRenderer (1.21.4+)
        VIEW_HOOKS.put("net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer", new String[] {"armor", "", "", "armorCutoutNoCull", "net/minecraft/client/renderer/rendertype/RenderTypes|net/minecraft/client/renderer/RenderType"});
        // 1.8.9 (Forge: MCP class names + SRG members): fire overlay constants, mount tint around RendererLivingEntity.doRender
        // fire: renderFireInFirstPerson ((F)V in 1.8.9, ()V in 1.12.2); helper = 1.12.2 renderItemInFirstPerson (shield lift after its pushMatrix)
        VIEW_HOOKS.put("net/minecraft/client/renderer/ItemRenderer", new String[] {"lfire", "func_78442_d", "", "func_187457_a", "func_179094_E"});
        VIEW_HOOKS.put("net/minecraft/client/renderer/entity/RendererLivingEntity", new String[] {"lmount", "func_76986_a", "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", "", ""}); // 1.8.9
        VIEW_HOOKS.put("net/minecraft/client/renderer/entity/RenderLivingBase", new String[] {"lmount", "func_76986_a", "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", "", ""}); // 1.12.2
        String[] a = String.valueOf(System.getProperty("sun.java.command")).split(" ");
        String v = null;
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals("--version")) v = a[i + 1];
        if (v == null) return;
        // Legacy Fabric (1.8.9 / 1.12.2) runs the game on Legacy intermediary, which reuses modern Fabric's class_N numbers
        // for unrelated classes: drop the modern Fabric rows there.
        if ((v.startsWith("1.8") || v.startsWith("1.12")) && Tiny.on()) for (Map<String, ?> t : java.util.Arrays.<Map<String, ?>>asList(NAME_HOOKS, LABEL_HOOKS, FLOAT_HOOKS, LOOK_HOOKS, VIEW_HOOKS, CHAT_HOOKS))
            t.keySet().removeIf(k -> k.startsWith("net/minecraft/class_"));
        if (v.equals("1.8.9")) {
            // vanilla notch names; on Legacy Fabric put189 maps them through Tiny.
            put189(NAME_HOOKS, "wn", 0, "f_", "()Leu;"); // EntityPlayer.getDisplayName
            put189(VIEW_HOOKS, "bfn", 1, "lfire", "f", "(F)V", "", ""); // ItemRenderer.renderFireInFirstPerson
            put189(VIEW_HOOKS, "bjl", 1, "lmount", "a", "(Lpr;DDDFF)V", "", ""); // RendererLivingEntity.doRender
            put189(LABEL_HOOKS, "bjl", 0, "a", "(Lpr;)Z"); // RendererLivingEntity.canRenderName
            put189(LOOK_HOOKS, "pk", 0, "c", "(FF)V", "lturn"); // Entity.setAngles
            put189(LOOK_HOOKS, "bfk", 0, "f", "(F)V", "lcam", "pk", "y|A", "z|B"); // EntityRenderer.orientCamera
            put189(LOOK_HOOKS, "biu", 0, "a", "(Ladm;Lavn;Lpk;Lpk;Lavh;F)V", "lcam", "pk", "y|A", "z|B"); // RenderManager.cacheActiveRenderInfo
        }
        if (v.startsWith("1.14")) { // 1.14: no PoseStack yet, GL-state pipeline like 1.12 (Fabric names; tables below for vanilla / Forge)
            VIEW_HOOKS.put("net/minecraft/class_759", new String[] {"lfire", "method_3236", "()V", "method_3228", "pushMatrix"}); // renderFire + renderArmWithItem
            VIEW_HOOKS.put("net/minecraft/class_922", new String[] {"lmount", "method_4054", "(Lnet/minecraft/class_1309;DDDFF)V", "", ""}); // LivingEntityRenderer.render
        }
        v = v.replaceFirst("^(\\d+\\.\\d+(?:\\.\\d+)?).*$", "$1"); // a loader suffix on the version id
        // obfuscated vanilla (<v>.txt) and Forge/NeoForge SRG members (<v>-srg.txt, 1.14-1.20.4): rows intermediary, runtime name, descriptor
        for (String suffix : new String[] {"", "-srg", "-moj"}) { // -moj: Mojang names of 1.20.5-1.21.10 (Forge/NeoForge)
            try {
                Map<String, String[]> rows = new HashMap<>();
                for (String l : new String(read("/names/" + v + suffix + ".txt"), StandardCharsets.UTF_8).split("\r?\n")) {
                    String[] r = l.split("\t", -1);
                    if (r.length < 3) continue;
                    for (int i = 0; i < r.length; i++) r[i] = r[i].trim(); // a CRLF checkout must not leave \r in the descriptor
                    rows.put(r[0], r);
                }
                fromTable(rows);
            } catch (Throwable t) { } // no table for this version
        }
    }

    /** Every name-keyed hook from one name table (the runtime names of one version). Rows missing a name skip that hook. */
    private static void fromTable(Map<String, String[]> rows) {
        String[] cls = rows.get("net.minecraft.class_1657"), m = rows.get("method_7299"), chat = rows.get("net.minecraft.class_338"), comp = rows.get("net.minecraft.class_2561"),
            lr = rows.get("net.minecraft.class_922"), lm = rows.get("method_4055"), ent = rows.get("net.minecraft.class_1297"), turn = rows.get("method_5872"),
            cam = rows.get("net.minecraft.class_4184"), rot = rows.get("method_19325"), gr = rows.get("net.minecraft.class_757"), fov = rows.get("method_3196"),
            ih = rows.get("net.minecraft.class_759"), hand = rows.get("method_22976");
        if (chat != null && comp != null) CHAT_HOOKS.put(slash(chat), slash(comp));
        if (cls != null && m != null && !m[2].isEmpty()) NAME_HOOKS.put(slash(cls), new String[] {m[1], m[2]});
        if (lr != null && lm != null && lm[2].length() > 1) LABEL_HOOKS.put(slash(lr), new String[] {lm[1], lm[2].substring(0, lm[2].indexOf(';') + 1)});
        if (ent != null && turn != null) LOOK_HOOKS.put(slash(ent), new String[] {turn[1], "(DD)V", "turn"});
        if (cam != null && rot != null) LOOK_HOOKS.put(slash(cam), new String[] {rot[1], "(FF)V", "cam"});
        if (gr != null && fov != null && !fov[2].isEmpty()) FLOAT_HOOKS.put(slash(gr), new String[] {fov[1], fov[2], "fov"});
        if (ih != null && hand != null && !hand[2].isEmpty()) FLOAT_HOOKS.put(slash(ih), new String[] {hand[1], hand[2], "hand"});
        viewRow(rows, "shield", "net.minecraft.class_759", "method_3228", "method_22903", null);
        viewRow(rows, "fire", "net.minecraft.class_4603", "method_23070", null, null);
        viewRow(rows, "tag", "net.minecraft.class_897", "method_62425", null, null);
        viewRow(rows, "mount", "net.minecraft.class_922", null, null, null);
        if (rows.containsKey("net.minecraft.class_12249")) viewRow(rows, "armor", "net.minecraft.class_10197", null, "method_75966", "net.minecraft.class_12249");
        else viewRow(rows, "armor", "net.minecraft.class_10197", null, "method_25448", "net.minecraft.class_1921"); // 1.21.4-1.21.10: RenderType
        String[] ps = rows.get("net.minecraft.class_4587");
        if (ps != null) POSES.add(slash(ps));
        String[] ih0 = rows.get("net.minecraft.class_759"), fire14 = rows.get("method_3236"), arm14 = rows.get("method_3228"), lr0 = rows.get("net.minecraft.class_922"), rend14 = rows.get("method_4054");
        if (ps == null && ih0 != null && fire14 != null && arm14 != null) VIEW_HOOKS.put(slash(ih0), new String[] {"lfire", fire14[1], fire14[2], arm14[1], "pushMatrix"}); // 1.14
        if (ps == null && lr0 != null && rend14 != null) VIEW_HOOKS.put(slash(lr0), new String[] {"lmount", rend14[1], rend14[2], "", ""});
    }

    /** A 1.8.9 hook row in notch names (method row[m], descriptor row[m + 1]; lcam rows: entity class + yaw/pitch fields at 3-5), mapped to Legacy intermediary on Legacy Fabric. */
    private static void put189(Map<String, String[]> table, String cls, int m, String... row) {
        if (Tiny.on()) {
            row[m] = Tiny.method(cls, row[m], row[m + 1]);
            row[m + 1] = Tiny.desc(row[m + 1]);
            if (row.length == 6 && row[2].equals("lcam")) {
                for (int i = 4; i < 6; i++) {
                    String[] f = row[i].split("\\|");
                    for (int k = 0; k < f.length; k++) f[k] = Tiny.field(row[3], f[k]);
                    row[i] = String.join("|", f);
                }
                row[3] = Tiny.cls(row[3]);
            }
            cls = Tiny.cls(cls);
        }
        table.put(cls, row);
    }

    private static String slash(String[] row) { return row[1].replace('.', '/'); }

    /** One obfuscated VIEW_HOOKS row from the version's name table (skipped when a name is missing). */
    private static void viewRow(Map<String, String[]> rows, String kind, String cls, String method, String helper, String owner) {
        String[] c = rows.get(cls), m = method == null ? null : rows.get(method), h = helper == null ? null : rows.get(helper), o = owner == null ? null : rows.get(owner);
        if (c == null || (method != null && m == null) || (helper != null && h == null) || (owner != null && o == null)) return;
        VIEW_HOOKS.put(c[1].replace('.', '/'), new String[] {kind, m == null ? "" : m[1], m == null ? "" : m[2], h == null ? "" : h[1], o == null ? "" : o[1].replace('.', '/')});
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

    /** LWJGL GL classes whose state-setting entry points feed Hook's GL shadow (LWJGL3 core *C classes, LWJGL 3.1 / LWJGL2 GLxx, extensions). */
    private static final Set<String> GL_CLASSES = new HashSet<>(java.util.Arrays.asList("GL11", "GL11C", "GL13", "GL13C", "GL14", "GL14C",
        "GL15", "GL15C", "GL20", "GL20C", "GL30", "GL30C", "GL33", "GL33C", "ARBShaderObjects", "ARBBufferObject", "ARBVertexBufferObject",
        "EXTFramebufferObject", "ARBFramebufferObject", "ARBMultitexture", "ARBVertexArrayObject", "ARBSamplerObjects"));
    /** name + descriptor -> Hook method (same descriptor) */
    private static final Map<String, String> GL_HOOKS = new HashMap<>();
    static {
        for (String s : new String[] {"glEnable(I)V", "glDisable(I)V", "glViewport(IIII)V", "glScissor(IIII)V", "glBindTexture(II)V", "glActiveTexture(I)V",
            "glActiveTextureARB(I)V", "glBindSampler(II)V", "glDepthMask(Z)V", "glColorMask(ZZZZ)V", "glBlendFunc(II)V", "glBlendFuncSeparate(IIII)V",
            "glBlendEquation(I)V", "glBlendEquationSeparate(II)V", "glPolygonMode(II)V", "glPixelStorei(II)V", "glBindBuffer(II)V", "glBindBufferARB(II)V",
            "glUseProgram(I)V", "glUseProgramObjectARB(I)V", "glBindVertexArray(I)V", "glBindFramebuffer(II)V", "glBindFramebufferEXT(II)V",
            "glDeleteTextures(I)V", "glDeleteBuffers(I)V", "glDeleteVertexArrays(I)V", "glDeleteFramebuffers(I)V", "glDeleteFramebuffersEXT(I)V", "glDeleteSamplers(I)V"})
            GL_HOOKS.put(s, s.substring(0, s.indexOf('(')));
    }
    static final String GL_PREFIX = "nether$";

    /**
     * GL shadow: every listed static entry point first reports its arguments to Hook. A native one (LWJGL3) is renamed
     * with GL_PREFIX (the JVM still binds it to the original JNI symbol, Instrumentation.setNativeMethodPrefix) and a Java
     * method of the original name reports, then calls it; a Java one (LWJGL2, delete helpers) gets the report at its start.
     */
    private static byte[] glShadow(final String owner, byte[] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            final List<String[]> wrap = new ArrayList<>();
            final boolean[] hit = {false};
            final boolean prefix = Hook.glShadowed; // natives are only renamed when the JVM accepted the prefix
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                    final String hook = (access & Opcodes.ACC_STATIC) != 0 ? GL_HOOKS.get(mname + desc) : null;
                    if (hook == null) return super.visitMethod(access, mname, desc, sig, ex);
                    hit[0] = true;
                    if ((access & Opcodes.ACC_NATIVE) != 0) {
                        if (!prefix) return super.visitMethod(access, mname, desc, sig, ex);
                        wrap.add(new String[] {mname, desc, hook, Integer.toString(access)});
                        return super.visitMethod(access, GL_PREFIX + mname, desc, sig, ex);
                    }
                    final String d = desc;
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, mname, desc, sig, ex)) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            loadArgs(this, d);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, hook, d, false);
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    for (String[] w : wrap) {
                        MethodVisitor mv = super.visitMethod(Integer.parseInt(w[3]) & ~Opcodes.ACC_NATIVE, w[0], w[1], null, null);
                        mv.visitCode();
                        loadArgs(mv, w[1]);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, w[2], w[1], false);
                        loadArgs(mv, w[1]);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, GL_PREFIX + w[0], w[1], false);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(0, 0);
                        mv.visitEnd();
                    }
                    super.visitEnd();
                }
            }, 0);
            return hit[0] ? cw.toByteArray() : null;
        } catch (Throwable t) {
            System.out.println("[nether-agent] GL shadow failed on " + owner + ": " + t);
            Hook.glShadowed = false;
            return null;
        }
    }

    /**
     * GlStateManager.clearGlErrors()V starts with "if (Hook.glErrClear()) return;" and _getError()I with
     * "if (Hook.glErrRead()) return 0;": vanilla's allocate-then-check pairs no longer stall on glGetError (Performance).
     */
    private static byte[] glErrors(byte[] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            final int[] n = {0};
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                    final boolean clear = "clearGlErrors".equals(mname) && "()V".equals(desc), read = "_getError".equals(mname) && "()I".equals(desc);
                    if ((access & Opcodes.ACC_STATIC) == 0 || !clear && !read) return mv;
                    n[0]++;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            dev.nether.shaded.asm.Label go = new dev.nether.shaded.asm.Label();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, clear ? "glErrClear" : "glErrRead", "()Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, go);
                            if (read) { super.visitInsn(Opcodes.ICONST_0); super.visitInsn(Opcodes.IRETURN); } else super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(go);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null); // both take no arguments: the entry frame
                            super.visitInsn(Opcodes.NOP); // never two frames at one offset if the original body starts with a loop target
                        }
                    };
                }
            }, 0);
            if (n[0] < 2) return null; // older GlStateManager without the pair: leave it alone
            System.out.println("[nether-agent] hooked GPU error checks");
            return cw.toByteArray();
        } catch (Throwable t) {
            System.out.println("[nether-agent] GPU error checks hook failed: " + t);
            return null;
        }
    }

    private static void loadArgs(MethodVisitor mv, String desc) {
        int slot = 0;
        for (Type t : Type.getArgumentTypes(desc)) { mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot); slot += t.getSize(); }
    }

    static final class LwjglTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
            if ("net/minecraft/client/main/Main".equals(name) && System.getProperty("nether.world") != null) return devArgs(bytes);
            if (name == null) return null;
            String[] nh = BARE ? null : NAME_HOOKS.get(name), lh = LABEL_HOOKS.get(name), fh = FLOAT_HOOKS.get(name), vh = VIEW_HOOKS.get(name), lk = LOOK_HOOKS.get(name);
            String ct = CHAT_HOOKS.get(name);
            if (!BARE && (nh != null || lh != null || fh != null || vh != null || lk != null || ct != null)) {
                // one class can carry several hooks (RendererLivingEntity: nametag + ride fade): each patch works on the last one's result
                byte[] b = bytes, r;
                if (nh != null && (r = playerName(b, nh[0], nh[1])) != null) b = r;
                if (lh != null && (r = label(b, lh[0], lh[1])) != null) b = r;
                if (fh != null && (r = floatReads(b, fh.length > 3 ? fh[3] : name, fh[0], fh[1], fh[2], fh.length > 4 ? fh[4] : null)) != null) b = r;
                if (vh != null && (r = view(b, name, vh)) != null) b = r;
                if (ct != null && (r = chat(b, ct)) != null) b = r;
                if (lk != null && (r = look(b, lk)) != null) b = r;
                return b == bytes ? null : b;
            }
            if (!BARE && ("com/mojang/blaze3d/opengl/GlStateManager".equals(name) || "com/mojang/blaze3d/platform/GlStateManager".equals(name))) return glErrors(bytes);
            if (!name.startsWith("org/lwjgl/")) return null;
            if (!BARE && name.startsWith("org/lwjgl/opengl/") && GL_CLASSES.contains(name.substring(17))) return glShadow(name, bytes);
            final boolean glfw = "org/lwjgl/glfw/GLFW".equals(name);
            final boolean display = "org/lwjgl/opengl/Display".equals(name);
            final boolean kb = "org/lwjgl/input/Keyboard".equals(name), ms = "org/lwjgl/input/Mouse".equals(name);
            final boolean sdl = name.startsWith("org/lwjgl/sdl/SDL") && (name.endsWith("/SDLVideo") || name.endsWith("/SDLEvents") || name.endsWith("/SDLKeyboard") || name.endsWith("/SDLMouse"));
            if (!glfw && !display && !kb && !ms && !sdl) return null;
            try {
                if (!BARE) installOverlay(loader, glfw ? 0 : sdl ? 2 : 1);
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

        /** In owner.method(descStart...): each read of a float field of owner is passed through Hook.<hook>(F)F. */
        private static byte[] floatReads(byte[] bytes, final String owner, final String method, final String descStart, final String hook, String only) {
            final Set<String> fields = only == null ? null : new HashSet<>(java.util.Arrays.asList(only.split("\\|")));
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
                                if (op == Opcodes.GETFIELD && owner.equals(fo) && "F".equals(fd) && (fields == null || fields.contains(fn))) {
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

        /** View editors (see VIEW_HOOKS). Straight-line inserts only, so no stack map frame changes. */
        private static byte[] view(byte[] bytes, final String owner, final String[] spec) {
            final String kind = spec[0];
            final Set<String> names = new HashSet<>(), helpers = new HashSet<>();
            if (!spec[1].isEmpty()) Collections.addAll(names, spec[1].split("\\|"));
            if (!spec[3].isEmpty()) Collections.addAll(helpers, spec[3].split("\\|"));
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final int[] n = {0}, shields = {0};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if ((access & Opcodes.ACC_ABSTRACT) != 0) return mv;
                        final boolean stat = (access & Opcodes.ACC_STATIC) != 0;
                        final boolean named = names.contains(mname) && (spec[2].isEmpty() || spec[2].equals(desc));
                        final Type[] at = Type.getArgumentTypes(desc);
                        if (kind.equals("shield")) {
                            if (!named || stat || at.length < 5) return mv;
                            final Type poseT = at[at.length - 3];
                            if (poseT.getSort() != Type.OBJECT || at[at.length - 5].getSort() != Type.OBJECT) return mv;
                            final int poseSlot = slot(at, at.length - 3), itemSlot = slot(at, at.length - 5);
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                boolean done;
                                @Override
                                public void visitMethodInsn(int op, String o, String nm, String d, boolean itf) {
                                    super.visitMethodInsn(op, o, nm, d, itf);
                                    if (done || op != Opcodes.INVOKEVIRTUAL || !"()V".equals(d) || !helpers.contains(nm) || !o.equals(poseT.getInternalName())) return;
                                    done = true;
                                    n[0]++;
                                    super.visitVarInsn(Opcodes.ALOAD, poseSlot);
                                    super.visitVarInsn(Opcodes.ALOAD, itemSlot);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "shield", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                            };
                        }
                        if (kind.equals("fire")) {
                            int pi = -1;
                            for (int k = 0; k < at.length && pi < 0; k++) if (at[k].getSort() == Type.OBJECT && POSES.contains(at[k].getInternalName())) pi = k;
                            final boolean wrap = named && stat && pi >= 0;
                            final int poseSlot = wrap ? slot(at, pi) - 1 : 0; // static: no this
                            if (!named && !helpers.contains(mname)) return mv;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    if (!wrap) return;
                                    n[0]++;
                                    super.visitVarInsn(Opcodes.ALOAD, poseSlot);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fireIn", "(Ljava/lang/Object;)V", false);
                                }
                                @Override
                                public void visitInsn(int op) {
                                    // the pose arg can be dead in a return's stack map frame (VerifyError): Hook kept it at the start
                                    if (wrap && op == Opcodes.RETURN) super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fireOut", "()V", false);
                                    super.visitInsn(op);
                                }
                                @Override
                                public void visitLdcInsn(Object v) {
                                    super.visitLdcInsn(v);
                                    if (v instanceof Float && (Float) v == 0.9f) { n[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fireA", "(F)F", false); }
                                    else if (v instanceof Integer && (Integer) v == 0xE5FFFFFF) { n[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fireArgb", "(I)I", false); }
                                }
                            };
                        }
                        if (kind.equals("tag")) {
                            if (!named || stat || at.length != 2 || at[0].getSort() != Type.OBJECT || at[1] != Type.FLOAT_TYPE || Type.getReturnType(desc).getSort() != Type.OBJECT) return mv;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitCode() { // the entity is read at the start only (dead slots at a return, as above)
                                    super.visitCode();
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountArg", "(Ljava/lang/Object;)V", false);
                                }
                                @Override
                                public void visitInsn(int op) {
                                    if (op == Opcodes.ARETURN) {
                                        n[0]++;
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountTag", "(Ljava/lang/Object;)V", false);
                                    }
                                    super.visitInsn(op);
                                }
                            };
                        }
                        if (kind.equals("mount")) {
                            if (stat || at.length < 4 || at[0].getSort() != Type.OBJECT) return mv; // 1.21.1: render(entity, yaw, partial, pose, buffers, light)
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                boolean typed, tinted;
                                @Override
                                public void visitLdcInsn(Object v) { // 1.15-1.20: renderToBuffer(..., r, g, b, translucent ? 0.15F : 1F)
                                    super.visitLdcInsn(v);
                                    if (typed && !tinted && v instanceof Float && (Float) v == 0.15f) { n[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountA", "(F)F", false); }
                                }
                                @Override
                                public void visitMethodInsn(int op, String o, String nm, String d, boolean itf) {
                                    if (typed && !tinted && op != Opcodes.INVOKESTATIC && d.endsWith("III)V")) {
                                        tinted = true; // 1.21.1: model.renderToBuffer(pose, buffer, light, overlay, color), colour on top
                                        n[0]++;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountColor", "(I)I", false);
                                    }
                                    if (!typed && op == Opcodes.INVOKEVIRTUAL && o.equals(owner) && d.contains("ZZZ)L")) {
                                        typed = true; // stack: ..., translucent, glowing -> translucent filtered under glowing
                                        n[0]++;
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitVarInsn(Opcodes.ALOAD, 1);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountTranslucent", "(ZLjava/lang/Object;)Z", false);
                                        super.visitInsn(Opcodes.SWAP);
                                    } else if (!typed && op == Opcodes.INVOKEVIRTUAL && o.equals(owner) && d.contains(";ZZ)L")) {
                                        typed = true; // 1.15: getRenderType(entity, visible, translucent): translucent on top
                                        n[0]++;
                                        super.visitVarInsn(Opcodes.ALOAD, 1);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountTranslucent", "(ZLjava/lang/Object;)Z", false);
                                    }
                                    super.visitMethodInsn(op, o, nm, d, itf);
                                    if (typed && !tinted && op == Opcodes.INVOKESTATIC && "(II)I".equals(d)) {
                                        tinted = true;
                                        n[0]++;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountColor", "(I)I", false);
                                    }
                                }
                            };
                        }
                        if (kind.equals("armor")) {
                            final Set<String> owners = new HashSet<>(java.util.Arrays.asList(spec[4].split("\\|")));
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitMethodInsn(int op, String o, String nm, String d, boolean itf) {
                                    boolean type = op == Opcodes.INVOKESTATIC && owners.contains(o) && helpers.contains(nm)
                                        && Type.getArgumentTypes(d).length == 1 && Type.getArgumentTypes(d)[0].getSort() == Type.OBJECT && Type.getReturnType(d).getSort() == Type.OBJECT;
                                    if (type) {
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountId", "(Ljava/lang/Object;)V", false);
                                    }
                                    super.visitMethodInsn(op, o, nm, d, itf);
                                    if (type) {
                                        n[0]++;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountType", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                        super.visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(d).getInternalName());
                                    } else if (op == Opcodes.INVOKESTATIC && o.equals(owner) && d.endsWith(";I)I")) {
                                        n[0]++;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "mountColor", "(I)I", false);
                                    }
                                }
                            };
                        }
                        if (kind.equals("lfire") && helpers.contains(mname) && !stat && at.length == 7 && at[5].getSort() == Type.OBJECT) {
                            final int item = slot(at, 5); // 1.12.2 renderItemInFirstPerson(player, partial, pitch, hand, swing, stack, equip)
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                boolean done;
                                @Override
                                public void visitMethodInsn(int op, String o, String nm, String d, boolean itf) {
                                    super.visitMethodInsn(op, o, nm, d, itf);
                                    if (done || op != Opcodes.INVOKESTATIC || !nm.equals(spec[4]) || !"()V".equals(d)) return;
                                    done = true;
                                    shields[0]++;
                                    super.visitVarInsn(Opcodes.ALOAD, item);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "lshield", "(Ljava/lang/Object;)V", false);
                                }
                            };
                        }
                        if (kind.equals("lfire")) { // 1.8.9 / 1.12.2 renderFireInFirstPerson: translate y -0.3F and colour alpha 0.9F
                            if (!named) return mv;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitLdcInsn(Object v) {
                                    super.visitLdcInsn(v);
                                    if (v instanceof Float && (Float) v == -0.3f) { n[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fireY", "(F)F", false); }
                                    else if (v instanceof Float && (Float) v == 0.9f) { n[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fireA", "(F)F", false); }
                                }
                            };
                        }
                        if (kind.equals("lmount")) { // 1.8.9 doRender: GL tint around the whole mob (MountOpacity's render events)
                            if (!named || stat) return mv;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    n[0]++;
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "tintIn", "(Ljava/lang/Object;)V", false);
                                }
                                @Override
                                public void visitInsn(int op) {
                                    if (op == Opcodes.RETURN) super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "tintOut", "()V", false);
                                    super.visitInsn(op);
                                }
                            };
                        }
                        return mv;
                    }
                }, 0);
                if (shields[0] > 0) Hook.shieldHooked = true;
                if (n[0] == 0 && shields[0] == 0) return null;
                if (kind.equals("shield")) Hook.shieldHooked = true;
                else if (kind.equals("fire") || (kind.equals("lfire") && n[0] > 0)) Hook.fireHooked = true;
                else if (kind.equals("mount") || kind.equals("lmount")) Hook.mountHooked = true;
                System.out.println("[nether-agent] hooked view " + kind + " (" + n[0] + ")");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] view " + kind + " hook failed: " + t);
                return null;
            }
        }

        /** Local slot of argument i of an instance method. */
        private static int slot(Type[] at, int i) {
            int s = 1;
            for (int k = 0; k < i; k++) s += at[k].getSize();
            return s;
        }

        /**
         * Freelook, at the method's start (no branches, so no stack map frames change): "turn" = Entity.turn(DD)V,
         * yRot = Hook.look(this, yRot, xRot), xRot = Hook.lookX(xRot); "cam" = Camera.setRotation(FF)V,
         * yaw = Hook.camYaw(yaw), pitch = Hook.camPitch(pitch).
         */
        private static byte[] look(byte[] bytes, final String[] spec) {
            final String kind = spec[2];
            final Set<String> methods = new HashSet<>(java.util.Arrays.asList(spec[0].split("\\|")));
            final boolean turn = kind.equals("turn"), lturn = kind.equals("lturn"), lcam = kind.equals("lcam");
            // lcam: {methods, desc ("" = any), "lcam", entity class, yaw fields, pitch fields}
            final Set<String> yawF = lcam ? new HashSet<>(java.util.Arrays.asList(spec[4].split("\\|"))) : null, pitchF = lcam ? new HashSet<>(java.util.Arrays.asList(spec[5].split("\\|"))) : null;
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final boolean[] done = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, mname, desc, sig, ex);
                        if (!methods.contains(mname) || !(spec[1].isEmpty() || spec[1].equals(desc)) || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return mv;
                        done[0] = true;
                        if (lcam) // 1.8.9 / 1.12.2 camera (EntityRenderer.orientCamera, RenderManager.cacheActiveRenderInfo): the view entity's rotation reads
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitFieldInsn(int op, String o, String n, String d) {
                                    super.visitFieldInsn(op, o, n, d);
                                    if (op != Opcodes.GETFIELD || !"F".equals(d) || !o.equals(spec[3])) return;
                                    if (yawF.contains(n)) super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "camYaw", "(F)F", false);
                                    else if (pitchF.contains(n)) super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "camPitch", "(F)F", false);
                                }
                            };
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                if (turn) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitVarInsn(Opcodes.DLOAD, 1);
                                    super.visitVarInsn(Opcodes.DLOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "look", "(Ljava/lang/Object;DD)D", false);
                                    super.visitVarInsn(Opcodes.DSTORE, 1);
                                    super.visitVarInsn(Opcodes.DLOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "lookX", "(D)D", false);
                                    super.visitVarInsn(Opcodes.DSTORE, 3);
                                } else if (lturn) { // 1.8.9 / 1.12.2 Entity.setAngles(yaw, pitch): float deltas, pitch subtracted
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitVarInsn(Opcodes.FLOAD, 1);
                                    super.visitVarInsn(Opcodes.FLOAD, 2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "lookL", "(Ljava/lang/Object;FF)F", false);
                                    super.visitVarInsn(Opcodes.FSTORE, 1);
                                    super.visitVarInsn(Opcodes.FLOAD, 2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "lookLX", "(F)F", false);
                                    super.visitVarInsn(Opcodes.FSTORE, 2);
                                } else {
                                    super.visitVarInsn(Opcodes.FLOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "camYaw", "(F)F", false);
                                    super.visitVarInsn(Opcodes.FSTORE, 1);
                                    super.visitVarInsn(Opcodes.FLOAD, 2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "camPitch", "(F)F", false);
                                    super.visitVarInsn(Opcodes.FSTORE, 2);
                                }
                            }
                        };
                    }
                }, 0);
                if (!done[0]) { System.out.println("[nether-agent] freelook " + kind + " method not found"); return null; }
                if (turn || lturn) Hook.turnHooked = true; else Hook.camHooked = true;
                System.out.println("[nether-agent] hooked freelook " + kind);
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[nether-agent] freelook " + kind + " hook failed: " + t);
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
