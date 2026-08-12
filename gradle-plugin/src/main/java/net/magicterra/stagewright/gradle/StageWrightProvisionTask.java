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

    /** Jars to install into the run directory's {@code mods/} — normally the StageWright loader jar.
     *  {@code @Classpath} rather than {@code @InputFiles} so a rebuild that changes nothing but a
     *  timestamp does not re-provision, which would delete the world for no reason. */
    @org.gradle.api.tasks.Classpath
    public abstract org.gradle.api.file.ConfigurableFileCollection getInstallMods();

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
                scripts == null ? null : scripts.toPath(),
                log);

        // After provisioning, not before: provisioning is what creates the run directory on a first
        // run, and installing into a directory that does not exist yet would have to create it in a
        // second place with a second set of assumptions about what a run directory is.
        java.util.List<java.nio.file.Path> jars = new java.util.ArrayList<>();
        for (File jar : getInstallMods()) jars.add(jar.toPath());
        if (!jars.isEmpty()) {
            net.magicterra.stagewright.engine.ModInstall.install(gameDir, jars, log);
            installIntoCompanion(jars, log);
        }
    }

    /**
     * Install the same jars into the companion client's own run directory.
     *
     * <p>The same jars, and that is the requirement rather than a convenience: a loader that finds a
     * different mod list on each end of a connection refuses it. So a companion missing what the
     * server has is not a degraded run, it is a run that cannot happen.
     *
     * <p>Only matters for a project that delivers the harness by INSTALLING it — a project that puts
     * it on the run classpath gets it in both halves for free, which is why this went unnoticed
     * while the first two forks with a companion happened to do that. The one that did not produced
     * a hang, not an error: its client had no director, never dialled, and the server sat waiting
     * for a player that was never coming. Nothing in either log said what was missing.
     */
    private void installIntoCompanion(java.util.List<java.nio.file.Path> jars,
                                      java.util.function.Consumer<String> log) {
        if (!getCompanionGameDirectory().isPresent()) return;
        java.nio.file.Path companionDir = getCompanionGameDirectory().get().getAsFile().toPath();
        try {
            java.nio.file.Files.createDirectories(companionDir);
        } catch (java.io.IOException e) {
            throw new org.gradle.api.GradleException(
                    "cannot create the companion run directory " + companionDir, e);
        }
        net.magicterra.stagewright.engine.ModInstall.install(companionDir, jars, log);
    }
}
