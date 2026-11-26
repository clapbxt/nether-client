package dev.nether.ov;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import dev.nether.agent.Hook;

/**
 * Entity occlusion culling (Performance, vault: projects/Performance). Our own design: the agent puts
 * "if (Hook.cull(entity, camX, camY, camZ)) return false;" at the start of the entity render dispatcher's should-render
 * test. On the render thread an answer is one identity-map lookup. A worker thread ("nether-cull") keeps the answers:
 * for every entity the game asked about, it walks voxel rays (Amanatides-Woo DDA) from the camera to the centre and the
 * 8 corners of the entity's box (grown by half a block) and calls the entity hidden only when every ray meets a block
 * that the game itself treats as a full opaque cube (isSolidRender / isOpaqueCube, cached per block state).
 * Never hidden: players, glowing or custom-named entities (their outline / name shows through walls), anything within
 * 3 blocks of the camera, very large entities, everything while the camera is inside a block, and everything for a
 * frame whose camera moved more than half a block from the one the answers were computed for. 1.8.9 / 1.12.2 pass the
 * view entity's feet to the test, so there the eye is feet + 1.62 and culling pauses in third person.
 */
final class Culling implements Hook.Cull {
    static final Culling C = new Culling();
    private Culling() {}

    /** One entity the game asked about. hidden is written by the worker, read by the render thread. */
    private static final class Rec {
        final Object e;
        final boolean player;
        /** a block entity at bx, by, bz (its position never changes) */
        boolean block;
        int bx, by, bz;
        long seen;
        volatile boolean hidden;
        Rec(Object e, boolean player) { this.e = e; this.player = player; }
    }

    /** A number or flag read through a getter method or a field. */
    private static final class Get {
        final Method m;
        final Field f;
        Get(Method m, Field f) { this.m = m; this.f = f; }
        double d(Object o) throws Exception { return m != null ? ((Number) m.invoke(o)).doubleValue() : f.getDouble(o); }
        boolean b(Object o) throws Exception { return (Boolean) m.invoke(o); }
        static Get of(Class<?> c, String method, String field, Class<?> type) {
            Method m = method == null ? null : Game.named(c, method, 0, type);
            if (m != null) return new Get(m, null);
            Field f = field == null ? null : Game.field(c, field);
            return f != null && f.getType() == type ? new Get(null, f) : null;
        }
    }

    // ---- render thread
    private static final Map<Object, Rec> recs = new IdentityHashMap<>();
    private static boolean dirty, legacy, third;
    private static int bound; // 0 not yet, 1 bound, -1 unsupported
    private static long tries, camFrame = -1, tickFrame, ticks;
    private static boolean active;
    private static Class<?> playerCls;
    private static Get gx, gy, gz, gw, gh, glow, named;

    // ---- shared
    private static volatile boolean on;
    private static volatile Rec[] snap = new Rec[0];
    private static volatile Object world;
    private static volatile double camX, camY, camZ, passX, passY, passZ;
    private static volatile long camSeq;
    private static volatile boolean passValid;
    private static Method getState, stateBlock, opaque;
    private static boolean opaqueWithPos;
    private static Constructor<?> posNew;
    private static Thread worker;

    /** Each overlay frame (Performance.tick): on/off, world, third person, pruning, the worker's entity list. */
    /** Entities are culled (Performance "Skip hidden entities"); else the entity test only records the camera. */
    private static volatile boolean entities;

    static void tick(boolean ents, boolean blocks) {
        Hook.beCull = blocks;
        if (ents != entities) { entities = ents; for (Rec r : recs.values()) if (!r.block) r.hidden = false; }
        boolean want = ents || blocks && Hook.beCullHooked;
        boolean ok = want && Hook.cullHooked && bound >= 0;
        if (ok != on) { on = ok; Hook.cull = ok ? C : null; if (!ok) { recs.clear(); snap = new Rec[0]; passValid = false; } }
        if (!ok) return;
        tickFrame = Hook.frames;
        ticks++;
        if (bound == 0) return; // binds on the first should-render call (a world exists then)
        Object w = Game.level();
        if (w != world) { world = w; passValid = false; } // another dimension: no answers from the old one's blocks
        third = legacy && Game.cameraType() != 0;
        if (ticks % 64 == 0) {
            for (Iterator<Rec> it = recs.values().iterator(); it.hasNext(); ) if (it.next().seen < tickFrame - 64) { it.remove(); dirty = true; }
        }
        if (dirty) { dirty = false; snap = recs.values().toArray(new Rec[0]); }
        if (worker == null) {
            worker = new Thread(Culling::run, "nether-cull");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    @Override
    public boolean hidden(Object e, double x, double y, double z) {
        if (bound != 1 && !bind()) return false;
        long f = Hook.frames;
        if (f != camFrame) {
            camFrame = f;
            double cy = legacy ? y + 1.62 : y;
            double dx = x - passX, dy = cy - passY, dz = z - passZ;
            // stale answers (overlay not ticking), legacy third person, or a camera far from the computed one: show all
            active = f - tickFrame < 30 && !third && passValid && dx * dx + dy * dy + dz * dz < 0.25;
            camX = x; camY = cy; camZ = z;
            camSeq = f;
        }
        if (!entities) return false; // block entities only: this call just gave the camera
        Rec r = recs.get(e);
        if (r == null) {
            if (recs.size() >= 8192 || e == null) return false;
            recs.put(e, r = new Rec(e, playerCls.isInstance(e)));
            dirty = true;
        }
        r.seen = f;
        return active && r.hidden;
    }

    // ---- block entities (same worker, same camera: the entity test of this frame records it)
    private static int beBound; // 0 not yet, 1 bound, -1 unsupported
    private static Method bePos, posX, posY, posZ, beRenderer, offScreen;
    private static boolean offScreenArg;
    /** A rec that is never hidden (renderer draws off screen / beacon beam, or no renderer). */
    private static final Rec NEVER = new Rec(null, true);

    @Override
    public boolean hiddenBlock(Object d, Object be) {
        if (bound != 1 || be == null) return false;
        long f = Hook.frames;
        Rec r = recs.get(be);
        if (r == null) {
            if (recs.size() >= 8192 || (beBound != 1 && !bindBlock(d))) return false;
            r = blockRec(d, be);
            recs.put(be, r);
            if (r != NEVER) dirty = true;
        }
        if (r == NEVER) return false;
        r.seen = f;
        return camFrame == f && active && r.hidden; // no entity test yet this frame: camera unknown, draw it
    }

    private static Rec blockRec(Object d, Object be) {
        try {
            Object rd = beRenderer.invoke(d, be);
            if (rd == null) return NEVER;
            boolean off = offScreenArg ? (Boolean) offScreen.invoke(rd, be) : (Boolean) offScreen.invoke(rd);
            if (off) return NEVER;
            Object p = bePos.invoke(be);
            Rec r = new Rec(be, false);
            r.block = true;
            r.bx = (Integer) posX.invoke(p);
            r.by = (Integer) posY.invoke(p);
            r.bz = (Integer) posZ.invoke(p);
            return r;
        } catch (Throwable t) {
            return NEVER;
        }
    }

    /** Block entity names, resolved from the hooked dispatcher method's types (render thread). */
    private static boolean bindBlock(Object d) {
        if (beBound < 0) return false;
        try {
            boolean v189 = legacy && String.valueOf(Game.version()).startsWith("1.8");
            Class<?> base = null;
            for (Class<?> k = d.getClass(); k != null && base == null; k = k.getSuperclass())
                for (Method m : k.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length >= 3 && p[1] == float.class && !p[0].isPrimitive() && (p.length == 3 && p[2] == int.class && m.getReturnType() == void.class
                        || !p[2].isPrimitive() && !m.getReturnType().isPrimitive() && (p.length == 3 || p.length == 4 && p[3] == boolean.class))) { base = p[0]; break; }
                }
            if (base == null) throw new NoSuchMethodException("block entity type");
            String rn = !legacy ? "method_3550" : Game.l(v189 ? "b" : "a", "func_147547_b");
            for (Method m : d.getClass().getMethods())
                if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] != Class.class && m.getParameterTypes()[0].isAssignableFrom(base)
                    && Game.named(d.getClass(), rn, 1, m.getReturnType()) != null && sameName(m, Game.named(d.getClass(), rn, 1, m.getReturnType()))) { beRenderer = m; break; }
            if (beRenderer == null) throw new NoSuchMethodException("getRenderer");
            beRenderer.setAccessible(true);
            Class<?> rc = beRenderer.getReturnType();
            if (!legacy) offScreen = Game.named(rc, "method_3563", 0, boolean.class); // shouldRenderOffScreen (beacon, end gateway, structure block ...)
            else if (v189) offScreen = Game.named(rc, Game.l("a", "func_181055_a"), 0, boolean.class); // forceTileEntityRender (beacon)
            else { offScreen = Game.named(rc, Game.l("a", "func_188185_a"), 1, boolean.class); offScreenArg = true; } // isGlobalRenderer(te)
            bePos = Game.named(base, !legacy ? "method_11016" : Game.l(v189 ? "v" : "w", "func_174877_v"), 0, null);
            Class<?> pc = bePos == null ? null : bePos.getReturnType();
            if (pc != null) {
                posX = Game.named(pc, !legacy ? "method_10263" : Game.l(v189 ? "n" : "p", "func_177958_n"), 0, int.class);
                posY = Game.named(pc, !legacy ? "method_10264" : Game.l(v189 ? "o" : "q", "func_177956_o"), 0, int.class);
                posZ = Game.named(pc, !legacy ? "method_10260" : Game.l(v189 ? "p" : "r", "func_177952_p"), 0, int.class);
            }
            String miss = offScreen == null ? "shouldRenderOffScreen" : bePos == null ? "getBlockPos" : posX == null || posY == null || posZ == null ? "BlockPos x/y/z" : null;
            if (miss != null) throw new NoSuchMethodException(miss);
            beBound = 1;
            System.out.println("[nether-agent] block entity culling bound (" + base.getName() + ")");
            return true;
        } catch (Throwable t) {
            beBound = -1;
            System.out.println("[nether-agent] block entity culling unavailable: " + t);
            return false;
        }
    }

    private static boolean sameName(Method a, Method b) { return b != null && a.getName().equals(b.getName()); }

    /** Resolves the game's names once (render thread, inside the world render). False = culling stays off. */
    private static boolean bind() {
        if (bound < 0 || System.currentTimeMillis() < tries) return false;
        tries = System.currentTimeMillis() + 3000;
        try {
            Object w = Game.level(), me = Game.player0();
            if (w == null || me == null) return false;
            legacy = Game.legacy();
            boolean v189 = legacy && String.valueOf(Game.version()).startsWith("1.8");
            Class<?> ec = me.getClass();
            playerCls = legacy ? null : Game.cls0("net.minecraft.class_1657");
            if (playerCls == null || !playerCls.isInstance(me)) playerCls = ec.getSuperclass().getSuperclass(); // LocalPlayer -> AbstractClientPlayer -> Player
            if (legacy) {
                gx = Get.of(ec, null, Game.l("s", "field_70165_t"), double.class);
                gy = Get.of(ec, null, Game.l("t", "field_70163_u"), double.class);
                gz = Get.of(ec, null, Game.l("u", "field_70161_v"), double.class);
                gw = Get.of(ec, null, Game.l("J", "field_70130_N"), float.class);
                gh = Get.of(ec, null, Game.l("K", "field_70131_O"), float.class);
                glow = v189 ? null : Get.of(ec, Game.l("", "func_184202_aL"), null, boolean.class); // 1.12.2 isGlowing (1.8.9 has none)
                named = Get.of(ec, Game.l("l_", "func_145818_k_"), null, boolean.class);
                getState = Game.named(w.getClass(), Game.l("p", "func_180495_p"), 1, null);
                if (getState != null && v189) {
                    stateBlock = Game.named(getState.getReturnType(), Game.l("c", "func_177230_c"), 0, null);
                    opaque = stateBlock == null ? null : Game.named(stateBlock.getReturnType(), Game.l("c", "func_149662_c"), 0, boolean.class);
                } else if (getState != null) opaque = Game.named(getState.getReturnType(), Game.l("", "func_185914_p"), 0, boolean.class);
            } else {
                gx = Get.of(ec, "method_23317", "field_5987", double.class);
                gy = Get.of(ec, "method_23318", "field_6010", double.class);
                gz = Get.of(ec, "method_23321", "field_6035", double.class);
                gw = Get.of(ec, "method_17681", null, float.class);
                gh = Get.of(ec, "method_17682", null, float.class);
                glow = Get.of(ec, "method_5851", null, boolean.class);
                named = Get.of(ec, "method_16914", null, boolean.class);
                getState = Game.named(w.getClass(), "method_8320", 1, null);
                if (getState != null) {
                    opaque = Game.named(getState.getReturnType(), "method_26216", 0, boolean.class); // isSolidRender() 1.21.2+
                    if (opaque == null) { opaque = Game.named(getState.getReturnType(), "method_26216", 2, boolean.class); opaqueWithPos = opaque != null; } // (BlockGetter, BlockPos) 1.16-1.21.1
                }
            }
            if (getState != null) posNew = getState.getParameterTypes()[0].getConstructor(int.class, int.class, int.class);
            String miss = gx == null ? "x" : gy == null ? "y" : gz == null ? "z" : gw == null ? "width" : gh == null ? "height" : named == null ? "hasCustomName"
                : !v189 && glow == null ? "glowing" : getState == null ? "getBlockState" : opaque == null ? "opaque test" : posNew == null ? "BlockPos" : null;
            if (miss != null) throw new NoSuchMethodException(miss);
            world = w;
            bound = 1;
            System.out.println("[nether-agent] entity culling bound (" + (legacy ? "legacy" : "modern") + ", players " + playerCls.getName() + ")");
            return true;
        } catch (Throwable t) {
            bound = -1;
            on = false;
            Hook.cull = null;
            System.out.println("[nether-agent] entity culling unavailable: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- worker

    private static void run() {
        long last = -1;
        while (true) {
            LockSupport.parkNanos(3_000_000L); // at most ~330 passes a second, one per rendered frame below that
            try {
                long seq = camSeq;
                if (!on || bound != 1 || seq == last || world == null) continue;
                last = seq;
                pass();
            } catch (Throwable t) {
                passValid = false;
                if (errors++ == 0) { System.out.println("[nether-agent] culling worker: " + t); t.printStackTrace(System.out); }
            }
        }
    }

    private static int errors;
    private static final Map<Object, Boolean> stateOpaque = new IdentityHashMap<>();
    /** voxel -> opaque for one pass (open addressing, generation-stamped) */
    private static final int VBITS = 15, VMASK = (1 << VBITS) - 1;
    private static final long[] vKey = new long[1 << VBITS];
    private static final int[] vGen = new int[1 << VBITS];
    private static final boolean[] vVal = new boolean[1 << VBITS];
    private static int gen, vUsed;
    private static Object pw;

    private static void pass() {
        double cx = camX, cy = camY, cz = camZ;
        pw = world;
        gen++;
        vUsed = 0;
        Rec[] s = snap;
        boolean inside = opaque((int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));
        for (Rec r : s) r.hidden = !inside && hide(r, cx, cy, cz);
        passX = cx; passY = cy; passZ = cz;
        passValid = pw == world;
    }

    private static boolean hide(Rec r, double cx, double cy, double cz) {
        if (r.player) return false;
        Object e = r.e;
        try {
            double x, y, z, h, x0, x1, y0, y1, z0, z1;
            if (r.block) {
                // the block grown by half a block, and a block more up and down (banners hang below / stand above theirs)
                x = r.bx + 0.5; y = r.by; z = r.bz + 0.5; h = 1;
                x0 = r.bx - 0.5; x1 = r.bx + 1.5; y0 = r.by - 1.5; y1 = r.by + 2.5; z0 = r.bz - 0.5; z1 = r.bz + 1.5;
            } else {
                double w = gw.d(e);
                h = gh.d(e);
                if (w > 6 || h > 6) return false;
                if (named.b(e) || (glow != null && glow.b(e))) return false;
                x = gx.d(e); y = gy.d(e); z = gz.d(e);
                double hw = w / 2 + 0.5;
                x0 = x - hw; x1 = x + hw; y0 = y - 0.5; y1 = y + h + 0.5; z0 = z - hw; z1 = z + hw;
            }
            double dx = Math.max(0, Math.max(x0 - cx, cx - x1)), dy = Math.max(0, Math.max(y0 - cy, cy - y1)), dz = Math.max(0, Math.max(z0 - cz, cz - z1));
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 9 || d2 > 256 * 256) return false;
            if (clear(cx, cy, cz, x, y + h / 2, z)) return false;
            for (int i = 0; i < 8; i++)
                if (clear(cx, cy, cz, (i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the voxel ray from a to b meets no opaque block between the camera's voxel and the target's voxel. */
    private static boolean clear(double ax, double ay, double az, double bx, double by, double bz) {
        int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
        int ex = (int) Math.floor(bx), ey = (int) Math.floor(by), ez = (int) Math.floor(bz);
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double inf = Double.POSITIVE_INFINITY;
        double tdx = sx == 0 ? inf : Math.abs(1 / dx), tdy = sy == 0 ? inf : Math.abs(1 / dy), tdz = sz == 0 ? inf : Math.abs(1 / dz);
        double tx = sx > 0 ? (x + 1 - ax) * tdx : sx < 0 ? (ax - x) * tdx : inf;
        double ty = sy > 0 ? (y + 1 - ay) * tdy : sy < 0 ? (ay - y) * tdy : inf;
        double tz = sz > 0 ? (z + 1 - az) * tdz : sz < 0 ? (az - z) * tdz : inf;
        int n = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        for (int i = 0; i < n; i++) {
            if (tx < ty && tx < tz) { x += sx; tx += tdx; } else if (ty < tz) { y += sy; ty += tdy; } else { z += sz; tz += tdz; }
            if (x == ex && y == ey && z == ez) return true;
            if (opaque(x, y, z)) return false;
        }
        return true;
    }

    private static boolean opaque(int x, int y, int z) {
        long k = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        int i = (int) (k ^ (k >>> 29) ^ (k >>> 17)) * 0x9E3779B1 >>> (32 - VBITS);
        for (int p = 0; p < 8; p++, i = (i + 1) & VMASK) {
            if (vGen[i] != gen) {
                boolean v = lookup(x, y, z);
                if (vUsed < (1 << VBITS) / 2) { vGen[i] = gen; vKey[i] = k; vVal[i] = v; vUsed++; }
                return v;
            }
            if (vKey[i] == k) return vVal[i];
        }
        return lookup(x, y, z);
    }

    /**
     * Render thread: the block at x, y, z is a full opaque cube (for world lyrics printed on walls). Binds the names on
     * first use; false when they are unknown or no world is loaded. Uncached (the worker owns the caches).
     */
    static boolean solidAt(int x, int y, int z) {
        if (bound == 0) bind();
        Object w = Game.level();
        if (bound != 1 || w == null) return false;
        try {
            Object pos = posNew.newInstance(x, y, z), st = getState.invoke(w, pos);
            if (st == null) return false;
            Object o = stateBlock != null ? opaque.invoke(stateBlock.invoke(st)) : opaqueWithPos ? opaque.invoke(st, w, pos) : opaque.invoke(st);
            return Boolean.TRUE.equals(o);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The game's own "full opaque cube" answer for the block at x, y, z (unloaded / racing chunk = see-through). */
    private static boolean lookup(int x, int y, int z) {
        try {
            Object pos = posNew.newInstance(x, y, z), st = getState.invoke(pw, pos);
            if (st == null) return false;
            Boolean o = stateOpaque.get(st);
            if (o == null) {
                o = stateBlock != null ? (Boolean) opaque.invoke(stateBlock.invoke(st)) : opaqueWithPos ? (Boolean) opaque.invoke(st, pw, pos) : (Boolean) opaque.invoke(st);
                if (!opaqueWithPos) stateOpaque.put(st, o);
            }
            return o;
        } catch (Throwable t) {
            return false;
        }
    }
}
