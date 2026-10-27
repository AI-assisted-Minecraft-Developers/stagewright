package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestExecutionResult;

/**
 * Runs a game's script tests: concurrently unless configured otherwise, as Jupiter runs its own. A test waits for
 * the script tests it depends on, then takes its {@code locks} (all of them shut out {@code isolated} tests, which
 * run alone), then runs.
 *
 * <p>Tests are submitted in dependency order, upstream first, to a pool that takes them in that order. So a test
 * waiting for its upstream never waits for one still queued behind it, and the waits cannot use up the pool. A test
 * in a {@code dependsOn} cycle has its verdict before anything runs, so nothing waits for it either.
 */
final class ScriptScheduler {

    /** Whether script tests run concurrently; by default what Jupiter's {@code parallel.enabled} says. */
    static final String PARALLEL = "stagewright.script.parallel.enabled";

    /** How many script tests may run at once; the processor count by default, and at least 2. */
    static final String THREADS = "stagewright.script.parallel.threads";

    private final Map<String, CompletableFuture<TestExecutionResult.Status>> results = new HashMap<>();
    private final Map<ScriptTestEngine.Test, String> cycles = new HashMap<>();
    private final ReentrantReadWriteLock isolation = new ReentrantReadWriteLock();
    private final Map<String, ReentrantLock> named = new ConcurrentHashMap<>();

    /** Run {@code tests}, each reporting itself through {@code run}, and return when every one has ended. */
    static void run(List<ScriptTestEngine.Test> tests, ConfigurationParameters config, Runner run) {
        ScriptScheduler scheduler = new ScriptScheduler();
        List<ScriptTestEngine.Test> order = scheduler.plan(tests);
        boolean parallel = config.getBoolean(PARALLEL)
                .orElseGet(() -> config.getBoolean("junit.jupiter.execution.parallel.enabled").orElse(false));
        if (!parallel) {
            for (ScriptTestEngine.Test test : order) scheduler.runOne(test, run);
            return;
        }
        int threads = config.get(THREADS).map(Integer::parseInt)
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
            for (ScriptTestEngine.Test test : order) all.add(CompletableFuture.runAsync(() -> scheduler.runOne(test, run), pool));
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
        }
    }

    /** Every test after the script tests it depends on; the tests of a cycle are failed here, before any runs. */
    private List<ScriptTestEngine.Test> plan(List<ScriptTestEngine.Test> tests) {
        Map<String, ScriptTestEngine.Test> byKey = new LinkedHashMap<>();
        for (ScriptTestEngine.Test test : tests) byKey.put(test.key(), test);
        List<ScriptTestEngine.Test> order = new ArrayList<>();
        Set<ScriptTestEngine.Test> visiting = new HashSet<>();
        for (ScriptTestEngine.Test test : byKey.values()) visit(test, byKey, visiting, order);
        for (ScriptTestEngine.Test test : order) {
            CompletableFuture<TestExecutionResult.Status> result = new CompletableFuture<>();
            if (cycles.containsKey(test)) result.complete(TestExecutionResult.Status.FAILED);
            results.put(test.key(), result);
        }
        return order;
    }

    private void visit(ScriptTestEngine.Test test, Map<String, ScriptTestEngine.Test> byKey,
                       Set<ScriptTestEngine.Test> visiting, List<ScriptTestEngine.Test> order) {
        if (order.contains(test)) return;
        if (!visiting.add(test)) {
            cycles.put(test, "dependsOn cycle through '" + test.key() + "', so none of them can go first");
            return;
        }
        for (String dependency : test.dependsOn()) {
            ScriptTestEngine.Test upstream = byKey.get(dependency);
            if (upstream != null) visit(upstream, byKey, visiting, order);
        }
        visiting.remove(test);
        if (!order.contains(test)) order.add(test);
    }

    /**
     * Once the script tests {@code test} depends on have ended: failed in a cycle, aborted when one did not pass, and
     * null when it may run.
     */
    TestExecutionResult blocker(ScriptTestEngine.Test test) {
        String cycle = cycles.get(test);
        if (cycle != null) return TestExecutionResult.failed(new IllegalStateException(cycle));
        for (String dependency : test.dependsOn()) {
            CompletableFuture<TestExecutionResult.Status> upstream = results.get(dependency);
            if (upstream == null) continue;
            TestExecutionResult.Status status = await(upstream);
            if (status != TestExecutionResult.Status.SUCCESSFUL) {
                return TestExecutionResult.aborted(new IllegalStateException("depends on " + dependency
                        + ", which did not pass (" + status + ")"));
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

    /** Take {@code test}'s locks, in name order so two tests cannot each hold what the other waits for. */
    AutoCloseable hold(Set<String> locks, boolean isolated) {
        List<Lock> held = new ArrayList<>();
        held.add(isolated ? isolation.writeLock() : isolation.readLock());
        for (String lock : new TreeSet<>(locks)) held.add(named.computeIfAbsent(lock, k -> new ReentrantLock()));
        for (Lock lock : held) lock.lock();
        return () -> {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
        };
    }
}
