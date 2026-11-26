package dev.nether.ov.features.performance;

import dev.nether.agent.Hook;

import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;

/**
 * Performance (2026-11-20, vault: projects/Performance): Nether's own fixes to the game's frame loop, on by default.
 * Each one is a setting so a player can hand the game its vanilla behaviour back if something looks off.
 * - GPU error checks (1.21.5+): vanilla brackets every buffer and texture allocation (each chunk section upload) with
 *   GlStateManager.clearGlErrors() + _getError() to spot GL_OUT_OF_MEMORY. Every glGetError makes the CPU wait until
 *   the driver has caught up with the GPU work queued so far. With this on, exactly those pairs skip the driver
 *   (the agent rewrites the two methods; any other _getError call still asks it).
 * - Hidden entities (all versions with a hooked entity render dispatcher): see {@link Culling}.
 * - Hidden block entities (1.8.9, 1.12.2, 1.21.9+, 26.x): the block entity dispatcher's per-block-entity method asks
 *   {@link Culling} too (same worker and rays; renderers that draw off screen, like beacons, are never skipped).
 * - Precise frame cap (1.13 - 1.21.x, GLFW limiter): see Hook.waitTimeout.
 * - HUD reuse: see Modules.boxes / Gfx.Rec.
 */
public final class Performance extends Module {
    public static final Performance M = new Performance();

    private Performance() {
        super("performance", "Performance", "Utility", "Nether's own fixes that make the game's frame loop faster. On by default.", false, true, 0, 0);
        add(Setting.header("Rendering"));
        add(Setting.bool("gpuChecks", "Skip GPU error checks", true).hint("The game stops to ask the graphics driver for errors around every chunk upload, which stalls each frame. Turn off to keep the game's own checks."));
        add(Setting.bool("cull", "Skip hidden entities", true).hint("Mobs and items fully behind solid blocks are not drawn. Players, glowing and named mobs always are. Turn off if something pops in late."));
        add(Setting.bool("beCull", "Skip hidden block entities", true).hint("Chests, signs, banners, heads and other block entities fully behind solid blocks are not drawn. Beacons always are. Turn off if one pops in late."));
        add(Setting.header("Frame pacing"));
        add(Setting.bool("preciseCap", "Precise frame cap", true).hint("With a max frame rate set, the game waits out the last moment of each frame itself instead of trusting a Windows timer that wakes late, so frames come evenly and reach the cap. Uses a little more CPU while capped."));
        add(Setting.bool("hudCache", "Reuse HUD drawing", true).hint("Nether's HUD is rebuilt up to 144 times a second and redrawn as-is in between, so high frame rates spend less time on it. Keys and clicks still update it at once."));
    }

    /** dev A/B: -Dnether.benchFeat=<name> turns that feature off in every other bench window. */
    static boolean benchOff(String feat) { return Hook.featOff && feat.equals(Hook.BENCH_FEAT); }

    public boolean hudCache() { return enabled() && bool("hudCache") && !benchOff("hud"); }

    @Override public void tick() {
        Hook.glErrSkip = enabled() && bool("gpuChecks");
        Hook.preciseWait = enabled() && bool("preciseCap") && !benchOff("cap");
        Culling.tick(enabled() && bool("cull") && !benchOff("cull"), enabled() && bool("beCull") && !benchOff("becull"));
    }
}
