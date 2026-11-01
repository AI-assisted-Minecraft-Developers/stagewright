package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;

/** The outer engine against a scripted fake game in a real JVM with the real supervision agent. */
class StageWrightTestEngineTest {

    private static final String FIXTURES = "net.magicterra.stagewright.testengine.fixtures.";

    @TempDir
    Path temp;

    private String previous;

    @BeforeEach
    void launchSpecsIn() throws IOException {
        previous = System.getProperty(LaunchSpec.PROPERTY);
        Path specs = Files.createDirectories(temp.resolve("launches"));
        System.setProperty(LaunchSpec.PROPERTY, specs.toString());
        for (String topology : List.of(
                "pass",
                "assertFail",
                "crash",
                "undiscovered",
                "dynamic",
                "world",
                "reqSkip",
                "otherSkip",
                "classSkip",
                "classSkipOther",
                "script",
                "restart",
                "leak",
                "leakReport")) {
            writeSpec(specs, topology, true);
        }
        writeSpec(specs, "worldDirty", false);
        writeSpec(specs, "clientStays", true, "companionStays");
        writeSpec(specs, "clientLeaves", true, "companionLeaves");
        writeSpec(specs, "clientCrashes", true, "companionCrashes");
        Files.writeString(specs.resolve("unlaunchable.json"), "{\"unavailable\": \"no --client was given\"}");
    }

    private void writeSpec(Path specs, String topology, boolean cleanWorld) throws IOException {
        writeSpec(specs, topology, cleanWorld, null);
    }

    /** With {@code companion}, a second fake game joined to the first, in a directory of its own, as a client is. */
    private void writeSpec(Path specs, String topology, boolean cleanWorld, String companion) throws IOException {
        Path game = Files.createDirectories(temp.resolve("game-" + topology));
        Files.writeString(specs.resolve(topology + ".json"), """
                {"gameDirectory": %s, "cleanWorld": %s, "timeoutMinutes": 2, "stallMinutes": 0, "server": %s,%s
                 "primary": %s%s}
                """.formatted(
                        q(game.toString()),
                        cleanWorld,
                        "world".equals(topology),
                        "leakReport".equals(topology) ? " \"leaks\": \"report\"," : "",
                        process(topology, game),
                        companion == null ? "" : ", \"companion\": " + process(companion, game.resolve("client"))));
    }

    private static String process(String scenario, Path directory) {
        return """
                {"java": %s, "jvmArgs": ["-cp", %s], "mainClass": %s, "args": [%s], "workingDirectory": %s}""".formatted(
                        q(System.getProperty("java.home") + "/bin/java"),
                        q(System.getProperty("java.class.path")),
                        q(FakeGame.class.getName()),
                        q(scenario),
                        q(directory.toString()));
    }

    @AfterEach
    void restore() {
        if (previous == null) {
            System.clearProperty(LaunchSpec.PROPERTY);
        } else {
            System.setProperty(LaunchSpec.PROPERTY, previous);
        }
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static EngineExecutionResults run(String fixture) {
        return EngineTestKit.engine("stagewright")
                .selectors(selectClass(FIXTURES + fixture))
                .execute();
    }

    private static Throwable failure(Event event) {
        return event.getPayload(TestExecutionResult.class)
                .orElseThrow()
                .getThrowable()
                .orElseThrow();
    }

    private static Throwable failureOf(Events tests, String displayName) {
        return tests.failed().stream()
                .filter(e -> e.getTestDescriptor().getDisplayName().equals(displayName))
                .map(StageWrightTestEngineTest::failure)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no failure for " + displayName));
    }

    private static Events containersFailed(EngineExecutionResults r) {
        return r.containerEvents().failed();
    }

    @Test
    void aPassingSceneReportsEveryTestSucceeded() {
        EngineExecutionResults r = run("PassFixture");
        assertEquals(2, r.testEvents().succeeded().count());
        assertEquals(0, r.testEvents().failed().count());
        assertEquals(0, containersFailed(r).count());
    }

    @Test
    void aGameTheAgentHadToForceDownFailsItsSceneNamingTheThread() throws IOException {
        int grace = SceneRun.agentGraceSeconds;
        SceneRun.agentGraceSeconds = 1;
        EngineExecutionResults r;
        try {
            r = run("LeakFixture");
        } finally {
            SceneRun.agentGraceSeconds = grace;
        }
        // The tests themselves passed; it is the scene that fails.
        assertEquals(2, r.testEvents().succeeded().count());
        assertEquals(0, r.testEvents().failed().count());
        Event scene = containersFailed(r).stream()
                .filter(e -> "leak".equals(e.getTestDescriptor().getDisplayName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the scene should fail"));
        String message = failure(scene).getMessage();
        assertTrue(message.contains("had to be forced down") && message.contains("mod-pool-1"), message);
        assertTrue(message.contains("started by "), message);
        assertTrue(r.allEvents().reportingEntryPublished().stream()
                .map(e -> e.getPayload(ReportEntry.class)
                        .orElseThrow()
                        .getKeyValuePairs()
                        .get("forcedDown"))
                .anyMatch(v -> v != null && v.contains("mod-pool-1")));
    }

    @Test
    void aTopologyThatReportsLeaksNamesTheThreadAndKeepsTheScenePassing() throws IOException {
        int grace = SceneRun.agentGraceSeconds;
        SceneRun.agentGraceSeconds = 1;
        EngineExecutionResults r;
        try {
            r = run("LeakReportFixture");
        } finally {
            SceneRun.agentGraceSeconds = grace;
        }
        assertEquals(2, r.testEvents().succeeded().count());
        assertEquals(0, r.testEvents().failed().count());
        assertEquals(
                0,
                containersFailed(r).count(),
                () -> String.valueOf(containersFailed(r).list()));
        assertTrue(r.allEvents().reportingEntryPublished().stream()
                .map(e -> e.getPayload(ReportEntry.class)
                        .orElseThrow()
                        .getKeyValuePairs()
                        .get("forcedDown"))
                .anyMatch(v -> v != null
                        && v.contains("had to be forced down")
                        && v.contains("mod-pool-1")
                        && v.contains("started by ")));
    }

    @Test
    void aJoinedClientThatStaysChangesNothing() {
        EngineExecutionResults r = run("ClientStaysFixture");
        assertEquals(
                2,
                r.testEvents().succeeded().count(),
                () -> String.valueOf(r.testEvents().failed().list()));
        assertEquals(
                0,
                containersFailed(r).count(),
                () -> String.valueOf(containersFailed(r).list()));
    }

    @Test
    void aJoinedClientThatCrashesIsSeenThroughItsAgent() {
        // The client stays up after writing its crash report: only the agent in it can tell.
        EngineExecutionResults r = run("ClientCrashesFixture");
        String message = failureOf(r.testEvents(), "a()").getMessage();
        assertTrue(message.contains("the joined client ended (CRASHED) by crashing: "), message);
        assertTrue(message.contains("crash-client.txt"), message);
    }

    @Test
    void aJoinedClientThatLeavesDuringTheRunFailsTheSceneNamingTheClient() throws IOException {
        EngineExecutionResults r = run("ClientLeavesFixture");
        assertEquals(0, r.testEvents().succeeded().count());
        String message = failureOf(r.testEvents(), "a()").getMessage();
        assertTrue(message.contains("the joined client ended (PROCESS_DIED)"), message);
        assertTrue(message.contains("clientLeaves-companion.log"), message);
        Event scene = containersFailed(r).stream()
                .filter(e -> "clientLeaves".equals(e.getTestDescriptor().getDisplayName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the scene should fail"));
        assertTrue(
                failure(scene).getMessage().startsWith("the joined client ended (PROCESS_DIED)"),
                failure(scene).getMessage());
        assertTrue(r.allEvents().reportingEntryPublished().stream()
                .map(e -> e.getPayload(ReportEntry.class)
                        .orElseThrow()
                        .getKeyValuePairs()
                        .get("companion"))
                .anyMatch(v -> v != null && v.contains("PROCESS_DIED")));
    }

    @Test
    void anAssertionInTheGameStaysAnAssertionHere() {
        EngineExecutionResults r = run("AssertFailFixture");
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals(1, r.testEvents().failed().count());
        Throwable t = failureOf(r.testEvents(), "b()");
        assertInstanceOf(AssertionError.class, t);
        assertEquals("expected 1 but was 2", t.getMessage());
        assertEquals("x.Y", t.getStackTrace()[0].getClassName());
        assertEquals(3, t.getStackTrace()[0].getLineNumber());
        assertEquals(
                1,
                r.testEvents().succeeded().stream()
                        .filter(e -> "a()".equals(e.getTestDescriptor().getDisplayName()))
                        .count());
    }

    @Test
    void aGameThatDiesFailsWhatItDidNotFinish() {
        EngineExecutionResults r = run("CrashFixture");
        assertEquals(0, r.testEvents().succeeded().count());
        assertEquals(2, r.testEvents().failed().count());
        for (String name : List.of("a()", "b()")) {
            String message = failureOf(r.testEvents(), name).getMessage();
            assertTrue(message.contains("PROCESS_DIED"), message);
        }
        assertTrue(containersFailed(r).count() >= 1, "the scene container should fail");
        assertTrue(containersFailed(r).stream()
                .anyMatch(e -> "crash".equals(e.getTestDescriptor().getDisplayName())));
    }

    @Test
    void aRestartTheGameAsksForRunsTheNextRoundOnTheSameDirectory() {
        EngineExecutionResults r = run("RestartFixture");
        assertEquals(
                0,
                r.testEvents().failed().count(),
                () -> String.valueOf(r.testEvents().failed().list()));
        assertEquals(2, r.testEvents().succeeded().count());
        assertEquals(0, containersFailed(r).count());
        assertTrue(
                r.allEvents().reportingEntryPublished().stream()
                        .anyMatch(e -> e.toString().contains("round 1")),
                "the scene should report its restart");
    }

    @Test
    void aTestTheGameDidNotDiscoverFails() {
        EngineExecutionResults r = run("UndiscoveredFixture");
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals(1, r.testEvents().failed().count());
        String message = failureOf(r.testEvents(), "b()").getMessage();
        assertTrue(message.contains("did not discover"), message);
    }

    @Test
    void invocationsTheGameRegistersAreReportedUnderTheirContainer() {
        EngineExecutionResults r = run("DynamicFixture");
        assertEquals(2, r.allEvents().dynamicallyRegistered().count());
        assertEquals(2, r.testEvents().succeeded().count());
        assertEquals(0, r.testEvents().failed().count());
        assertEquals(0, containersFailed(r).count());
    }

    @Test
    void aSceneDeclaringAnUnappliedKeyFailsNamingIt() {
        EngineExecutionResults r = run("UnappliedFixture");
        assertEquals(0, r.testEvents().succeeded().count());
        assertEquals(2, r.testEvents().failed().count());
        String message = failureOf(r.testEvents(), "a()").getMessage();
        assertTrue(message.contains("world"), message);
    }

    @Test
    void creationSettingsOnAWorldThatIsKeptFailTheSceneNamingCleanWorld() {
        EngineExecutionResults r = run("WorldDirtyFixture");
        assertEquals(0, r.testEvents().succeeded().count());
        assertEquals(2, r.testEvents().failed().count());
        String message = failureOf(r.testEvents(), "a()").getMessage();
        assertTrue(message.contains("cleanWorld"), message);
    }

    @Test
    void aWorldBlockReachesTheGameAndTheTopologysServerPropertiesAreLeftAlone() throws IOException {
        Path properties = temp.resolve("game-world").resolve("server.properties");
        Files.writeString(properties, "server-port=1\n");
        EngineExecutionResults r = run("WorldBlockFixture");
        assertEquals(
                2,
                r.testEvents().succeeded().count(),
                "tests: "
                        + r.testEvents().failed().stream()
                                .map(e -> failure(e).getMessage())
                                .toList());
        assertEquals(0, r.testEvents().failed().count());
        assertEquals("server-port=1\n", Files.readString(properties));
    }

    @Test
    void serverPropertiesTheSceneWritesNeverReachTheTopologysDirectory() {
        EngineExecutionResults r = run("WorldBlockFixture");
        // The fake game reports no server-port=1 here, so its check fails; what matters is where the file went.
        assertEquals(2, r.testEvents().failed().count());
        assertTrue(!Files.exists(temp.resolve("game-world").resolve("server.properties")));
        assertTrue(Files.isRegularFile(
                SceneDirectory.of(temp.resolve("game-world"), "worldBlock").resolve("server.properties")));
    }

    @Test
    void aPassingSceneKeepsOnlyItsArtifactsInItsOwnDirectory() throws IOException {
        Path topology = temp.resolve("game-pass");
        Files.writeString(topology.resolve("options.txt"), "copied");
        Files.createDirectories(topology.resolve("mods"));
        EngineExecutionResults r = run("PassFixture");
        assertEquals(2, r.testEvents().succeeded().count());
        Path scene = SceneDirectory.of(topology, "pass");
        assertTrue(
                Files.isRegularFile(scene.resolve("stagewright/logs/pass-game.log")), "its log, under its directory");
        try (var left = Files.list(scene)) {
            assertEquals(
                    List.of("stagewright"),
                    left.map(p -> p.getFileName().toString()).toList());
        }
        assertTrue(r.allEvents().reportingEntryPublished().stream()
                .map(e -> e.getPayload(ReportEntry.class)
                        .orElseThrow()
                        .getKeyValuePairs()
                        .get("directory"))
                .anyMatch(scene.toString()::equals));
        assertTrue(Files.isDirectory(topology.resolve("mods")), "the shared directory is not touched through the link");
    }

    @Test
    void scenesRunSideBySideEachInItsOwnDirectory() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .configurationParameter(SceneScheduler.SCENES, "3")
                .selectors(
                        selectClass(FIXTURES + "PassFixture"),
                        selectClass(FIXTURES + "DynamicFixture"),
                        selectClass(FIXTURES + "AssertFailFixture"))
                .execute();
        assertEquals(
                5,
                r.testEvents().succeeded().count(),
                () -> String.valueOf(r.testEvents().failed().list()));
        assertEquals(1, r.testEvents().failed().count());
        assertEquals(0, containersFailed(r).count());
        for (String scene : List.of("pass", "dynamic", "assertFail")) {
            assertTrue(
                    Files.isRegularFile(SceneDirectory.of(temp.resolve("game-" + scene), scene)
                            .resolve("stagewright/logs/" + scene + "-game.log")),
                    scene);
        }
    }

    @Test
    void aFailingSceneKeepsItsWholeDirectory() throws IOException {
        Path topology = temp.resolve("game-assertFail");
        Files.writeString(topology.resolve("options.txt"), "copied");
        Files.createDirectories(topology.resolve("mods"));
        run("AssertFailFixture");
        Path scene = SceneDirectory.of(topology, "assertFail");
        assertEquals("copied", Files.readString(scene.resolve("options.txt")));
        assertTrue(
                Files.isDirectory(scene.resolve("mods"), LinkOption.NOFOLLOW_LINKS),
                "mods/ is a real directory, not a link to the topology's");
    }

    /** How the engine descriptor itself finished. */
    private static TestExecutionResult engineResult(EngineExecutionResults r) {
        return r.containerEvents().finished().stream()
                .filter(e -> e.getTestDescriptor().getParent().isEmpty())
                .map(e -> e.getPayload(TestExecutionResult.class).orElseThrow())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the engine descriptor never finished"));
    }

    private static void assertEngineSuccessful(EngineExecutionResults r) {
        assertEquals(
                TestExecutionResult.Status.SUCCESSFUL,
                engineResult(r).getStatus(),
                () -> String.valueOf(engineResult(r).getThrowable().orElse(null)));
    }

    private static String skipReason(EngineExecutionResults r, String displayName) {
        return r.testEvents().skipped().stream()
                .filter(e -> e.getTestDescriptor().getDisplayName().equals(displayName))
                .map(e -> e.getPayload(String.class).orElse(null))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not reported skipped: " + displayName));
    }

    @Test
    void aTestSkippedForARequirementInEveryItsSceneFailsTheEngineNamingIt() {
        EngineExecutionResults r = run("ReqSkipFixture");
        TestExecutionResult engine = engineResult(r);
        assertEquals(TestExecutionResult.Status.FAILED, engine.getStatus());
        String message = engine.getThrowable().orElseThrow().getMessage();
        assertTrue(message.contains("ReqSkipFixture#b"), message);
        assertTrue(message.contains("reqSkip"), message);
        assertTrue(message.contains("this game has no CLIENT"), message);
        assertTrue(!message.contains("ReqSkipFixture#a"), message);
        // The test itself is skipped, not failed; the failure belongs to the run.
        assertEquals(1, r.testEvents().skipped().count());
        assertTrue(skipReason(r, "b()").startsWith("@Requires: "));
        assertEquals(0, r.testEvents().failed().count());
        assertEquals(1, r.testEvents().succeeded().count());
    }

    @Test
    void aRequirementMetInAnotherSceneKeepsTheEngineSuccessful() {
        EngineExecutionResults r = run("TwoScenesFixture");
        assertEngineSuccessful(r);
        assertEquals(0, r.testEvents().failed().count());
        assertEquals(1, r.testEvents().skipped().count());
        assertEquals(3, r.testEvents().succeeded().count());
    }

    @Test
    void aSkipForAnyOtherReasonEverywhereKeepsTheEngineSuccessful() {
        EngineExecutionResults r = run("OtherSkipFixture");
        assertEngineSuccessful(r);
        assertEquals(0, r.testEvents().failed().count());
        assertEquals("@Disabled", skipReason(r, "b()"));
    }

    @Test
    void aClassLevelRequirementSkipDoesNotFailItsMethodsAsUndiscoveredButFailsTheEngine() {
        EngineExecutionResults r = run("ClassSkipFixture");
        assertEquals(
                0,
                r.testEvents().failed().count(),
                () -> r.testEvents().failed().stream()
                        .map(e -> failure(e).getMessage())
                        .toList()
                        .toString());
        assertEquals(
                0,
                containersFailed(r).stream()
                        .filter(e -> e.getTestDescriptor().getParent().isPresent())
                        .count());
        TestExecutionResult engine = engineResult(r);
        assertEquals(TestExecutionResult.Status.FAILED, engine.getStatus());
        String message = engine.getThrowable().orElseThrow().getMessage();
        assertTrue(message.contains("ClassSkipFixture#a") && message.contains("ClassSkipFixture#b"), message);
    }

    @Test
    void aClassLevelSkipForAnyOtherReasonLeavesNothingFailed() {
        EngineExecutionResults r = run("ClassSkipOtherFixture");
        assertEquals(
                0,
                r.testEvents().failed().count(),
                () -> r.testEvents().failed().stream()
                        .map(e -> failure(e).getMessage())
                        .toList()
                        .toString());
        assertEquals(0, containersFailed(r).count());
        assertEngineSuccessful(r);
    }

    /** How the scene node named {@code scene} was skipped; fails when it was not. */
    private static String sceneSkipReason(EngineExecutionResults r, String scene) {
        return r.containerEvents().skipped().stream()
                .filter(e -> e.getTestDescriptor() instanceof Nodes.Scene s && s.name.equals(scene))
                .map(e -> e.getPayload(String.class).orElse(null))
                .findFirst()
                .orElseThrow(() -> new AssertionError("scene not reported skipped: " + scene));
    }

    @Test
    void aSceneWhoseTopologyTheRunCannotLaunchIsSkippedWithTheReason() {
        EngineExecutionResults r = run("UnlaunchableFixture");
        assertEquals(0, r.testEvents().failed().count());
        assertEquals(0, r.testEvents().started().count(), "no game was started for it");
        assertEquals(0, containersFailed(r).count());
        String reason = sceneSkipReason(r, "unlaunchable");
        assertTrue(reason.contains("no --client was given"), reason);
        assertEngineSuccessful(r);
    }

    @Test
    void aTestOnlyAnUnlaunchableSceneCouldRunDoesNotFailTheRunForCoverage() {
        EngineExecutionResults r = run("ReqSkipOrUnlaunchableFixture");
        assertEquals(0, r.testEvents().failed().count());
        assertEquals("@Requires: this game has no CLIENT", skipReason(r, "b()"));
        sceneSkipReason(r, "unlaunchable");
        assertEngineSuccessful(r);
    }

    @Test
    void aMissingLaunchSpecFailsItsTestsUnlessTheRunSaysToSkipThem() {
        EngineExecutionResults failed = run("MissingSpecFixture");
        assertEquals(2, failed.testEvents().failed().count());
        assertTrue(failureOf(failed.testEvents(), "a()").getMessage().contains("has no launch spec"));
        System.setProperty(LaunchSpec.MISSING, "skip");
        try {
            EngineExecutionResults skipped = run("MissingSpecFixture");
            assertEquals(0, skipped.testEvents().failed().count());
            String reason = sceneSkipReason(skipped, "missingSpec");
            assertTrue(reason.contains("noSpecWritten.json is missing"), reason);
            assertEngineSuccessful(skipped);
        } finally {
            System.clearProperty(LaunchSpec.MISSING);
        }
    }

    @Test
    void aSceneWithNoDeclarationFileFails() {
        EngineExecutionResults r = run("NoFileFixture");
        assertEquals(0, r.testEvents().succeeded().count());
        assertEquals(2, r.testEvents().failed().count());
        String message = failureOf(r.testEvents(), "b()").getMessage();
        assertTrue(message.contains("has no declaration"), message);
    }

    @Test
    void scriptTestsAreFoundWithoutTheGameAndReportedFromIt() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/arithmetic.js"))
                .execute();
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals(
                "adds",
                r.testEvents().succeeded().list().get(0).getTestDescriptor().getDisplayName());
        // What the test recorded in the game is published here too.
        assertEquals(
                List.of("2"),
                r.allEvents().reportingEntryPublished().stream()
                        .map(e -> e.getPayload(ReportEntry.class)
                                .orElseThrow()
                                .getKeyValuePairs()
                                .get("sum"))
                        .filter(java.util.Objects::nonNull)
                        .toList());
        assertEquals("expected 3 but was 2", failureOf(r.testEvents(), "fails").getMessage());
    }

    @Test
    void aRecordedValueIsAReportEntryABlankOneIncluded() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/arithmetic.js"))
                .execute();
        List<java.util.Map<String, String>> entries = r.allEvents().reportingEntryPublished().stream()
                .map(e -> e.getPayload(ReportEntry.class).orElseThrow().getKeyValuePairs())
                .toList();
        assertTrue(entries.contains(java.util.Map.of("note", "two\nlines")), entries.toString());
        // A report entry cannot hold a blank value: the blank one names itself under a key of its own.
        assertTrue(
                entries.contains(java.util.Map.of(StageWrightTestEngine.BLANK_RECORD, "none found")),
                entries.toString());
    }

    @Test
    void yamlTestsTakeTheFilesSceneAndAreReportedTheSameWay() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/declared.yaml"))
                .execute();
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals("expected 3 but was 2", failureOf(r.testEvents(), "fails").getMessage());
    }

    /**
     * A run as a Gradle {@code Test} task makes it: the fixture class selected, the script tests found under the test
     * root, and Gradle's {@code --tests} filter applied to what the engine discovered.
     */
    private EngineExecutionResults runFiltered(String fixture, String pattern) {
        String root = System.getProperty(StageWrightTestEngine.TEST_ROOTS);
        Path resources = Path.of(java.net.URI.create(getClass()
                        .getClassLoader()
                        .getResource("stagewright/tests/arithmetic.js")
                        .toString()))
                .getParent()
                .getParent()
                .getParent();
        System.setProperty(StageWrightTestEngine.TEST_ROOTS, resources.toString());
        try {
            return EngineTestKit.engine("stagewright")
                    .selectors(selectClass(FIXTURES + fixture))
                    .filters(new GradleTestsFilter(pattern))
                    .execute();
        } finally {
            if (root == null) {
                System.clearProperty(StageWrightTestEngine.TEST_ROOTS);
            } else {
                System.setProperty(StageWrightTestEngine.TEST_ROOTS, root);
            }
        }
    }

    private static List<String> scenesStarted(EngineExecutionResults r) {
        return r.containerEvents().started().stream()
                .map(Event::getTestDescriptor)
                .filter(d -> d instanceof Nodes.Scene)
                .map(TestDescriptor::getDisplayName)
                .toList();
    }

    @Test
    void aTestsFilterNamingAJavaClassStartsNoScriptScene() {
        EngineExecutionResults r = runFiltered("PassFixture", "*PassFixture");
        assertEquals(List.of("pass"), scenesStarted(r));
        assertEquals(2, r.testEvents().succeeded().count());
        assertEquals(
                0,
                r.testEvents().failed().count(),
                () -> String.valueOf(r.testEvents().failed().list()));
        assertTrue(!Files.exists(temp.resolve("game-script").resolve("stagewright")), "no game for the script scene");
    }

    @Test
    void aTestsFilterNamingAScriptRunsThatScriptOnly() {
        EngineExecutionResults r = runFiltered("PassFixture", "*arithmetic*");
        assertEquals(List.of("script"), scenesStarted(r));
        // The game was given that file alone, or it would not have reported these two under it.
        assertEquals(
                List.of("adds"),
                r.testEvents().succeeded().stream()
                        .map(e -> e.getTestDescriptor().getDisplayName())
                        .toList());
        assertEquals("expected 3 but was 2", failureOf(r.testEvents(), "fails").getMessage());
        // The unreadable file and the YAML one were left out as well.
        assertEquals(1, r.testEvents().failed().count());
    }

    @Test
    void aTestsFilterMatchesAScriptTestByItsPathAndName() {
        EngineExecutionResults r = runFiltered("PassFixture", "stagewright/tests/arithmetic.js.adds");
        assertEquals(List.of("script"), scenesStarted(r));
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals(
                0,
                r.testEvents().failed().count(),
                () -> String.valueOf(r.testEvents().failed().list()));
    }

    @Test
    void scriptNodesNameTheirSceneAndFileAsTheClassAndTheTestAsTheMethod() {
        TestDescriptor test = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/arithmetic.js"))
                .execute()
                .testEvents()
                .succeeded()
                .list()
                .get(0)
                .getTestDescriptor();
        assertEquals(
                java.util.Optional.of(MethodSource.from("stagewright/tests/arithmetic.js", "adds")), test.getSource());
        TestDescriptor file = test.getParent().orElseThrow();
        assertEquals(
                java.util.Optional.of(ClassSource.from("script/stagewright/tests/arithmetic.js")), file.getSource());
        assertEquals(
                java.util.Optional.of(ClassSource.from("script")),
                file.getParent().orElseThrow().getSource());
    }

    /** Gradle files a test's XML by the nearest class source above it: the scene and the class, not the class alone. */
    @Test
    void javaNodesNameTheirSceneAndClassAsTheClass() {
        TestDescriptor test =
                run("PassFixture").testEvents().succeeded().list().get(0).getTestDescriptor();
        assertEquals(FIXTURES + "PassFixture", ((MethodSource) test.getSource().orElseThrow()).getClassName());
        TestDescriptor testClass = test.getParent().orElseThrow();
        assertEquals(
                java.util.Optional.of(ClassSource.from("pass/" + FIXTURES + "PassFixture")), testClass.getSource());
        assertEquals(
                java.util.Optional.of(ClassSource.from("pass")),
                testClass.getParent().orElseThrow().getSource());
    }

    /**
     * What Gradle 8.14's JUnit Platform runner applies for {@code --tests} ({@code JUnitPlatformTestClassProcessor
     * .ClassMethodNameFilter}), for one fully qualified pattern: a node under a matching class is kept; a method source
     * is matched as {@code class.method}; a class source without children as the class; any other source defers to the
     * parent, and a node without a source is kept.
     */
    private static final class GradleTestsFilter implements PostDiscoveryFilter {
        private final Pattern pattern;

        GradleTestsFilter(String glob) {
            StringBuilder regex = new StringBuilder();
            // As Gradle's TestSelectionMatcher prepares one.
            for (String part : glob.split("\\*", -1)) {
                if (part.isEmpty()) {
                    regex.append(".*");
                    continue;
                }
                if (!regex.isEmpty()) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            this.pattern = Pattern.compile(regex.toString());
        }

        private boolean matches(String className, String method) {
            return pattern.matcher(className).matches()
                    || method != null
                            && pattern.matcher(className + "." + method).matches();
        }

        @Override
        public FilterResult apply(TestDescriptor descriptor) {
            for (TestDescriptor d = descriptor;
                    d.getParent().isPresent();
                    d = d.getParent().get()) {
                if (d.getSource().orElse(null) instanceof ClassSource c && matches(c.getClassName(), null)) {
                    return FilterResult.included("class match");
                }
            }
            return FilterResult.includedIf(shouldRun(descriptor, false));
        }

        private boolean shouldRun(TestDescriptor d, boolean checkingParent) {
            TestSource source = d.getSource().orElse(null);
            if (source == null) {
                return true;
            }
            if (source instanceof MethodSource m) {
                return matches(m.getClassName(), m.getMethodName())
                        || d.getParent()
                                .flatMap(p -> p.getSource()
                                        .filter(ClassSource.class::isInstance)
                                        .map(ClassSource.class::cast))
                                .filter(c -> matches(c.getClassName(), m.getMethodName()))
                                .isPresent();
            }
            if (source instanceof ClassSource c) {
                if (!checkingParent) {
                    for (TestDescriptor child : d.getChildren()) {
                        if (shouldRun(child, false)) {
                            return true;
                        }
                    }
                }
                return !d.getChildren().isEmpty()
                        || matches(c.getClassName(), null)
                        || matches(c.getClassName(), d.getLegacyReportingName());
            }
            return d.getParent().isPresent() && shouldRun(d.getParent().get(), true);
        }
    }

    @Test
    void aScriptWhoseTopLevelUsesTheGameFailsHere() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/usesTheGame.js"))
                .execute();
        String message =
                failureOf(r.testEvents(), "stagewright/tests/usesTheGame.js").getMessage();
        assertTrue(message.startsWith("stagewright/tests/usesTheGame.js:2: "), message);
        assertTrue(message.contains("cannot use the game"), message);
    }
}
