package net.magicterra.stagewright.script;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;

/**
 * {@code driver(method, params)} — every worlddriver verb, through one binding.
 *
 * <p>Binding verbs one at a time would have been the obvious shape and the wrong one. worlddriver
 * routes its whole surface through a single {@code DriverApi.route(String, Map)}; MCP, the WebSocket
 * RPC and the in-JVM Rhino transport all do nothing but translate parameters and call it, and the
 * validation suite asserts all three return byte-identical results. A per-verb binding here would
 * be a fourth transport with its own hand-maintained copy of every schema — drifting from the other
 * three the first time a verb gains a parameter, and adding a permanent tax on every verb worlddriver
 * ever adds. Binding the router instead means this surface is complete on the day it is written and
 * stays complete without being touched.
 *
 * <p>Reached reflectively, and that is not incidental: Rhino arrives with worlddriver but ALSO with
 * KubeJS, so a pack can have scene files and no driver at all. A hard reference would turn that pack
 * into a {@code NoClassDefFoundError} at scene-load time; reflection turns it into a sentence
 * explaining which mod is missing, thrown only if a scene actually calls a verb.
 */
final class DriverAccess {

    private static final String DRIVER_COMMON = "net.magicterra.worlddriver.WorldDriverCommon";

    private DriverAccess() {}

    static void install(Context cx, ScriptableObject scope) {
        ScriptableObject.putProperty(scope, "driver", new DriverFunction(), cx);
    }

    private static final class DriverFunction extends BaseFunction {
        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            if (args.length == 0) {
                throw new IllegalStateException("driver(method, params) needs a method name,"
                        + " e.g. driver('mc.observe.player')");
            }
            String method = String.valueOf(args[0]);
            Object params = args.length > 1 ? args[1] : null;
            Object result = route(method, asMap(toJava(params)));
            return toJs(result, cx, scope);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object converted) {
            if (converted instanceof Map<?, ?> m) return (Map<String, Object>) m;
            return Map.of();
        }
    }

    /**
     * Call {@code WorldDriverCommon.api().route(method, params)}.
     *
     * <p>On the server thread, synchronously, because that is where a scene body already is — the
     * same thread every verb's write path would bounce to anyway. A verb that spans ticks (a walk,
     * a mine) returns a handle immediately and the scene awaits its completion the same way it
     * awaits anything else; nothing here blocks the tick waiting for the game to change, which it
     * could not do anyway.
     */
    private static Object route(String method, Map<String, Object> params) {
        Object api;
        try {
            Class<?> common = Class.forName(DRIVER_COMMON);
            api = common.getMethod("api").invoke(null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("driver('" + method + "') needs the worlddriver mod, which"
                    + " is not in this run's mods folder — scene files can assert on the world without"
                    + " it, but driving the game is worlddriver's surface", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach worlddriver's api(): " + e, e);
        }
        if (api == null) {
            throw new IllegalStateException("driver('" + method + "') was called before worlddriver"
                    + " finished initialising — its api is still null");
        }
        try {
            Method route = api.getClass().getMethod("route", String.class, Map.class);
            return route.invoke(api, method, params);
        } catch (InvocationTargetException e) {
            // The verb itself failed. Surface ITS message: a scene author debugging
            // mc.bot.walkTo needs worlddriver's complaint, not a reflection wrapper around it.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("driver('" + method + "') failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot invoke worlddriver's route(): " + e, e);
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
