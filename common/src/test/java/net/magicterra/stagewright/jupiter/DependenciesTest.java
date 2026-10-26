package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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

    // ---- inner launcher ------------------------------------------------------------------------------------

    private record Finished(TestExecutionResult.Status status, String message) {}

    private static final class Recorder implements TestExecutionListener {
        final List<String> started = new ArrayList<>();
        final Map<String, Finished> finished = new LinkedHashMap<>();

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
}
