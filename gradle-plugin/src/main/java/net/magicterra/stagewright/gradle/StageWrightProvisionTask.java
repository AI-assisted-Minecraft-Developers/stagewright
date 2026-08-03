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

    /** Whether to delete the world. */
    @Input
    public abstract Property<Boolean> getCleanWorld();

    /** A checked-in directory of {@code .js} scene files to install for this run. */
    @Internal
    public abstract DirectoryProperty getSceneScripts();

    @TaskAction
    public void provision() {
        File scripts = getSceneScripts().isPresent() ? getSceneScripts().get().getAsFile() : null;
        RunDirectory.provision(
                getGameDirectory().get().getAsFile().toPath(),
                getResultsFile().get(),
                Boolean.TRUE.equals(getCleanWorld().getOrElse(true)),
                scripts == null ? null : scripts.toPath(),
                line -> getLogger().lifecycle("[stagewright] {}", line));
    }
}
