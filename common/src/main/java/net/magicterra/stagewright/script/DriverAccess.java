package net.magicterra.stagewright.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.contract.DriverBinding;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;

/** Installs the common JavaScript surface over an injected driver binding. */
final class DriverAccess {

    private DriverAccess() {}

    static void install(Context cx, ScriptableObject scope, DriverBinding binding) {
        ScriptableObject.putProperty(scope, "driver", new DriverFunction(binding), cx);
    }

    private static final class DriverFunction extends BaseFunction {
        private final DriverBinding binding;

        DriverFunction(DriverBinding binding) { this.binding = binding; }
        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            if (args.length == 0) {
                throw new IllegalStateException("driver(method, params) needs a method name,"
                        + " e.g. driver('mc.observe.player')");
            }
            String method = String.valueOf(args[0]);
            Object params = args.length > 1 ? args[1] : null;
            Object result = binding.route(method, asMap(toJava(params)));
            return toJs(result, cx, scope);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object converted) {
            if (converted instanceof Map<?, ?> m) return (Map<String, Object>) m;
            return Map.of();
        }
    }

    // ---- conversions ----
    //
    // Both directions are here rather than left to Rhino's default wrapping, and the return
    // direction is the one that matters. route() answers with java.util.Map, which Rhino hands back
    // as a Java object: a scene author would have to write result.get("x") and could not iterate it
    // with for..in or read result.pos.y at all. Converting to native objects is the difference
    // between a binding a pack author can use and one they have to be taught.

    private static Object toJava(Object js) {
        if (js == null || js instanceof Undefined) return null;
        if (js instanceof NativeArray array) {
            List<Object> out = new ArrayList<>();
            for (Object o : array) out.add(toJava(o));
            return out;
        }
        if (js instanceof Map<?, ?> map) {           // NativeObject implements Map in this fork
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toJava(v)));
            return out;
        }
        if (js instanceof CharSequence cs) return cs.toString();
        return js;
    }

    private static Object toJs(Object java, Context cx, Scriptable scope) {
        if (java == null) return null;
        if (java instanceof Map<?, ?> map) {
            Scriptable out = cx.newObject(scope);
            map.forEach((k, v) -> ScriptableObject.putProperty(out, String.valueOf(k), toJs(v, cx, scope), cx));
            return out;
        }
        if (java instanceof List<?> list) {
            Object[] items = new Object[list.size()];
            for (int i = 0; i < items.length; i++) items[i] = toJs(list.get(i), cx, scope);
            return cx.newArray(scope, items);
        }
        if (java instanceof Number || java instanceof Boolean || java instanceof CharSequence) return java;
        // Anything else stays a Java object on purpose — a scene that asks for one can still call
        // its methods, and guessing at a JS shape for it would lose more than it gains.
        return cx.javaToJS(java, scope);
    }
}
