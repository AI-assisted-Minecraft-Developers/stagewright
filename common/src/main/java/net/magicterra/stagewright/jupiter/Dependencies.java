package net.magicterra.stagewright.jupiter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.magicterra.stagewright.test.DependsOn;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * {@link DependsOn}: which tests the run holds, how each one ended, and the check before a dependent test that its
 * dependencies all succeeded. The ordering half is {@link DependencyOrder}. Registered for every test through
 * extension auto-detection, like {@link GameExtension}.
 */
public final class Dependencies implements BeforeEachCallback, TestWatcher {

    /** A test method by class and name. */
    record Ref(String className, String method) {
        @Override
        public String toString() {
            return className.substring(className.lastIndexOf('.') + 1) + "#" + method;
        }
    }

    private enum Outcome { SUCCEEDED, FAILED, ABORTED, DISABLED }

    private static volatile Set<Ref> planned = Set.of();
    private static final Map<Ref, Outcome> OUTCOMES = new ConcurrentHashMap<>();

    /** The dependencies {@code method}, a test of {@code testClass}, declares. */
    static List<Ref> upstream(Class<?> testClass, Method method) {
        DependsOn d = method.getAnnotation(DependsOn.class);
        if (d == null) return List.of();
        String owner = d.in() == Void.class ? testClass.getName() : d.in().getName();
        List<Ref> refs = new ArrayList<>();
        for (String name : d.value()) refs.add(new Ref(owner, name));
        return refs;
    }

    /**
     * Before the run: remember which tests it holds and answer every dependency cycle among them, each as the
     * tests around it. A run with a cycle must not start.
     */
    static List<String> prepare(TestPlan plan) {
        Map<Ref, List<Ref>> edges = new HashMap<>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier id : plan.getDescendants(root)) {
                if (!id.isTest() || !(id.getSource().orElse(null) instanceof MethodSource source)) continue;
                Ref ref = new Ref(source.getClassName(), source.getMethodName());
                edges.put(ref, upstream(source.getJavaClass(), source.getJavaMethod()));
            }
        }
        planned = Set.copyOf(edges.keySet());
        OUTCOMES.clear();
        List<String> cycles = new ArrayList<>();
        Set<Ref> done = new HashSet<>();
        for (Ref start : edges.keySet()) findCycle(start, edges, new LinkedHashSet<>(), done, cycles);
        return cycles;
    }

    private static void findCycle(Ref at, Map<Ref, List<Ref>> edges, LinkedHashSet<Ref> path, Set<Ref> done,
            List<String> cycles) {
        if (done.contains(at)) return;
        if (path.contains(at)) {
            List<Ref> loop = new ArrayList<>(path);
            loop = loop.subList(loop.indexOf(at), loop.size());
            cycles.add(String.join(" -> ", loop.stream().map(Ref::toString).toList()) + " -> " + at);
            return;
        }
        path.add(at);
        for (Ref next : edges.getOrDefault(at, List.of())) {
            if (edges.containsKey(next)) findCycle(next, edges, path, done, cycles);
        }
        path.remove(at);
        done.add(at);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        for (Ref ref : upstream(context.getRequiredTestClass(), context.getRequiredTestMethod())) {
            if (!planned.contains(ref)) Assumptions.abort("depends on " + ref + ", which is not in this run");
            Outcome outcome = OUTCOMES.get(ref);
            if (outcome == null) {
                // Tests run one at a time, so an unfinished dependency can only mean it is ordered after this one.
                Assumptions.abort("depends on " + ref + ", which has not run yet: a @TestMethodOrder or"
                        + " @TestClassOrder puts this test first");
            }
            switch (outcome) {
                case SUCCEEDED -> { }
                case FAILED -> Assumptions.abort("depends on " + ref + ", which failed");
                case ABORTED -> Assumptions.abort("depends on " + ref + ", which was aborted");
                case DISABLED -> Assumptions.abort("depends on " + ref + ", which is disabled");
            }
        }
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        record(context, Outcome.SUCCEEDED);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        record(context, Outcome.FAILED);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        record(context, Outcome.ABORTED);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        record(context, Outcome.DISABLED);
    }

    private static void record(ExtensionContext context, Outcome outcome) {
        Ref ref = new Ref(context.getRequiredTestClass().getName(), context.getRequiredTestMethod().getName());
        // A parameterized test ends once per invocation; any one that did not succeed decides.
        OUTCOMES.merge(ref, outcome, (was, now) -> was == Outcome.SUCCEEDED ? now : was);
    }
}
