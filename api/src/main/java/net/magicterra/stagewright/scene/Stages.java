package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.contract.SceneFailure;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns {@link SceneDef}-annotated methods into {@link Scene} records.
 *
 * <p>This is the registration path {@link SceneProvider} uses by default, and it exists to delete a
 * class of bug rather than to save typing. Under {@code Scene.of("sb.magnetPulls", 200,
 * X::magnetPulls)} the scene's name is written twice — once as a string the orchestrator reconciles
 * against a manifest, once as a method reference the compiler checks — and only the second one moves
 * when you rename the method. Deriving the name FROM the method leaves one copy.
 *
 * <p><b>Scanning is per-class, never over the classpath.</b> Only the holder classes handed to
 * {@link #scan} are reflected over. Classpath scanning inside NeoForge's module layer is slow and
 * loader-specific, and its failure mode is a scene that silently does not register — which reads as
 * a smaller, GREEN suite.
 *
 * <p><b>Order is by name, not by declaration.</b> Reflection cannot recover source order
 * ({@code getDeclaredMethods} is explicitly unspecified), so the alternative to sorting is an order
 * that varies between JVMs — and origin slots are assigned in registry order, so a varying order
 * means arenas move between runs and byte-exact physics goldens stop reproducing. Sorting also means
 * inserting a scene mid-file no longer shifts every later scene's arena. It does NOT make slots
 * stable against a new name sorting early, which is why {@link SceneDef#originSlot()} still exists.
 */
public final class Stages {

    private Stages() {}

    /** Collect the scenes declared by each holder class, in name order within each class. */
    public static List<Scene> scan(Class<?>... holders) {
        List<Scene> out = new ArrayList<>();
        for (Class<?> holder : holders) {
            out.addAll(scanOne(holder));
        }
        return List.copyOf(out);
    }

    private static List<Scene> scanOne(Class<?> holder) {
        SceneSet set = holder.getAnnotation(SceneSet.class);
        String prefix = set == null ? "" : set.value() + ".";

        List<Method> methods = new ArrayList<>();
        for (Method m : holder.getDeclaredMethods()) {
            if (m.isAnnotationPresent(SceneDef.class)) methods.add(m);
        }
        methods.sort(Comparator.comparing(Method::getName));

        List<Scene> out = new ArrayList<>(methods.size());
        for (Method m : methods) {
            reject(holder, m);
            m.setAccessible(true);                 // scene methods are an implementation detail: let them be private
            SceneDef def = m.getAnnotation(SceneDef.class);
            String name = prefix + m.getName();
            out.add(new Scene(name, def.budget(), def.required(),
                    def.mustSkip() ? Canary.MUST_SKIP : Canary.NONE,
                    invoker(name, m), def.originSlot(), def.chunkRadius(), def.terrain(), def.clock(),
                    def.dimension().isBlank() ? null : def.dimension(), true));
        }
        return out;
    }

    /** A malformed scene method is a hard error, never a skip: skipping shrinks the suite silently
     *  and the smaller suite still reports GREEN. */
    private static void reject(Class<?> holder, Method m) {
        String where = holder.getName() + "#" + m.getName();
        if (!Modifier.isStatic(m.getModifiers())) {
            throw new IllegalStateException("@SceneDef method must be static: " + where);
        }
        if (m.getReturnType() != void.class) {
            throw new IllegalStateException("@SceneDef method must return void: " + where);
        }
        if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != SceneContext.class) {
            throw new IllegalStateException("@SceneDef method must take exactly one SceneContext: " + where);
        }
    }

    /** Unwrap the reflective layer so a SceneFailure thrown inside the body reaches the harness as
     *  itself. Without this every assertion surfaces as an InvocationTargetException and the harness
     *  reports "unexpected InvocationTargetException: null" — the reason field, which is the only
     *  diagnostic channel that survives a burst-dropping logger, would carry nothing. */
    private static java.util.function.Consumer<SceneContext> invoker(String name, Method m) {
        return ctx -> {
            try {
                m.invoke(null, ctx);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new SceneFailure("scene '" + name + "' threw " + cause);
            } catch (IllegalAccessException e) {
                throw new SceneFailure("scene '" + name + "' is not invokable: " + e);
            }
        };
    }
}
