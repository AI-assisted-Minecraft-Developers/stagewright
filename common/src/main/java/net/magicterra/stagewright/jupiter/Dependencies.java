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
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * {@link DependsOn} and a script test's {@code dependsOn}: which tests the run holds, how each one ended, and the wait
 * before a dependent test until its dependencies have ended, all of them having passed. Java and script tests run side
 * by side, so either kind may wait for the other. The ordering half is {@link DependencyOrder}, and for script tests
 * {@link ScriptScheduler}. Registered for every test through extension auto-detection, like {@link GameExtension},
 * which calls {@link #awaitUpstream} before the test takes its locks.
 *
 * <p>A test is named by a key: {@code com.example.SomeTest#method} for a Java test, {@code file.js#name} for a
 * script's, the file being its class path resource.
 */
public final class Dependencies implements TestWatcher {

    private static final String PARALLEL = "junit.jupiter.execution.parallel.enabled";
    private static final String DEPENDS_ON = "depends on ";

    private static volatile Set<Ref> planned = Set.of();
    private static volatile TestPlan currentPlan;
    private static final Map<Ref, Outcome> OUTCOMES = new ConcurrentHashMap<>();
    /** Completed once a planned test has ended for good: every invocation of it, or the container it was in. */
    private static final Map<Ref, CompletableFuture<Void>> ENDED = new ConcurrentHashMap<>();
    /**
     * What every test of the run depends on, by key: Java tests' from {@link #prepare}, scripts' from {@link #scripts}.
     */
    private static final Map<String, List<String>> EDGES = new ConcurrentHashMap<>();
    /** How each script test of the run ended, completed as it ends. */
    private static final Map<String, CompletableFuture<TestExecutionResult.Status>> SCRIPTS = new ConcurrentHashMap<>();

    /**
     * Marks tests ended as the launcher reports their nodes finished or skipped. The node of a test method itself, not
     * an invocation of it; and every test under a container that ended, since a class that failed in {@code @BeforeAll}
     * or was disabled reports nothing for its methods.
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

    /** A test method by class and name. */
    record Ref(String className, String method) {
        @Override
        public String toString() {
            return className.substring(className.lastIndexOf('.') + 1) + "#" + method;
        }

        String key() {
            return className + "#" + method;
        }

        static Ref of(String key) {
            int hash = key.indexOf('#');
            return new Ref(key.substring(0, hash), key.substring(hash + 1));
        }
    }

    private enum Outcome {
        SUCCEEDED,
        FAILED,
        ABORTED,
        DISABLED
    }

    /** The Java tests {@code method}, a test of {@code testClass}, depends on. */
    static List<Ref> upstream(Class<?> testClass, Method method) {
        DependsOn d = method.getAnnotation(DependsOn.class);
        if (d == null) {
            return List.of();
        }
        String owner = d.in() == Void.class ? testClass.getName() : d.in().getName();
        List<Ref> refs = new ArrayList<>();
        for (String name : d.value()) {
            refs.add(new Ref(owner, name));
        }
        return refs;
    }

    /** The script tests {@code method} depends on, by key. */
    static List<String> scriptUpstream(Method method) {
        DependsOn d = method.getAnnotation(DependsOn.class);
        return d == null ? List.of() : List.of(d.scripts());
    }

    /**
     * The Java tests {@code method} must come after: those it names, and those the script tests it names depend on,
     * through any chain of script tests. What orders Java tests one after another has to see both, or a test could be
     * put first that waits for a script test waiting for a test after it.
     */
    static List<Ref> javaUpstream(Class<?> testClass, Method method) {
        List<Ref> refs = new ArrayList<>(upstream(testClass, method));
        Set<String> seen = new HashSet<>();
        List<String> pending = new ArrayList<>(scriptUpstream(method));
        while (!pending.isEmpty()) {
            String key = pending.remove(pending.size() - 1);
            if (!seen.add(key)) {
                continue;
            }
            if (isScript(key)) {
                pending.addAll(EDGES.getOrDefault(key, List.of()));
            } else {
                refs.add(Ref.of(key));
            }
        }
        return refs;
    }

    /** Whether {@code key} names a script test rather than a Java one: its file is a script. */
    static boolean isScript(String key) {
        int hash = key.indexOf('#');
        String file = hash < 0 ? key : key.substring(0, hash);
        return file.endsWith(".js") || ScriptTestEngine.isYaml(file);
    }

    /**
     * Before the run's Java tests are discovered: the script tests it holds and what each depends on, by key. The Java
     * tests' order is decided at their discovery and depends on these.
     */
    static void scripts(Map<String, List<String>> edges) {
        EDGES.keySet().removeIf(Dependencies::isScript);
        SCRIPTS.clear();
        edges.forEach((key, upstream) -> {
            EDGES.put(key, List.copyOf(upstream));
            SCRIPTS.put(key, new CompletableFuture<>());
        });
    }

    /** What {@code key} depends on, of either kind; nothing for a test not in the run. */
    static List<String> edges(String key) {
        return EDGES.getOrDefault(key, List.of());
    }

    /**
     * Before the run: remember which Java tests it holds and answer every dependency cycle among its tests with a Java
     * test in it, each as the tests around it. A run with one must not start. A cycle of script tests alone fails only
     * those tests, as {@link ScriptScheduler} finds.
     */
    static List<String> prepare(TestPlan plan) {
        Map<Ref, List<String>> java = new HashMap<>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier id : plan.getDescendants(root)) {
                // A parameterized test is a container here, its invocations not yet discovered.
                Ref ref = methodNode(plan, id);
                if (ref == null) {
                    continue;
                }
                MethodSource source = (MethodSource) id.getSource().orElseThrow();
                List<String> keys = new ArrayList<>();
                for (Ref up : upstream(source.getJavaClass(), source.getJavaMethod())) {
                    keys.add(up.key());
                }
                keys.addAll(scriptUpstream(source.getJavaMethod()));
                java.put(ref, keys);
            }
        }
        planned = Set.copyOf(java.keySet());
        currentPlan = plan;
        OUTCOMES.clear();
        ENDED.clear();
        EDGES.keySet().removeIf(key -> !isScript(key));
        java.forEach((ref, keys) -> EDGES.put(ref.key(), keys));
        for (Ref ref : planned) {
            ENDED.put(ref, new CompletableFuture<>());
        }
        List<String> cycles = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (Ref start : java.keySet()) {
            findCycle(start.key(), new LinkedHashSet<>(), done, cycles);
        }
        return cycles;
    }

    private static void findCycle(String at, Set<String> path, Set<String> done, List<String> cycles) {
        if (done.contains(at)) {
            return;
        }
        if (path.contains(at)) {
            List<String> loop = new ArrayList<>(path);
            loop = loop.subList(loop.indexOf(at), loop.size());
            if (!loop.stream().allMatch(Dependencies::isScript)) {
                cycles.add(String.join(
                                " -> ", loop.stream().map(Dependencies::show).toList()) + " -> " + show(at));
            }
            return;
        }
        path.add(at);
        for (String next : edges(at)) {
            if (EDGES.containsKey(next)) {
                findCycle(next, path, done, cycles);
            }
        }
        path.remove(at);
        done.add(at);
    }

    private static String show(String key) {
        return isScript(key) ? key : Ref.of(key).toString();
    }

    /**
     * How the Java test {@code className#method} ended, for a script test depending on it, once it has: null when it
     * succeeded, else why the dependent cannot run.
     */
    static String blockerFor(String className, String method) {
        Ref ref = new Ref(className, method);
        if (!planned.contains(ref)) {
            return earlierRound(ref.key());
        }
        CompletableFuture<Void> ended = ENDED.get(ref);
        if (ended != null) {
            try {
                ended.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "had not ended when this test was interrupted";
            } catch (ExecutionException e) {
                // never completed exceptionally
            }
        }
        Outcome outcome = OUTCOMES.get(ref);
        if (outcome == null) {
            return "ended without running: its class failed or was skipped first";
        }
        return switch (outcome) {
            case SUCCEEDED -> null;
            case FAILED -> "failed";
            case ABORTED -> "was aborted";
            case DISABLED -> "is disabled";
        };
    }

    /**
     * For a test this game does not run: null when it passed in an earlier round, before a restart; else why a test
     * depending on it cannot run.
     */
    static String earlierRound(String key) {
        String status = Rounds.endedBefore(key);
        if (status == null) {
            return "is not in this run";
        }
        return TestExecutionResult.Status.SUCCESSFUL.name().equals(status)
                ? null
                : "did not pass before the restart (" + status + ")";
    }

    /** How every test of this game ended, by key, for the rounds after it. */
    static Map<String, TestExecutionResult.Status> outcomes() {
        Map<String, TestExecutionResult.Status> all = new HashMap<>();
        for (Ref ref : planned) {
            Outcome o = OUTCOMES.get(ref);
            all.put(
                    ref.key(),
                    o == Outcome.SUCCEEDED
                            ? TestExecutionResult.Status.SUCCESSFUL
                            : o == Outcome.FAILED
                                    ? TestExecutionResult.Status.FAILED
                                    : TestExecutionResult.Status.ABORTED);
        }
        SCRIPTS.forEach((key, ended) -> all.put(key, ended.getNow(TestExecutionResult.Status.ABORTED)));
        return all;
    }

    /** A script test has ended, {@code status} saying how. */
    static void scriptEnded(String key, TestExecutionResult.Status status) {
        CompletableFuture<TestExecutionResult.Status> ended = SCRIPTS.get(key);
        if (ended != null) {
            ended.complete(status);
        }
    }

    /**
     * The script tests are over: any that never ran ended so, which a Java test waiting for one is told. Without it a
     * script engine that broke off would leave that test waiting for good.
     */
    static void scriptsOver() {
        SCRIPTS.values().forEach(f -> f.complete(TestExecutionResult.Status.ABORTED));
    }

    /** The Java tests are over, as {@link #scriptsOver} is for scripts. */
    static void javaOver() {
        ENDED.values().forEach(f -> f.complete(null));
    }

    /**
     * Before a Java test takes its locks: wait for the tests it depends on, and abort it unless each of them passed. A
     * Java test it depends on that has not run yet is waited for only in a parallel run; one at a time, it can only be
     * ordered after this one. A script test is always waited for, since the scripts run beside the Java tests.
     */
    static void awaitUpstream(ExtensionContext context) throws InterruptedException {
        boolean parallel = context.getConfigurationParameter(PARALLEL)
                .map(Boolean::parseBoolean)
                .orElse(false);
        for (Ref ref : upstream(context.getRequiredTestClass(), context.getRequiredTestMethod())) {
            if (!planned.contains(ref)) {
                String why = earlierRound(ref.key());
                if (why != null) {
                    Assumptions.abort(DEPENDS_ON + ref + ", which " + why);
                }
                continue;
            }
            if (parallel) {
                await(ENDED.get(ref));
            }
            Outcome outcome = OUTCOMES.get(ref);
            if (outcome == null) {
                if (!parallel) {
                    Assumptions.abort(DEPENDS_ON + ref + ", which has not run yet: a @TestMethodOrder or"
                            + " @TestClassOrder puts this test first");
                }
                Assumptions.abort(
                        DEPENDS_ON + ref + ", which ended without running: its class failed or was" + " skipped first");
            }
            switch (outcome) {
                case SUCCEEDED -> {}
                case FAILED -> Assumptions.abort(DEPENDS_ON + ref + ", which failed");
                case ABORTED -> Assumptions.abort(DEPENDS_ON + ref + ", which was aborted");
                case DISABLED -> Assumptions.abort(DEPENDS_ON + ref + ", which is disabled");
                default -> throw new IllegalStateException("unhandled outcome " + outcome);
            }
        }
        for (String key : scriptUpstream(context.getRequiredTestMethod())) {
            CompletableFuture<TestExecutionResult.Status> ended = SCRIPTS.get(key);
            if (ended == null) {
                String why = earlierRound(key);
                if (why == null) {
                    continue;
                }
                Assumptions.abort(DEPENDS_ON + key + ", which " + why
                        + (why.startsWith("is not") ? ": not in this scene, or its file did not load" : ""));
            }
            await(ended);
            TestExecutionResult.Status status = ended.getNow(TestExecutionResult.Status.ABORTED);
            if (status != TestExecutionResult.Status.SUCCESSFUL) {
                Assumptions.abort(DEPENDS_ON + key + ", which did not pass (" + status + ")");
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
     * Block until {@code ended} is done. On a test worker, a fork-join thread, a plain wait would hold one of the
     * pool's threads while what it waits for may still be queued for one; a managed block lets the pool add a thread
     * for as long as this one waits. Elsewhere it is a plain wait. No cycle can make this wait forever: a run with one
     * never starts.
     */
    private static void await(CompletableFuture<?> ended) throws InterruptedException {
        if (ended == null || ended.isDone()) {
            return;
        }
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

    private static void ended(TestIdentifier id, boolean skipped) {
        TestPlan plan = currentPlan;
        if (plan == null) {
            return;
        }
        Ref own = methodNode(plan, id);
        if (own != null) {
            complete(own, skipped);
        }
        for (TestIdentifier d : plan.getDescendants(id)) {
            Ref ref = methodNode(plan, d);
            if (ref != null) {
                complete(ref, skipped);
            }
        }
    }

    private static void complete(Ref ref, boolean skipped) {
        // A disabled class reports only itself; its methods are disabled with it.
        if (skipped) {
            OUTCOMES.putIfAbsent(ref, Outcome.DISABLED);
        }
        CompletableFuture<Void> ended = ENDED.get(ref);
        if (ended != null) {
            ended.complete(null);
        }
    }

    /** The test {@code id} is the method node of, or null for a class, an invocation, or anything else. */
    private static Ref methodNode(TestPlan plan, TestIdentifier id) {
        if (!(id.getSource().orElse(null) instanceof MethodSource source)) {
            return null;
        }
        boolean invocation = plan.getParent(id)
                .flatMap(TestIdentifier::getSource)
                .filter(MethodSource.class::isInstance)
                .isPresent();
        return invocation ? null : new Ref(source.getClassName(), source.getMethodName());
    }

    private static void record(ExtensionContext context, Outcome outcome) {
        Ref ref = new Ref(
                context.getRequiredTestClass().getName(),
                context.getRequiredTestMethod().getName());
        // A parameterized test ends once per invocation; any one that did not succeed decides.
        OUTCOMES.merge(ref, outcome, (was, now) -> was == Outcome.SUCCEEDED ? now : was);
    }
}
