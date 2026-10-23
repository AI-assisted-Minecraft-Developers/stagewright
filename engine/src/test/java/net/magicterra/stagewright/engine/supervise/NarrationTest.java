package net.magicterra.stagewright.engine.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NarrationTest {

    @Test
    void aLeakIsNamedWithTheJarsItsStackRunsThrough() {
        List<String> lines = Narration.leaks(List.of(new Supervisor.Leak("mod-pool-1",
                List.of("java.base@21/java.lang.Thread.sleep(Native Method)", "x.Pool.run(Pool.java:9)"),
                List.of("file:/mods/x.jar"))));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("WARNING:"), lines.toString());
        // The JDK's own frames, with or without their module prefix, say nothing about whose thread it is.
        assertEquals("  mod-pool-1 at x.Pool.run(Pool.java:9)  [file:/mods/x.jar]", lines.get(1));
    }

    @Test
    void aStackOfNothingButTheJdkFallsBackToItsTopFrame() {
        List<String> lines = Narration.leaks(List.of(new Supervisor.Leak("t",
                List.of("java.lang.Thread.sleep(Native Method)"), List.of())));
        assertEquals("  t at java.lang.Thread.sleep(Native Method)", lines.get(1));
    }

    @Test
    void workersOfOnePoolAreOneLineWithACountAndNameWhoStartedThem() {
        List<String> parked = List.of("java.base@21/jdk.internal.misc.Unsafe.park(Native Method)");
        List<Supervisor.Leak> leaks = new java.util.ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            leaks.add(new Supervisor.Leak("pool-2-thread-" + i, parked, List.of(),
                    List.of("x.Scenes.start", "x.Mod.init"), List.of("file:/mods/x.jar")));
        }
        leaks.add(new Supervisor.Leak("pool-1-thread-1", parked, List.of(), List.of("y.Net.open"), List.of()));
        List<String> lines = Narration.leaks(leaks);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals("  pool-2-thread-* x24 started at x.Scenes.start  [file:/mods/x.jar]", lines.get(1));
        assertEquals("  pool-1-thread-1 started at y.Net.open", lines.get(2));
    }

    @Test
    void aThreadWithoutAnOriginKeepsTheOldWordingAmongThoseWithOne() {
        List<String> lines = Narration.leaks(List.of(
                new Supervisor.Leak("HTTP-Dispatcher", List.of("sun.nio.ch.EPoll.wait(Native Method)"), List.of()),
                new Supervisor.Leak("worker-1", List.of("a.B.run(B.java:1)"), List.of("file:/a.jar"),
                        List.of("a.B.start"), List.of("file:/a.jar"))));
        assertEquals("  HTTP-Dispatcher at sun.nio.ch.EPoll.wait(Native Method)", lines.get(1));
        assertEquals("  worker-1 started at a.B.start  [file:/a.jar]", lines.get(2));
    }

    @Test
    void noLeakSaysNothing() {
        assertEquals(List.of(), Narration.leaks(List.of()));
    }

    @Test
    void aMainThreadInsideGlfwWindowCreationIsAStuckStartAndNotAMod() {
        String said = Narration.stuckStart(List.of(
                new Supervisor.ThreadStack("Server thread", List.of("java.base@21/java.lang.Object.wait(Native Method)")),
                new Supervisor.ThreadStack("main", List.of(
                        "org.lwjgl.system.JNI.invokePV(Native Method)",
                        "org.lwjgl.glfw.GLFW.glfwShowWindow(GLFW.java:4178)",
                        "net.neoforged.fml.loading.progress.DisplayWindow.initWindow(DisplayWindow.java:80)"))));
        assertTrue(said.contains("main is inside GLFW's glfwShowWindow"), said);
        assertTrue(Narration.stuckStart(List.of(new Supervisor.ThreadStack("Render thread", List.of(
                "app//org.lwjgl.glfw.GLFW.glfwCreateWindow(GLFW.java:1)")))).contains("glfwCreateWindow"));
    }

    @Test
    void aStackThatMatchesNothingKnownIsNotClassified() {
        assertEquals(null, Narration.stuckStart(List.of()));
        assertEquals(null, Narration.stuckStart(List.of(new Supervisor.ThreadStack("main", List.of(
                "java.desktop/java.awt.EventQueue.getNextEvent(EventQueue.java:600)")))));
        // Polling events is what a healthy client does all day; only the window calls count.
        assertEquals(null, Narration.stuckStart(List.of(new Supervisor.ThreadStack("Render thread", List.of(
                "org.lwjgl.glfw.GLFW.glfwPollEvents(GLFW.java:1)")))));
    }

    @Test
    void aStallNamesTheMinutesTheScreenAndWhatTheProcessWasDoing() {
        String said = Narration.noProgress("client", 1, "armed", 0, "the title screen", "it is ASLEEP");
        assertTrue(said.contains("for 1 minute:"), said);
        assertTrue(said.contains("sitting on the title screen"), said);
        assertTrue(said.contains("it is ASLEEP."), said);
        assertTrue(Narration.noProgress("server", 5, "armed", 3, null, null).contains("5 minutes"));
    }
}
