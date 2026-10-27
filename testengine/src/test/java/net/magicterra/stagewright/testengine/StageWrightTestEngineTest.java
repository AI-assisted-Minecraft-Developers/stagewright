package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
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
        for (String topology : List.of("pass", "assertFail", "crash", "undiscovered", "dynamic", "world",
                "reqSkip", "otherSkip", "classSkip", "classSkipOther", "script")) {
            writeSpec(specs, topology, true);
        }
        writeSpec(specs, "worldDirty", false);
    }

    private void writeSpec(Path specs, String topology, boolean cleanWorld) throws IOException {
        Path game = Files.createDirectories(temp.resolve("game-" + topology));
        Files.writeString(specs.resolve(topology + ".json"), """
                {"gameDirectory": %s, "cleanWorld": %s, "timeoutMinutes": 2, "stallMinutes": 0, "server": %s,
                 "primary": {"java": %s, "jvmArgs": ["-cp", %s], "mainClass": %s, "args": [%s],
                             "workingDirectory": %s}}
                """.formatted(q(game.toString()), cleanWorld, topology.equals("world"),
                q(System.getProperty("java.home") + "/bin/java"),
                q(System.getProperty("java.class.path")), q(FakeGame.class.getName()), q(topology),
                q(game.toString())));
    }

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty(LaunchSpec.PROPERTY);
        else System.setProperty(LaunchSpec.PROPERTY, previous);
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static EngineExecutionResults run(String fixture) {
        return EngineTestKit.engine("stagewright").selectors(selectClass(FIXTURES + fixture)).execute();
    }

    private static Throwable failure(Event event) {
        return event.getPayload(TestExecutionResult.class).orElseThrow().getThrowable().orElseThrow();
    }

    private static Throwable failureOf(Events tests, String displayName) {
        return tests.failed().stream().filter(e -> e.getTestDescriptor().getDisplayName().equals(displayName))
                .map(StageWrightTestEngineTest::failure).findFirst()
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
    void anAssertionInTheGameStaysAnAssertionHere() {
        EngineExecutionResults r = run("AssertFailFixture");
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals(1, r.testEvents().failed().count());
        Throwable t = failureOf(r.testEvents(), "b()");
        assertInstanceOf(AssertionError.class, t);
        assertEquals("expected 1 but was 2", t.getMessage());
        assertEquals("x.Y", t.getStackTrace()[0].getClassName());
        assertEquals(3, t.getStackTrace()[0].getLineNumber());
        assertEquals(1, r.testEvents().succeeded().stream()
                .filter(e -> e.getTestDescriptor().getDisplayName().equals("a()")).count());
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
        assertTrue(containersFailed(r).stream().anyMatch(e -> e.getTestDescriptor().getDisplayName().equals("crash")));
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
    void aWorldBlockReachesTheGameAndServerPropertiesComeBackAfterwards() throws IOException {
        Path properties = temp.resolve("game-world").resolve("server.properties");
        Files.writeString(properties, "server-port=1\n");
        EngineExecutionResults r = run("WorldBlockFixture");
        assertEquals(2, r.testEvents().succeeded().count(), "tests: " + r.testEvents().failed().stream()
                .map(e -> failure(e).getMessage()).toList());
        assertEquals(0, r.testEvents().failed().count());
        assertEquals("server-port=1\n", Files.readString(properties));
    }

    @Test
    void serverPropertiesTheSceneCreatedAreRemovedWhenThereWasNoneBefore() {
        EngineExecutionResults r = run("WorldBlockFixture");
        // The fake game reports no server-port=1 here, so its check fails; what matters is the file is gone again.
        assertEquals(2, r.testEvents().failed().count());
        assertTrue(!Files.exists(temp.resolve("game-world").resolve("server.properties")));
    }

    @Test
    void theSceneNodePublishesItsWorldAsAReportEntry() {
        EngineExecutionResults r = run("WorldBlockFixture");
        List<Event> published = r.allEvents().reportingEntryPublished().stream()
                .filter(e -> e.getTestDescriptor().getDisplayName().equals("worldBlock")).toList();
        String world = published.stream()
                .flatMap(e -> e.getPayload(org.junit.platform.engine.reporting.ReportEntry.class).stream())
                .map(entry -> entry.getKeyValuePairs().get("world")).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow(() -> new AssertionError("no world entry on the scene: " + published));
        assertTrue(world.contains("seed=5471") && world.contains("preset=minecraft:flat")
                && world.contains("time=6000"), world);
    }

    /** How the engine descriptor itself finished. */
    private static TestExecutionResult engineResult(EngineExecutionResults r) {
        return r.containerEvents().finished().stream()
                .filter(e -> e.getTestDescriptor().getParent().isEmpty())
                .map(e -> e.getPayload(TestExecutionResult.class).orElseThrow())
                .findFirst().orElseThrow(() -> new AssertionError("the engine descriptor never finished"));
    }

    private static void assertEngineSuccessful(EngineExecutionResults r) {
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, engineResult(r).getStatus(),
                () -> String.valueOf(engineResult(r).getThrowable().orElse(null)));
    }

    private static String skipReason(EngineExecutionResults r, String displayName) {
        return r.testEvents().skipped().stream()
                .filter(e -> e.getTestDescriptor().getDisplayName().equals(displayName))
                .map(e -> e.getPayload(String.class).orElse(null)).findFirst()
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
        assertEquals(0, r.testEvents().failed().count(), () -> r.testEvents().failed().stream()
                .map(e -> failure(e).getMessage()).toList().toString());
        assertEquals(0, containersFailed(r).stream()
                .filter(e -> e.getTestDescriptor().getParent().isPresent()).count());
        TestExecutionResult engine = engineResult(r);
        assertEquals(TestExecutionResult.Status.FAILED, engine.getStatus());
        String message = engine.getThrowable().orElseThrow().getMessage();
        assertTrue(message.contains("ClassSkipFixture#a") && message.contains("ClassSkipFixture#b"), message);
    }

    @Test
    void aClassLevelSkipForAnyOtherReasonLeavesNothingFailed() {
        EngineExecutionResults r = run("ClassSkipOtherFixture");
        assertEquals(0, r.testEvents().failed().count(), () -> r.testEvents().failed().stream()
                .map(e -> failure(e).getMessage()).toList().toString());
        assertEquals(0, containersFailed(r).count());
        assertEngineSuccessful(r);
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
                .selectors(selectClasspathResource("stagewright/tests/arithmetic.js")).execute();
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals("adds", r.testEvents().succeeded().list().get(0).getTestDescriptor().getDisplayName());
        // What the test recorded in the game is published here too.
        assertEquals(List.of("2"), r.allEvents().reportingEntryPublished().stream()
                .map(e -> e.getPayload(ReportEntry.class).orElseThrow().getKeyValuePairs().get("sum"))
                .filter(java.util.Objects::nonNull).toList());
        assertEquals("expected 3 but was 2", failureOf(r.testEvents(), "fails").getMessage());
    }

    @Test
    void yamlTestsTakeTheFilesSceneAndAreReportedTheSameWay() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/declared.yaml")).execute();
        assertEquals(1, r.testEvents().succeeded().count());
        assertEquals("expected 3 but was 2", failureOf(r.testEvents(), "fails").getMessage());
    }

    @Test
    void aScriptWhoseTopLevelUsesTheGameFailsHere() {
        EngineExecutionResults r = EngineTestKit.engine("stagewright")
                .selectors(selectClasspathResource("stagewright/tests/usesTheGame.js")).execute();
        String message = failureOf(r.testEvents(), "stagewright/tests/usesTheGame.js").getMessage();
        assertTrue(message.startsWith("stagewright/tests/usesTheGame.js:2: "), message);
        assertTrue(message.contains("cannot use the game"), message);
    }
}
