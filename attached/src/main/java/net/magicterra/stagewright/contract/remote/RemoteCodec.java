package net.magicterra.stagewright.contract.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.latvian.mods.rhino.Callable;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.Wrapper;
import net.magicterra.stagewright.contract.SceneFailure;

/**
 * What a script's values look like on the wire, and what the wire's look like to a script.
 *
 * <p>The wire carries plain JVM types — maps, lists, strings, numbers, booleans, null — which is
 * what a driver call already is in both directions. Two kinds of value do not survive that and get a
 * marker ({@link Wire#HANDLE}, {@link Wire#ID}): an object that lives in the game, and a thing a
 * registry names. Anything else a script could hold has no meaning in the game and is refused here,
 * by name, rather than turned into a string that happens to go through.
 */
final class RemoteCodec {

    /** A script's value as the wire carries it. */
    Object encode(Object value, RemoteSession session) {
        if (value == null || Undefined.isUndefined(value)) return null;
        if (value instanceof Wrapper w) return encode(w.unwrap(), session);
        if (value instanceof CharSequence || value.getClass().getSimpleName().equals("ConsString")) {
            return value.toString();
        }
        if (value instanceof Boolean || value instanceof String) return value;
        if (value instanceof Number n) return number(n);
        if (value instanceof RemoteRef ref) return handle(ref, session);
        if (value instanceof RemoteId id) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(Wire.ID, id.kind());
            out.put("id", id.id());
            return out;
        }
        if (value instanceof Callable) {
            throw new SceneFailure("a function cannot be sent to the game: it would have to run there, and a"
                    + " script's functions run in this process. Do the work here and send the result, or"
                    + " keep this scene in config/stagewright/scenes where it runs in the game.");
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean marked = false;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                marked |= key.startsWith("$");
                out.put(key, encode(e.getValue(), session));
            }
            if (!marked) return out;
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(Wire.MAP, out);
            return wrapped;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            for (Object item : items) out.add(encode(item, session));
            return out;
        }
        if (value instanceof Object[] items) {
            List<Object> out = new ArrayList<>(items.length);
            for (Object item : items) out.add(encode(item, session));
            return out;
        }
        if (value instanceof Enum<?> e) return e.name();
        throw new SceneFailure("a " + value.getClass().getName() + " cannot be sent to the game: only"
                + " strings, numbers, booleans, lists, maps, and objects the game itself handed out can cross.");
    }

    /** What the game answered, in the form a script can hold. */
    Object decode(Object wire, RemoteSession session) {
        if (wire instanceof Map<?, ?> m) {
            if (m.containsKey(Wire.HANDLE)) {
                int handle = ((Number) m.get(Wire.HANDLE)).intValue();
                return session.ref(handle, m.get("type") == null ? "Object" : String.valueOf(m.get("type")));
            }
            if (m.containsKey(Wire.ID)) {
                return new RemoteId(String.valueOf(m.get(Wire.ID)), String.valueOf(m.get("id")));
            }
            Map<?, ?> source = m.containsKey(Wire.MAP) ? (Map<?, ?>) m.get(Wire.MAP) : m;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : source.entrySet()) out.put(String.valueOf(e.getKey()), decode(e.getValue(), session));
            return out;
        }
        if (wire instanceof List<?> items) {
            List<Object> out = new ArrayList<>(items.size());
            for (Object item : items) out.add(decode(item, session));
            return out;
        }
        return wire;
    }

    private static Map<String, Object> handle(RemoteRef ref, RemoteSession session) {
        if (ref.session() != session) {
            throw new SceneFailure("an object from another scene's session was passed to this one: " + ref
                    + " belongs to '" + ref.session().scene() + "' and went away when that scene ended");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(Wire.HANDLE, ref.handle());
        return out;
    }

    /** Integral values as integers: a script's numbers are all doubles, and the game's int parameters
     *  take only the whole ones. */
    private static Object number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return n.longValue();
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new SceneFailure("a number that is " + d + " cannot be sent to the game");
        }
        if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return (long) d;
        return d;
    }
}
