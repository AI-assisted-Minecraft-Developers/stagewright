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

    /** A loader's run task that left one of its arguments unset. */
    public abstract static class NullArgumentRun extends JavaExec {
        @Override
        public List<String> getAllJvmArgs() {
            return new ArrayList<>(Arrays.asList("-Xmx1g", null));
        }
    }
}
