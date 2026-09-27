package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;

/**
 * A run task's one {@code doFirst}: the display check, then each companion, then the hold banners.
 *
 * <p>One action in a fixed order, because separate {@code doFirst}s run in reverse of whichever
 * topology Gradle resolved first. A class rather than a lambda: with no companion it holds only
 * strings, so the run stays configuration-cacheable.
 */
final class RunSideProcesses implements Action<Task> {

    private static final String KEY = "stagewright.sideProcesses";

    /** A companion to start: its spec, where its output goes, and the service that owns it. */
    record Companion(JavaExec spec, File log, Provider<StageWrightSideProcessService> service) {}

    private final Set<String> clientTopologies = new LinkedHashSet<>();
    private final Map<String, Companion> companions = new LinkedHashMap<>();
    private final Set<String> banners = new LinkedHashSet<>();

    private RunSideProcesses() {}

    /** The run's action, hung on it the first time anything is registered. */
    static RunSideProcesses of(Task run) {
        var extra = run.getExtensions().getExtraProperties();
        if (extra.has(KEY)) return (RunSideProcesses) extra.get(KEY);
        RunSideProcesses action = new RunSideProcesses();
        extra.set(KEY, action);
        run.doFirst(action);
        return action;
    }

    /** The run task is a client of this topology, so it needs a display. */
    void requireDisplay(String topology) {
        clientTopologies.add(topology);
    }

    /** Start {@code spec} beside the run; false if it was already registered. */
    boolean startCompanion(JavaExec spec, File log, Provider<StageWrightSideProcessService> service) {
        return companions.putIfAbsent(spec.getPath(), new Companion(spec, log, service)) == null;
    }

    /** A line to print once the run is about to start. */
    void banner(String line) {
        banners.add(line);
    }

    Set<String> clientTopologies() {
        return Collections.unmodifiableSet(clientTopologies);
    }

    Set<String> companionPaths() {
        return Collections.unmodifiableSet(companions.keySet());
    }

    Set<String> banners() {
        return Collections.unmodifiableSet(banners);
    }

    @Override
    public void execute(Task task) {
        if (!clientTopologies.isEmpty()) {
            Map<String, ?> env = task instanceof JavaExec exec ? SideProcesses.environmentOf(exec) : System.getenv();
            SideProcesses.requireDisplay("topology '" + String.join("', '", clientTopologies) + "'", env);
        }
        for (Companion companion : companions.values()) {
            companion.log().getParentFile().mkdirs();
            companion.service().get().startCompanion(companion.spec(), companion.log(), task.getLogger());
        }
        banners.forEach(task.getLogger()::lifecycle);
    }
}
