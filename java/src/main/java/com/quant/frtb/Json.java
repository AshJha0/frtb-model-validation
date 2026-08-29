package com.quant.frtb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser, sufficient for the bundled data
 * files ({@code portfolio.json}, {@code sbm_params.json}, {@code nmrf.json})
 * and the cross-language golden file.
 *
 * <p>Objects parse to {@link LinkedHashMap} (key order preserved, which the
 * engine relies on for reproducible iteration), arrays to {@link ArrayList},
 * numbers to {@link Double}, plus {@link String}, {@link Boolean} and
 * {@code null}. Malformed input raises {@link IllegalArgumentException}.
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Parse a complete JSON document; trailing non-whitespace is an error. */
    static Object parse(String text) {
        Json p = new Json(text);
        Object v = p.parseValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Json: trailing characters at offset " + p.pos);
        }
        return v;
    }

    // ------------------------------------------------------------ helpers --

    /** Narrow a parsed value to an object, with a context for error messages. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object v, String ctx) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Json: expected object at " + ctx);
        }
        return (Map<String, Object>) v; // parser only ever builds Map<String,Object>
    }

    /** Narrow a parsed value to an array, with a context for error messages. */
    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object v, String ctx) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("Json: expected array at " + ctx);
        }
        return (List<Object>) v;
    }

    /** Narrow a parsed value to a number. */
    static double asNumber(Object v, String ctx) {
        if (!(v instanceof Double d)) {
            throw new IllegalArgumentException("Json: expected number at " + ctx);
        }
        return d;
    }

    /** Narrow a parsed value to a string. */
    static String asString(Object v, String ctx) {
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Json: expected string at " + ctx);
        }
        return s;
    }

    /** Fetch a required key from an object ({@code ValueError} analogue). */
    static Object require(Map<String, Object> obj, String key, String ctx) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException("missing '" + key + "' in " + ctx);
        }
        return obj.get(key);
    }

    // ------------------------------------------------------------- parser --

    private Object parseValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw new IllegalArgumentException("Json: unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArrayValue();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> obj = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            if (peek() != ':') {
                throw new IllegalArgumentException("Json: expected ':' at offset " + pos);
            }
            pos++;
            obj.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return obj;
            } else {
                throw new IllegalArgumentException("Json: expected ',' or '}' at offset " + pos);
            }
        }
    }

    private List<Object> parseArrayValue() {
        List<Object> arr = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return arr;
        }
        while (true) {
            arr.add(parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return arr;
            } else {
                throw new IllegalArgumentException("Json: expected ',' or ']' at offset " + pos);
            }
        }
    }

    private String parseString() {
        if (peek() != '"') {
            throw new IllegalArgumentException("Json: expected string at offset " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("Json: unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = text.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Json: bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String token = text.substring(start, pos);
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Json: bad number '" + token + "' at offset " + start);
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw new IllegalArgumentException("Json: bad literal at offset " + pos);
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("Json: unexpected end of input");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
