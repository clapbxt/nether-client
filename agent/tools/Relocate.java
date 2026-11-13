import java.io.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Build tool: copies org.objectweb.asm.* from a jar into a directory as dev.nether.shaded.asm.* (so we never clash with Fabric's ASM). */
public class Relocate {
    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        Remapper rm = new Remapper() {
            @Override public String map(String n) { return n.startsWith("org/objectweb/asm/") ? "dev/nether/shaded/asm/" + n.substring(18) : n; }
        };
        for (int i = 1; i < a.length; i++) {
            try (ZipFile z = new ZipFile(a[i])) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                    ZipEntry en = e.nextElement();
                    String n = en.getName();
                    if (!n.endsWith(".class") || !n.startsWith("org/objectweb/asm/") || n.endsWith("module-info.class")) continue;
                    ClassReader cr = new ClassReader(z.getInputStream(en).readAllBytes());
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, rm), 0);
                    Path p = out.resolve(rm.map(n.substring(0, n.length() - 6)) + ".class");
                    Files.createDirectories(p.getParent());
                    Files.write(p, cw.toByteArray());
                }
            }
        }
    }
}
