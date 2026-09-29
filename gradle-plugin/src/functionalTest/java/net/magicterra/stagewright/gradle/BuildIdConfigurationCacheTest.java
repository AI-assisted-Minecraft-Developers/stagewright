package net.magicterra.stagewright.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildIdConfigurationCacheTest {

    @TempDir
    Path dir;

    /** Outside the project, so the git trace is not part of the work tree it reports on. */
    @TempDir
    Path bin;

    private void git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=t",
                "-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed");
    }

    @Test
    void aPlainServerTopologyStoresAConfigurationCacheEntry() throws Exception {
        // A git work tree, so there is a build id to take and the plugin has a reason to run git.
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'net.magicterra.stagewright' }
                tasks.register('runServer', JavaExec) {
                    mainClass = 'none'
                    classpath = files()
                }
                stagewright { topologies { dedicated { runTask = 'runServer' } } }
                """);
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "init");

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("stagewrightDedicated", "--dry-run", "--configuration-cache")
                .build();
        assertTrue(result.getOutput().contains("Configuration cache entry stored"), result.getOutput());
    }

    @Test
    void anEditNoRunLoadsKeepsTheBuildsIdAcrossTheBuild() throws Exception {
        twoRuns(false);
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "init");

        // Git's own trace of each command it runs, so the test can count them on any platform.
        Path log = bin.resolve("git-trace.log");
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("GIT_TRACE", log.toAbsolutePath().toString());

        String stored = gate(env);
        assertTrue(stored.contains("Configuration cache entry stored"), stored);
        String a = id(stored, "runA");
        assertTrue(a.startsWith("git:") && !a.contains("+"), a);
        // The tree changed, but nothing runB loads was written since runA read it.
        assertEquals(a, id(stored, "runB"));
        long heads = Files.readAllLines(log).stream().filter(l -> l.contains("git rev-parse HEAD")).count();
        assertEquals(2, heads, String.join("\n", Files.readAllLines(log)));

        // A cache hit replays the configuration, not the id: the tree is edited from the start now.
        String reused = gate(env);
        assertTrue(reused.contains("Reusing configuration cache"), reused);
        String again = id(reused, "runA");
        assertTrue(again.startsWith(a + "+"), again);
        assertEquals(again, id(reused, "runB"));
    }

    @Test
    void aRunThatLoadsWhatWasWrittenAfterTheTreeChangedMatchesNoOtherRun() throws Exception {
        twoRuns(true);
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "init");

        String output = gate(System.getenv());
        String a = id(output, "runA");
        String b = id(output, "runB");
        assertTrue(a.startsWith("git:") && !a.contains("+"), a);
        // runB's classpath was written after the edit, so whether it holds the edit is unknown.
        assertTrue(b.startsWith("changed during build ") && b.contains(": " + a + " -> " + a + "+"), b);
    }

    @Test
    void classesALoaderNamesInModClassesAreCheckedLikeTheClasspath() throws Exception {
        twoRuns(true, true);
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "init");

        String output = gate(System.getenv());
        String a = id(output, "runA");
        String b = id(output, "runB");
        assertTrue(a.startsWith("git:") && !a.contains("+"), a);
        // Off runB's classpath, but its loader hands the game the directory written after the edit.
        assertTrue(b.startsWith("changed during build "), b);
    }

    @Test
    void aBuildGitGivesNoIdSaysSoOnce() throws Exception {
        twoRuns(false);
        String output = gate(System.getenv());
        assertEquals("null", id(output, "runA"));
        assertEquals("null", id(output, "runB"));
        assertEquals(1, output.split("no build id for", -1).length - 1, output);
    }

    /**
     * Two runs in one build, with an edit to a tracked-to-be file between them. With
     * {@code writesAfter}, a task between the edit and runB writes into runB's classpath, as a
     * compile of the edited source would. With {@code modClasses}, that directory is not on the
     * classpath but named in a {@code MOD_CLASSES} the run task binds late, as loom does.
     */
    private void twoRuns(boolean writesAfter) throws IOException {
        twoRuns(writesAfter, false);
    }

    private void twoRuns(boolean writesAfter, boolean modClasses) throws IOException {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'java'; id 'net.magicterra.stagewright' }
                def late = layout.buildDirectory.dir('late')
                abstract class LoomRun extends JavaExec {
                    @Internal abstract MapProperty<String, String> getInternalEnvironmentVars()
                }
                ['runA', 'runB'].each { name ->
                    tasks.register(name, LoomRun) {
                        mainClass = 'Print'
                        classpath = sourceSets.main.runtimeClasspath%s
                        internalEnvironmentVars.put('MOD_CLASSES', late.map { 'mod%%%%' + it.asFile.path })
                        args name
                    }
                }""".formatted(modClasses ? "" : " + files(late)") + """


                // An edit between two runs, as a long gate can see one.
                def notes = file('notes.txt')
                tasks.register('edit') {
                    mustRunAfter 'runA'
                    doLast { notes.text = 'edited' }
                }
                tasks.register('writeLate') {
                    dependsOn 'edit'
                    def out = late
                    doLast { out.get().file('late.txt').asFile.with { parentFile.mkdirs(); text = 'x' } }
                }
                tasks.named('runB') { dependsOn(%s); mustRunAfter 'runA' }
                stagewright { topologies { a { runTask = 'runA' }; b { runTask = 'runB' } } }
                """.formatted(writesAfter ? "'writeLate'" : "'edit'"));
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("Print.java"), """
                public class Print {
                    public static void main(String[] a) {
                        System.out.println("BUILD-" + a[0] + "=" + System.getProperty("stagewright.build"));
                    }
                }
                """);
        Files.writeString(dir.resolve("notes.txt"), "clean");
    }

    /** --continue: the verdicts fail for want of a results file, after both runs have printed. */
    private String gate(Map<String, String> env) {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withEnvironment(env)
                .withArguments("stagewrightA", "stagewrightB", "--continue", "--configuration-cache")
                .buildAndFail()
                .getOutput();
    }

    private static String id(String output, String run) {
        Matcher m = Pattern.compile("BUILD-" + run + "=(.+)").matcher(output);
        assertTrue(m.find(), output);
        return m.group(1).trim();
    }
}
