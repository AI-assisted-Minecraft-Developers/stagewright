package net.magicterra.stagewright.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.json.JsonObject;
import net.magicterra.stagewright.engine.json.JsonValue;

/**
 * Reads one JSON value into plain Java values.
 *
 * <p>An adapter over the vendored minimal-json, not a parser: the places a hand-written JSON parser
 * goes wrong — escapes, surrogate pairs, the number grammar — are exactly the ones that fail quietly.
 * See {@code json/VENDORED.md} for why the library is copied in rather than depended on.
 *
 * <p>Objects become {@link LinkedHashMap}, arrays {@link List}, numbers {@link Double}, and the rest
 * map to their obvious Java types. Insertion order is preserved.
 */
public final class Json {

    private Json() {}

    /** Parse one JSON value. Throws {@link IllegalArgumentException} on anything malformed. */
    public static Object parse(String text) {
        try {
            return toJava(net.magicterra.stagewright.engine.json.Json.parse(text));
        } catch (RuntimeException e) {
            // The library's parse failures are unchecked and its own type. Callers upstream catch
            // IllegalArgumentException and drop the line; that contract must not change just
            // because the parser behind it did.
            throw new IllegalArgumentException("malformed JSON: " + e.getMessage(), e);
        }
    }

    private static Object toJava(JsonValue value) {
        if (value == null || value.isNull()) return null;
        if (value.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            JsonObject object = value.asObject();
            for (JsonObject.Member member : object) {
                out.put(member.getName(), toJava(member.getValue()));
            }
            return out;
        }
        if (value.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonValue item : value.asArray()) out.add(toJava(item));
            return out;
        }
        if (value.isString()) return value.asString();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asDouble();
        return value.toString();
    }
}
