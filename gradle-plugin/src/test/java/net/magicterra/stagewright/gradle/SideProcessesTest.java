package net.magicterra.stagewright.gradle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.tasks.JavaExec;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideProcessesTest {

    @TempDir
    Path dir;

    @Test
    void aNullArgumentALoaderLeftIsNamedWhenTheCompanionStarts() {
        var project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        var companion = project.getTasks().register("runClient", NullArgumentRun.class).get();
        companion.getMainClass().set("Client");
        var thrown = assertThrows(GradleException.class,
                () -> SideProcesses.startCompanion(companion, dir.resolve("client.log").toFile(), project.getLogger()));
        assertTrue(thrown.getMessage().contains("null"), thrown.getMessage());
    }

    @Test
    void modClassesNamesEachDirectoryWithOrWithoutItsMod() {
        String sep = java.io.File.pathSeparator;
        var dirs = SideProcesses.modClasses(java.util.Map.of("MOD_CLASSES",
                "worlddriver%%/a/classes" + sep + "worlddriver%%/a/resources" + sep + "/bare"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(new java.io.File("/a/classes"),
                new java.io.File("/a/resources"), new java.io.File("/bare")), dirs);
        assertTrue(SideProcesses.modClasses(java.util.Map.of()).isEmpty());
    }

    /** A loader's run task that left one of its arguments unset. */
    public abstract static class NullArgumentRun extends JavaExec {
        @Override
        public List<String> getAllJvmArgs() {
            return new ArrayList<>(Arrays.asList("-Xmx1g", null));
        }
    }
}
