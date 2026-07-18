package net.magicterra.testkit.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registers the {@code testkit { ... }} extension and three tasks — {@code testkitServer}
 * / {@code testkitClient} / {@code testkitE2E} — on the project it is applied to (the root
 * project for the dogfood consumer, this repo). Each task shells the frozen python
 * orchestration contract; the argument assembly below is FROZEN (P3b T1) and mirrored in
 * the plan's File Structure table.
 *
 * <p>Task registration is lazy ({@code tasks.register}) so applying the plugin adds
 * negligible configuration-time cost and never breaks an existing task.
 */
public class TestkitPlugin implements Plugin<Project> {

    static final String TASK_GROUP = "verification";

    @Override
    public void apply(Project project) {
        TestkitExtension ext =
            project.getExtensions().create("testkit", TestkitExtension.class);
        ext.getLoader().convention("fabric");
        ext.getPythonExecutable().convention("python3");
        ext.getScriptsDir().convention("scripts/testkit");
        ext.getExtraArgs().convention(Collections.emptyList());

        // testkitServer → t0.py, dedicated-server dogfood triplet (frozen assembly).
        //   t0.py --loader L --run-task :L:runDogfoodServer
        //         --results L/run-dogfood/testkit-results.jsonl
        //         --expect-file <scriptsDir>/expected-scenes-L.txt   [+ extraArgs]
        project.getTasks().register("testkitServer", TestkitRunTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Runs the testkit dedicated-server dogfood suite (t0.py), "
                + "shelling the frozen orchestration contract.");
            wireCommon(project, ext, task);
            task.getScriptArgs().set(project.provider(() -> {
                String loader = ext.getLoader().get();
                String scripts = ext.getScriptsDir().get();
                List<String> a = new ArrayList<>();
                a.add(scripts + "/t0.py");
                a.add("--loader");
                a.add(loader);
                a.add("--run-task");
                a.add(":" + loader + ":runDogfoodServer");
                a.add("--results");
                a.add(loader + "/run-dogfood/testkit-results.jsonl");
                a.add("--expect-file");
                a.add(scripts + "/expected-scenes-" + loader + ".txt");
                a.addAll(ext.getExtraArgs().get());
                return a;
            }));
        });

        // testkitClient → t1.py (client topology); testkitE2E → t2.py (end-to-end).
        // Both: plain `--loader L`; extension.expectFile threaded as --expect-file when
        // present; extraArgs appended.
        registerClientTopology(project, ext, "testkitClient", "t1.py",
            "Runs the testkit client (T1) suite (t1.py), shelling the frozen orchestration contract.");
        registerClientTopology(project, ext, "testkitE2E", "t2.py",
            "Runs the testkit end-to-end (T2) suite (t2.py), shelling the frozen orchestration contract.");
    }

    private void registerClientTopology(Project project, TestkitExtension ext,
                                        String taskName, String script, String description) {
        project.getTasks().register(taskName, TestkitRunTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription(description);
            wireCommon(project, ext, task);
            task.getScriptArgs().set(project.provider(() -> {
                String loader = ext.getLoader().get();
                String scripts = ext.getScriptsDir().get();
                List<String> a = new ArrayList<>();
                a.add(scripts + "/" + script);
                a.add("--loader");
                a.add(loader);
                if (ext.getExpectFile().isPresent()) {
                    a.add("--expect-file");
                    a.add(ext.getExpectFile().get().getAsFile().getAbsolutePath());
                }
                a.addAll(ext.getExtraArgs().get());
                return a;
            }));
        });
    }

    private void wireCommon(Project project, TestkitExtension ext, TestkitRunTask task) {
        task.getPythonExecutable().set(ext.getPythonExecutable());
        // Working dir = the applied project's directory (root project for the dogfood
        // consumer); the frozen relative paths (scripts/testkit/..., L/run-dogfood/...)
        // resolve against it.
        task.getWorkingDir().set(project.getLayout().getProjectDirectory());
        // A test-runner that shells a long-lived orchestrator must re-run every
        // invocation; it declares no outputs, so pin it never-up-to-date explicitly.
        task.getOutputs().upToDateWhen(t -> false);
    }
}
