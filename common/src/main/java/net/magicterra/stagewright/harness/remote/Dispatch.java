package net.magicterra.stagewright.harness.remote;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Calling a method on a game object by name, from values that arrived as JSON.
 *
 * <p>The method is looked up on the object's own class at the moment of the call, so there is no list
 * of what an out-of-process scene may do: it may do what the in-process scene may do, with the same
 * names, because both end in the same {@code SceneContext}. A name the class lacks fails here with
 * the class and the arity, and the names it does have.
 *
 * <p>Overloads are settled the way a script engine settles them, which is by looking at the values:
 * each candidate of the right arity is scored by how exactly the arguments fit its parameters, the
 * best wins, and a tie is an error naming both rather than a coin toss.
 */
public final class Dispatch {
    private Dispatch() {}

    /** The call could not be made: no such method, or no unambiguous one for these values. */
    public static final class Unresolved extends RuntimeException {
        Unresolved(String message) {
            super(message);
        }
    }

    /**
     * @throws Unresolved when no method fits
     * @throws RuntimeException whatever the method threw, as it threw it
     */
    public static Object invoke(Object target, String name, List<Object> args) {
        if (target == null) throw new Unresolved("there is no object to call '" + name + "' on");
        Chosen chosen = choose(target.getClass(), name, args);
        try {
            return chosen.method().invoke(target, chosen.arguments());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new Unresolved("'" + name + "' on " + target.getClass().getSimpleName()
                    + " is not callable from here: " + e.getMessage());
        }
    }

    record Chosen(Method method, Object[] arguments) {}

    static Chosen choose(Class<?> type, String name, List<Object> args) {
        List<Method> named = new ArrayList<>();
        Set<List<Class<?>>> seen = new HashSet<>();
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getDeclaringClass() == Object.class) continue;
            Method visible = visible(m);
            if (visible == null) continue;
            if (seen.add(List.of(visible.getParameterTypes()))) named.add(visible);
        }
        if (named.isEmpty()) {
            throw new Unresolved("no method '" + name + "' on " + type.getSimpleName() + ". It has: "
                    + names(type));
        }
        Chosen best = null;
        int bestScore = Integer.MAX_VALUE;
        List<Method> tied = new ArrayList<>();
        for (Method m : named) {
            Object[] converted = new Object[m.getParameterCount()];
            int score = fit(m, args, converted);
            if (score < 0) continue;
            if (score < bestScore) {
                best = new Chosen(m, converted);
                bestScore = score;
                tied.clear();
                tied.add(m);
            } else if (score == bestScore) {
                tied.add(m);
            }
        }
        if (best == null) {
            throw new Unresolved("no overload of '" + name + "' on " + type.getSimpleName() + " takes "
                    + describe(args) + ". The overloads are: " + signatures(named));
        }
        if (tied.size() > 1) {
            throw new Unresolved("'" + name + "' on " + type.getSimpleName() + " is ambiguous for "
                    + describe(args) + ": " + signatures(tied));
        }
        return best;
    }

    /** A score (lower is closer) and the converted arguments in {@code out}; negative when it does not fit. */
    private static int fit(Method m, List<Object> args, Object[] out) {
        Class<?>[] params = m.getParameterTypes();
        int fixed = m.isVarArgs() ? params.length - 1 : params.length;
        if (m.isVarArgs() ? args.size() < fixed : args.size() != fixed) return -1;
        int total = 0;
        for (int i = 0; i < fixed; i++) {
            int s = convert(args.get(i), params[i], out, i);
            if (s < 0) return -1;
            total += s;
        }
        if (m.isVarArgs()) {
            Class<?> component = params[fixed].getComponentType();
            Object rest = Array.newInstance(component, args.size() - fixed);
            Object[] slot = new Object[1];
            for (int i = fixed; i < args.size(); i++) {
                int s = convert(args.get(i), component, slot, 0);
                if (s < 0) return -1;
                Array.set(rest, i - fixed, slot[0]);
                total += s;
            }
            out[fixed] = rest;
            total += 10;     // an exact-arity overload beats a varargs one
        }
        return total;
    }

    /**
     * Put {@code value} into {@code out[at]} as a {@code type}; the score is 0 for an exact fit, more
     * the further it is bent, and negative when it cannot be.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int convert(Object value, Class<?> type, Object[] out, int at) {
        if (value == null) {
            if (type.isPrimitive()) return -1;
            out[at] = null;
            return 1;
        }
        Class<?> boxed = box(type);
        if (value instanceof Long l) {
            long n = l;
            // The wire has one kind of whole number, so a value that fits an int goes to the int overload
            // first, as a script's 5 would; only one that does not fit is a long.
            boolean fitsInt = n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE;
            if (boxed == Long.class) { out[at] = n; return fitsInt ? 1 : 0; }
            if (boxed == Integer.class) { if (!fitsInt) return -1; out[at] = (int) n; return 0; }
            if (boxed == Short.class) { if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) return -1; out[at] = (short) n; return 2; }
            if (boxed == Byte.class) { if (n < Byte.MIN_VALUE || n > Byte.MAX_VALUE) return -1; out[at] = (byte) n; return 2; }
            if (boxed == Double.class) { out[at] = (double) n; return 3; }
            if (boxed == Float.class) { out[at] = (float) n; return 3; }
            if (boxed == Object.class || boxed == Number.class) {
                // A script's integer is an int to everything that did not say long.
                out[at] = n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (Object) (int) n : (Object) n;
                return 5;
            }
            return -1;
        }
        if (value instanceof Double d) {
            if (boxed == Double.class) { out[at] = d; return 0; }
            if (boxed == Float.class) { out[at] = d.floatValue(); return 1; }
            if (boxed == Object.class || boxed == Number.class) { out[at] = d; return 5; }
            // A whole-valued double is what a script's 2.0 is; refuse 2.5 for an int, loudly, by not fitting.
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return convert((long) d.doubleValue(), type, out, at) < 0 ? -1 : 4;
            return -1;
        }
        if (value instanceof Boolean) {
            if (boxed == Boolean.class) { out[at] = value; return 0; }
            return boxed == Object.class ? set(out, at, value, 5) : -1;
        }
        if (value instanceof String s) {
            if (boxed == String.class) { out[at] = s; return 0; }
            if (boxed == Character.class && s.length() == 1) { out[at] = s.charAt(0); return 1; }
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(s)) { out[at] = constant; return 1; }
                }
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(s)) { out[at] = constant; return 2; }
                }
                return -1;
            }
            if (boxed == CharSequence.class || boxed == Object.class || boxed == Comparable.class) {
                return set(out, at, s, 4);
            }
            return -1;
        }
        if (value instanceof List<?> list) {
            if (type.isArray()) {
                Class<?> component = type.getComponentType();
                Object array = Array.newInstance(component, list.size());
                Object[] slot = new Object[1];
                for (int i = 0; i < list.size(); i++) {
                    if (convert(list.get(i), component, slot, 0) < 0) return -1;
                    Array.set(array, i, slot[0]);
                }
                out[at] = array;
                return 2;
            }
            if (type == List.class || type == Collection.class || type == Iterable.class || type == Object.class) {
                return set(out, at, list, type == Object.class ? 5 : 0);
            }
            return type.isInstance(value) ? set(out, at, value, 1) : -1;
        }
        if (value instanceof Map<?, ?> && (type == Map.class || type == Object.class)) {
            return set(out, at, value, type == Object.class ? 5 : 0);
        }
        if (type.isInstance(value)) return set(out, at, value, type == Object.class ? 5 : 0);
        return -1;
    }

    private static int set(Object[] out, int at, Object value, int score) {
        out[at] = value;
        return score;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    /**
     * {@code m} as callable from here: itself when its class is public and exported, else the same
     * method as a public supertype declares it (a private collection class answers through
     * {@code List}), else nothing.
     */
    private static Method visible(Method m) {
        if (callable(m.getDeclaringClass())) return m;
        return declaredAbove(m.getDeclaringClass(), m.getName(), m.getParameterTypes());
    }

    private static Method declaredAbove(Class<?> from, String name, Class<?>[] params) {
        List<Class<?>> above = new ArrayList<>();
        if (from.getSuperclass() != null) above.add(from.getSuperclass());
        above.addAll(List.of(from.getInterfaces()));
        for (Class<?> c : above) {
            if (callable(c)) {
                try {
                    return c.getMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                    // Not declared at this level; its own supertypes may still declare it.
                }
            }
            Method found = declaredAbove(c, name, params);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean callable(Class<?> c) {
        return Modifier.isPublic(c.getModifiers()) && c.getModule().isExported(c.getPackageName());
    }

    private static String names(Class<?> type) {
        Set<String> out = new TreeSet<>(Comparator.naturalOrder());
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() != Object.class && visible(m) != null) out.add(m.getName());
        }
        return String.join(", ", out);
    }

    private static String signatures(List<Method> methods) {
        List<String> out = new ArrayList<>();
        for (Method m : methods) {
            List<String> params = new ArrayList<>();
            for (Class<?> p : m.getParameterTypes()) params.add(p.getSimpleName());
            out.add(m.getName() + "(" + String.join(", ", params) + ")");
        }
        return String.join("; ", out);
    }

    private static String describe(List<Object> args) {
        List<String> out = new ArrayList<>();
        for (Object a : args) out.add(a == null ? "null" : a.getClass().getSimpleName());
        return args.size() + " argument(s) (" + String.join(", ", out) + ")";
    }
}
