package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

/**
 * Judges one topology's results file and turns the contract's exit code into a build outcome.
 *
 * <p>Split from the run task on purpose: the game is started by the host build's own run task, and
 * this reads what that run left behind. That split is also why the exit code of the GAME is never
 * consulted — a dedicated server that halts cleanly exits 0 whether every scene passed or every
 * scene failed, so the results file is the only thing that carries a verdict.
 */
public abstract class StageWrightVerdictTask extends DefaultTask {

    /**
     * The results JSONL the run wrote.
     *
     * <p>{@code @Internal}, deliberately, rather than {@code @InputFile}. A missing results file is
     * the single most informative outcome this task has — it means the game never armed the harness —
     * and an {@code @InputFile} would have Gradle abort during input snapshotting with a generic
     * "specified file does not exist" before the action ever runs, throwing away the contract's ENV
     * verdict and the sentence explaining it.
     */
    @org.gradle.api.tasks.Internal
    public abstract RegularFileProperty getResults();

    /** Optional expected-scenes manifest. */
    @InputFile
    @Optional
    public abstract RegularFileProperty getExpectFile();

    /** Topology name, for the report header. */
    @org.gradle.api.tasks.Input
    public abstract Property<String> getTopologyName();

    @TaskAction
    public void judge() {
        File results = getResults().get().getAsFile();
        String topology = getTopologyName().getOrElse("stagewright");

        if (!results.isFile()) {
            throw new GradleException("stagewright " + topology + ": ENV — the run wrote no results"
                    + "\n  expected: " + results.getAbsolutePath()
                    + "\n  The game started and exited without arming the harness. Usual causes: the"
                    + " StageWright mod jar is not in this run's runtime classpath, the run does not"
                    + " set -Dstagewright.autorun=true, or mod loading failed before the server"
                    + " reached its first tick — the run's own log says which."
                    + "\n  exit-code legend: 0 GREEN / 1 RED / 2 DEAD / 3 ENV");
        }

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> records;
        try {
            records = Verdict.parse(results.toPath(), warnings);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + results, e);
        }
        for (String w : warnings) {
            getLogger().warn("[stagewright:{}] {}", topology, w);
        }

        List<String> expected = readExpected();
        Verdict.Result verdict = Verdict.judge(records, expected);
        for (String line : verdict.report()) {
            getLogger().lifecycle("[stagewright:{}] {}", topology, line);
        }
        getLogger().lifecycle("[stagewright:{}] VERDICT: {}", topology, verdict.label());

        if (verdict.code() != 0) {
            throw new GradleException("stagewright " + topology + ": " + verdict.label()
                    + "\n  results: " + results.getAbsolutePath()
                    + "\n  exit-code legend: 0 GREEN / 1 RED / 2 DEAD (the framework itself is broken;"
                    + " this run's results are void) / 3 ENV (the game never armed)");
        }
    }

    private List<String> readExpected() {
        if (!getExpectFile().isPresent()) return null;
        File file = getExpectFile().get().getAsFile();
        List<String> names = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                // '#' comments and blank lines, and commas so a manifest can be written either one
                // name per line or comma-separated without the two forms disagreeing.
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash).trim();
                if (line.isEmpty()) continue;
                for (String part : line.split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty()) names.add(name);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the expected-scenes manifest " + file, e);
        }
        if (names.isEmpty()) {
            throw new GradleException("the expected-scenes manifest " + file + " names no scenes —"
                    + " an empty manifest would silently disable coverage reconciliation");
        }
        return names;
    }
}
