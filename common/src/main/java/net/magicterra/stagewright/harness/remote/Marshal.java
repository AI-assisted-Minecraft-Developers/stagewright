package net.magicterra.stagewright.harness.remote;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Between the objects the game works with and the JSON an out-of-process runner can hold.
 *
 * <p>Three kinds of value cross, and only three. Plain data — null, booleans, numbers, strings,
 * lists, maps — goes as itself. A thing a registry names (a block) goes as {@code {"$id":kind,
 * "id":…}} and is looked up again on the way in, so a script can write {@code block('stone')} and
 * the game receives the block. Everything else goes as {@code {"$handle":n,"type":…}}: the runner
 * keeps the number, the game keeps the object, and a later call names the number.
 *
 * <p>Objects never go by value, even a record that would fit. A record has methods that are not its
 * components, and a script that calls one expects the object's answer, not a lookup in a copy.
 */
public final class Marshal {
    public static final String HANDLE = "$handle";
    public static final String ID = "$id";
    public static final String MAP = "$map";
    public static final String NULL = "$null";

    /** What the game's registries contribute, so this class needs none of them loaded to be tested. */
    public interface Registries {
        /** {@code {kind, id}} when {@code o} is something a registry names, else null. */
        String[] nameOf(Object o);

        /** The registered thing, or an {@link IllegalArgumentException} saying what is wrong with the id. */
        Object find(String kind, String id);
    }

    private final Handles handles;
    private final Registries registries;

    public Marshal(Handles handles, Registries registries) {
        this.handles = handles;
        this.registries = registries;
    }

    /** A value the game produced, in the form the wire carries. */
    public JsonElement encode(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement j) return j;
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Float || value instanceof Double) return number(((Number) value).doubleValue());
        if (value instanceof Number n) return new JsonPrimitive(n.longValue());
        if (value instanceof CharSequence || value instanceof Character) return new JsonPrimitive(value.toString());
        String[] named = registries.nameOf(value);
        if (named != null) {
            JsonObject out = new JsonObject();
            out.addProperty(ID, named[0]);
            out.addProperty("id", named[1]);
            return out;
        }
        if (value instanceof Map<?, ?> m) return map(m);
        if (value instanceof Collection<?> c) {
            JsonArray out = new JsonArray();
            for (Object item : c) out.add(encode(item));
            return out;
        }
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            JsonArray out = new JsonArray();
            for (Object item : (Object[]) value) out.add(encode(item));
            return out;
        }
        if (value.getClass().isArray()) {
            JsonArray out = new JsonArray();
            for (int i = 0; i < Array.getLength(value); i++) out.add(encode(Array.get(value, i)));
            return out;
        }
        JsonObject out = new JsonObject();
        out.addProperty(HANDLE, handles.numberFor(value));
        out.addProperty("type", typeName(value));
        return out;
    }

    private JsonElement map(Map<?, ?> m) {
        JsonObject out = new JsonObject();
        boolean marked = false;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            marked |= key.startsWith("$");
            out.add(key, encode(e.getValue()));
        }
        if (!marked) return out;
        JsonObject wrapped = new JsonObject();
        wrapped.add(MAP, out);
        return wrapped;
    }

    private static JsonElement number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // JSON has no spelling for these, and a string would arrive as text a script compares
            // against a number without complaint.
            throw new IllegalStateException("the game answered " + d + ", which cannot cross as a number");
        }
        return new JsonPrimitive(d);
    }

    /** What a script is told the object is, for messages: the simple name of its class. */
    static String typeName(Object value) {
        Class<?> c = value.getClass();
        String simple = c.getSimpleName();
        return simple.isEmpty() ? c.getName() : simple;
    }

    /**
     * A value the runner sent, as the plain Java the game's methods take: whole numbers as longs and
     * the rest as doubles (a method wanting an {@code int} is converted when it is chosen), lists,
     * maps, and the objects and registry entries the markers name.
     */
    public Object decode(JsonElement wire) {
        if (wire == null || wire.isJsonNull()) return null;
        if (wire.isJsonPrimitive()) {
            JsonPrimitive p = wire.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return p.getAsString();
            String text = p.getAsString();
            return text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0
                    ? (Object) p.getAsLong() : (Object) p.getAsDouble();
        }
        if (wire.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement item : wire.getAsJsonArray()) out.add(decode(item));
            return out;
        }
        JsonObject o = wire.getAsJsonObject();
        if (o.has(NULL)) return null;
        if (o.has(HANDLE)) return handles.get(o.get(HANDLE).getAsInt());
        if (o.has(ID)) return registries.find(o.get(ID).getAsString(), o.get("id").getAsString());
        JsonObject source = o.has(MAP) ? o.getAsJsonObject(MAP) : o;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : source.entrySet()) out.put(e.getKey(), decode(e.getValue()));
        return out;
    }
}
