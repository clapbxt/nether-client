package dev.nether.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Legacy Fabric 1.8.9 / 1.12.2: the game runs remapped to Legacy intermediary (ave -> net/minecraft/class_1600), so the
 * agent's notch names go through this table, read from net.legacyfabric:intermediary:<version> (mappings/mappings.tiny,
 * tiny v1 official -> intermediary) on the class path. Loaded lazily by whichever side asks first; empty (off) anywhere
 * else. On 1.12.2 the code's names are SRG: Names maps them to notch first, then this table (Game lmode 4).
 */
public final class Tiny {
    private Tiny() { }

    /** CLS official -> intermediary class, REV the reverse; MEM "owner.name" (fields) / "owner.name(desc)" (methods) -> intermediary;
     *  ND "name(desc)" -> the method's intermediary name ("" when several), for overrides the tiny lists on the root only; OFF intermediary member -> official name. */
    private static final Map<String, String> CLS = new HashMap<>(), REV = new HashMap<>(), MEM = new HashMap<>(), ND = new HashMap<>(), OFF = new HashMap<>();
    private static boolean tried, on;

    /** The Minecraft version the table is for ("1.8.9", "1.12.2"), "" when off. */
    public static String ver = "";

    /** True when the game runs on Legacy Fabric (the table is on the class path). */
    public static synchronized boolean on() {
        if (!tried) { tried = true; load(); }
        return on;
    }

    private static void load() {
        for (String p : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("net/legacyfabric/intermediary/([^/]+)/").matcher(p.replace('\\', '/'));
            if (!vm.find()) continue;
            try (ZipFile z = new ZipFile(p)) {
                ZipEntry e = z.getEntry("mappings/mappings.tiny");
                if (e == null) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8));
                for (String l; (l = r.readLine()) != null; ) {
                    String[] a = l.split("\t");
                    if (a[0].equals("CLASS") && a.length >= 3) { CLS.put(a[1], a[2]); REV.put(a[2], a[1]); }
                    else if (a[0].equals("FIELD") && a.length >= 5) { MEM.put(a[1] + "." + a[3], a[4]); OFF.put(a[4], a[3]); }
                    else if (a[0].equals("METHOD") && a.length >= 5) {
                        MEM.put(a[1] + "." + a[3] + a[2], a[4]);
                        OFF.put(a[4], a[3]);
                        String k = a[3] + a[2], had = ND.get(k);
                        ND.put(k, had == null || had.equals(a[4]) ? a[4] : "");
                    }
                }
                on = !CLS.isEmpty();
                if (on) ver = vm.group(1);
                System.out.println("[nether-agent] Legacy Fabric " + ver + " names: " + CLS.size() + " classes, " + MEM.size() + " members");
                return;
            } catch (Throwable t) {
                System.out.println("[nether-agent] Legacy Fabric names unreadable: " + t);
            }
        }
    }

    /** Intermediary internal name of an official class (dots or slashes in, slashes out); unknown names pass through. */
    public static String cls(String official) {
        String c = CLS.get(official.replace('.', '/'));
        return c == null ? official : c;
    }

    /** Official name of a runtime class (dots or slashes); null when it is not a mapped game class. */
    public static String rev(String intermediary) { return REV.get(intermediary.replace('.', '/')); }

    /** A descriptor with every official class mapped. */
    public static String desc(String d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            b.append(c);
            if (c != 'L') continue;
            int e = d.indexOf(';', i);
            b.append(cls(d.substring(i + 1, e))).append(';');
            i = e;
        }
        return b.toString();
    }

    /** Intermediary name of official method owner.name(desc) (an override listed only on its root: by name and descriptor). */
    public static String method(String owner, String name, String desc) {
        String m = MEM.get(owner + "." + name + desc);
        if (m == null) m = ND.get(name + desc);
        return m == null || m.isEmpty() ? name : m;
    }

    /** Intermediary name of official field owner.name. */
    public static String field(String owner, String name) {
        String f = MEM.get(owner + "." + name);
        return f == null ? name : f;
    }

    /** Official name of an intermediary member (method_ / field_); other names pass through. */
    public static String off(String member) {
        String o = OFF.get(member);
        return o == null ? member : o;
    }
}
