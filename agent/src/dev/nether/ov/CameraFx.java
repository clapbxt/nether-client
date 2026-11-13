package dev.nether.ov;

/**
 * Camera comfort through the game's own options (damage tilt, FOV effects, distortion, view bobbing, glint). Values are
 * written only while you play and put back when a screen opens, on pause and on exit, so options.txt never sees them
 * (same as Zoom). A row whose option this version does not have is hidden.
 */
final class CameraFx extends Module {
    /** {setting key, option label key, default value, "d" double | "b" boolean} */
    private static final String[][] OPTS = {
        {"tilt", "options.damageTiltStrength", "d"}, {"fovfx", "options.fovEffectScale", "d"}, {"distort", "options.screenEffectScale", "d"},
        {"bob", "options.viewBobbing", "b"}, {"glint", "options.glintStrength", "d"}};
    private final Object[] opt = new Object[OPTS.length];
    private final Object[] orig = new Object[OPTS.length];
    private boolean bound;

    CameraFx() {
        super("camera", "Camera Comfort", "Visual", "Less camera shake and motion: hit tilt, FOV change, nausea and bobbing. Your own settings come back when you pause or leave.", false, false, 0, 0);
        add(Setting.slider("tilt", "Damage tilt", 0, 1, 0.05, 0.25).hint("How far the view tilts when you take damage (1 = vanilla, 0 = none)."));
        add(Setting.slider("fovfx", "FOV effects", 0, 1, 0.05, 0.4).hint("How much sprinting and speed effects change the field of view (0 = fixed)."));
        add(Setting.slider("distort", "Distortion effects", 0, 1, 0.05, 0.3).hint("Strength of the nausea and portal wobble."));
        add(Setting.bool("bob", "View bobbing", true).hint("Off stops the sway while walking; on leaves your own setting."));
        add(Setting.slider("glint", "Enchant glint", 0, 1, 0.05, 0.5).hint("Strength of the enchantment shimmer."));
        needs(2);
    }

    @Override void tick() {
        if (!bound && Game.fabric() && Game.me() != null && due(500)) {
            bound = Data.optionByKey("options.viewBobbing") != null || Data.optionByKey("options.fovEffectScale") != null || Data.optionByKey("options.damageTiltStrength") != null;
            if (bound) {
                for (int i = 0; i < OPTS.length; i++) {
                    opt[i] = Data.optionByKey(OPTS[i][1]);
                    if (opt[i] == null) drop(OPTS[i][0]);
                }
            }
        }
        if (!bound) return;
        if (enabled() && Game.playing()) {
            for (int i = 0; i < OPTS.length; i++) {
                if (opt[i] == null) continue;
                Object cur = Data.optValue(opt[i]);
                if (cur == null) continue;
                if (orig[i] == null) orig[i] = cur;
                Object want = OPTS[i][2].equals("b") ? (Object) (bool("bob") ? orig[i] : Boolean.FALSE) : (Object) num(OPTS[i][0]);
                if (!want.equals(cur)) Data.optSet(opt[i], want);
            }
        } else restore();
    }

    void restore() {
        for (int i = 0; i < OPTS.length; i++) {
            if (orig[i] != null) { Data.optSet(opt[i], orig[i]); orig[i] = null; }
        }
    }
}
