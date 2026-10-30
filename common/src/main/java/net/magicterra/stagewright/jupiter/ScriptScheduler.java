package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestExecutionResult;

/**
 * Runs a game's script tests: concurrently unless configured otherwise, as Jupiter runs its own, and beside the Java
 * tests. A test waits for the tests it depends on, of either kind, then takes its {@code locks} in {@link RunLocks}
 * (all of them shut out {@code isolated} tests, which run alone), then runs. Run one at a time, each test runs alone,
 * Java tests included.
 *
 * <p>Tests are submitted in dependency order, upstream first, to a pool that takes them in that order; the order
 * follows a dependency through Java tests too, since a Java test may wait for a script test in turn. So a test waiting
 * for its upstream never waits for one still queued behind it, and the waits cannot use up the pool. A test in a
 * {@code dependsOn} cycle of script tests has its verdict before anything runs, so nothing waits for it either; a cycle
 * through a Java test refuses the whole run before this is asked.
 */
final class ScriptScheduler {

    /** Whether script tests run concurrently; by default what Jupiter's {@code parallel.enabled} says. */
    static final String PARALLEL = "stagewright.script.parallel.enabled";

    /** How many script tests may run at once; the processor count by default, and at least 2. */
    static final String THREADS = "stagewright.script.parallel.threads";

    private final Map<String, CompletableFuture<TestExecutionResult.Status>> results = new HashMap<>();

    private final Map<ScriptTestEngine.Test, String> cycles = new HashMap<>();

    private final boolean parallel;

    private ScriptScheduler(boolean parallel) {
        this.parallel = parallel;
    }

    /** Run {@code tests}, each reporting itself through {@code run}, and return when every one has ended. */
    // The pool is shut down in the finally block below, after every submitted test has been joined.
    @SuppressWarnings("PMD.CloseResource")
    static void run(List<ScriptTestEngine.Test> tests, ConfigurationParameters config, Runner run) {
        boolean parallel = config.getBoolean(PARALLEL)
                .orElseGet(() -> config.getBoolean("junit.jupiter.execution.parallel.enabled")
                        .orElse(false));
        ScriptScheduler scheduler = new ScriptScheduler(parallel);
        List<ScriptTestEngine.Test> order = scheduler.plan(tests);
        if (!parallel) {
            for (ScriptTestEngine.Test test : order) {
                scheduler.runOne(test, run);
            }
            return;
        }
        int threads = config.get(THREADS)
                .map(Integer::parseInt)
                .orElse(Math.max(2, Runtime.getRuntime().availableProcessors()));
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "stagewright-script-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setContextClassLoader(Thread.currentThread().getContextClassLoader());
            return t;
        });
        try {
            List<CompletableFuture<?>> all = new ArrayList<>();
            for (ScriptTestEngine.Test test : order) {
                all.add(CompletableFuture.runAsync(() -> scheduler.runOne(test, run), pool));
            }
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).join();
        } finally {
            pool.shutdown();
        }
    }

    /** One test's run, given what keeps it from running; answers how it ended. */
    @FunctionalInterface
    interface Runner {
        TestExecutionResult.Status run(ScriptTestEngine.Test test, ScriptScheduler scheduler);
    }

    private void runOne(ScriptTestEngine.Test test, Runner run) {
        TestExecutionResult.Status status = TestExecutionResult.Status.FAILED;
        try {
            status = run.run(test, this);
        } finally {
            results.get(test.key()).complete(status);
            Dependencies.scriptEnded(test.key(), status);
        }
    }

    /** Every test after the tests it depends on; the tests of a cycle are failed here, before any runs. */
    private List<ScriptTestEngine.Test> plan(List<ScriptTestEngine.Test> tests) {
        Map<String, ScriptTestEngine.Test> byKey = new LinkedHashMap<>();
        for (ScriptTestEngine.Test test : tests) {
            byKey.put(test.key(), test);
        }
        List<ScriptTestEngine.Test> order = new ArrayList<>();
        Set<ScriptTestEngine.Test> visiting = new HashSet<>();
        Set<String> javaSeen = new HashSet<>();
        for (ScriptTestEngine.Test test : byKey.values()) {
            visit(test, byKey, visiting, javaSeen, order);
        }
        for (ScriptTestEngine.Test test : order) {
            CompletableFuture<TestExecutionResult.Status> result = new CompletableFuture<>();
            if (cycles.containsKey(test)) {
                result.complete(TestExecutionResult.Status.FAILED);
            }
            results.put(test.key(), result);
        }
        return order;
    }

    private void visit(
            ScriptTestEngine.Test test,
            Map<String, ScriptTestEngine.Test> byKey,
            Set<ScriptTestEngine.Test> visiting,
            Set<String> javaSeen,
            List<ScriptTestEngine.Test> order) {
        if (order.contains(test)) {
            return;
        }
        if (!visiting.add(test)) {
            cycles.put(test, "dependsOn cycle through '" + test.key() + "', so none of them can go first");
            return;
        }
        for (String dependency : test.dependsOn()) {
            visitKey(dependency, byKey, visiting, javaSeen, order);
        }
        visiting.remove(test);
        if (!order.contains(test)) {
            order.add(test);
        }
    }

    /** A script test is visited; a Java test is passed through to the script tests it depends on. */
    private void visitKey(
            String key,
            Map<String, ScriptTestEngine.Test> byKey,
            Set<ScriptTestEngine.Test> visiting,
            Set<String> javaSeen,
            List<ScriptTestEngine.Test> order) {
        ScriptTestEngine.Test upstream = byKey.get(key);
        if (upstream != null) {
            visit(upstream, byKey, visiting, javaSeen, order);
        } else if (!Dependencies.isScript(key) && javaSeen.add(key)) {
            for (String next : Dependencies.edges(key)) {
                visitKey(next, byKey, visiting, javaSeen, order);
            }
        }
    }

    /**
     * Once the script tests {@code test} depends on have ended: failed in a cycle, aborted when one did not pass, and
     * null when it may run.
     */
    TestExecutionResult blocker(ScriptTestEngine.Test test) {
        String cycle = cycles.get(test);
        if (cycle != null) {
            return TestExecutionResult.failed(new IllegalStateException(cycle));
        }
        for (String dependency : test.dependsOn()) {
            CompletableFuture<TestExecutionResult.Status> upstream = results.get(dependency);
            if (upstream == null) {
                continue;
            }
            TestExecutionResult.Status status = await(upstream);
            if (status != TestExecutionResult.Status.SUCCESSFUL) {
                return TestExecutionResult.aborted(new IllegalStateException(
                        "depends on " + dependency + ", which did not pass (" + status + ")"));
            }
        }
        return null;
    }

    /** Whether {@code key} names a script test of this run. */
    boolean has(String key) {
        return results.containsKey(key);
    }

    private static TestExecutionResult.Status await(CompletableFuture<TestExecutionResult.Status> upstream) {
        try {
            return upstream.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestExecutionResult.Status.ABORTED;
        } catch (ExecutionException e) {
            return TestExecutionResult.Status.FAILED;
        }
    }

    /** Take {@code test}'s locks; all of them, alone, when it is isolated or the script tests run one at a time. */
    AutoCloseable hold(Set<String> locks, boolean isolated) {
        return RunLocks.hold(RunLocks.exclusive(locks), isolated || !parallel);
    }
}
