import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mac 安裝器用的最小 JSON：物件→LinkedHashMap、陣列→ArrayList、數字→Long／Double。不依賴遊戲 jar（卸載不能靠它）。 */
final class Json {
    private static final int MAX_DEPTH = 64;
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.err("trailing data");
        return v;
    }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON " + m + " at " + i); }

    private void ws() { while (i < s.length() && " \t\r\n\uFEFF".indexOf(s.charAt(i)) >= 0) i++; }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected key");
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) throw err("duplicate key " + k);
            m.put(k, value(depth + 1));
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            expect(']');
            return a;
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw err("expected " + c);
        i++;
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw err("control character in string");
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw err("bad unicode escape");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException x) { throw err("bad unicode escape"); }
                    i += 4;
                }
                default -> throw err("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw err("unexpected character");
        try {
            return n.matches("-?\\d+") ? (Object) Long.valueOf(n) : (Object) Double.valueOf(n);
        } catch (NumberFormatException x) {
            throw err("bad number");
        }
    }

    /** 兩格縮排、LF，結尾換行。 */
    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, "");
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, String ind) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(ind).append("  ");
                quote(b, (String) e.getKey());
                b.append(": ");
                write(b, e.getValue(), ind + "  ");
                b.append(++n < m.size() ? ",\n" : "\n");
            }
            b.append(ind).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                b.append(ind).append("  ");
                write(b, l.get(k), ind + "  ");
                b.append(k + 1 < l.size() ? ",\n" : "\n");
            }
            b.append(ind).append(']');
        } else throw new IllegalArgumentException("unsupported JSON value " + v.getClass());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    // ---- 取值小工具（型別不符一律丟 IllegalArgumentException，呼叫端轉成退出碼）
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v, String what) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException(what + " is not an object");
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object v, String what) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException(what + " is not an array");
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof String s) return s;
        throw new IllegalArgumentException(key + " is missing or not a string");
    }

    static String str(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v instanceof String s ? s : dflt;
    }
}
