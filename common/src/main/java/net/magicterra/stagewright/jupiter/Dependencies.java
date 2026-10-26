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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import net.magicterra.stagewright.test.DependsOn;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
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

    private static final String PARALLEL = "junit.jupiter.execution.parallel.enabled";

    private static volatile Set<Ref> planned = Set.of();
    private static volatile TestPlan currentPlan;
    private static final Map<Ref, Outcome> OUTCOMES = new ConcurrentHashMap<>();
    /** Completed once a planned test has ended for good: every invocation of it, or the container it was in. */
    private static final Map<Ref, CompletableFuture<Void>> ENDED = new ConcurrentHashMap<>();

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
                // A parameterized test is a container here, its invocations not yet discovered.
                Ref ref = methodNode(plan, id);
                if (ref == null) continue;
                MethodSource source = (MethodSource) id.getSource().orElseThrow();
                edges.put(ref, upstream(source.getJavaClass(), source.getJavaMethod()));
            }
        }
        planned = Set.copyOf(edges.keySet());
        currentPlan = plan;
        OUTCOMES.clear();
        ENDED.clear();
        for (Ref ref : planned) ENDED.put(ref, new CompletableFuture<>());
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
    public void beforeEach(ExtensionContext context) throws InterruptedException {
        boolean parallel = context.getConfigurationParameter(PARALLEL).map(Boolean::parseBoolean).orElse(false);
        for (Ref ref : upstream(context.getRequiredTestClass(), context.getRequiredTestMethod())) {
            if (!planned.contains(ref)) Assumptions.abort("depends on " + ref + ", which is not in this run");
            if (parallel) awaitEnd(ref);
            Outcome outcome = OUTCOMES.get(ref);
            if (outcome == null) {
                if (!parallel) {
                    // Tests run one at a time, so an unfinished dependency can only be ordered after this one.
                    Assumptions.abort("depends on " + ref + ", which has not run yet: a @TestMethodOrder or"
                            + " @TestClassOrder puts this test first");
                }
                Assumptions.abort("depends on " + ref + ", which ended without running: its class failed or was"
                        + " skipped first");
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

    /**
     * Block until {@code ref} has ended. The test worker is a fork-join thread, and a plain wait there would hold one
     * of the pool's threads while the dependency may still be queued for one; a managed block lets the pool add a
     * thread for as long as this one waits. No cycle can make this wait forever: a run with one never starts.
     */
    private static void awaitEnd(Ref ref) throws InterruptedException {
        CompletableFuture<Void> ended = ENDED.get(ref);
        if (ended == null || ended.isDone()) return;
        ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
            @Override
            public boolean block() throws InterruptedException {
                try {
                    ended.get();
                } catch (ExecutionException e) {
                    // never completed exceptionally
                }
                return true;
            }

            @Override
            public boolean isReleasable() {
                return ended.isDone();
            }
        });
    }

    /**
     * Marks tests ended as the launcher reports their nodes finished or skipped. The node of a test method itself, not
     * an invocation of it; and every test under a container that ended, since a class that failed in
     * {@code @BeforeAll} or was disabled reports nothing for its methods.
     */
    static final TestExecutionListener ENDINGS = new TestExecutionListener() {
        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            ended(id, false);
        }

        @Override
        public void executionSkipped(TestIdentifier id, String reason) {
            ended(id, true);
        }
    };

    private static void ended(TestIdentifier id, boolean skipped) {
        TestPlan plan = currentPlan;
        if (plan == null) return;
        Ref own = methodNode(plan, id);
        if (own != null) complete(own, skipped);
        for (TestIdentifier d : plan.getDescendants(id)) {
            Ref ref = methodNode(plan, d);
            if (ref != null) complete(ref, skipped);
        }
    }

    private static void complete(Ref ref, boolean skipped) {
        // A disabled class reports only itself; its methods are disabled with it.
        if (skipped) OUTCOMES.putIfAbsent(ref, Outcome.DISABLED);
        CompletableFuture<Void> ended = ENDED.get(ref);
        if (ended != null) ended.complete(null);
    }

    /** The test {@code id} is the method node of, or null for a class, an invocation, or anything else. */
    private static Ref methodNode(TestPlan plan, TestIdentifier id) {
        if (!(id.getSource().orElse(null) instanceof MethodSource source)) return null;
        boolean invocation = plan.getParent(id).flatMap(TestIdentifier::getSource)
                .filter(MethodSource.class::isInstance).isPresent();
        return invocation ? null : new Ref(source.getClassName(), source.getMethodName());
    }

    private static void record(ExtensionContext context, Outcome outcome) {
        Ref ref = new Ref(context.getRequiredTestClass().getName(), context.getRequiredTestMethod().getName());
        // A parameterized test ends once per invocation; any one that did not succeed decides.
        OUTCOMES.merge(ref, outcome, (was, now) -> was == Outcome.SUCCEEDED ? now : was);
    }
}
