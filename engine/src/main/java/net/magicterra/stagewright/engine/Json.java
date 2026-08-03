package net.magicterra.stagewright.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recursive-descent JSON reader, sized for the results stream and nothing else.
 *
 * <p>Hand-written rather than pulled from a library because this class decides whether a build
 * passes. Gradle exposes no JSON parser of its own, so the alternatives were a dependency that must
 * resolve identically on Gradle 8.9 and 9.3 across every consumer's repository setup, or Groovy's
 * {@code JsonSlurper}, which ties the verdict to whichever Groovy the running Gradle bundles. Both
 * put a moving part underneath the one component that must never be the reason a build is wrong.
 *
 * <p>Objects become {@link LinkedHashMap}, arrays {@link List}, numbers {@link Double}, and the rest
 * map to their obvious Java types. No streaming, no pretty printing — the input is one small object
 * per line.
 */
public final class Json {

    private final String src;
    private int at;

    private Json(String src) {
        this.src = src;
    }

    /** Parse one JSON value. Throws {@link IllegalArgumentException} on anything malformed. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.at != p.src.length()) {
            throw new IllegalArgumentException("trailing characters at offset " + p.at);
        }
        return v;
    }

    private Object value() {
        if (at >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        char c = src.charAt(at);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++;                                       // '{'
        ws();
        if (peek() == '}') { at++; return out; }
        while (true) {
            ws();
            String key = string();
            ws();
            if (peek() != ':') throw new IllegalArgumentException("expected ':' at offset " + at);
            at++;
            ws();
            out.put(key, value());
            ws();
            char c = peek();
            at++;
            if (c == '}') return out;
            if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at offset " + (at - 1));
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        at++;                                       // '['
        ws();
        if (peek() == ']') { at++; return out; }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = peek();
            at++;
            if (c == ']') return out;
            if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at offset " + (at - 1));
        }
    }

    private String string() {
        if (peek() != '"') throw new IllegalArgumentException("expected a string at offset " + at);
        at++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (at >= src.length()) throw new IllegalArgumentException("unterminated string");
            char c = src.charAt(at++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char esc = src.charAt(at++);
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
                    sb.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + esc);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < src.length() && "+-0123456789.eE".indexOf(src.charAt(at)) >= 0) at++;
        if (start == at) throw new IllegalArgumentException("expected a value at offset " + at);
        return Double.valueOf(src.substring(start, at));
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, at)) {
            throw new IllegalArgumentException("expected '" + literal + "' at offset " + at);
        }
        at += literal.length();
    }

    private char peek() {
        if (at >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        return src.charAt(at);
    }

    private void ws() {
        while (at < src.length() && Character.isWhitespace(src.charAt(at))) at++;
    }
}
