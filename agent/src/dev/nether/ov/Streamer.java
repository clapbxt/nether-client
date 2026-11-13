package dev.nether.ov;

/** One switch (and key) that hides what identifies you or your location from the screen while you stream. */
final class Streamer extends Module {
    Streamer() {
        super("streamer", "Streamer Mode", "Utility", "Hides your server address, coordinates, waypoints and what you are listening to, so nothing leaks on stream.", false, false, 0, 0);
        add(Setting.key("key", "Toggle key", -1).hint("Switches streamer mode on and off in the world (unbound by default)."));
        add(Setting.header("Hide"));
        add(Setting.bool("address", "Server address", true).hint("The Server Address module shows 'hidden'."));
        add(Setting.bool("coords", "Coordinates", true).hint("The Coordinates module shows 'hidden'."));
        add(Setting.bool("waypoints", "Waypoints", true).hint("Waypoint markers and compass ticks are not drawn."));
        add(Setting.bool("media", "Media title and artist", true).hint("The Media Player module is not drawn."));
        add(Setting.bool("lyrics", "Lyrics", true).hint("Lyrics are not drawn."));
    }

    /** True when streamer mode is on and hides this item ("address", "coords", "waypoints", "media", "lyrics"). */
    static boolean hides(String what) {
        Streamer s = Modules.STREAMER;
        return s.enabled() && s.bool(what);
    }

    /** Toggle key from {@link Modules#onKey}; true = used. */
    boolean key(int k) {
        int mine = (int) num("key");
        if (mine < 0 || k != mine || !injected() || locked()) return false;
        Config.set(id, "enabled", !wantedOn());
        Toast.show("Streamer mode " + (wantedOn() ? "on" : "off"));
        return true;
    }
}
