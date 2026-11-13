package dev.nether.ov;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal JSON: objects are LinkedHashMap, arrays ArrayList, numbers Double, plus String/Boolean/null. */
final class Json {
    private Json() {}

    static Object parse(String s) {
        P p = new P(s);
        Object o = p.val();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return o;
    }

    private static final class P {
        final String s;
        int i, depth;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        void expect(char c) {
            ws();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
            i++;
        }

        Object val() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            if ((c == '{' || c == '[') && depth > 64) throw new IllegalArgumentException("nested too deep at " + i);
            if (c == '{' || c == '[') {
                depth++;
                try { return c == '{' ? obj() : arr(); } finally { depth--; }
            }
            return scalar(c);
        }

        Object obj() {
            {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    expect(':');
                    m.put(k, val());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
        }

        Object arr() {
            {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(val());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
        }

        Object scalar(char c) {
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad token at " + i);
            return Double.valueOf(s.substring(st, i));
        }

        String str() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }

    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        w(b, o, 0);
        return b.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder b, Object o, int d) {
        if (o == null) b.append("null");
        else if (o instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                indent(b, d + 1);
                q(b, e.getKey());
                b.append(": ");
                w(b, e.getValue(), d + 1);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            indent(b, d);
            b.append('}');
        } else if (o instanceof List<?>) {
            List<?> l = (List<?>) o;
            b.append('[');
            for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(", "); w(b, l.get(i), d + 1); }
            b.append(']');
        } else if (o instanceof String) q(b, (String) o);
        else if (o instanceof Number) {
            Number n = (Number) o;
            double v = n.doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) b.append("null"); // not JSON: the reader falls back to the default
            else if (v == Math.rint(v) && Math.abs(v) < 1e15) b.append((long) v);
            else {
                String t = String.format(Locale.ROOT, "%.4f", v);
                t = t.replaceAll("0+$", "");
                b.append(t.endsWith(".") ? t + "0" : t);
            }
        } else b.append(o);
    }

    private static void indent(StringBuilder b, int d) { for (int i = 0; i < d; i++) b.append("  "); }

    private static void q(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }
}
