package net.magicterra.stagewright.gradle;

import java.io.File;
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

    /** Outside the project, so the git wrapper is not part of the work tree it reports on. */
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
    void everyRunInACachedBuildGetsOneIdFromOneGitCall() throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'java'; id 'net.magicterra.stagewright' }
                ['runA', 'runB'].each { name ->
                    tasks.register(name, JavaExec) {
                        mainClass = 'Print'
                        classpath = sourceSets.main.runtimeClasspath
                        args name
                    }
                }
                stagewright { topologies { a { runTask = 'runA' }; b { runTask = 'runB' } } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("Print.java"), """
                public class Print {
                    public static void main(String[] a) {
                        System.out.println("BUILD-" + a[0] + "=" + System.getProperty("stagewright.build"));
                    }
                }
                """);
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "init");

        // A git on PATH that logs each call, so the test can count them.
        Path log = bin.resolve("calls.log");
        Path wrapper = bin.resolve("git");
        Files.writeString(wrapper, "#!/bin/sh\necho \"$*\" >> '" + log + "'\nexec '" + realGit() + "' \"$@\"\n");
        wrapper.toFile().setExecutable(true);
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PATH", bin + File.pathSeparator + System.getenv("PATH"));

        // --continue: the verdicts fail for want of a results file, after both runs have printed.
        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withEnvironment(env)
                .withArguments("stagewrightA", "stagewrightB", "--continue", "--configuration-cache")
                .buildAndFail();
        String out = result.getOutput();
        assertTrue(out.contains("Configuration cache entry stored"), out);
        Matcher a = Pattern.compile("BUILD-runA=(\\S+)").matcher(out);
        Matcher b = Pattern.compile("BUILD-runB=(\\S+)").matcher(out);
        assertTrue(a.find() && b.find(), out);
        assertTrue(a.group(1).startsWith("git:"), a.group(1));
        assertEquals(a.group(1), b.group(1));
        long heads = Files.readAllLines(log).stream().filter(l -> l.contains("rev-parse HEAD")).count();
        assertEquals(1, heads, String.join("\n", Files.readAllLines(log)));
    }

    private static String realGit() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("sh", "-c", "command -v git").start();
        String path = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0 || path.isEmpty()) throw new IllegalStateException("git is not on PATH");
        return path;
    }
}
