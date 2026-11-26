package dev.nether.ov.features.hud;

import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;

import static dev.nether.ov.framework.Modules.*;

public final class Cps extends RowsModule {
    public Cps() {
        super("cps", "CPS", "HUD", "Left and right clicks per second.", true, 0.012, 0.105);
        add(Setting.choice("show", "Show", "Split", "Split", "Combined", "Left", "Right").hint("Split = left | right, Combined = both added, or one button only."));
        add(Setting.bool("only", "Only while clicking", false).hint("The box is hidden until you click."));
    }
    @Override public boolean visible() { return !bool("only") || Input.cps(0) + Input.cps(1) > 0 || sampling(this); }
    @Override public String[][] rows() {
        int l = Input.cps(0), r = Input.cps(1);
        switch (str("show")) {
            case "Combined": return new String[][] {{"CPS", String.valueOf(l + r)}};
            case "Left": return new String[][] {{"CPS L", String.valueOf(l)}};
            case "Right": return new String[][] {{"CPS R", String.valueOf(r)}};
            default: return new String[][] {{"CPS", l + " | " + r}};
        }
    }
}
