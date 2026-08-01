package net.magicterra.stagewright.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle TestKit functional tests for the stagewright plugin. Every test spins up a
 * synthetic consumer project whose {@code pythonExecutable} points at a generated stub
 * that records its argv to a file and exits with a controlled code — so we assert the
 * FROZEN argument assembly and the exit-code propagation semantics without a real game
 * or a real python orchestrator.
 */
class StageWrightPluginFunctionalTest {

    @TempDir
    File projectDir;

    private File argvFile;

    /** Writes settings.gradle + a build.gradle that applies the plugin with {@code testkitConfig}. */
    private void writeConsumer(String testkitConfig) throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(),
            "rootProject.name = 'consumer'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(),
            "plugins { id 'net.magicterra.stagewright' }\n"
                + "testkit {\n" + testkitConfig + "\n}\n");
    }

    /**
     * Generates an executable stub that records each argv element on its own line to
     * {@link #argvFile} and exits with {@code exitCode}. Returns the stub's absolute path.
     */
    private String writeStub(int exitCode) throws IOException {
        argvFile = new File(projectDir, "argv.txt");
        File stub = new File(projectDir, "stub.sh");
        String body = ""
            + "#!/bin/sh\n"
            + ": > '" + argvFile.getAbsolutePath() + "'\n"
            + "for a in \"$@\"; do\n"
            + "  printf '%s\\n' \"$a\" >> '" + argvFile.getAbsolutePath() + "'\n"
            + "done\n"
            + "exit " + exitCode + "\n";
        Files.writeString(stub.toPath(), body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(stub.toPath(),
            PosixFilePermissions.fromString("rwxr-xr-x"));
        return stub.getAbsolutePath();
    }

    private List<String> recordedArgv() throws IOException {
        return Files.readAllLines(argvFile.toPath(), StandardCharsets.UTF_8);
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(args);
    }

    private static String gradlePath(String absolute) {
        // Groovy single-quoted string embedding of an absolute path.
        return "'" + absolute.replace("\\", "\\\\") + "'";
    }

    // (a) plugin applies + tasks exist.
    @Test
    void pluginAppliesAndTasksExist() throws IOException {
        writeConsumer("loader = 'fabric'");
        BuildResult result = runner("tasks", "--group", "verification").build();
        String out = result.getOutput();
        assertTrue(out.contains("stagewrightServer"), out);
        assertTrue(out.contains("stagewrightClient"), out);
        assertTrue(out.contains("stagewrightE2E"), out);
    }

    // (b) stagewrightServer assembles EXACTLY the frozen dogfood argv for loader=fabric.
    @Test
    void stagewrightServerFabricFrozenArgs() throws IOException {
        String stub = writeStub(0);
        writeConsumer("loader = 'fabric'\n  pythonExecutable = " + gradlePath(stub));
        runner("stagewrightServer").build();
        assertEquals(List.of(
            "scripts/stagewright/t0.py",
            "--loader", "fabric",
            "--run-task", ":fabric:runDogfoodServer",
            "--results", "fabric/run-dogfood/testkit-results.jsonl",
            "--expect-file", "scripts/stagewright/expected-scenes-fabric.txt"
        ), recordedArgv());
    }

    // (b) stagewrightServer assembles EXACTLY the frozen dogfood argv for loader=neoforge.
    @Test
    void stagewrightServerNeoforgeFrozenArgs() throws IOException {
        String stub = writeStub(0);
        writeConsumer("loader = 'neoforge'\n  pythonExecutable = " + gradlePath(stub));
        runner("stagewrightServer").build();
        assertEquals(List.of(
            "scripts/stagewright/t0.py",
            "--loader", "neoforge",
            "--run-task", ":neoforge:runDogfoodServer",
            "--results", "neoforge/run-dogfood/testkit-results.jsonl",
            "--expect-file", "scripts/stagewright/expected-scenes-neoforge.txt"
        ), recordedArgv());
    }

    // (c) exit 0 → task success.
    @Test
    void exitZeroSucceeds() throws IOException {
        String stub = writeStub(0);
        writeConsumer("loader = 'fabric'\n  pythonExecutable = " + gradlePath(stub));
        BuildResult result = runner("stagewrightServer").build();
        assertTrue(result.getOutput().contains("[testkit] exec:"), result.getOutput());
    }

    // (d) exit 1 → task failure, message contains the full command and "RED".
    @Test
    void exitOneFailsWithCommandAndRed() throws IOException {
        String stub = writeStub(1);
        writeConsumer("loader = 'fabric'\n  pythonExecutable = " + gradlePath(stub));
        BuildResult result = runner("stagewrightServer").buildAndFail();
        String out = result.getOutput();
        assertTrue(out.contains("RED"), out);
        assertTrue(out.contains("BLOCKED-multiround"), out);          // full legend present
        assertTrue(out.contains("scripts/stagewright/t0.py --loader fabric"), out); // full command
        assertTrue(out.contains(":fabric:runDogfoodServer"), out);
    }

    // (e) exit 3 → failure message contains "ENV".
    @Test
    void exitThreeFailsWithEnv() throws IOException {
        String stub = writeStub(3);
        writeConsumer("loader = 'fabric'\n  pythonExecutable = " + gradlePath(stub));
        BuildResult result = runner("stagewrightServer").buildAndFail();
        String out = result.getOutput();
        assertTrue(out.contains("ENV"), out);
        assertTrue(out.contains("verdict: ENV"), out);
    }

    // (f) expectFile + extraArgs threading on a client topology (t1).
    @Test
    void expectFileAndExtraArgsThreadedOnClient() throws IOException {
        String stub = writeStub(0);
        File expect = new File(projectDir, "my-expect.txt");
        Files.writeString(expect.toPath(), "ui.scene\n");
        writeConsumer(
            "loader = 'neoforge'\n"
                + "  pythonExecutable = " + gradlePath(stub) + "\n"
                + "  expectFile = file(" + gradlePath(expect.getAbsolutePath()) + ")\n"
                + "  extraArgs = ['--wall', '120']");
        runner("stagewrightClient").build();
        assertEquals(List.of(
            "scripts/stagewright/t1.py",
            "--loader", "neoforge",
            "--expect-file", expect.getAbsolutePath(),
            "--wall", "120"
        ), recordedArgv());
    }

    // stagewrightServer ignores extension.expectFile (frozen dogfood expect-file wins) but
    // still appends extraArgs — guards the frozen-table contract against drift.
    @Test
    void stagewrightServerIgnoresExpectFileButKeepsExtraArgs() throws IOException {
        String stub = writeStub(0);
        File expect = new File(projectDir, "my-expect.txt");
        Files.writeString(expect.toPath(), "ui.scene\n");
        writeConsumer(
            "loader = 'fabric'\n"
                + "  pythonExecutable = " + gradlePath(stub) + "\n"
                + "  expectFile = file(" + gradlePath(expect.getAbsolutePath()) + ")\n"
                + "  extraArgs = ['--wall', '300']");
        runner("stagewrightServer").build();
        List<String> argv = recordedArgv();
        // The dogfood expect-file is the loader-derived manifest, NOT the extension value.
        assertTrue(argv.contains("scripts/stagewright/expected-scenes-fabric.txt"), argv.toString());
        assertFalse(argv.contains(expect.getAbsolutePath()), argv.toString());
        assertEquals("300", argv.get(argv.size() - 1), argv.toString());
    }
}
