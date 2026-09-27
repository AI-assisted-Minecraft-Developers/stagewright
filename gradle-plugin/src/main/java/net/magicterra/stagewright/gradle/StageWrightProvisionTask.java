package net.magicterra.stagewright.gradle;

import java.io.File;

import net.magicterra.stagewright.engine.RunDirectory;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * Gradle's face on {@link RunDirectory} — declares the inputs, does none of the work.
 *
 * <p>The rules for what a run directory must look like live in the engine because the CLI needs the
 * same ones against an installed modpack. What is left here is the part that is genuinely Gradle's:
 * typed properties, up-to-date checking, and a logger.
 */
public abstract class StageWrightProvisionTask extends DefaultTask {

    /** The run's game directory. */
    @Internal
    public abstract DirectoryProperty getGameDirectory();

    /** Results file name, relative to the game directory. */
    @Input
    public abstract Property<String> getResultsFile();

    /**
     * The companion client's results file, for topologies that have one — it lives in the
     * companion's own run directory, not under {@link #getGameDirectory()}.
     *
     * <p>Here rather than only on the verdict task because a file the verdict READS and provision
     * never CLEARS is a false green: the companion starts, dies before writing its header, and the
     * verdict judges the previous run's complete results. Every file the verdict reads has to be
     * cleared by the task that prepares the run, and the way to keep that true is for the two tasks
     * to be given the same set from the same topology.
     */
    @Internal
    public abstract org.gradle.api.file.RegularFileProperty getCompanionResultsFile();

    /**
     * The companion client's own run directory, for topologies that have one.
     *
     * <p>Resolved from the companion run task rather than declared, so a build script that names a
     * {@code companionRunTask} does not also have to restate where that run lives — the loader
     * plugin already knows, and two statements of the same fact is one that can drift.
     */
    @Internal
    public abstract DirectoryProperty getCompanionGameDirectory();

    /** Whether to delete the world. */
    @Input
    public abstract Property<Boolean> getCleanWorld();

    /** A checked-in directory of {@code .js} scene files to install for this run. */
    @Internal
    public abstract DirectoryProperty getSceneScripts();

    @TaskAction
    public void provision() {
        File scripts = getSceneScripts().isPresent() ? getSceneScripts().get().getAsFile() : null;
        java.nio.file.Path gameDir = getGameDirectory().get().getAsFile().toPath();
        java.util.function.Consumer<String> log =
                line -> getLogger().lifecycle("[stagewright] {}", line);

        java.util.List<java.nio.file.Path> stale = new java.util.ArrayList<>();
        stale.add(gameDir.resolve(getResultsFile().get()));
        if (getCompanionResultsFile().isPresent()) {
            stale.add(getCompanionResultsFile().get().getAsFile().toPath());
        }

        RunDirectory.provision(
                gameDir,
                stale,
                Boolean.TRUE.equals(getCleanWorld().getOrElse(true)),
                true,
                log);
        RunDirectory.installAuthoredContent(gameDir, scripts == null ? null : scripts.toPath(), log);

        // The harness reaches the game as a dependency of the run — modLocalRuntime under loom,
        // localRuntime under ModDevGradle — so nothing is copied into mods/. What an earlier version
        // of this plugin copied there is swept, or the loader would find the harness twice.
        net.magicterra.stagewright.engine.ModInstall.uninstall(gameDir, log);
        if (getCompanionGameDirectory().isPresent()) {
            net.magicterra.stagewright.engine.ModInstall.uninstall(
                    getCompanionGameDirectory().get().getAsFile().toPath(), log);
        }
    }
}
