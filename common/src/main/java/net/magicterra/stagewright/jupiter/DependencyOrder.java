package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The default method and class order of an in-game run: Jupiter's own order, except that a test runs after the tests
 * its {@code @DependsOn} names, and a class after the classes it names with {@code in}; a test named through script
 * tests ({@link Dependencies#javaUpstream}) counts as named. Stable, so tests with no dependencies keep their places. A
 * cycle leaves its members where they were; the run refuses it before this is asked.
 */
public final class DependencyOrder implements MethodOrderer, ClassOrderer {

    /**
     * Whether the class {@link #orderMethods} was last asked about has a test depending on another of its own. Jupiter
     * asks {@link #getDefaultExecutionMode} straight after, for the same class, on the one discovery thread.
     */
    private boolean dependentWithin;

    @Override
    public void orderMethods(MethodOrdererContext context) {
        boolean[] within = {false};
        sort(context.getMethodDescriptors(), m -> m.getMethod().getName(), m -> {
            List<String> before = new ArrayList<>();
            for (Dependencies.Ref ref : Dependencies.javaUpstream(context.getTestClass(), m.getMethod())) {
                if (ref.className().equals(context.getTestClass().getName())) before.add(ref.method());
            }
            within[0] |= !before.isEmpty();
            return before;
        });
        dependentWithin = within[0];
    }

    /**
     * A class whose tests depend on one another runs them one at a time, in this order. Concurrently, a dependent test
     * could start first and take a {@code @ResourceLock} before waiting for a test that needs the same lock, and
     * neither would ever finish. Any other class leaves the mode to the run's configuration.
     */
    @Override
    public Optional<ExecutionMode> getDefaultExecutionMode() {
        return dependentWithin ? Optional.of(ExecutionMode.SAME_THREAD) : Optional.empty();
    }

    @Override
    public void orderClasses(ClassOrdererContext context) {
        sort(context.getClassDescriptors(), c -> c.getTestClass().getName(), c -> {
            Set<String> before = new HashSet<>();
            for (var method : c.getTestClass().getDeclaredMethods()) {
                for (Dependencies.Ref ref : Dependencies.javaUpstream(c.getTestClass(), method)) {
                    if (!ref.className().equals(c.getTestClass().getName())) before.add(ref.className());
                }
            }
            return List.copyOf(before);
        });
    }

    /** Reorder {@code items} in place: each after every item {@code before} names, otherwise as they were. */
    private static <T> void sort(List<T> items, Function<T, String> key, Function<T, List<String>> before) {
        Set<String> present = new HashSet<>();
        for (T item : items) present.add(key.apply(item));
        List<T> remaining = new ArrayList<>(items);
        List<T> ordered = new ArrayList<>(items.size());
        Set<String> placed = new HashSet<>();
        while (!remaining.isEmpty()) {
            T next = null;
            for (T item : remaining) {
                boolean ready = true;
                for (String dep : before.apply(item)) {
                    if (present.contains(dep) && !placed.contains(dep)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    next = item;
                    break;
                }
            }
            // Only a cycle leaves nothing ready: keep the rest in their original order.
            if (next == null) {
                ordered.addAll(remaining);
                break;
            }
            remaining.remove(next);
            ordered.add(next);
            placed.add(key.apply(next));
        }
        items.clear();
        items.addAll(ordered);
    }
}
