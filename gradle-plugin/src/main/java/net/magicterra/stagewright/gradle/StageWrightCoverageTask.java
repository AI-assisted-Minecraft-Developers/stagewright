package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.BuildId;
import net.magicterra.stagewright.engine.Coverage;
import net.magicterra.stagewright.engine.Verdict;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * Reconciles every topology's results against each other: did each scene execute SOMEWHERE?
 *
 * <p>Deliberately does NOT depend on the run tasks. Two reasons, and the second is the one that
 * would bite. First, the runs bind a fixed server port, so they are already strictly sequential and
 * the host build — not this task — decides their order. Second, a {@code dependsOn} would make this
 * task unreachable exactly when it is most informative: a topology whose verdict is RED throws, the
 * build stops, and the cross-run report that would have said "and by the way three scenes have
 * never run anywhere" is never produced.
 *
 * <p>So it reads what the runs left behind, and a results file that is not there is a reported
 * failure naming the topology rather than a silently smaller reconciliation. Dropping it would be
 * worse than useless: the union of executed scenes would shrink, and the missing run's scenes would
 * be blamed on the runs that did happen.
 */
public abstract class StageWrightCoverageTask extends DefaultTask {

    /**
     * Topology name to the results file it writes.
     *
     * <p>{@code @Internal} for the same reason as {@code StageWrightVerdictTask.getResults()}: a
     * missing file is a verdict this task must be able to report, and an input annotation would have
     * Gradle abort during snapshotting with a generic message before the action ever runs.
     */
    @Internal
    public abstract MapProperty<String, RegularFile> getResultsByTopology();

    /**
     * Companion client results, keyed like the descriptor labels the client: topology name plus
     * {@code -client}. Its probe is a registered scene like any other, and a probe that skipped on
     * every topology is as untested as a server scene that did.
     */
    @Internal
    public abstract MapProperty<String, RegularFile> getCompanionResultsByTopology();

    @TaskAction
    public void judge() {
        Map<String, RegularFile> declared = new LinkedHashMap<>(getResultsByTopology().get());
        if (declared.isEmpty()) {
            throw new GradleException("stagewright coverage: no topologies are declared, so there is"
                    + " nothing to reconcile. Coverage over zero runs is not a pass.");
        }
        for (Map.Entry<String, RegularFile> companion : getCompanionResultsByTopology().get().entrySet()) {
            // A plain put would drop one of the two results files from the union without a word.
            if (declared.containsKey(companion.getKey())) {
                throw new GradleException("stagewright coverage: a topology is named '" + companion.getKey()
                        + "', which is also the label of another topology's companion client results."
                        + " Rename that topology so both results files are reconciled.");
            }
            declared.put(companion.getKey(), companion.getValue());
        }

        List<String> missing = new ArrayList<>();
        List<Coverage.Run> runs = new ArrayList<>();
        for (Map.Entry<String, RegularFile> e : declared.entrySet()) {
            File file = e.getValue().getAsFile();
            if (!file.isFile()) {
                missing.add(e.getKey() + " (" + file.getAbsolutePath() + ")");
                continue;
            }
            List<String> warnings = new ArrayList<>();
            List<Map<String, Object>> records;
            try {
                records = Verdict.parse(file.toPath(), warnings);
            } catch (IOException io) {
                throw new UncheckedIOException("cannot read " + file, io);
            }
            for (String w : warnings) {
                getLogger().warn("[stagewright:coverage] {}: {}", e.getKey(), w);
            }
            runs.add(new Coverage.Run(e.getKey(), records));
        }

        if (!missing.isEmpty()) {
            throw new GradleException("stagewright coverage: ENV — these topologies have not been run"
                    + " (or were run and wrote nothing):\n    " + String.join("\n    ", missing)
                    + "\n  Coverage is a statement about the whole suite, so it cannot be made from a"
                    + " subset: a topology that did not run contributes no executed scenes, and every"
                    + " scene only it can run would be reported against the topologies that did.");
        }

        Coverage.Result result = Coverage.judge(runs);
        for (String line : result.report()) {
            getLogger().lifecycle("[stagewright:coverage] {}", line);
        }
        if (result.code() == 3) {
            throw new GradleException("stagewright coverage: ENV — these results cannot support a"
                    + " coverage claim.\n  FILTERED: re-run that topology without -Pstagewright.scenes,"
                    + " then reconcile again.\n  NO EVIDENCE: no run registered a scene; each topology's"
                    + " own verdict (stagewright<Topology>) says why.\n  MIXED BUILDS: re-run the"
                    + " topologies whose build is not the one you mean to judge, then reconcile again;"
                    + " or the whole gate, on a tree nobody edits meanwhile, when a run says '"
                    + BuildId.CHANGED_DURING_BUILD.trim() + "'.");
        }
        if (result.code() != 0) {
            throw new GradleException("stagewright coverage: RED — a scene this suite registers"
                    + " executed in none of its " + runs.size() + " results files. See the UNCOVERED"
                    + " lines above for which, and what each run said instead.");
        }
    }
}
