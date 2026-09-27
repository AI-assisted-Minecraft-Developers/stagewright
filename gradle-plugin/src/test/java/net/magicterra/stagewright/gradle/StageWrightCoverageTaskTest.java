package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageWrightCoverageTaskTest {

    @TempDir
    Path dir;

    private static final String SERVER_RAN = """
            {"type":"suite","registered":[{"name":"a"}]}
            {"type":"scene","name":"a","outcome":"PASS"}
            """;

    private Project project() {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply(StageWrightPlugin.class);
        return project;
    }

    private StageWrightTopology topology(Project project, String name, String results) throws IOException {
        StageWrightTopology topology = project.getExtensions().getByType(StageWrightExtension.class)
                .getTopologies().create(name);
        File game = dir.resolve("run-" + name).toFile();
        Files.createDirectories(game.toPath());
        topology.getGameDirectory().set(game);
        Path file = new File(game, topology.getResultsFile().get()).toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, results);
        return topology;
    }

    private void companion(StageWrightTopology topology, String results) throws IOException {
        Path file = dir.resolve(StageWrightPlugin.companionLabel(topology) + ".jsonl");
        Files.writeString(file, results);
        topology.getCompanionResultsFile().set(file.toFile());
    }

    private static StageWrightCoverageTask coverage(Project project) {
        return (StageWrightCoverageTask) project.getTasks().getByName("stagewrightCoverage");
    }

    @Test
    void aCompanionProbeThatNeverRanIsUncovered() throws IOException {
        // The probe is registered only by the client, so only the companion file can name it.
        Project project = project();
        companion(topology(project, "dedicated", SERVER_RAN), """
                {"type":"suite","registered":[{"name":"probe"}]}
                {"type":"scene","name":"probe","outcome":"PASS","skipped":true,"reason":"skipped: no player"}
                """);
        GradleException red = assertThrows(GradleException.class, () -> coverage(project).judge());
        assertTrue(red.getMessage().contains("RED"), red.getMessage());
    }

    @Test
    void aCompanionProbeThatRanCounts() throws IOException {
        Project project = project();
        companion(topology(project, "dedicated", SERVER_RAN), """
                {"type":"suite","registered":[{"name":"probe"}]}
                {"type":"scene","name":"probe","outcome":"PASS"}
                """);
        assertDoesNotThrow(() -> coverage(project).judge());
    }

    @Test
    void aTopologyNamedLikeACompanionLabelIsRefused() throws IOException {
        Project project = project();
        companion(topology(project, "foo", SERVER_RAN), SERVER_RAN);
        topology(project, "foo-client", SERVER_RAN);
        GradleException clash = assertThrows(GradleException.class, () -> coverage(project).judge());
        assertTrue(clash.getMessage().contains("'foo-client'"), clash.getMessage());
    }
}
