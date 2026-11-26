package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import java.time.LocalTime;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Clock extends RowsModule {
    public Clock() {
        super("clock", "Clock", "HUD", "Local time of day.", false, 0.92, 0.02);
        add(Setting.header("Format"));
        add(Setting.bool("h24", "24-hour clock", true));
        add(Setting.bool("seconds", "Show seconds", false));
        add(Setting.bool("date", "Show date", false));
    }
    @Override public String[][] rows() {
        LocalTime t = LocalTime.now();
        boolean h24 = bool("h24"), sec = bool("seconds");
        int hh = h24 ? t.getHour() : (t.getHour() % 12 == 0 ? 12 : t.getHour() % 12);
        String s = String.format(Locale.ROOT, h24 ? "%02d:%02d" : "%d:%02d", hh, t.getMinute()) + (sec ? String.format(Locale.ROOT, ":%02d", t.getSecond()) : "") + (h24 ? "" : (t.getHour() < 12 ? " AM" : " PM"));
        if (bool("date")) {
            java.time.LocalDate d = java.time.LocalDate.now();
            String day = d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
            String mon = d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
            return new String[][] {{"TIME", s}, {"DATE", day + " " + d.getDayOfMonth() + " " + mon}};
        }
        return new String[][] {{"TIME", s}};
    }
}
