package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import net.magicterra.stagewright.engine.supervise.Supervisor;
import org.junit.jupiter.api.Test;

/** What a scene's failure says about how its game ended, without starting one. */
class SceneRunReportTest {

    private static final File LOG = new File("scene-game.log");
    private static final List<Supervisor.ThreadStack> STUCK = List.of(new Supervisor.ThreadStack("Server thread",
            List.of("com.mod.Locks.await(Locks.java:12)", "net.minecraft.server.MinecraftServer.tickServer(x)")));

    @Test
    void aStallNamesWhereTheServerThreadStood() {
        String why = SceneRun.describe(Supervisor.Ended.STALLED, "armed", 812, null, STUCK, LOG);
        assertTrue(why.startsWith("(STALLED) stalled in phase armed at tick 812; its log is scene-game.log"), why);
        assertTrue(why.contains("\n  Server thread\n    at com.mod.Locks.await(Locks.java:12)\n    at net.minecraft"), why);
    }

    @Test
    void aStallWithNoSampleSaysOnlyThePhaseAndTick() {
        assertEquals("(STALLED) stalled in phase armed at tick 812; its log is scene-game.log",
                SceneRun.describe(Supervisor.Ended.STALLED, "armed", 812, null, List.of(), LOG));
    }

    @Test
    void aGameDoneWithTestsMissingCarriesNoStacks() {
        String why = SceneRun.describe(Supervisor.Ended.DONE, "armed", 812, null, STUCK, LOG);
        assertFalse(why.contains("Server thread"), why);
    }

    @Test
    void aForcedDownNamesEachThreadItsCodeAndWhoStartedIt() {
        Supervisor.Leak leak = new Supervisor.Leak("mod-pool-1",
                List.of("java.lang.Thread.sleep(Native)", "a.B.c(B.java:1)", "a.B.d(B.java:2)", "a.B.e(B.java:3)",
                        "a.B.f(B.java:4)"),
                List.of("file:/mods/b.jar"), List.of("a.Mod.init", "a.Mod.load"), List.of("file:/mods/mod.jar"));
        String report = SceneRun.forcedDown(0, List.of(leak), true, 30);
        assertTrue(report.startsWith("the game had to be forced down: its JVM was still up 30s after its run, held by"
                + " 1 thread:\n  mod-pool-1\n    at java.lang.Thread.sleep(Native)"), report);
        // The top frames only.
        assertTrue(report.contains("a.B.e(B.java:3)") && !report.contains("a.B.f"), report);
        assertTrue(report.contains("\n    code from file:/mods/b.jar"), report);
        assertTrue(report.contains("\n    started by a.Mod.init in file:/mods/mod.jar"), report);
    }

    @Test
    void aForcedDownWithNoThreadSaysSo() {
        assertEquals("round 1's game had to be forced down: its JVM was still up 30s after its run, held by no thread"
                + " the agent could name", SceneRun.forcedDown(1, List.of(), true, 30));
    }

    @Test
    void aGameThatOutlivedTheAgentTooWasKilled() {
        assertEquals("the game was still up 40s after its run and was killed",
                SceneRun.forcedDown(0, List.of(), false, 30));
    }
}
