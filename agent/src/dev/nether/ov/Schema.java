package dev.nether.ov;

/** Build-time dump of every module and setting as JSON (agent/build/schema.json), so the launcher's Client page
 *  edits exactly the settings the in-game menu shows. */
public final class Schema {
    private Schema() {}

    private static String q(String s) { return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private static String val(Object o) { return o instanceof String ? q((String) o) : String.valueOf(o); }

    public static void main(String[] a) {
        StringBuilder b = new StringBuilder("{\"modules\":[");
        boolean first = true;
        for (Module m : Modules.all) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(q(m.id)).append(",\"name\":").append(q(m.name)).append(",\"cat\":").append(q(m.cat))
                .append(",\"group\":").append(q(m.group())).append(",\"desc\":").append(q(m.desc)).append(",\"hud\":").append(m.hud)
                .append(",\"icon\":").append(q(m.icon()[0])).append(",\"on\":").append(m.defOn).append(",\"x\":").append(m.dx).append(",\"y\":").append(m.dy)
                .append(",\"safety\":").append(q(Module.SAFETY_ID[m.safety()])).append(",\"home\":").append(m.hud ? q(m.home()) : "null").append(",\"settings\":[");
            for (int i = 0; i < m.settings.size(); i++) {
                Setting s = m.settings.get(i);
                if (i > 0) b.append(',');
                b.append("{\"t\":").append(s.t).append(",\"key\":").append(q(s.key)).append(",\"label\":").append(q(s.label))
                    .append(",\"min\":").append(s.min).append(",\"max\":").append(s.max).append(",\"step\":").append(s.step)
                    .append(",\"def\":").append(val(s.def));
                if (s.hint != null) b.append(",\"hint\":").append(q(s.hint));
                b.append(",\"opts\":[");
                if (s.opts != null) for (int k = 0; k < s.opts.length; k++) b.append(k > 0 ? "," : "").append(q(s.opts[k]));
                b.append("]}");
            }
            b.append("]}");
        }
        b.append("]}");
        System.out.println(b);
        System.exit(0);
    }
}
