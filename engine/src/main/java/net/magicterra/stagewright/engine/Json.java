package net.magicterra.stagewright.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.json.JsonObject;
import net.magicterra.stagewright.engine.json.JsonValue;

/**
 * Reads one results line into plain Java values.
 *
 * <p>An adapter over the vendored minimal-json, not a parser. This class used to be a hand-written
 * recursive-descent reader, which was a mistake worth naming: it is the component that decides
 * whether a build passes, and the places a JSON parser goes wrong — escapes, surrogate pairs, the
 * number grammar — are exactly the ones that fail quietly and produce a confident wrong verdict.
 * See {@code json/VENDORED.md} for why the library is copied in rather than depended on.
 *
 * <p>Objects become {@link LinkedHashMap}, arrays {@link List}, numbers {@link Double}, and the rest
 * map to their obvious Java types — the shapes {@link Verdict} reads. Insertion order is preserved,
 * because a report that lists a run's scenes in a different order than the file did is harder to
 * check against the file.
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
