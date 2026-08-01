package net.magicterra.stagewright.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle TestKit functional tests for the {@code testmod} source-set convention
 * (P3b T3). v1 boundary under test:
 * <ul>
 *   <li>{@code testmodSourceSet} defaults OFF — zero impact, no source set exists.</li>
 *   <li>ON + {@code java} plugin present — a {@code testmod} source set is registered
 *       whose compile+runtime classpaths include main's output; the read-only
 *       {@code testkit.testmodSourceSetRef} extension property resolves to it.</li>
 *   <li>ON + NO {@code java} plugin — registration is gated via
 *       {@code Plugins.withType(JavaPlugin)}, so the reaction never fires and the build
 *       does NOT crash (documented no-java-plugin behavior choice).</li>
 *   <li>The three orchestrator tasks (stagewrightServer/stagewrightClient/stagewrightE2E) are
 *       unaffected in both flag states.</li>
 * </ul>
 */
class StageWrightTestmodSourceSetFunctionalTest {

    @TempDir
    File projectDir;

    private void writeSettings() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(),
            "rootProject.name = 'consumer'\n");
    }

    private void writeBuildGradle(String content) throws IOException {
        Files.writeString(new File(projectDir, "build.gradle").toPath(), content);
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(args);
    }

    // (a) flag off (default; no `stagewright { }` block at all) → no `testmod` source set,
    // and the read-only extension property resolves to null.
    @Test
    void flagOffDefaultNoTestmodSourceSet() throws IOException {
        writeSettings();
        writeBuildGradle(
            "plugins { id 'java'; id 'net.magicterra.stagewright' }\n"
                + "task checkNoTestmod {\n"
                + "  doLast {\n"
                + "    assert sourceSets.findByName('testmod') == null\n"
                + "    assert testkit.testmodSourceSetRef == null\n"
                + "    println 'OK-NO-TESTMOD'\n"
                + "  }\n"
                + "}\n");
        BuildResult result = runner("checkNoTestmod").build();
        assertTrue(result.getOutput().contains("OK-NO-TESTMOD"), result.getOutput());
    }

    // (b) flag on + `java` plugin present → `testmod` source set exists, its compile AND
    // runtime classpaths contain main's output dir, and the read-only extension property
    // resolves to the same SourceSet.
    @Test
    void flagOnRegistersTestmodWithMainOnClasspath() throws IOException {
        writeSettings();
        writeBuildGradle(
            "plugins { id 'java'; id 'net.magicterra.stagewright' }\n"
                + "stagewright { testmodSourceSet = true }\n"
                + "task checkTestmod {\n"
                + "  doLast {\n"
                + "    def ss = sourceSets.findByName('testmod')\n"
                + "    assert ss != null\n"
                + "    def mainOut = sourceSets.main.output.classesDirs.files\n"
                + "    assert ss.compileClasspath.files.containsAll(mainOut)\n"
                + "    assert ss.runtimeClasspath.files.containsAll(mainOut)\n"
                + "    assert testkit.testmodSourceSetRef != null\n"
                + "    assert testkit.testmodSourceSetRef.name == 'testmod'\n"
                + "    println 'OK-TESTMOD-PRESENT'\n"
                + "  }\n"
                + "}\n");
        BuildResult result = runner("checkTestmod").build();
        assertTrue(result.getOutput().contains("OK-TESTMOD-PRESENT"), result.getOutput());
    }

    // v1 boundary: flag on but the applied project never gains the `java` plugin → the
    // withType(JavaPlugin) reaction never fires, registration is a no-op, and the build
    // must NOT crash. This is the documented no-java-plugin behavior choice.
    @Test
    void flagOnWithoutJavaPluginDoesNotCrash() throws IOException {
        writeSettings();
        writeBuildGradle(
            "plugins { id 'net.magicterra.stagewright' }\n"
                + "stagewright { testmodSourceSet = true }\n"
                + "task checkNoCrash {\n"
                + "  doLast {\n"
                + "    assert testkit.testmodSourceSetRef == null\n"
                + "    println 'OK-NO-CRASH'\n"
                + "  }\n"
                + "}\n");
        BuildResult result = runner("checkNoCrash").build();
        assertTrue(result.getOutput().contains("OK-NO-CRASH"), result.getOutput());
    }

    // (c) three orchestrator tasks unaffected — flag OFF.
    @Test
    void orchestratorTasksUnaffectedFlagOff() throws IOException {
        writeSettings();
        writeBuildGradle("plugins { id 'java'; id 'net.magicterra.stagewright' }\n");
        BuildResult result = runner("tasks", "--group", "verification").build();
        String out = result.getOutput();
        assertTrue(out.contains("stagewrightServer"), out);
        assertTrue(out.contains("stagewrightClient"), out);
        assertTrue(out.contains("stagewrightE2E"), out);
    }

    // (c) three orchestrator tasks unaffected — flag ON.
    @Test
    void orchestratorTasksUnaffectedFlagOn() throws IOException {
        writeSettings();
        writeBuildGradle(
            "plugins { id 'java'; id 'net.magicterra.stagewright' }\n"
                + "stagewright { testmodSourceSet = true }\n");
        BuildResult result = runner("tasks", "--group", "verification").build();
        String out = result.getOutput();
        assertTrue(out.contains("stagewrightServer"), out);
        assertTrue(out.contains("stagewrightClient"), out);
        assertTrue(out.contains("stagewrightE2E"), out);
    }
}
