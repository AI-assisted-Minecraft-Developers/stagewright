package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.Progress;
import net.magicterra.stagewright.engine.Verdict;
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

    /**
     * The results a companion CLIENT wrote, when this topology has one that probes.
     *
     * <p>Judged as its own self-contained suite rather than merged into the server's records: the
     * two files are written by two processes with their own headers, footers and scene lists, and
     * concatenating them would produce a stream with two headers that the contract has no meaning
     * for. Reconciled against its own header instead of the expected-scenes manifest, which names
     * the SERVER's scenes — the client's probes are not scenes and do not run on every topology.
     *
     * <p>{@code @Internal} for the same reason as {@link #getResults()}: a missing file is a
     * verdict this task must be allowed to report, not an input-snapshotting error.
     */
    @org.gradle.api.tasks.Internal
    public abstract RegularFileProperty getCompanionResults();

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
        // Only ever non-empty when nothing wrote a footer — see Progress. Logged as well as attached
        // to the failure below, because the two are read in different situations: the log line is
        // what a human scrolling the run sees, the exception is what CI quotes.
        String position = Progress.lastKnownPosition(results.toPath(), records).orElse(null);
        if (position != null) {
            getLogger().lifecycle("[stagewright:{}] {}", topology, position);
        }

        Verdict.Result companion = judgeCompanion(topology);

        getLogger().lifecycle("[stagewright:{}] VERDICT: {}", topology, verdict.label());

        // The worse of the two wins, and the codes are already ordered by severity: GREEN 0 < RED 1
        // < DEAD 2 < ENV 3. A green server with a red client is a red run — the whole reason the
        // client half asserts anything is that the server cannot see what it sees.
        Verdict.Result worst = companion != null && companion.code() > verdict.code()
                ? companion : verdict;
        if (worst.code() != 0) {
            throw new GradleException("stagewright " + topology + ": " + worst.label()
                    + (worst == companion ? " (from the companion client)" : "")
                    + "\n  results: " + results.getAbsolutePath()
                    + (position == null ? "" : "\n  " + position)
                    + (companion == null ? ""
                        : "\n  client results: " + getCompanionResults().get().getAsFile().getAbsolutePath())
                    + "\n  exit-code legend: 0 GREEN / 1 RED / 2 DEAD (the framework itself is broken;"
                    + " this run's results are void) / 3 ENV (the game never armed)");
        }
    }

    /**
     * Judge the companion client's probe results, or null when this topology declares none.
     *
     * <p>A declared-but-absent file is reported rather than ignored: the client was launched to
     * assert something, and silence from it is the one outcome that must never read as agreement.
     */
    private Verdict.Result judgeCompanion(String topology) {
        if (!getCompanionResults().isPresent()) return null;
        File file = getCompanionResults().get().getAsFile();
        if (!file.isFile()) {
            getLogger().lifecycle("[stagewright:{}] client: ENV — the companion client wrote no"
                    + " results at {}", topology, file.getAbsolutePath());
            return new Verdict.Result(3, List.of());
        }
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> records;
        try {
            records = Verdict.parse(file.toPath(), warnings);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        for (String w : warnings) {
            getLogger().warn("[stagewright:{}] client: {}", topology, w);
        }
        Verdict.Result result = Verdict.judge(records, null);
        for (String line : result.report()) {
            getLogger().lifecycle("[stagewright:{}] client: {}", topology, line);
        }
        getLogger().lifecycle("[stagewright:{}] CLIENT VERDICT: {}", topology, result.label());
        return result;
    }

    private List<String> readExpected() {
        if (!getExpectFile().isPresent()) return null;
        File file = getExpectFile().get().getAsFile();
        List<String> names = net.magicterra.stagewright.engine.Manifest.read(file.toPath());
        if (names.isEmpty()) {
            throw new GradleException("the expected-scenes manifest " + file + " names no scenes —"
                    + " an empty manifest reconciles against nothing and would pass any run");
        }
        return names;
    }
}
