package net.magicterra.stagewright.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared base task for the three testkit entry points. It assembles a command from
 * {@link #getPythonExecutable()} + {@link #getScriptArgs()} (the frozen assembly table is
 * built in {@link StageWrightPlugin}), runs it with {@link ExecOperations} streaming the
 * orchestrator's stdout/stderr live to the gradle console, and propagates the exit code
 * verbatim: any non-zero code throws a {@link GradleException} whose message carries the
 * full, copy-paste re-runnable command line and the orchestration-contract exit-code
 * legend. The plugin never parses JSONL nor re-judges — the orchestrator is the sole
 * verdict authority.
 */
public abstract class StageWrightRunTask extends DefaultTask {

    /** Orchestration contract v0 exit-code legend. */
    static final String LEGEND =
        "exit-code legend: 0 GREEN / 1 RED / 2 DEAD / 3 ENV / 4 BLOCKED-multiround";

    /** Python interpreter to invoke. */
    @Input
    public abstract Property<String> getPythonExecutable();

    /** Full argv passed to the interpreter (orchestrator script path first, then flags). */
    @Input
    public abstract ListProperty<String> getScriptArgs();

    /** Working directory for the orchestrator (the applied/root project directory). */
    @Internal
    public abstract DirectoryProperty getWorkingDir();

    private final ExecOperations execOps;

    @Inject
    public StageWrightRunTask(ExecOperations execOps) {
        this.execOps = execOps;
    }

    private static String verdictLabel(int code) {
        switch (code) {
            case 1:  return "RED";
            case 2:  return "DEAD";
            case 3:  return "ENV";
            case 4:  return "BLOCKED-multiround";
            default: return "FAILED";
        }
    }

    @TaskAction
    public void run() {
        List<String> command = new ArrayList<>();
        command.add(getPythonExecutable().get());
        command.addAll(getScriptArgs().get());

        String printable = String.join(" ", command);
        File cwd = getWorkingDir().get().getAsFile();

        getLogger().lifecycle("[testkit] exec: {}", printable);
        getLogger().lifecycle("[testkit] working dir: {}", cwd.getAbsolutePath());

        // ExecOperations connects the child's stdout/stderr to this process's
        // System.out/err by default, so orchestrator output streams live to the
        // gradle console. ignoreExitValue lets us surface a curated failure instead
        // of gradle's opaque "finished with non-zero exit value".
        ExecResult result = execOps.exec(spec -> {
            spec.commandLine(command);
            spec.setWorkingDir(cwd);
            spec.setIgnoreExitValue(true);
        });

        int code = result.getExitValue();
        if (code != 0) {
            throw new GradleException(
                "testkit orchestrator failed — verdict: " + verdictLabel(code)
                    + " (exit " + code + ").\n"
                    + "  command: " + printable + "\n"
                    + "  working dir: " + cwd.getAbsolutePath() + "\n"
                    + "  " + LEGEND);
        }
    }
}
