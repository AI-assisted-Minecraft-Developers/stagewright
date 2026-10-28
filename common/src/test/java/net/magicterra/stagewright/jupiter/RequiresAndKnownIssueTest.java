package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.magicterra.stagewright.capability.Mods;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.KnownIssue;
import net.magicterra.stagewright.test.Requires;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * {@link Requires} and {@link KnownIssue} end to end, through an inner launcher configured as {@link JupiterRun}
 * configures the in-game one (extension auto-detection on). The samples are disabled in the outer run and enabled
 * only while the inner launcher runs.
 */
class RequiresAndKnownIssueTest {

    private static final String AWAIT = "stagewright.awaitPlayer";

    private static volatile boolean inLauncher;

    /** Condition of every sample class: true only while this test's own launcher runs. */
    public static boolean inLauncher() {
        return inLauncher;
    }

    private static final Set<String> RAN = new HashSet<>();

    private String previousAwait;
    private Object previousMods;
    private Object previousGame;

    @BeforeEach
    void saveGlobals() throws Exception {
        synchronized (RAN) {
            RAN.clear();
        }
        previousAwait = System.getProperty(AWAIT);
        System.clearProperty(AWAIT);
        previousMods = staticField(Mods.class, "installed").get(null);
        previousGame = staticField(GameExtension.class, "game").get(null);
    }

    @AfterEach
    void restoreGlobals() throws Exception {
        if (previousAwait == null) System.clearProperty(AWAIT);
        else System.setProperty(AWAIT, previousAwait);
        staticField(Mods.class, "installed").set(null, previousMods);
        staticField(GameExtension.class, "game").set(null, previousGame);
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
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

    @EnabledIf("net.magicterra.stagewright.jupiter.RequiresAndKnownIssueTest#inLauncher")
    static class Known {
        @Test
        @KnownIssue("broken upstream")
        void failing() {
            ran("Known#failing");
            throw new IllegalStateException("the original failure");
        }

        @Test
        @KnownIssue("fixed already")
        void passing() {
            ran("Known#passing");
        }

        @Test
        @KnownIssue("needs a thing")
        void aborting() {
            Assumptions.abort("no thing here");
        }

        @Test
        void plainFailing() {
            fail("plain failure");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.RequiresAndKnownIssueTest#inLauncher")
    static class Capabilities {
        @Test
        @Requires(Requires.Capability.DEDICATED_SERVER)
        void dedicated() {
            ran("Capabilities#dedicated");
        }

        @Test
        @Requires(Requires.Capability.INTEGRATED_SERVER)
        void integrated() {
            ran("Capabilities#integrated");
        }

        @Test
        @Requires(Requires.Capability.CLIENT)
        void client() {
            ran("Capabilities#client");
        }

        @Test
        void unannotated() {
            ran("Capabilities#unannotated");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.RequiresAndKnownIssueTest#inLauncher")
    @Requires(Requires.Capability.INTEGRATED_SERVER)
    static class WholeClass {
        @Test
        void one() {
            ran("WholeClass#one");
        }

        @Test
        void two() {
            ran("WholeClass#two");
        }
    }

    @EnabledIf("net.magicterra.stagewright.jupiter.RequiresAndKnownIssueTest#inLauncher")
    static class ModsSample {
        @Test
        @Requires(mods = "present")
        void present() {
            ran("ModsSample#present");
        }

        @Test
        @Requires(mods = "absent")
        void absent() {
            ran("ModsSample#absent");
        }
    }

    // ---- inner launcher ------------------------------------------------------------------------------------

    private record Finished(TestExecutionResult.Status status, String message, Throwable cause) {}

    private static final class Recorder implements TestExecutionListener {
        /** Finished tests, by "Class#method". */
        final Map<String, Finished> finished = new LinkedHashMap<>();
        /** Skipped nodes (tests and containers), by "Class#method" or "Class", with the reason. */
        final Map<String, String> skipped = new LinkedHashMap<>();

        private static String name(TestIdentifier id) {
            var source = id.getSource().orElse(null);
            if (source instanceof MethodSource m) {
                String cls = m.getClassName();
                return cls.substring(cls.lastIndexOf('$') + 1) + "#" + m.getMethodName();
            }
            if (source instanceof ClassSource c) {
                String cls = c.getClassName();
                return cls.substring(cls.lastIndexOf('$') + 1);
            }
            return null;
        }

        @Override
        public void executionSkipped(TestIdentifier id, String reason) {
            String name = name(id);
            if (name != null) skipped.put(name, reason);
        }

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            if (!id.isTest()) return;
            String name = name(id);
            if (name != null) {
                finished.put(name, new Finished(result.getStatus(),
                        result.getThrowable().map(Throwable::getMessage).orElse(null),
                        result.getThrowable().map(Throwable::getCause).orElse(null)));
            }
        }
    }

    /** A game whose server is dedicated or not, whatever it is asked. */
    private static Game gameOn(boolean dedicated) {
        return (Game) Proxy.newProxyInstance(Game.class.getClassLoader(), new Class<?>[] {Game.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "onServer" -> dedicated;
                    case "runOnServer" -> {
                        @SuppressWarnings("unchecked")
                        Consumer<Object> work = (Consumer<Object>) args[0];
                        work.accept(null);
                        yield null;
                    }
                    default -> null;
                });
    }

    private static Recorder run(boolean dedicated, Class<?> sample) {
        inLauncher = true;
        try {
            GameExtension.bind(gameOn(dedicated));
            List<DiscoverySelector> selectors = new ArrayList<>();
            selectors.add(DiscoverySelectors.selectClass(sample));
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectors)
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    .build();
            Launcher launcher = LauncherFactory.create();
            TestPlan plan = launcher.discover(request);
            Recorder recorder = new Recorder();
            launcher.execute(plan, recorder);
            return recorder;
        } finally {
            inLauncher = false;
        }
    }

    private static void assertSkippedRequiring(Recorder r, String name, String what) {
        String reason = r.skipped.get(name);
        assertNotNull(reason, name + " was not skipped; finished=" + r.finished.get(name));
        assertTrue(reason.startsWith("@Requires: "), reason);
        assertTrue(reason.contains(what), reason);
        assertFalse(r.finished.containsKey(name), name + " also finished");
    }

    // ---- @KnownIssue ---------------------------------------------------------------------------------------

    @Test
    void aFailingKnownIssueIsAbortedWithTheReasonAndTheOriginalCause() {
        Finished f = run(true, Known.class).finished.get("Known#failing");
        assertNotNull(f);
        assertEquals(TestExecutionResult.Status.ABORTED, f.status(), f.message());
        assertTrue(f.message().contains("broken upstream"), f.message());
        assertTrue(f.cause() instanceof IllegalStateException, String.valueOf(f.cause()));
        assertEquals("the original failure", f.cause().getMessage());
    }

    @Test
    void aPassingKnownIssueFailsNamingTheAnnotation() {
        Recorder r = run(true, Known.class);
        Finished f = r.finished.get("Known#passing");
        assertNotNull(f);
        assertEquals(TestExecutionResult.Status.FAILED, f.status(), f.message());
        assertTrue(f.message().contains("@KnownIssue"), f.message());
        assertTrue(didRun("Known#passing"));
    }

    @Test
    void anAbortingKnownIssueStaysAborted() {
        Finished f = run(true, Known.class).finished.get("Known#aborting");
        assertNotNull(f);
        assertEquals(TestExecutionResult.Status.ABORTED, f.status(), f.message());
        assertFalse(f.message().contains("known issue"), "the abort should pass through unwrapped: " + f.message());
        assertTrue(f.message().contains("no thing here"), f.message());
    }

    @Test
    void anUnannotatedFailureStaysFailed() {
        Finished f = run(true, Known.class).finished.get("Known#plainFailing");
        assertNotNull(f);
        assertEquals(TestExecutionResult.Status.FAILED, f.status(), f.message());
        assertEquals("plain failure", f.message());
    }

    // ---- @Requires -----------------------------------------------------------------------------------------

    @Test
    void onADedicatedGameDedicatedRunsAndIntegratedIsSkippedNamingIt() {
        Recorder r = run(true, Capabilities.class);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("Capabilities#dedicated").status());
        assertTrue(didRun("Capabilities#dedicated"));
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("Capabilities#unannotated").status());
        assertSkippedRequiring(r, "Capabilities#integrated", "INTEGRATED_SERVER");
        assertFalse(didRun("Capabilities#integrated"));
    }

    @Test
    void onADedicatedGameClientIsSkippedUnlessAPlayerIsAwaited() {
        Recorder without = run(true, Capabilities.class);
        assertSkippedRequiring(without, "Capabilities#client", "CLIENT");
        assertFalse(didRun("Capabilities#client"));

        System.setProperty(AWAIT, "true");
        Recorder with = run(true, Capabilities.class);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, with.finished.get("Capabilities#client").status());
        assertTrue(didRun("Capabilities#client"));
    }

    @Test
    void onAnIntegratedGameClientRunsAndDedicatedIsSkipped() {
        Recorder r = run(false, Capabilities.class);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("Capabilities#client").status());
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("Capabilities#integrated").status());
        assertSkippedRequiring(r, "Capabilities#dedicated", "DEDICATED_SERVER");
    }

    @Test
    void aClassLevelRequirementSkipsTheWholeClass() {
        Recorder dedicated = run(true, WholeClass.class);
        assertSkippedRequiring(dedicated, "WholeClass", "INTEGRATED_SERVER");
        assertTrue(dedicated.finished.isEmpty(), dedicated.finished.toString());
        assertFalse(didRun("WholeClass#one"));
        assertFalse(didRun("WholeClass#two"));

        Recorder integrated = run(false, WholeClass.class);
        assertNull(integrated.skipped.get("WholeClass"));
        assertEquals(2, integrated.finished.size());
        assertTrue(didRun("WholeClass#one") && didRun("WholeClass#two"));
    }

    @Test
    void modsAreRequiredByIdAgainstTheInstalledList() {
        Mods.install(Map.of("present", "1"));
        Recorder r = run(true, ModsSample.class);
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, r.finished.get("ModsSample#present").status());
        assertSkippedRequiring(r, "ModsSample#absent", "mod absent");
        assertFalse(didRun("ModsSample#absent"));
    }

    @Test
    void modsRequirementWithNoInstalledListFailsLoudly() throws Exception {
        staticField(Mods.class, "installed").set(null, null);
        Recorder r = run(true, ModsSample.class);
        // The condition throwing is a failure of the test, never a silent skip.
        Finished f = r.finished.get("ModsSample#present");
        assertNotNull(f, "skipped=" + r.skipped);
        assertEquals(TestExecutionResult.Status.FAILED, f.status(), f.message());
        assertTrue(f.message().contains("never installed"), f.message());
        assertFalse(didRun("ModsSample#present"));
    }
}
