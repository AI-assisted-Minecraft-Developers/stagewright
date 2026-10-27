package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * {@link DependsOn} end to end: sample classes run through an inner launcher configured as {@link JupiterRun}
 * configures the in-game one. The samples are disabled in the outer run and enabled only while the inner launcher
 * runs ({@link #inLauncher}).
 */
class DependenciesTest {

    private static volatile boolean inLauncher;

    /** Condition of every sample class: true only while this test's own launcher runs. */
    public static boolean inLauncher() {
        return inLauncher;
    }

    /** Bodies that ran, by "Class#method". */
    private static final Set<String> RAN = new HashSet<>();

    @BeforeEach
    void reset() {
        synchronized (RAN) {
            RAN.clear();
        }
        MARKS.clear();
        THREADS.clear();
        FLAG.set(false);
        COUNT.set(0);
        SEEN_BY_DOWNSTREAM.set(-1);
    }

    private static void ran(String name) {
        synchronized (RAN) {
            RAN.add(name);
        }
    }

    private static boolean didRun(String name) {
        synchronized (RAN) {
            return RAN.contains(name);
        }
    }

    // ---- samples -------------------------------------------------------------------------------------------

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Ordering {
        @Test
        @DependsOn("b")
        void c() {
            ran("Ordering#c");
        }

        @Test
        @DependsOn("a")
        void b() {
            ran("Ordering#b");
        }

        @Test
        void a() {
            ran("Ordering#a");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Failing {
        @Test
        void a() {
            fail("a is broken");
        }

        @Test
        @DependsOn("a")
        void b() {
            ran("Failing#b");
        }

        @Test
        @DependsOn("b")
        void c() {
            ran("Failing#c");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Missing {
        @Test
        @DependsOn("nope")
        void lonely() {
            ran("Missing#lonely");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Cyclic {
        @Test
        @DependsOn("y")
        void x() {
            ran("Cyclic#x");
        }

        @Test
        @DependsOn("x")
        void y() {
            ran("Cyclic#y");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class First {
        @Test
        void first() {
            ran("First#first");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Second {
        @Test
        @DependsOn(value = "first", in = First.class)
        void second() {
            ran("Second#second");
        }
    }

    // ---- parallel samples ----------------------------------------------------------------------------------

    /** Wall-clock marks (nanoTime) by name, and a flag the cross-class samples hand over. */
    private static final Map<String, Long> MARKS = new ConcurrentHashMap<>();
    private static final Set<String> THREADS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FLAG = new AtomicBoolean();
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final AtomicInteger SEEN_BY_DOWNSTREAM = new AtomicInteger(-1);

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class SlowUpstream {
        @Test
        void upstream() {
            MARKS.put("upstream.start", System.nanoTime());
            sleep(300);
            FLAG.set(true);
            MARKS.put("upstream.end", System.nanoTime());
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class SlowDownstream {
        @Test
        @DependsOn(value = "upstream", in = SlowUpstream.class)
        void downstream() {
            MARKS.put("downstream.start", System.nanoTime());
            assertTrue(FLAG.get(), "upstream's flag was not set yet");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class FailingUpstream {
        @Test
        void upstream() {
            sleep(200);
            fail("upstream is broken");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class FailingDownstream {
        @Test
        @DependsOn(value = "upstream", in = FailingUpstream.class)
        void downstream() {
            ran("FailingDownstream#downstream");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class BeforeAllBroken {
        @BeforeAll
        static void boom() {
            sleep(100);
            throw new IllegalStateException("beforeAll is broken");
        }

        @Test
        void upstream() {
            ran("BeforeAllBroken#upstream");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class BeforeAllDownstream {
        @Test
        @DependsOn(value = "upstream", in = BeforeAllBroken.class)
        void downstream() {
            ran("BeforeAllDownstream#downstream");
        }
    }

    @Disabled("sample: disabled upstream class")
    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class DisabledUpstream {
        @Test
        void upstream() {
            ran("DisabledUpstream#upstream");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class DisabledDownstream {
        @Test
        @DependsOn(value = "upstream", in = DisabledUpstream.class)
        void downstream() {
            ran("DisabledDownstream#downstream");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class ParamUpstream {
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3})
        void upstream(int i) {
            sleep(150);
            if (COUNT.incrementAndGet() == 3) MARKS.put("param.lastEnd", System.nanoTime());
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class ParamDownstream {
        @Test
        @DependsOn(value = "upstream", in = ParamUpstream.class)
        void downstream() {
            SEEN_BY_DOWNSTREAM.set(COUNT.get());
            MARKS.put("param.downstream.start", System.nanoTime());
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Locked {
        // "a" sorts first by name yet depends on "b"; both hold the same write lock.
        @Test
        @ResourceLock("x")
        @DependsOn("b")
        void a() {
            ran("Locked#a");
        }

        @Test
        @ResourceLock("x")
        void b() {
            sleep(100);
            ran("Locked#b");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class Plain {
        @Test
        void one() {
            THREADS.add(Thread.currentThread().getName());
            sleep(300);
        }

        @Test
        void two() {
            THREADS.add(Thread.currentThread().getName());
            sleep(300);
        }

        @Test
        void three() {
            THREADS.add(Thread.currentThread().getName());
            sleep(300);
        }
    }

    // ---- samples depending on script tests -----------------------------------------------------------------

    private static final String SETUP = "t/setup.js#setup";
    private static final String MID = "t/setup.js#mid";
    private static final String LOOP = "t/setup.js#loop";

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class OnScript {
        @Test
        @DependsOn(scripts = SETUP)
        void downstream() {
            MARKS.put("onScript.start", System.nanoTime());
            ran("OnScript#downstream");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class ThroughScript {
        // "c" depends on MID, which depends on "a": "a" has to run first although nothing in Java says so.
        @Test
        @DependsOn(scripts = MID)
        void c() {
            ran("ThroughScript#c");
        }

        @Test
        void a() {
            ran("ThroughScript#a");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class CyclicThroughScript {
        @Test
        @DependsOn(scripts = LOOP)
        void x() {
            ran("CyclicThroughScript#x");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.DependenciesTest#inLauncher")
    static class LockedWithScripts {
        @Test
        @ResourceLock("stagewright.weather")
        void overrides() {
            MARKS.put("locked.start", System.nanoTime());
        }
    }

    // ---- inner launcher ------------------------------------------------------------------------------------

    private record Finished(TestExecutionResult.Status status, String message) {}

    private static final class Recorder implements TestExecutionListener {
        final List<String> started = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Finished> finished = Collections.synchronizedMap(new LinkedHashMap<>());

        private static String name(TestIdentifier id) {
            if (!id.isTest() || !(id.getSource().orElse(null) instanceof MethodSource source)) return null;
            String cls = source.getClassName();
            return cls.substring(cls.lastIndexOf('$') + 1) + "#" + source.getMethodName();
        }

        @Override
        public void executionStarted(TestIdentifier id) {
            String name = name(id);
            if (name != null) started.add(name);
        }

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            String name = name(id);
            if (name != null) {
                finished.put(name, new Finished(result.getStatus(),
                        result.getThrowable().map(Throwable::getMessage).orElse(null)));
            }
        }
    }

    private static Game stubGame() {
        return (Game) Proxy.newProxyInstance(Game.class.getClassLoader(), new Class<?>[] {Game.class},
                (proxy, method, args) -> {
                    // GameExtension's afterEach restores the test's overrides through the server thread; there
                    // are none here, so running the work with no server is enough.
                    switch (method.getName()) {
                        case "runOnServer" -> {
                            @SuppressWarnings("unchecked")
                            Consumer<Object> work = (Consumer<Object>) args[0];
                            work.accept(null);
                            return null;
                        }
                        case "onServer" -> {
                            @SuppressWarnings("unchecked")
                            Function<Object, Object> work = (Function<Object, Object>) args[0];
                            return work.apply(null);
                        }
                        default -> {
                            return null;
                        }
                    }
                });
    }

    private static LauncherDiscoveryRequest request(Class<?>... classes) {
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (Class<?> c : classes) selectors.add(DiscoverySelectors.selectClass(c));
        return LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.testmethod.order.default", DependencyOrder.class.getName())
                .configurationParameter("junit.jupiter.testclass.order.default", DependencyOrder.class.getName())
                .build();
    }

    private static Recorder run(Class<?>... classes) {
        inLauncher = true;
        try {
            GameExtension.bind(stubGame());
            Launcher launcher = LauncherFactory.create();
            TestPlan plan = launcher.discover(request(classes));
            List<String> cycles = Dependencies.prepare(plan);
            assertTrue(cycles.isEmpty(), "unexpected cycles: " + cycles);
            Recorder recorder = new Recorder();
            launcher.execute(plan, recorder);
            return recorder;
        } finally {
            inLauncher = false;
        }
    }

    /** As {@link #run}, configured like {@link JupiterRun}'s launcher: parallel, concurrent, ENDINGS passed. */
    private static Recorder runParallel(Class<?>... classes) {
        inLauncher = true;
        try {
            GameExtension.bind(stubGame());
            List<DiscoverySelector> selectors = new ArrayList<>();
            for (Class<?> c : classes) selectors.add(DiscoverySelectors.selectClass(c));
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectors)
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    .configurationParameter("junit.jupiter.testmethod.order.default", DependencyOrder.class.getName())
                    .configurationParameter("junit.jupiter.testclass.order.default", DependencyOrder.class.getName())
                    .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                    .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                    .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                    // Fixed, so the result does not depend on the build machine's core count.
                    .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                    .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
                    .build();
            Launcher launcher = LauncherFactory.create();
            TestPlan plan = launcher.discover(request);
            List<String> cycles = Dependencies.prepare(plan);
            assertTrue(cycles.isEmpty(), "unexpected cycles: " + cycles);
            Recorder recorder = new Recorder();
            launcher.execute(plan, recorder, Dependencies.ENDINGS);
            return recorder;
        } finally {
            inLauncher = false;
        }
    }

    // ---- cases ---------------------------------------------------------------------------------------------

    @Test
    void runsDependenciesFirst() {
        Recorder r = run(Ordering.class);
        assertEquals(List.of("Ordering#a", "Ordering#b", "Ordering#c"), r.started);
        for (String name : r.started) {
            assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get(name).status(), name);
        }
    }

    @Test
    void failureAbortsDependentsTransitively() {
        Recorder r = run(Failing.class);
        assertEquals(TestExecutionResult.Status.FAILED, r.finished.get("Failing#a").status());

        Finished b = r.finished.get("Failing#b");
        assertNotNull(b);
        assertEquals(TestExecutionResult.Status.ABORTED, b.status(), b.message());
        assertTrue(b.message().contains("which failed"), b.message());

        Finished c = r.finished.get("Failing#c");
        assertNotNull(c);
        assertEquals(TestExecutionResult.Status.ABORTED, c.status(), c.message());
        assertTrue(c.message().contains("which was aborted"), c.message());

        assertFalse(didRun("Failing#b"), "b's body ran");
        assertFalse(didRun("Failing#c"), "c's body ran");
    }

    @Test
    void missingDependencyAborts() {
        Recorder r = run(Missing.class);
        Finished f = r.finished.get("Missing#lonely");
        assertNotNull(f);
        assertEquals(TestExecutionResult.Status.ABORTED, f.status(), f.message());
        assertTrue(f.message().contains("not in this run"), f.message());
        assertFalse(didRun("Missing#lonely"));
    }

    @Test
    void cycleIsReportedByPrepare() {
        inLauncher = true;
        try {
            TestPlan plan = LauncherFactory.create().discover(request(Cyclic.class));
            List<String> cycles = Dependencies.prepare(plan);
            assertEquals(1, cycles.size(), cycles.toString());
            assertTrue(cycles.get(0).contains("#x"), cycles.get(0));
            assertTrue(cycles.get(0).contains("#y"), cycles.get(0));
        } finally {
            inLauncher = false;
        }
    }

    @Test
    void dependencyInAnotherClassRunsFirst() {
        // Second is selected before First; the class order must still put First ahead.
        Recorder r = run(Second.class, First.class);
        assertEquals(List.of("First#first", "Second#second"), r.started);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("First#first").status());
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("Second#second").status());
    }

    // ---- parallel cases ------------------------------------------------------------------------------------

    @Test
    @Timeout(60)
    void parallelWaitsForUpstreamInAnotherClass() {
        Recorder r = runParallel(SlowDownstream.class, SlowUpstream.class);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("SlowUpstream#upstream").status());
        Finished d = r.finished.get("SlowDownstream#downstream");
        assertNotNull(d);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, d.status(), d.message());
        assertTrue(MARKS.get("downstream.start") >= MARKS.get("upstream.end"), "downstream began before upstream ended");
    }

    @Test
    @Timeout(60)
    void parallelFailedUpstreamAbortsDependent() {
        Recorder r = runParallel(FailingDownstream.class, FailingUpstream.class);
        assertEquals(TestExecutionResult.Status.FAILED, r.finished.get("FailingUpstream#upstream").status());
        Finished d = r.finished.get("FailingDownstream#downstream");
        assertNotNull(d);
        assertEquals(TestExecutionResult.Status.ABORTED, d.status(), d.message());
        assertTrue(d.message().contains("failed"), d.message());
        assertFalse(didRun("FailingDownstream#downstream"));
    }

    @Test
    @Timeout(60)
    void parallelBeforeAllFailureAbortsDependentWithoutHanging() {
        Recorder r = runParallel(BeforeAllDownstream.class, BeforeAllBroken.class);
        assertFalse(didRun("BeforeAllBroken#upstream"));
        Finished d = r.finished.get("BeforeAllDownstream#downstream");
        assertNotNull(d);
        assertEquals(TestExecutionResult.Status.ABORTED, d.status(), d.message());
        assertTrue(d.message().contains("ended without running"), d.message());
        assertFalse(didRun("BeforeAllDownstream#downstream"));
    }

    @Test
    @Timeout(60)
    void parallelDisabledUpstreamClassAbortsDependentWithoutHanging() {
        Recorder r = runParallel(DisabledDownstream.class, DisabledUpstream.class);
        assertFalse(didRun("DisabledUpstream#upstream"));
        Finished d = r.finished.get("DisabledDownstream#downstream");
        assertNotNull(d);
        assertEquals(TestExecutionResult.Status.ABORTED, d.status(), d.message());
        // A skipped class reports nothing for its methods; its skip marks them disabled.
        assertTrue(d.message().contains("which is disabled"), d.message());
        assertFalse(didRun("DisabledDownstream#downstream"));
    }

    @Test
    @Timeout(60)
    void parallelDependencyOnParameterizedUpstream() {
        Recorder r = runParallel(ParamDownstream.class, ParamUpstream.class);
        Finished d = r.finished.get("ParamDownstream#downstream");
        assertNotNull(d);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, d.status(), d.message());
        assertEquals(3, COUNT.get(), "all three invocations ran");
        assertEquals(3, SEEN_BY_DOWNSTREAM.get(), "downstream started after every invocation ended");
        assertTrue(MARKS.get("param.downstream.start") > MARKS.get("param.lastEnd"));
    }

    @Test
    @Timeout(60)
    void parallelIntraClassDependencyUnderSharedLockDoesNotDeadlock() {
        Recorder r = runParallel(Locked.class);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("Locked#b").status());
        Finished a = r.finished.get("Locked#a");
        assertNotNull(a);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, a.status(), a.message());
        assertTrue(didRun("Locked#a") && didRun("Locked#b"));
    }

    @Test
    @Timeout(60)
    void parallelRunsIndependentTestsOfAPlainClassConcurrently() {
        Recorder r = runParallel(Plain.class);
        for (String name : List.of("Plain#one", "Plain#two", "Plain#three")) {
            assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get(name).status(), name);
        }
        assertTrue(THREADS.size() > 1, "all tests ran on one thread: " + THREADS);
    }

    // ---- script tests --------------------------------------------------------------------------------------

    @AfterEach
    void forgetScripts() {
        Dependencies.scripts(Map.of());
    }

    /** Ends script test {@code key} as {@code status} after {@code ms}, as the script engine's thread would. */
    private static Thread endScriptLater(String key, TestExecutionResult.Status status, long ms) {
        Thread t = new Thread(() -> {
            sleep(ms);
            MARKS.put("script.end", System.nanoTime());
            Dependencies.scriptEnded(key, status);
        });
        t.start();
        return t;
    }

    @Test
    @Timeout(60)
    void aJavaTestWaitsForTheScriptTestItDependsOn() throws InterruptedException {
        Dependencies.scripts(Map.of(SETUP, List.of()));
        Thread script = endScriptLater(SETUP, TestExecutionResult.Status.SUCCESSFUL, 300);
        Recorder r = run(OnScript.class);
        script.join();
        Finished d = r.finished.get("OnScript#downstream");
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, d.status(), d.message());
        assertTrue(MARKS.get("onScript.start") >= MARKS.get("script.end"), "began before the script test ended");
    }

    @Test
    @Timeout(60)
    void aScriptTestThatDidNotPassAbortsItsJavaDependent() {
        Dependencies.scripts(Map.of(SETUP, List.of()));
        Dependencies.scriptEnded(SETUP, TestExecutionResult.Status.FAILED);
        Finished d = run(OnScript.class).finished.get("OnScript#downstream");
        assertEquals(TestExecutionResult.Status.ABORTED, d.status(), d.message());
        assertTrue(d.message().contains("did not pass (FAILED)"), d.message());
        assertFalse(didRun("OnScript#downstream"));
    }

    @Test
    void aScriptTestNotInTheRunAbortsItsJavaDependent() {
        Finished d = run(OnScript.class).finished.get("OnScript#downstream");
        assertEquals(TestExecutionResult.Status.ABORTED, d.status(), d.message());
        assertTrue(d.message().contains("not in this run"), d.message());
    }

    @Test
    @Timeout(60)
    void scriptsThatNeverRanReleaseTheirDependents() throws InterruptedException {
        Dependencies.scripts(Map.of(SETUP, List.of()));
        Thread over = new Thread(() -> {
            sleep(200);
            Dependencies.scriptsOver();
        });
        over.start();
        Finished d = run(OnScript.class).finished.get("OnScript#downstream");
        over.join();
        assertEquals(TestExecutionResult.Status.ABORTED, d.status(), d.message());
    }

    @Test
    void javaTestsAreOrderedThroughTheScriptTestsBetweenThem() {
        Dependencies.scripts(Map.of(MID, List.of(ThroughScript.class.getName() + "#a")));
        Dependencies.scriptEnded(MID, TestExecutionResult.Status.SUCCESSFUL);
        Recorder r = run(ThroughScript.class);
        assertEquals(List.of("ThroughScript#a", "ThroughScript#c"), r.started);
    }

    @Test
    void aCycleThroughAScriptTestIsReportedByPrepare() {
        Dependencies.scripts(Map.of(LOOP, List.of(CyclicThroughScript.class.getName() + "#x")));
        inLauncher = true;
        try {
            List<String> cycles = Dependencies.prepare(LauncherFactory.create().discover(
                    request(CyclicThroughScript.class)));
            assertEquals(1, cycles.size(), cycles.toString());
            assertTrue(cycles.get(0).contains(LOOP) && cycles.get(0).contains("#x"), cycles.get(0));
        } finally {
            inLauncher = false;
        }
    }

    @Test
    void aCycleOfScriptTestsAloneIsLeftToTheScripts() {
        Dependencies.scripts(Map.of(SETUP, List.of(MID), MID, List.of(SETUP)));
        inLauncher = true;
        try {
            assertTrue(Dependencies.prepare(LauncherFactory.create().discover(request(Plain.class))).isEmpty());
        } finally {
            inLauncher = false;
        }
    }

    @Test
    @Timeout(60)
    void aJavaTestWaitsForTheLockAScriptTestHolds() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        Thread script = new Thread(() -> {
            try (AutoCloseable held = RunLocks.hold(RunLocks.exclusive(Set.of("stagewright.weather")), false)) {
                holding.countDown();
                sleep(300);
                MARKS.put("script.end", System.nanoTime());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.start();
        holding.await();
        Recorder r = runParallel(LockedWithScripts.class);
        script.join();
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("LockedWithScripts#overrides").status());
        assertTrue(MARKS.get("locked.start") >= MARKS.get("script.end"), "ran while a script test held its lock");
    }
}
