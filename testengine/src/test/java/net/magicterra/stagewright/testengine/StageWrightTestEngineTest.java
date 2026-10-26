package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
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
        for (String topology : List.of("pass", "assertFail", "crash", "undiscovered", "dynamic")) {
            Path game = Files.createDirectories(temp.resolve("game-" + topology));
            Files.writeString(specs.resolve(topology + ".json"), """
                    {"gameDirectory": %s, "cleanWorld": true, "timeoutMinutes": 2, "stallMinutes": 0,
                     "primary": {"java": %s, "jvmArgs": ["-cp", %s], "mainClass": %s, "args": [%s],
                                 "workingDirectory": %s}}
                    """.formatted(q(game.toString()), q(System.getProperty("java.home") + "/bin/java"),
                    q(System.getProperty("java.class.path")), q(FakeGame.class.getName()), q(topology),
                    q(game.toString())));
        }
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
    void aSceneWithNoDeclarationFileFails() {
        EngineExecutionResults r = run("NoFileFixture");
        assertEquals(0, r.testEvents().succeeded().count());
        assertEquals(2, r.testEvents().failed().count());
        String message = failureOf(r.testEvents(), "b()").getMessage();
        assertTrue(message.contains("has no declaration"), message);
    }
}
