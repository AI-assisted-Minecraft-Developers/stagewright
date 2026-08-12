package net.magicterra.stagewright.contract;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;

/**
 * Java reachable from a scene file, the way a modpack author already expects it to be.
 *
 * <p>KubeJS gives pack authors Java, and a scene file that cannot do what their KubeJS scripts do is
 * a downgrade dressed as a safety feature. The trust boundary agrees: these files sit in the pack's
 * own {@code config/} directory next to the mods, put there by the same person who chose the mods.
 * Anything reachable from here was already reachable from any mod in that folder, and from any
 * KubeJS script beside it. Withholding it buys nothing and costs the author the one escape hatch
 * that makes a test framework usable against mods it has never heard of.
 *
 * <p><b>This is not worlddriver's script sandbox and must never be confused with it.</b> That one
 * evaluates scripts arriving over RPC from a remote caller — untrusted input, kept closed, with
 * negative tests in {@code validation/08_sandbox.js} guarding every edge. This class builds a
 * separate scope, from a separate {@code Context}, over files already on disk. Widening here does
 * not widen there; the two never share a scope.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>The KubeJS Rhino fork DELETED the package machinery — no {@code NativeJavaPackage}, no
 * {@code NativeJavaTopPackage}, no {@code ImporterTopLevel} — so {@code cx.initStandardObjects()}
 * produces a scope where {@code java} is simply not defined. What the fork keeps is
 * {@link NativeJavaClass}, and what KubeJS hands its users on top of it is {@code Java.loadClass}.
 * Both surfaces are rebuilt here: {@code Java.loadClass} because it is the idiom pack authors have
 * already typed a hundred times, and {@code java}/{@code Packages} traversal because it is what
 * every other JS-on-JVM environment has taught them to reach for first.
 */
final class JavaAccess {

    private JavaAccess() {}

    /** Install {@code java}, {@code Packages} and {@code Java} into a scene file's scope. */
    static void install(Context cx, ScriptableObject scope) {
        ScriptableObject.putProperty(scope, "Packages", new JavaPackage("", scope), cx);
        ScriptableObject.putProperty(scope, "java", new JavaPackage("java", scope), cx);
        ScriptableObject.putProperty(scope, "javax", new JavaPackage("javax", scope), cx);
        ScriptableObject.putProperty(scope, "net", new JavaPackage("net", scope), cx);
        ScriptableObject.putProperty(scope, "com", new JavaPackage("com", scope), cx);
        ScriptableObject.putProperty(scope, "org", new JavaPackage("org", scope), cx);
        ScriptableObject.putProperty(scope, "Java", javaObject(cx, scope), cx);
    }

    /** The {@code Java} namespace: {@code loadClass} throws on a miss, {@code tryLoadClass} returns null. */
    private static ScriptableObject javaObject(Context cx, ScriptableObject scope) {
        ScriptableObject java = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(java, "loadClass", new LoadClass(true), cx);
        ScriptableObject.putProperty(java, "tryLoadClass", new LoadClass(false), cx);
        return java;
    }

    static Class<?> find(String name) {
        try {
            // The scene loader's own loader, which is the mod classpath: initialize=false so merely
            // naming a class cannot run its static block as a side effect of a typo.
            return Class.forName(name, false, JavaAccess.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** {@code Java.loadClass(name)} / {@code Java.tryLoadClass(name)}. */
    private static final class LoadClass extends BaseFunction {
        private final boolean loud;

        LoadClass(boolean loud) {
            this.loud = loud;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            String name = args.length > 0 ? String.valueOf(args[0]) : "";
            Class<?> found = find(name);
            if (found != null) return new NativeJavaClass(cx, scope, found);
            if (!loud) return null;
            throw new IllegalStateException("no such class '" + name + "' on the scene classpath"
                    + " — a class from a mod is only loadable if that mod is in this run's mods folder");
        }
    }

    /**
     * One segment of a dotted name, resolving to a class as soon as one exists at that name.
     *
     * <p>Property access cannot tell a package from a typo until it is asked for something: reading
     * {@code java.util.Lst} is indistinguishable from reading {@code java.util} right up to the point
     * where the result is used. So a miss keeps returning packages, and the error surfaces at the
     * call rather than at the access. {@code Java.loadClass} exists for exactly the cases where that
     * is not good enough — it names the failure at the moment of the lookup.
     */
    private static final class JavaPackage extends ScriptableObject {
        private final String prefix;

        JavaPackage(String prefix, Scriptable scope) {
            this.prefix = prefix;
            setParentScope(scope);
        }

        @Override
        public String getClassName() {
            return "JavaPackage";
        }

        @Override
        public Object get(Context cx, String name, Scriptable start) {
            // Let real properties (and anything the prototype chain answers) win over a class named
            // the same thing — otherwise toString() on a package object resolves to a lookup.
            Object own = super.get(cx, name, start);
            if (own != Scriptable.NOT_FOUND && own != null) return own;

            String full = prefix.isEmpty() ? name : prefix + "." + name;
            Class<?> found = find(full);
            if (found != null) return new NativeJavaClass(cx, getParentScope(), found);
            // Nested classes are Outer$Inner to the JVM but read as Outer.Inner in every other
            // language a pack author has used; try that before giving up on the segment.
            Class<?> nested = prefix.isEmpty() ? null : find(prefix + "$" + name);
            if (nested != null) return new NativeJavaClass(cx, getParentScope(), nested);
            return new JavaPackage(full, getParentScope());
        }

        @Override
        public boolean has(Context cx, String name, Scriptable start) {
            return true;
        }

        @Override
        public String toString() {
            return "[JavaPackage " + prefix + "]";
        }
    }
}
