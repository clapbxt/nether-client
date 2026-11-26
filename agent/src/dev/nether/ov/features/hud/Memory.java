package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Memory extends RowsModule {
    public Memory() {
        super("memory", "Memory", "HUD", "Java heap in use.", false, 0.87, 0.085);
        add(Setting.choice("mode", "Display", "Used / Max", "Used / Max", "Percent"));
    }
    @Override public String[][] rows() {
        Runtime r = Runtime.getRuntime();
        double used = (r.totalMemory() - r.freeMemory()) / 1073741824.0, max = r.maxMemory() / 1073741824.0;
        String v = str("mode").equals("Percent") ? Math.round(used / max * 100) + "%" : String.format(Locale.ROOT, "%.1f / %.1f GB", used, max);
        return new String[][] {{"MEM", v}};
    }
}
