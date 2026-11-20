package dev.nether.ov;

import dev.nether.agent.Hook;

/**
 * Performance (2026-11-20, vault: projects/Performance): Nether's own fixes to the game's frame loop, on by default.
 * Each one is a setting so a player can hand the game its vanilla behaviour back if something looks off.
 * - GPU error checks (1.21.5+): vanilla brackets every buffer and texture allocation (each chunk section upload) with
 *   GlStateManager.clearGlErrors() + _getError() to spot GL_OUT_OF_MEMORY. Every glGetError makes the CPU wait until
 *   the driver has caught up with the GPU work queued so far. With this on, exactly those pairs skip the driver
 *   (the agent rewrites the two methods; any other _getError call still asks it).
 * - Hidden entities (all versions with a hooked entity render dispatcher): see {@link Culling}.
 */
final class Performance extends Module {
    static final Performance M = new Performance();

    private Performance() {
        super("performance", "Performance", "Utility", "Nether's own fixes that make the game's frame loop faster. On by default.", false, true, 0, 0);
        add(Setting.bool("gpuChecks", "Skip GPU error checks", true).hint("The game stops to ask the graphics driver for errors around every chunk upload, which stalls each frame. Turn off to keep the game's own checks."));
        add(Setting.bool("cull", "Skip hidden entities", true).hint("Mobs and items fully behind solid blocks are not drawn. Players, glowing and named mobs always are. Turn off if something pops in late."));
    }

    @Override void tick() {
        Hook.glErrSkip = enabled() && bool("gpuChecks");
        Culling.tick(enabled() && bool("cull"));
    }
}
