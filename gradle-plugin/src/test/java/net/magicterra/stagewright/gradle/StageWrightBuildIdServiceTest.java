package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StageWrightBuildIdServiceTest {

    @TempDir
    Path dir;

    @Test
    void aFirstRunGitGaveNoIdDoesNotSetTheBuildsId() throws Exception {
        StageWrightBuildIdService service = service();
        assertEquals(List.of(), service.arguments(":run", List.of()));
        committed();
        List<String> id = service.arguments(":run", List.of());
        assertEquals(1, id.size());
        assertTrue(id.get(0).startsWith("-Dstagewright.build=git:"), id.get(0));
        assertEquals(id, service.arguments(":run", List.of()));
    }

    @Test
    void anEditNothingTheRunLoadsWasWrittenAfterKeepsTheBuildsId() throws Exception {
        committed();
        List<File> classpath = List.of(written(-60_000).toFile());
        StageWrightBuildIdService service = service();
        String first = service.arguments(":a", classpath).get(0);
        Files.writeString(dir.resolve("Scene.java"), "class Scene { int x; }");
        assertEquals(first, service.arguments(":b", classpath).get(0));
    }

    @Test
    void aRunGitCannotReadKeepsTheBuildsIdUnlessWhatItLoadsWasWrittenSince() throws Exception {
        committed();
        Path classes = written(-60_000);
        StageWrightBuildIdService service = service();
        String first = service.arguments(":a", List.of(classes.toFile())).get(0);
        Files.move(dir.resolve(".git"), dir.resolve("moved.git"));
        assertEquals(first, service.arguments(":b", List.of(classes.toFile())).get(0));

        Files.setLastModifiedTime(classes, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        String unread = service.arguments(":c", List.of(classes.toFile())).get(0);
        assertTrue(unread.startsWith("-Dstagewright.build=changed during build "), unread);
        assertTrue(unread.endsWith(first.substring("-Dstagewright.build=".length()) + " -> unknown"), unread);
    }

    @Test
    void twoRunsThatMayHaveLoadedAnEditMatchNeitherEachOtherNorTheFirst() throws Exception {
        committed();
        Path classes = written(-60_000);
        StageWrightBuildIdService service = service();
        String first = service.arguments(":a", List.of(classes.toFile())).get(0);
        Files.writeString(dir.resolve("Scene.java"), "class Scene { int x; }");
        Files.setLastModifiedTime(classes, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        String b = service.arguments(":b", List.of(classes.toFile())).get(0);
        String c = service.arguments(":c", List.of(classes.toFile())).get(0);
        assertTrue(b.startsWith("-Dstagewright.build=changed during build "), b);
        assertNotEquals(first, b);
        assertNotEquals(b, c);
    }

    @Test
    void aRunIsAnsweredOnceHoweverOftenItsArgumentIsAsked() throws Exception {
        committed();
        var project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        var service = registered(project);
        service.get().arguments(":first", List.of());
        Path classes = written(60_000);
        Files.writeString(dir.resolve("Scene.java"), "class Scene { int x; }");
        BuildIdArgument argument = argument(service, ":run", project.files(classes.toFile()));
        // Changed, so each fresh answer would carry a nonce of its own.
        assertEquals(argument.asArguments(), argument.asArguments());
    }

    @Test
    void aCompanionStartedAfterTheTreeChangedCarriesOneId() throws Exception {
        committed();
        var project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        var service = registered(project);
        service.get().arguments(":first", List.of());
        Files.writeString(dir.resolve("Scene.java"), "class Scene { int x; }");
        var companion = project.getTasks().register("runClient", org.gradle.api.tasks.JavaExec.class).get();
        companion.getJvmArgumentProviders().add(
                argument(service, companion.getPath(), project.files(written(60_000).toFile())));
        List<String> ids = SideProcesses.jvmArgs(companion).stream()
                .filter(a -> a.startsWith("-Dstagewright.build=")).toList();
        assertEquals(1, ids.size(), ids.toString());
    }

    @Test
    void aCompanionWhoseEagerArgumentsLeaveTheIdOutStillCarriesOne() throws Exception {
        committed();
        var project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        var service = registered(project);
        var companion = project.getTasks().register("runClient", LoaderRun.class).get();
        companion.getJvmArgumentProviders().add(argument(service, companion.getPath(), project.files()));
        assertEquals(1, SideProcesses.jvmArgs(companion).stream()
                .filter(a -> a.startsWith("-Dstagewright.build=git:")).count());
    }

    @Test
    void aNullArgumentBesideTheBuildIdIsStillNamedWhenTheCompanionStarts() throws Exception {
        committed();
        var project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        var service = registered(project);
        var companion = project.getTasks().register("runClient", SideProcessesTest.NullArgumentRun.class).get();
        companion.getMainClass().set("Client");
        companion.getJvmArgumentProviders().add(argument(service, companion.getPath(), project.files()));
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(org.gradle.api.GradleException.class,
                () -> SideProcesses.startCompanion(companion, dir.resolve("client.log").toFile(), project.getLogger()));
        assertTrue(thrown.getMessage().contains("null"), thrown.getMessage());
    }

    /** A loader's run task whose eager list is its own, without what argument providers add. */
    public abstract static class LoaderRun extends org.gradle.api.tasks.JavaExec {
        @Override
        public List<String> getAllJvmArgs() {
            return new ArrayList<>(getJvmArgs());
        }
    }

    @Test
    void aTreeChangedBackAfterARunSawItChangedNoLongerVouchesForWhatWasCompiledMeanwhile() throws Exception {
        committed();
        Path classes = written(-60_000);
        StageWrightBuildIdService service = service();
        String first = service.arguments(":a", List.of(classes.toFile())).get(0);
        Thread.sleep(5);
        Files.writeString(dir.resolve("Scene.java"), "class Scene { int x; }");
        assertEquals(first, service.arguments(":b", List.of(classes.toFile())).get(0));
        // Compiled from the edit, then the edit is undone and :c finds the tree as it was.
        Files.setLastModifiedTime(classes, FileTime.fromMillis(System.currentTimeMillis()));
        Files.writeString(dir.resolve("Scene.java"), "class Scene {}");
        assertEquals(first, service.arguments(":c", List.of()).get(0));
        Files.writeString(dir.resolve("Scene.java"), "class Scene { int x; }");
        String d = service.arguments(":d", List.of(classes.toFile())).get(0);
        assertTrue(d.startsWith("-Dstagewright.build=changed during build "), d);
    }

    private static BuildIdArgument argument(org.gradle.api.provider.Provider<StageWrightBuildIdService> service,
                                            String run, org.gradle.api.file.FileCollection classpath) {
        return new BuildIdArgument(service, run, classpath, java.util.Map.of(), List.of());
    }

    /** A class file, untracked, last modified {@code offsetMs} from now. */
    private Path written(long offsetMs) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("build/classes"));
        Path klass = Files.writeString(classes.resolve("Scene.class"), "bytes");
        FileTime when = FileTime.fromMillis(System.currentTimeMillis() + offsetMs);
        Files.setLastModifiedTime(klass, when);
        Files.setLastModifiedTime(classes, when);
        return classes;
    }

    private static org.gradle.api.provider.Provider<StageWrightBuildIdService> registered(
            org.gradle.api.Project project) {
        return project.getGradle().getSharedServices().registerIfAbsent("stagewrightBuildId",
                StageWrightBuildIdService.class,
                spec -> spec.getParameters().getWorkTree().set(project.getProjectDir()));
    }

    private StageWrightBuildIdService service() {
        return registered(ProjectBuilder.builder().withProjectDir(dir.toFile()).build()).get();
    }

    private void committed() throws Exception {
        assumeTrue(git("init", "-q") == 0, "git is not available");
        Files.writeString(dir.resolve("Scene.java"), "class Scene {}");
        Files.writeString(dir.resolve(".gitignore"), "build/\n");
        assertEquals(0, git("add", "Scene.java", ".gitignore"));
        assertEquals(0, git("commit", "-qm", "c"));
    }

    private int git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false",
                "-c", "user.name=t", "-c", "user.email=t@t"));
        command.addAll(List.of(args));
        try {
            return new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            return -1;
        }
    }
}
