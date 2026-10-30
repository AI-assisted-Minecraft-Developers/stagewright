package net.magicterra.stagewright.jupiter;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.minecraft.core.BlockPos;

/**
 * A capability facet as a script test sees it: an object of its public methods by name, since a script handles only
 * plain values. Overloads are told apart by argument count, then by which converts. Arguments arrive as plain values
 * and are converted to what the method takes — a number to its numeric type, {@code {x, y, z}} to a {@link BlockPos}, a
 * {@code t.player()} or {@code t.bot()} handle to its {@link Body}. A result comes back plain: a list or map as one, a
 * position as {@code {x, y, z}}, the facet itself (a chained call) as this same object, any other object wrapped the
 * same way.
 */
final class ScriptFacets {

    private static final Object NO = new Object();

    private ScriptFacets() {}

    // The first overload that takes the arguments answers the call, so the loop ends on its return; a method
    // answering its own receiver is handed back as the facet itself, which is an identity test by definition.
    @SuppressWarnings({"PMD.AvoidBranchingStatementAsLastInLoop", "PMD.CompareObjectsWithEquals"})
    static Map<String, Object> wrap(Game game, Object target) {
        if (target.getClass().getName().startsWith("net.minecraft.")) {
            throw new IllegalArgumentException(
                    "a facet handed a script a " + target.getClass().getName()
                            + ": Minecraft's method names differ between runtimes, so a facet must answer plain values");
        }
        Map<String, List<Method>> byName = new TreeMap<>();
        for (Method m : target.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Method callable = callable(m);
            if (callable != null) {
                byName.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(callable);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        byName.forEach((name, methods) -> out.put(name, (Scripting.Function) args -> {
            for (Method m : methods) {
                Object[] converted = arguments(game, m, args);
                if (converted == null) {
                    continue;
                }
                Object result = invoke(m, target, converted);
                return result == target ? out : plain(game, result);
            }
            List<String> shapes = new ArrayList<>();
            for (Method m : methods) {
                shapes.add(signature(m));
            }
            throw new IllegalArgumentException(name + " takes " + String.join(" or ", shapes) + ", not " + args.length
                    + " argument(s) like " + Expectations.show(List.of(args)));
        }));
        return out;
    }

    /** {@code m}, or the same method on a public supertype when its class is not public and so cannot be called. */
    private static Method callable(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            return m;
        }
        for (Class<?> c = m.getDeclaringClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                try {
                    Method found = i.getMethod(m.getName(), m.getParameterTypes());
                    if (Modifier.isPublic(found.getDeclaringClass().getModifiers())) {
                        return found;
                    }
                } catch (NoSuchMethodException ignored) {
                    // not on this interface
                }
            }
        }
        return null;
    }

    // Rethrows the target's own RuntimeException or Error unchanged, so the original trace is what reaches the script.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot call " + signature(m) + ": " + e.getMessage(), e);
        }
    }

    /** The arguments {@code m} takes made from {@code args}, or null when they do not fit it. */
    // Null is the answer for arguments that do not fit the overload, and the caller tests for it.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static Object[] arguments(Game game, Method m, Object... args) {
        Class<?>[] params = m.getParameterTypes();
        int fixed = m.isVarArgs() ? params.length - 1 : params.length;
        if (m.isVarArgs() ? args.length < fixed : args.length != fixed) {
            return null;
        }
        Object[] out = new Object[params.length];
        for (int i = 0; i < fixed; i++) {
            Object v = convert(game, params[i], args[i]);
            if (v == NO) {
                return null;
            }
            out[i] = v;
        }
        if (m.isVarArgs()) {
            Class<?> component = params[fixed].getComponentType();
            Object rest = Array.newInstance(component, args.length - fixed);
            for (int i = fixed; i < args.length; i++) {
                Object v = convert(game, component, args[i]);
                if (v == NO) {
                    return null;
                }
                Array.set(rest, i - fixed, v);
            }
            out[fixed] = rest;
        }
        return out;
    }

    private static Object convert(Game game, Class<?> type, Object v) {
        if (v == null) {
            return type.isPrimitive() ? NO : null;
        }
        if (v instanceof Number n) {
            boolean whole = n.doubleValue() == Math.rint(n.doubleValue());
            if ((type == int.class || type == Integer.class) && whole) {
                return n.intValue();
            }
            if ((type == long.class || type == Long.class) && whole) {
                return n.longValue();
            }
            if ((type == short.class || type == Short.class) && whole) {
                return n.shortValue();
            }
            if ((type == byte.class || type == Byte.class) && whole) {
                return n.byteValue();
            }
            if (type == double.class || type == Double.class) {
                return n.doubleValue();
            }
            if (type == float.class || type == Float.class) {
                return n.floatValue();
            }
        }
        if (type == boolean.class && v instanceof Boolean) {
            return v;
        }
        if (v instanceof Map<?, ?> map) {
            if (type == Body.class && map.get("name") instanceof String name && map.get("ref") instanceof String) {
                return new BodyImpl(game, name);
            }
            if (type == BlockPos.class
                    && map.get("x") instanceof Number x
                    && map.get("y") instanceof Number y
                    && map.get("z") instanceof Number z) {
                return new BlockPos(x.intValue(), y.intValue(), z.intValue());
            }
        }
        return type.isInstance(v) ? v : NO;
    }

    private static Object plain(Game game, Object v) {
        if (v == null
                || v instanceof Boolean
                || v instanceof String
                || v instanceof Number
                || v instanceof Scripting.Function) {
            return v;
        }
        if (v instanceof Character || v instanceof Enum<?>) {
            return v.toString();
        }
        if (v instanceof BlockPos pos) {
            return ScriptHandles.position(pos);
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, value) -> out.put(String.valueOf(k), plain(game, value)));
            return out;
        }
        if (v instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>();
            for (Object item : items) {
                out.add(plain(game, item));
            }
            return out;
        }
        if (v.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(v); i++) {
                out.add(plain(game, Array.get(v, i)));
            }
            return out;
        }
        return wrap(game, v);
    }

    private static String signature(Method m) {
        List<String> params = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
            params.add(p.getSimpleName());
        }
        return m.getName() + "(" + String.join(", ", params) + ")";
    }
}
