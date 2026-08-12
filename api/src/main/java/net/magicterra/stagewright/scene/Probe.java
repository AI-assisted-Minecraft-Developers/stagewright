package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Reflection into a mod's API, for a scene author who cannot compile anything.
 *
 * <p>The escape hatch behind {@link CapabilityProvider}. A modpack author has {@code .js} files and a
 * mods folder; when the mod they need has no adapter and they cannot write one, this is the only
 * remaining route to it. Reached as {@link SceneContext#probe(String)}:
 *
 * <pre>{@code
 * if (!s.hasClass('com.simibubi.create.Create')) return s.skip('this scene needs Create');
 * var net = s.probe('com.simibubi.create.content.kinetics.KineticNetwork').on(network);
 * s.expect(net.call('getCapacity').asDouble()).isAtLeast(64);
 * }</pre>
 *
 * <h2>Why this is sound for mod classes and forbidden for Minecraft's</h2>
 *
 * <p>The JS-safety rule ({@link SceneContext#originX()}) says a scene may HOLD a Minecraft object but
 * must never call a method ON one, because a production Fabric jar carries intermediary names —
 * {@code getX} is {@code method_10263} there — so a by-name call works on NeoForge and throws on
 * Fabric.
 *
 * <p><b>Mod classes are not remapped.</b> {@code com.simibubi.create.*} is spelled the same in every
 * runtime, so by-name reflection against a mod's own API is exactly as safe as calling it directly.
 * The same technique is unconditionally wrong against {@code net.minecraft.*}.
 *
 * <p>So that is enforced rather than documented: {@link SceneContext#probe(String)} refuses any class
 * under {@code net.minecraft}, and says why. The facets exist for those.
 *
 * <p>Passing a Minecraft object as an ARGUMENT is fine and is how this is meant to be used — that is
 * holding, not calling. {@code s.player()} and {@code s.level()} feed a mod API perfectly well.
 *
 * <h2>Everything non-primitive comes back wrapped</h2>
 *
 * <p>A call returning an object hands back another {@code Probe}, not the object. That keeps chains
 * going, and it means a JS scene can never end up holding a bare Minecraft object it might then call
 * a method on — which would walk straight back into the trap above. Leaving the wrapper is explicit:
 * {@link #asString()}, {@link #asInt()}, {@link #asDouble()}, {@link #asBoolean()}, {@link #isNull()}.
 *
 * <h2>This is a hatch, not an API</h2>
 *
 * <p>The same reflection appearing three times in a pack's scenes is a {@link CapabilityProvider}
 * waiting to be written. Reflection has no compiler, no rename safety and no schema; it is here so
 * that a missing adapter is an inconvenience rather than a wall.
 */
public final class Probe {

    private final Class<?> type;
    private final Object instance;      // null for a static/unbound probe
    private final Object value;         // for a probe wrapping a returned value
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
            throw new SceneFailure("on(null) — there is nothing to bind to. If this came out of"
                    + " another probe, check isNull() first.");
        }
        return new Probe(unwrap(target).getClass(), unwrap(target));
    }

    /** Call an instance method on the bound instance. */
    public Probe call(String method, Object... args) {
        Object target = instance;
        if (target == null) {
            throw new SceneFailure("call('" + method + "') needs an instance — this probe is on the"
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
            throw new SceneFailure("field('" + name + "') needs an instance — bind one with on(x),"
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
        if (v == null) return false;
        for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) return true;
            for (Class<?> i : c.getInterfaces()) if (i.getName().equals(className)) return true;
        }
        return false;
    }

    /** {@code String.valueOf} of the probed value, or {@code ""} for null. Deliberately not
     *  {@code "null"}: a scene comparing strings should not have to distinguish those two. */
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
        if (v instanceof Boolean b) return b;
        throw new SceneFailure("asBoolean() on a " + v.getClass().getName() + " (" + v + ")");
    }

    /**
     * The probed value as a list of probes, for a method returning a collection.
     *
     * <p>Elements are wrapped like any other return value, so a list of a mod's objects stays walkable
     * without any of them arriving bare.
     */
    public List<Probe> asList() {
        Object v = required("asList");
        List<Probe> out = new ArrayList<>();
        if (v instanceof Iterable<?> it) {
            for (Object element : it) out.add(new Probe(element));
            return out;
        }
        if (v instanceof Object[] array) {
            for (Object element : array) out.add(new Probe(element));
            return out;
        }
        throw new SceneFailure("asList() on a " + v.getClass().getName() + ", which is neither an"
                + " Iterable nor an array");
    }

    /** The raw value, for handing straight back to another mod API. Deliberately last in this
     *  class and deliberately not named {@code get}: a scene that calls a method on what this
     *  returns has left the safety this class exists to provide. */
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
        if (v instanceof Number n) return n.doubleValue();
        throw new SceneFailure(what + "() on a " + v.getClass().getName() + " (" + v + ")");
    }

    private Object required(String what) {
        Object v = wrapsValue ? value : instance;
        if (v == null) {
            throw new SceneFailure(what + "() on a probe holding null — guard with isNull() first."
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
            throw new SceneFailure(type.getName() + "." + name + " threw "
                    + cause.getClass().getName() + ": " + cause.getMessage());
        } catch (IllegalAccessException e) {
            throw new SceneFailure("cannot call " + type.getName() + "." + name + ": " + e);
        }
    }

    /**
     * Find a method by name and argument count, listing the alternatives when there is no match.
     *
     * <p>By count rather than by exact parameter types: a scene author reflecting into a mod does not
     * know whether a parameter is declared as {@code List} or {@code Collection}, and making them
     * find out is what turns this hatch into a decompiler session. Ambiguity between two same-arity
     * overloads is reported rather than guessed.
     */
    private Method find(String name, Object[] args, boolean wantStatic) {
        List<Method> matches = new ArrayList<>();
        for (Method m : allMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isStatic(m.getModifiers()) != wantStatic) continue;
            if (m.getParameterCount() == args.length) matches.add(m);
        }
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            List<String> shapes = new ArrayList<>();
            for (Method m : matches) shapes.add(signature(m));
            throw new SceneFailure(type.getName() + " has " + matches.size() + " methods called '"
                    + name + "' taking " + args.length + " argument(s) and this cannot tell them"
                    + " apart: " + String.join(", ", shapes) + ". A capability adapter can; that is"
                    + " the point at which to write one.");
        }
        throw new SceneFailure("no " + (wantStatic ? "static " : "") + "method '" + name + "' taking "
                + args.length + " argument(s) on " + type.getName() + candidates(name));
    }

    /** Near misses, so the fix is in the message rather than in a decompiler. Same rule as
     *  {@link Ids}: a lookup that fails without naming the alternatives is a lookup the author has
     *  to go elsewhere to answer. */
    private String candidates(String name) {
        TreeSet<String> sameName = new TreeSet<>();
        TreeSet<String> allNames = new TreeSet<>();
        for (Method m : allMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            allNames.add(m.getName());
            if (m.getName().equals(name)) sameName.add(signature(m));
        }
        if (!sameName.isEmpty()) {
            return " — it does declare " + String.join(", ", sameName)
                    + ", so the name is right and the argument count is not";
        }
        if (allNames.isEmpty()) return " — and it declares no methods of its own at all";
        List<String> shown = new ArrayList<>(allNames).subList(0, Math.min(allNames.size(), 12));
        return " — what it does declare: " + String.join(", ", shown)
                + (allNames.size() > shown.size() ? " (+" + (allNames.size() - shown.size()) + " more)" : "");
    }

    private List<Method> allMethods() {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) out.add(m);
            for (Class<?> i : c.getInterfaces()) {
                for (Method m : i.getDeclaredMethods()) out.add(m);
            }
        }
        return out;
    }

    private static String signature(Method m) {
        List<String> params = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) params.add(p.getSimpleName());
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
                throw new SceneFailure("cannot read " + type.getName() + "." + name + ": " + e);
            }
        }
        TreeSet<String> names = new TreeSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) names.add(f.getName());
        }
        throw new SceneFailure("no field '" + name + "' on " + type.getName()
                + (names.isEmpty() ? "" : " — it declares " + String.join(", ", names)));
    }

    /** Arguments arriving as probes are passed on as what they wrap, so a chain reads naturally
     *  instead of making the author call unwrapped() at every hop. */
    private static Object[] unwrapAll(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = unwrap(args[i]);
        return out;
    }

    private static Object unwrap(Object o) {
        return o instanceof Probe p ? p.unwrapped() : o;
    }
}
