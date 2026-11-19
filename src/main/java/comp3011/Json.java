package comp3011;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser: parses a JSON document into a tree of
 * {@link Map}, {@link List}, {@link String}, {@link Number}, {@link Boolean}
 * and {@code null}. Intentionally small - only what the subtitle service needs
 * to read (OpenAI verbose_json) and to navigate its own responses.
 *
 * <p>Not a general-purpose JSON library: no streaming, no pretty printing, no
 * number-precision preservation beyond {@link Double}/{@link Long}. That is
 * enough for the subtitle endpoints.</p>
 */
public final class Json {
    private Json() {
    }

    /**
     * Parses a JSON document string into an object tree.
     *
     * @param json the JSON text
     * @return the parsed tree, or {@code null} if the input is blank/unparseable
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Parser p = new Parser(json);
        Object value = p.parseValue();
        p.skipWhitespace();
        return value;
    }

    /** {@code obj.get("segments")} style navigation, safely returning null. */
    @SuppressWarnings("unchecked")
    public static Object get(Object root, String key) {
        if (root instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, ?>) map : null;
    }

    public static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    public static double asNumber(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWhitespace();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // '{'
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (i < s.length() && s.charAt(i) == ':') i++;
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // '['
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return list;
        }

        String parseString() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            return null;
        }

        Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            return null;
        }

        Number parseNumber() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String token = s.substring(start, i);
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
