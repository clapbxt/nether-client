package dev.nether.ov;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A native "open files" dialog from inside the game, for the wardrobe's UPLOAD. LWJGL 3's tinyfd (shipped with the game
 * since 1.13) when the game has it, else AWT's FileDialog (1.8.9: LWJGL 2, AWT not headless). Blocks: call it off the
 * render thread. Returns the picked paths (empty = cancelled), or null when no dialog could be opened.
 */
final class FilePick {
    private FilePick() {}

    static List<String> pick(String title) {
        String home = System.getProperty("user.home", "");
        File dl = new File(home, "Downloads");
        String start = (dl.isDirectory() ? dl.getPath() : home) + File.separator;
        try {
            ClassLoader l = Game.loader();
            Class<?> tfd = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs", true, l);
            Class<?> pb = Class.forName("org.lwjgl.PointerBuffer", false, l);
            Object r = tfd.getMethod("tinyfd_openFileDialog", CharSequence.class, CharSequence.class, pb, CharSequence.class, boolean.class)
                .invoke(null, title, start, null, null, true);
            List<String> out = new ArrayList<>();
            if (r instanceof String) for (String p : ((String) r).split("\\|")) if (!p.isEmpty()) out.add(p);
            return out;
        } catch (Throwable t) {
            System.out.println("[nether-agent] tinyfd file dialog: " + t);
        }
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return null;
            java.awt.FileDialog d = new java.awt.FileDialog((java.awt.Frame) null, title, java.awt.FileDialog.LOAD);
            d.setDirectory(start);
            d.setMultipleMode(true);
            d.setAlwaysOnTop(true);
            d.setVisible(true);
            List<String> out = new ArrayList<>();
            for (File f : d.getFiles()) out.add(f.getPath());
            d.dispose();
            return out;
        } catch (Throwable t) {
            System.out.println("[nether-agent] AWT file dialog: " + t);
            return null;
        }
    }
}
