package dev.nether.ov;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Start / stop / lap / reset stopwatch on keys. */
final class Stopwatch extends RowsModule {
    private boolean running;
    private long startNs, accNs;
    private final List<Long> laps = new ArrayList<>();
    private int dropped; // laps trimmed off the front, so numbering keeps counting

    Stopwatch() {
        super("stopwatch", "Stopwatch", "HUD", "A stopwatch with lap times, driven by keys.", false, 0.15, 0.6);
        add(Setting.key("toggleKey", "Start / stop key", 297).hint("Default F8."));
        add(Setting.key("lapKey", "Lap key", 298).hint("Default F9. Shows the last three laps."));
        add(Setting.key("resetKey", "Reset key", 296).hint("Default F7. Clears the time and the laps."));
        add(Setting.bool("hundredths", "Show hundredths", true));
    }

    private long ns() { return accNs + (running ? System.nanoTime() - startNs : 0); }

    /** Key press from {@link Modules#onKey}; true = used (the game does not see it). */
    boolean key(int k) {
        if (!enabled()) return false;
        if (k == (int) num("toggleKey")) {
            if (running) { accNs = ns(); running = false; } else { startNs = System.nanoTime(); running = true; }
            return true;
        }
        if (k == (int) num("lapKey")) { if (running || accNs > 0) { laps.add(ns()); if (laps.size() > 20) { laps.remove(0); dropped++; } } return true; }
        if (k == (int) num("resetKey")) { running = false; accNs = 0; laps.clear(); dropped = 0; return true; }
        return false;
    }

    private String fmt(long ns) {
        long cs = ns / 10_000_000L, s = cs / 100, m = s / 60;
        return bool("hundredths") ? String.format(Locale.ROOT, "%d:%02d.%02d", m, s % 60, cs % 100) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    @Override String[][] rows() {
        List<String[]> r = new ArrayList<>(4);
        r.add(new String[] {running ? "TIME" : accNs > 0 ? "STOPPED" : "READY", (running ? "" : accNs > 0 ? "#FFB020|" : "") + fmt(ns())});
        for (int i = Math.max(0, laps.size() - 3); i < laps.size(); i++) r.add(new String[] {"LAP " + (dropped + i + 1), fmt(laps.get(i) - (i > 0 ? laps.get(i - 1) : 0))});
        return r.toArray(new String[0][]);
    }
}
