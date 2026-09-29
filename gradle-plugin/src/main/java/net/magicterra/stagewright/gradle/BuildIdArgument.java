package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * {@code -Dstagewright.build} for a run, taken when the run starts. Taking it while configuring
 * starts git in a phase the configuration cache forbids processes in, and a cache hit would replay
 * the id of whatever code was checked out when the entry was stored.
 *
 * <p>One per run task, carrying what that run loads, which is what the build service checks a
 * changed tree against: its classpath, and the class directories {@code MOD_CLASSES} names, set on
 * the task or bound late by its loader. Answered once: the eager argument list and a companion launch
 * both ask, and asked again a run would take the tree, and the git commands, again.
 */
final class BuildIdArgument implements CommandLineArgumentProvider {

    private final Provider<StageWrightBuildIdService> service;
    private final String run;
    private final FileCollection classpath;
    private final Map<String, String> environment;
    private final List<MapProperty<?, ?>> lateEnvironment;
    private transient List<String> answer;

    BuildIdArgument(Provider<StageWrightBuildIdService> service, String run, FileCollection classpath,
                    Map<String, String> environment, List<MapProperty<?, ?>> lateEnvironment) {
        this.service = service;
        this.run = run;
        this.classpath = classpath;
        this.environment = new LinkedHashMap<>(environment);
        // Mutable copies: the configuration cache cannot read back an immutable List.copyOf.
        this.lateEnvironment = new ArrayList<>(lateEnvironment);
    }

    Provider<StageWrightBuildIdService> service() {
        return service;
    }

    /** Everything the build service checks this run against, read as the run starts. */
    List<File> loaded() {
        List<File> loaded = new ArrayList<>(classpath.getFiles());
        Map<String, String> env = new LinkedHashMap<>(environment);
        env.putAll(SideProcesses.merged(lateEnvironment));
        loaded.addAll(SideProcesses.modClasses(env));
        return loaded;
    }

    @Override
    public synchronized Iterable<String> asArguments() {
        if (answer == null) answer = service.get().arguments(run, loaded());
        return answer;
    }
}
