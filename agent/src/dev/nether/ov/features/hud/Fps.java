package dev.nether.ov.features.hud;

import dev.nether.ov.Overlay;
import dev.nether.ov.framework.FrameStats;
import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Fps extends RowsModule {
    public Fps() {
        super("fps", "FPS", "HUD", "Frames per second, updated every second.", true, 0.012, 0.02);
        add(Setting.header("Display"));
        add(Setting.bool("ms", "Show frame time", false));
        add(Setting.bool("tint", "Color by speed", false).hint("Green at 60 FPS and up, amber from 30, red below."));
        add(Setting.bool("stats", "Show average, minimum, 1% low", false).hint("Over the last 10 seconds. The 1% low is the speed of your worst frames."));
    }
    @Override public void tick() { if (live() && bool("stats")) FrameStats.want = true; }
    private String c(float f, String v) { return bool("tint") ? tint(f >= 60, f >= 30, v) : v; }
    @Override public String[][] rows() {
        int f = Overlay.fps;
        String v = bool("ms") && f > 0 ? f + "  " + String.format(Locale.ROOT, "%.1f ms", 1000.0 / f) : String.valueOf(f);
        if (!bool("stats")) return new String[][] {{"FPS", c(f, v)}};
        FrameStats.refresh(10, 1);
        if (FrameStats.avg <= 0) return new String[][] {{"FPS", c(f, v)}};
        return new String[][] {{"FPS", c(f, v)}, {"AVG", c(FrameStats.avg, String.valueOf(Math.round(FrameStats.avg)))},
            {"MIN", c(FrameStats.min, String.valueOf(Math.round(FrameStats.min)))}, {"1% LOW", c(FrameStats.low1, String.valueOf(Math.round(FrameStats.low1)))}};
    }
}
