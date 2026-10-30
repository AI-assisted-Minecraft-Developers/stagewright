package net.magicterra.stagewright.capability;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.opentest4j.TestAbortedException;

/**
 * Reflection into a mod's API, for a test author who cannot compile against it.
 *
 * <p>The escape hatch behind {@link CapabilityProvider}: when the mod a test needs has no adapter, this is the
 * remaining route to it. Reached as {@link #of(String)}:
 *
 * <pre>{@code
 * if (Probe.hasClass("com.simibubi.create.Create")) {
 *     double capacity = game.onServer(server -> Probe.of("com.example.Api").callStatic("capacity").asDouble());
 * }
 * }</pre>
 *
 * <p>Calls run on the caller's thread; a test reaching into game state wraps them in
 * {@link net.magicterra.stagewright.test.Game#onServer}.
 *
 * <h2>Why this is sound for mod classes and forbidden for Minecraft's</h2>
 *
 * <p>A production Fabric jar carries intermediary names — {@code getX} is {@code method_10263} there — so a by-name
 * call on a Minecraft class works on NeoForge and throws on Fabric. <b>Mod classes are not remapped</b>, so by-name
 * reflection against a mod's own API is as safe as calling it directly. {@link #of} therefore refuses any class under
 * {@code net.minecraft}. Passing a Minecraft object as an ARGUMENT is fine — that is holding, not calling.
 *
 * <h2>Everything non-primitive comes back wrapped</h2>
 *
 * <p>A call returning an object hands back another {@code Probe}, not the object, so chains keep going and a script
 * never ends up holding a bare Minecraft object. Leaving the wrapper is explicit: {@link #asString()},
 * {@link #asInt()}, {@link #asDouble()}, {@link #asBoolean()}, {@link #isNull()}.
 *
 * <p>The same reflection appearing three times in a pack's tests is a {@link CapabilityProvider} waiting to be written.
 * This is a hatch, not an API.
 */
public final class Probe {

    /**
     * A probe on a mod's class, static calls only until {@link #on} binds an instance.
     *
     * @throws IllegalStateException for a class under {@code net.minecraft}
     * @throws TestAbortedException when the class is not in this runtime: the mod that ships it is not installed
     */
    public static Probe of(String className) {
        if (className.startsWith("net.minecraft.")) {
            throw new IllegalStateException("Probe.of('" + className + "') — reflection into net.minecraft is"
                    + " refused, and would not have worked: a production Fabric jar carries intermediary names, so"
                    + " getX is method_10263 there and this call would pass on NeoForge and throw on Fabric. Probe is"
                    + " for a MOD's classes, which are not remapped.");
        }
        Class<?> type = classOrNull(className);
        if (type == null) {
            throw new TestAbortedException("this test needs the class '" + className + "', which is not in this"
                    + " runtime — the mod that ships it is not installed here");
        }
        return new Probe(type, null);
    }

    /** Whether a class is in this runtime, so a test can branch rather than abort. */
    public static boolean hasClass(String className) {
        return classOrNull(className) != null;
    }

    /** Through the loader that loaded StageWright, which on both loaders sees the mods' classes. */
    private static Class<?> classOrNull(String className) {
        try {
            return Class.forName(className, false, Probe.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private final Class<?> type;
    private final Object instance; // null for a static/unbound probe
    private final Object value; // for a probe wrapping a returned value
    private final boolean wrapsValue;

    Probe(Class<?> type, Object instance) {
        this.type = type;
        this.instance = instance;
        this.value = null;
        this.wrapsValue = false;
    }

    private Probe(Object value) {
        this.type = value == null ? Void.class : value.getClass();
        this.instance = value;
        this.value = value;
        this.wrapsValue = true;
    }

    /** The class this probe is on. */
    public String className() {
        return type.getName();
    }

    /** Bind to an instance, so {@link #call} does not have to take one every time. */
    public Probe on(Object target) {
        if (target == null) {
            throw new IllegalStateException("on(null) — there is nothing to bind to. If this came out of"
                    + " another probe, check isNull() first.");
        }
        return new Probe(unwrap(target).getClass(), unwrap(target));
    }

    /** Call an instance method on the bound instance. */
    public Probe call(String method, Object... args) {
        Object target = instance;
        if (target == null) {
            throw new IllegalStateException("call('" + method + "') needs an instance — this probe is on the"
                    + " class " + type.getName() + " and nothing is bound. Use on(x) first, or"
                    + " callStatic for a static method.");
        }
        return invoke(target, method, args);
    }

    /** Call a static method. */
    public Probe callStatic(String method, Object... args) {
        return invoke(null, method, args);
    }

    /** Read an instance field on the bound instance. */
    public Probe field(String name) {
        if (instance == null) {
            throw new IllegalStateException("field('" + name + "') needs an instance — bind one with on(x),"
                    + " or use staticField for a static one.");
        }
        return read(name, instance);
    }

    /** Read a static field. */
    public Probe staticField(String name) {
        return read(name, null);
    }

    /** Whether the probed value is null — the guard before every {@code as*} below. */
    public boolean isNull() {
        return wrapsValue ? value == null : instance == null;
    }

    /** Whether this value is an instance of a named class, by name so no import is needed. */
    public boolean isA(String className) {
        Object v = wrapsValue ? value : instance;
        if (v == null) {
            return false;
        }
        for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) {
                return true;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code String.valueOf} of the probed value, or {@code ""} for null. Deliberately not {@code "null"}: a scene
     * comparing strings should not have to distinguish those two.
     */
    public String asString() {
        Object v = wrapsValue ? value : instance;
        return v == null ? "" : String.valueOf(v);
    }

    public int asInt() {
        return (int) number("asInt");
    }

    public double asDouble() {
        return number("asDouble");
    }

    public boolean asBoolean() {
        Object v = required("asBoolean");
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("asBoolean() on a " + v.getClass().getName() + " (" + v + ")");
    }

    /**
     * The probed value as a list of probes, for a method returning a collection.
     *
     * <p>Elements are wrapped like any other return value, so a list of a mod's objects stays walkable without any of
     * them arriving bare.
     */
    public List<Probe> asList() {
        Object v = required("asList");
        List<Probe> out = new ArrayList<>();
        if (v instanceof Iterable<?> it) {
            for (Object element : it) {
                out.add(new Probe(element));
            }
            return out;
        }
        if (v instanceof Object[] array) {
            for (Object element : array) {
                out.add(new Probe(element));
            }
            return out;
        }
        throw new IllegalStateException(
                "asList() on a " + v.getClass().getName() + ", which is neither an" + " Iterable nor an array");
    }

    /**
     * The raw value, for handing straight back to another mod API. Deliberately last in this class and deliberately not
     * named {@code get}: a scene that calls a method on what this returns has left the safety this class exists to
     * provide.
     */
    public Object unwrapped() {
        return wrapsValue ? value : instance;
    }

    @Override
    public String toString() {
        return "Probe[" + type.getName() + (isNull() ? ", null]" : ", " + asString() + "]");
    }

    // ---- internals ----

    private double number(String what) {
        Object v = required(what);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException(what + "() on a " + v.getClass().getName() + " (" + v + ")");
    }

    private Object required(String what) {
        Object v = wrapsValue ? value : instance;
        if (v == null) {
            throw new IllegalStateException(what + "() on a probe holding null — guard with isNull() first."
                    + " A mod API returning null where a scene expected a value is usually the"
                    + " finding, not an accident.");
        }
        return v;
    }

    private Probe invoke(Object target, String name, Object[] args) {
        Object[] actual = unwrapAll(args);
        Method method = find(name, actual, target == null);
        try {
            method.setAccessible(true);
            return new Probe(method.invoke(target, actual));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(
                    type.getName() + "." + name + " threw " + cause.getClass().getName() + ": " + cause.getMessage());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot call " + type.getName() + "." + name + ": " + e);
        }
    }

    /**
     * Find a method by name and argument count, listing the alternatives when there is no match.
     *
     * <p>By count rather than by exact parameter types: a scene author reflecting into a mod does not know whether a
     * parameter is declared as {@code List} or {@code Collection}, and making them find out is what turns this hatch
     * into a decompiler session. Ambiguity between two same-arity overloads is reported rather than guessed.
     */
    private Method find(String name, Object[] args, boolean wantStatic) {
        List<Method> matches = new ArrayList<>();
        for (Method m : allMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (Modifier.isStatic(m.getModifiers()) != wantStatic) {
                continue;
            }
            if (m.getParameterCount() == args.length) {
                matches.add(m);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            List<String> shapes = new ArrayList<>();
            for (Method m : matches) {
                shapes.add(signature(m));
            }
            throw new IllegalStateException(type.getName() + " has " + matches.size() + " methods called '"
                    + name + "' taking " + args.length + " argument(s) and this cannot tell them"
                    + " apart: " + String.join(", ", shapes) + ". A capability adapter can; that is"
                    + " the point at which to write one.");
        }
        throw new IllegalStateException("no " + (wantStatic ? "static " : "") + "method '" + name + "' taking "
                + args.length + " argument(s) on " + type.getName() + candidates(name));
    }

    /**
     * Near misses, so the fix is in the message rather than in a decompiler. Same rule as {@link Ids}: a lookup that
     * fails without naming the alternatives is a lookup the author has to go elsewhere to answer.
     */
    private String candidates(String name) {
        TreeSet<String> sameName = new TreeSet<>();
        TreeSet<String> allNames = new TreeSet<>();
        for (Method m : allMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            allNames.add(m.getName());
            if (m.getName().equals(name)) {
                sameName.add(signature(m));
            }
        }
        if (!sameName.isEmpty()) {
            return " — it does declare " + String.join(", ", sameName)
                    + ", so the name is right and the argument count is not";
        }
        if (allNames.isEmpty()) {
            return " — and it declares no methods of its own at all";
        }
        List<String> shown = new ArrayList<>(allNames).subList(0, Math.min(allNames.size(), 12));
        return " — what it does declare: " + String.join(", ", shown)
                + (allNames.size() > shown.size() ? " (+" + (allNames.size() - shown.size()) + " more)" : "");
    }

    private List<Method> allMethods() {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                out.add(m);
            }
            for (Class<?> i : c.getInterfaces()) {
                for (Method m : i.getDeclaredMethods()) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static String signature(Method m) {
        List<String> params = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
            params.add(p.getSimpleName());
        }
        return m.getName() + "(" + String.join(", ", params) + ")";
    }

    private Probe read(String name, Object target) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return new Probe(f.get(target));
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read " + type.getName() + "." + name + ": " + e);
            }
        }
        TreeSet<String> names = new TreeSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                names.add(f.getName());
            }
        }
        throw new IllegalStateException("no field '" + name + "' on " + type.getName()
                + (names.isEmpty() ? "" : " — it declares " + String.join(", ", names)));
    }

    /**
     * Arguments arriving as probes are passed on as what they wrap, so a chain reads naturally instead of making the
     * author call unwrapped() at every hop.
     */
    private static Object[] unwrapAll(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = unwrap(args[i]);
        }
        return out;
    }

    private static Object unwrap(Object o) {
        return o instanceof Probe p ? p.unwrapped() : o;
    }
}
