package dev.nether.ov;

import dev.nether.agent.Hook;
import java.lang.reflect.Field;

/**
 * View editors (Tier B, Agent VIEW_HOOKS): Shield View Editor and Fire View Editor behave like OverlayTweaks (held
 * shield height; fire overlay height, opacity, hidden while fire resistant), Ride View Editor like MountOpacity (the
 * entity you ride fades per mob type, optionally by look direction or only in first person). Values and ranges follow
 * those mods; the game draws everything itself, this side only feeds {@link Hook}.
 */
final class Views {
    private Views() {}

    private static Object shieldItem, fireRes;
    /** PoseStack.translate flavour: 0 unknown, 1 (FFF) 1.19.3+, 2 (DDD) before. */
    private static int moveKind;

    private static boolean floatTranslate(Class<?> c) {
        String n = Game.n("method_46416");
        for (java.lang.reflect.Method m : c.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(n) && p.length == 3 && p[0] == float.class && p[1] == float.class && p[2] == float.class) return true;
        }
        return false;
    }
    private static boolean bound;
    /** where the game holds a first-person item (main hand; the off hand mirrors x), the hand view's scaling pivot */
    private static final float HX = 0.56f, HY = -0.52f, HZ = -0.72f;

    /** Hook.pose / Hook.translucent in the game's own names (once, lazily: the game classes must be loaded). */
    static void bind() {
        if (bound) return;
        if (Game.legacy()) { bound = true; bindLegacy(); return; }
        if (!Game.fabric()) return;
        bound = true;
        if (Game.cls0("net.minecraft.class_4587") == null) bindGl14(); // 1.14: GL state, no PoseStack
        Hook.pose = new Hook.Pose() {
            @Override public void push(Object ps) { Game.call(ps, "method_22903"); }
            @Override public void pop(Object ps) { Game.call(ps, "method_22909"); }
            @Override public void move(Object ps, float dy) { // translate(FFF) 1.19.3+, (DDD) before
                if (moveKind == 0) moveKind = floatTranslate(ps.getClass()) ? 1 : 2;
                if (moveKind == 1) Game.call(ps, "method_46416", 0f, dy, 0f);
                else Game.call(ps, "method_22904", 0d, (double) dy, 0d);
            }
            @Override public boolean shield(Object stack) {
                if (shieldItem == null) shieldItem = stat("net.minecraft.class_1802", "field_8255");
                return shieldItem != null && Game.call(stack, "method_7909") == shieldItem;
            }
            @Override public void hand(Object ps, Object stack, float x, float y, float z, float s) {
                Object me = Game.fld(Game.mc(), "field_1724");
                Object off = me == null ? null : Game.call(me, "method_6079"); // the off-hand stack: mirror sideways
                boolean left = off != null && off == stack && Boolean.FALSE.equals(Game.call(off, "method_7960"));
                if (left) x = -x;
                // scale about where the game holds the item (0.56, -0.52, -0.72), not about the eye
                float k = 1 - s, tx = x + (left ? -HX : HX) * k, ty = y + HY * k, tz = z + HZ * k;
                if (moveKind == 0) moveKind = floatTranslate(ps.getClass()) ? 1 : 2;
                if (moveKind == 1) Game.call(ps, "method_46416", tx, ty, tz);
                else Game.call(ps, "method_22904", (double) tx, (double) ty, (double) tz);
                if (s != 1) Game.call(ps, "method_22905", s, s, s);
            }
        };
        Hook.translucent = id -> {
            Class<?> rt = Game.cls0("net.minecraft.class_12249"); // 1.21.11+ RenderTypes.entityTranslucent
            if (rt != null) return Game.stat(rt, "method_76000", id);
            rt = Game.cls0("net.minecraft.class_1921"); // up to 1.21.10: RenderType.entityTranslucent (Fabric)
            if (rt != null) return Game.stat(rt, "method_23580", id);
            try { // Forge/NeoForge up to 1.21.10: Mojang names, RenderType still in client.renderer
                return Game.stat(Class.forName("net.minecraft.client.renderer.RenderType", true, Game.loader()), "entityTranslucent", id);
            } catch (Throwable t) { return null; }
        };
    }

    /** 1.14 (no PoseStack yet): ride fade and shield lift through blaze3d's GlStateManager (unobfuscated in 1.14). */
    private static void bindGl14() {
        final Class<?> gl;
        try { gl = Class.forName("com.mojang.blaze3d.platform.GlStateManager", true, Game.loader()); } catch (Throwable t) { return; }
        Hook.tint = new Hook.Tint() {
            @Override public void begin(float a) {
                Game.stat(gl, "enableBlend");
                Game.stat(gl, "enableAlphaTest");
                Game.stat(gl, "alphaFunc", 516, 0.1f);
                Game.stat(gl, "blendFuncSeparate", 770, 771, 1, 0);
                Game.stat(gl, "color4f", 1f, 1f, 1f, a);
            }
            @Override public void end() {
                Game.stat(gl, "color4f", 1f, 1f, 1f, 1f);
                Game.stat(gl, "disableBlend");
            }
            @Override public void shield(Object stack, float dy) {
                if (shieldItem == null) shieldItem = stat("net.minecraft.class_1802", "field_8255");
                if (shieldItem != null && Game.call(stack, "method_7909") == shieldItem) Game.stat(gl, "translatef", 0f, dy, 0f);
            }
        };
    }

    /** 1.8.9: the mount fade through GlStateManager (its colour cache must see every change), as MountOpacity's render events. */
    private static void bindLegacy() {
        // hand view (renderItemInFirstPerson): one matrix level around the whole first-person pass
        Hook.handGlIn = () -> {
            org.lwjgl.opengl.GL11.glPushMatrix();
            float s = Hook.handS, k = 1 - s; // scale about the held item's spot, as on modern versions
            org.lwjgl.opengl.GL11.glTranslatef(Hook.handX + HX * k, Hook.handY + HY * k, Hook.handZ + HZ * k);
            if (s != 1) org.lwjgl.opengl.GL11.glScalef(s, s, s);
        };
        Hook.handGlOut = org.lwjgl.opengl.GL11::glPopMatrix;
        final Class<?> gl = lcls("bfl", "net.minecraft.client.renderer.GlStateManager");
        if (gl == null) return;
        Hook.tint = new Hook.Tint() {
            @Override public void begin(float a) {
                Game.stat(gl, Game.l("l", "func_179147_l")); // enableBlend
                Game.stat(gl, Game.l("d", "func_179141_d")); // enableAlpha
                Game.stat(gl, Game.l("a", "func_179092_a"), 516, 0.1f); // alphaFunc(GREATER, 0.1)
                Game.stat(gl, Game.l("a", "func_179120_a"), 770, 771, 1, 0); // tryBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO)
                Game.stat(gl, Game.l("c", "func_179131_c"), 1f, 1f, 1f, a); // color
            }
            @Override public void end() {
                Game.stat(gl, Game.l("c", "func_179131_c"), 1f, 1f, 1f, 1f);
                Game.stat(gl, Game.l("k", "func_179084_k")); // disableBlend
            }
            @Override public void shield(Object stack, float dy) { // 1.12.2 (Forge): Items.SHIELD
                if (shieldItem == null) shieldItem = lstat(lcls("air", "net.minecraft.init.Items"), "cR", "field_185159_cQ");
                if (shieldItem != null && Game.call(stack, Game.l("c", "func_77973_b")) == shieldItem)
                    Game.stat(gl, Game.l("c", "func_179109_b"), 0f, dy, 0f); // translate(FFF)
            }
        };
    }

    /** 1.8.9 (else 1.12.2 on the legacy path): what exists differs (shields, llamas). */
    static boolean v189() {
        if (v189 == null) v189 = "1.8.9".equals(Game.version());
        return v189;
    }
    private static Boolean v189;

    /** A 1.8.9 game class (vanilla notch name or Forge's MCP name); null when missing. */
    static Class<?> lcls(String notch, String mcp) {
        try { return Game.cls(Game.l(notch, mcp)); } catch (Throwable t) { return null; }
    }

    /** A static field of a 1.8.9 game class; null when missing. */
    static Object lstat(Class<?> c, String notch, String srg) {
        try { Field f = c == null ? null : Game.field(c, Game.l(notch, srg)); return f == null ? null : f.get(null); } catch (Throwable t) { return null; }
    }

    /** A static field of a game class by intermediary names; null when missing. */
    static Object stat(String cls, String field) {
        try {
            Class<?> c = Game.cls(cls);
            Field f = Game.field(c, field);
            return f == null ? null : f.get(null);
        } catch (Throwable t) { return null; }
    }

    /** Diagram shared by the previews: a screen with the vanilla spot as an outline and the edited one filled. */
    static void screen(float cx, float cy, float w, float h) {
        Gfx.rectG(cx - w / 2, cy - h / 2, w, h, 8, 0xFF0D0E12, 0xFF050508);
        Gfx.ring(cx - w / 2, cy - h / 2, w, h, 8, 1, Gfx.LN2);
    }

    // ---------------------------------------------------------------- Shield View Editor

    static final class Shield extends Module {
        Shield() {
            super("shieldview", "Shield View Editor", "Visual", "Lower the shield you hold in first person so it covers less of your view.", false, false, 0, 0);
            add(Setting.header("Position"));
            add(Setting.slider("height", "Shield height", -0.5, 0, 0.01, -0.2).hint("How far the held shield sits below its normal spot. 0 = vanilla."));
            needs(4); // 1.12.2 too (Forge)
        }

        @Override boolean available() { return super.available() && Hook.shieldHooked; }
        @Override String lockNote() { return !available() && Game.legacy() && v189() ? "NO SHIELDS IN 1.8.9" : super.lockNote(); }

        @Override void tick() {
            if (enabled()) bind();
            Hook.shieldDy = enabled() ? (float) num("height") : 0;
        }

        @Override void preview(float cx, float cy) {
            float w = 200, h = 112, k = 90;
            screen(cx, cy, w, h);
            float d = (float) -num("height") * k, sx = cx + 38, sy = cy - 14;
            Gfx.ring(sx - 17, sy - 20, 34, 40, 6, 1, Gfx.a(Th.mut, 0.7f));
            int[] pc = Gfx.clipIn(cx - w / 2, cy - h / 2, w, h);
            ModIcons.draw("shieldview", sx - 17, sy - 17 + d, 34, Th.ac);
            Gfx.reclip(pc);
            Gfx.textC(Gfx.MONO, 10, cx, cy + h / 2 + 16, Gfx.DIM, String.format(java.util.Locale.ROOT, "HEIGHT %.2f", num("height")), 0);
        }
    }

    // ---------------------------------------------------------------- Hand View Editor

    static final class Hand extends Module {
        Hand() {
            super("handview", "Hand View Editor", "Visual", "Move and resize your first-person hand and the item you hold.", false, false, 0, 0);
            add(Setting.header("Position"));
            add(Setting.slider("x", "Sideways", -0.6, 0.6, 0.01, 0).hint("Positive moves it right; the off hand mirrors it."));
            add(Setting.slider("y", "Height", -0.6, 0.6, 0.01, 0).hint("Positive moves it up."));
            add(Setting.slider("z", "Distance", -0.6, 0.6, 0.01, 0).hint("Positive brings it closer."));
            add(Setting.header("Size"));
            add(Setting.slider("scale", "Size", 0.4, 1.5, 0.01, 1).hint("1 = vanilla."));
            needs(4);
        }

        @Override boolean available() { return super.available() && (Hook.shieldHooked || Hook.handViewHooked); }

        @Override void tick() {
            boolean on = enabled();
            if (on) bind();
            Hook.handX = on ? (float) num("x") : 0;
            Hook.handY = on ? (float) num("y") : 0;
            Hook.handZ = on ? (float) num("z") : 0;
            Hook.handS = on ? (float) num("scale") : 1;
        }

        @Override void preview(float cx, float cy) {
            float w = 200, h = 112, k = 90;
            screen(cx, cy, w, h);
            float sz = 40 * (float) num("scale") * (float) (1 + num("z") * 0.8);
            float sx = cx + 44 + (float) num("x") * k, sy = cy + 18 - (float) num("y") * k;
            Gfx.ring(cx + 44 - 20, cy + 18 - 20, 40, 40, 6, 1, Gfx.a(Th.mut, 0.7f));
            int[] pc = Gfx.clipIn(cx - w / 2, cy - h / 2, w, h);
            ModIcons.draw("handview", sx - sz / 2, sy - sz / 2, sz, Th.ac);
            Gfx.reclip(pc);
            Gfx.textC(Gfx.MONO, 10, cx, cy + h / 2 + 16, Gfx.DIM, String.format(java.util.Locale.ROOT, "X %.2f  Y %.2f  Z %.2f  SIZE %.2f", num("x"), num("y"), num("z"), num("scale")), 0);
        }
    }

    // ---------------------------------------------------------------- Fire View Editor

    static final class Fire extends Module {
        Fire() {
            super("fireview", "Fire View Editor", "Visual", "Lower the burning overlay and make it see-through, or hide it while you are fire resistant.", false, false, 0, 0);
            add(Setting.header("Overlay"));
            add(Setting.slider("height", "Fire height", -0.5, 0, 0.01, -0.3).hint("How far the overlay sits below its normal spot. 0 = vanilla."));
            add(Setting.slider("opacity", "Fire opacity", 0, 100, 1, 90).hint("90 = vanilla."));
            add(Setting.header("Behaviour"));
            add(Setting.bool("resist", "Hide when fire resistant", false).hint("Hidden while you have Fire Resistance (or are in Creative). On 1.8.9 it blinks back in the last 5 seconds."));
            needs(4);
        }

        @Override boolean available() { return super.available() && Hook.fireHooked; }

        @Override void tick() {
            if (!enabled()) { Hook.fireDy = 0; Hook.fireAlpha = -1; return; }
            bind();
            Hook.fireDy = (float) num("height");
            Hook.fireAlpha = (float) num("opacity") / 100f * (bool("resist") ? shown() : 1);
        }

        /** How much of the overlay shows with "hide when fire resistant" on: 0 hidden, 1 shown (1.8.9: blinks out). */
        private static float shown() {
            Object me = Game.player0();
            if (me == null) return 1;
            if (Game.legacy()) { // OverlayTweaks 1.8.9: hidden, blinking during the last 100 ticks of the effect
                if (fireRes == null) fireRes = lstat(lcls("pe", "net.minecraft.potion.Potion"), "n", "field_76426_n");
                if (fireRes == null) fireRes = lstat(lcls("vb", "net.minecraft.init.MobEffects"), "l", "field_76426_n"); // 1.12.2
                if (fireRes == null || !Boolean.TRUE.equals(Game.call(me, Game.l("a", "func_70644_a"), fireRes))) return 1;
                Object fx = Game.call(me, Game.l("b", "func_70660_b"), fireRes);
                Object d = Game.call(fx, Game.l("b", "func_76459_b"));
                int ticks = d instanceof Integer ? (Integer) d : Integer.MAX_VALUE;
                return ticks > 100 ? 0 : 0.5f - (float) Math.sin(ticks * Math.PI * 0.2) * 0.5f;
            }
            Object ab = Game.call(me, "method_31549"); // getAbilities() 1.17+, the abilities field before
            if (ab == null) ab = Game.fld(me, "field_7503");
            if (Boolean.TRUE.equals(Game.fld(ab, "field_7477"))) return 0; // creative (abilities.instabuild)
            if (fireRes == null) fireRes = stat("net.minecraft.class_1294", "field_5918");
            return fireRes != null && Boolean.TRUE.equals(Game.call(me, "method_6059", fireRes)) ? 0 : 1;
        }

        @Override void preview(float cx, float cy) {
            float w = 200, h = 112, k = 90;
            screen(cx, cy, w, h);
            float d = (float) -num("height") * k, op = (float) num("opacity") / 100f;
            int[] pc = Gfx.clipIn(cx - w / 2, cy - h / 2, w, h);
            for (int i = 0; i < 2; i++) {
                float fx = i == 0 ? cx - w / 2 + 14 : cx + w / 2 - 58;
                Gfx.ring(fx, cy - 6, 44, 50, 6, 1, Gfx.a(Th.mut, 0.7f));
                ModIcons.draw("fireview", fx + 2, cy - 4 + d, 40, Gfx.a(Th.ac, op));
            }
            Gfx.reclip(pc);
            Gfx.textC(Gfx.MONO, 10, cx, cy + h / 2 + 16, Gfx.DIM, String.format(java.util.Locale.ROOT, "HEIGHT %.2f  -  %d%%", num("height"), Math.round(num("opacity"))), 0);
        }
    }

    // ---------------------------------------------------------------- Ride View Editor

    static final class Ride extends Module {
        /** entity type path -> opacity setting key (MountOpacity's groups: horses incl. donkey/mule/skeleton/zombie) */
        private Object lastVehicle;
        private String lastKey = "other";

        Ride() {
            super("rideview", "Ride View Editor", "Visual", "Fade the mob you ride so you can see past it. Set per mob, or by where you look.", false, false, 0, 0);
            add(Setting.header("Opacity"));
            add(Setting.slider("horse", "Horses", 0, 100, 1, 40).hint("Horse, donkey, mule, skeleton and zombie horse."));
            add(Setting.slider("pig", "Pigs", 0, 100, 1, 40));
            add(Setting.slider("llama", "Llamas", 0, 100, 1, 40).when(() -> !Game.legacy() || !v189())); // 1.11+
            add(Setting.slider("strider", "Striders", 0, 100, 1, 40).when(() -> !Game.legacy())); // not in 1.8.9
            add(Setting.slider("camel", "Camels", 0, 100, 1, 40).when(() -> !Game.legacy())); // not in 1.8.9
            add(Setting.slider("nautilus", "Nautiluses", 0, 100, 1, 40).when(() -> !Game.legacy())); // not in 1.8.9
            add(Setting.slider("ghast", "Happy ghasts", 0, 100, 1, 40).when(() -> !Game.legacy())); // not in 1.8.9
            add(Setting.slider("other", "Other mobs", 0, 100, 1, 40));
            add(Setting.header("Behaviour"));
            add(Setting.bool("firstPerson", "Only in first person", false));
            add(Setting.bool("look", "Fade by look direction", false).hint("Opaque while looking up, fading to the set opacity as you look down."));
            add(Setting.slider("lookOffset", "Look offset", -90, 90, 1, 20).when(() -> bool("look")));
            add(Setting.slider("lookRange", "Look range", 10, 180, 1, 80).when(() -> bool("look")));
            needs(4);
        }

        @Override boolean available() { return super.available() && Hook.mountHooked; }

        @Override void tick() {
            Object me = enabled() ? Game.player0() : null;
            Object v = me == null ? null : Game.legacy() ? (v189() ? Game.fld(me, Game.l("m", "field_70154_o")) : Game.call(me, "func_184187_bx")) : Game.call(me, "method_5854"); // ridingEntity / getVehicle
            if (v == null || (bool("firstPerson") && !Game.firstPerson())) { Hook.mountOf = null; Hook.mountAlpha = 255; return; }
            bind();
            if (v != lastVehicle) { lastVehicle = v; lastKey = group(v); }
            float o = (float) num(lastKey);
            if (bool("look")) {
                double[] p = Game.player();
                float t = p == null ? 0 : Math.max(0, Math.min(1, (float) ((p[4] + num("lookOffset")) / num("lookRange"))));
                o = 100 - t * (100 - o);
            }
            int a = Math.round(o * 2.55f);
            Hook.mountAlpha = a;
            Hook.mountOf = a < 255 ? v : null;
        }

        private static String group(Object v) {
            if (Game.legacy() && !v189()) { // 1.12.2 (Forge, MCP class names): every horse kind has its own class
                String c = v.getClass().getSimpleName();
                return c.contains("Llama") ? "llama" : c.contains("Horse") || c.contains("Donkey") || c.contains("Mule") ? "horse" : c.equals("EntityPig") ? "pig" : "other";
            }
            if (Game.legacy()) { // EntityList.getEntityString: every horse kind is EntityHorse in 1.8.9
                Object n = Game.stat(lcls("pm", "net.minecraft.entity.EntityList"), Game.l("b", "func_75621_b"), v);
                return "EntityHorse".equals(n) ? "horse" : "Pig".equals(n) ? "pig" : "other";
            }
            Object type = Game.call(v, "method_5864");
            Class<?> et = Game.cls0("net.minecraft.class_1299");
            Object key = type == null || et == null ? null : Game.stat(et, "method_5890", type);
            String k = String.valueOf(key);
            k = k.substring(k.indexOf(':') + 1);
            if (k.contains("camel")) return "camel";
            if (k.contains("llama")) return "llama";
            if (k.contains("horse") || k.equals("donkey") || k.equals("mule")) return "horse";
            if (k.equals("pig")) return "pig";
            if (k.equals("strider")) return "strider";
            if (k.contains("nautilus")) return "nautilus";
            if (k.equals("happy_ghast")) return "ghast";
            return "other";
        }

        @Override void preview(float cx, float cy) {
            float w = 200, h = 112;
            screen(cx, cy, w, h);
            float o = (float) num("horse") / 100f;
            Gfx.ring(cx - 30, cy - 30, 60, 60, 8, 1, Gfx.a(Th.mut, 0.7f));
            ModIcons.draw("rideview", cx - 26, cy - 26, 52, Gfx.a(Th.ac, Math.max(0.08f, o)));
            Gfx.textC(Gfx.MONO, 10, cx, cy + h / 2 + 16, Gfx.DIM, "HORSES " + Math.round(num("horse")) + "%" + (bool("look") ? "  -  BY LOOK" : ""), 0);
        }
    }
}
