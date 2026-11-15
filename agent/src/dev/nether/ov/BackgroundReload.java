package dev.nether.ov;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Background Reload: a resource reload (pack change, F3+T, server pack) runs behind the game instead of under the
 * loading screen. The game's loading overlay is taken off screen (Minecraft.overlay = null) and its finish callback is
 * run here once the reload's future completes, exactly as the overlay would. The first load at startup is left alone.
 * Everything is found by shape (no names): the overlay is the only value of an abstract-typed Minecraft field whose class
 * holds a boolean (fadeIn), a Consumer (onFinish) and a reload instance (an interface with a no-arg CompletableFuture
 * method). 1.14+; 1.8.9 reloads synchronously on the game thread, so there is nothing to move.
 */
final class BackgroundReload extends Module {
    private Field slot;
    private Object held;
    private CompletableFuture<?> done;
    private Consumer<Object> finish;

    BackgroundReload() {
        super("bgreload", "Background Reload", "Utility", "Resource packs load in the background: no loading screen, keep playing while they reload.", false, false, 0, 0);
    }

    @Override boolean available() { return Game.mc() != null && !Game.legacy(); }

    @Override void tick() {
        if (held != null) { poll(); return; } // finish a taken reload even if switched off meanwhile
        if (!enabled()) return;
        Object mc = Game.mc();
        if (mc == null) return;
        try {
            if (slot != null) { take(mc, slot); return; }
            for (Field f : mc.getClass().getDeclaredFields()) {
                Class<?> t = f.getType();
                if (Modifier.isStatic(f.getModifiers()) || t.isInterface() || !Modifier.isAbstract(t.getModifiers())) continue;
                f.setAccessible(true);
                if (take(mc, f)) { slot = f; return; }
            }
        } catch (Throwable t) {
            System.out.println("[nether-agent] background reload: " + t);
        }
    }

    /** Takes the overlay in f off screen when it is a reload's loading overlay (not the startup one). */
    @SuppressWarnings("unchecked")
    private boolean take(Object mc, Field f) throws Exception {
        Object o = f.get(mc);
        if (o == null) return false;
        Field fade = null, cb = null, rl = null;
        Method fut = null;
        for (Class<?> c = o.getClass(); c != null && c != f.getType() && c != Object.class; c = c.getSuperclass()) {
            for (Field x : c.getDeclaredFields()) {
                if (Modifier.isStatic(x.getModifiers())) continue;
                Class<?> t = x.getType();
                if (t == boolean.class) { if (fade != null) return false; fade = x; }
                else if (t == Consumer.class) cb = x;
                else if (t.isInterface() && rl == null) {
                    for (Method m : t.getMethods()) if (m.getParameterCount() == 0 && m.getReturnType() == CompletableFuture.class) { rl = x; fut = m; break; }
                }
            }
        }
        if (fade == null || cb == null || rl == null) return false;
        fade.setAccessible(true); cb.setAccessible(true); rl.setAccessible(true);
        if (!fade.getBoolean(o)) return true; // the startup load: the game is not ready, leave it on screen
        Object r = rl.get(o);
        if (r == null) return true;
        done = (CompletableFuture<?>) fut.invoke(r);
        finish = (Consumer<Object>) cb.get(o);
        f.set(mc, null);
        held = o;
        System.out.println("[nether-agent] background reload: resources reloading in the background");
        return true;
    }

    /** The overlay's own finish: callback with the error (if any) once the reload is done. */
    private void poll() {
        if (!done.isDone()) return;
        Throwable err = null;
        try { done.join(); } catch (Throwable t) { err = t.getCause() != null ? t.getCause() : t; }
        held = null;
        Consumer<Object> c = finish;
        done = null; finish = null;
        try { c.accept(err == null ? Optional.empty() : Optional.of(err)); }
        catch (Throwable t) { System.out.println("[nether-agent] background reload finish: " + t); }
    }
}
