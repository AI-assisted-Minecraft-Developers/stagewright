package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import net.magicterra.stagewright.engine.json.JsonArray;
import net.magicterra.stagewright.engine.json.JsonObject;
import net.magicterra.stagewright.engine.json.PrettyPrint;

/**
 * Writes {@code <topology>.json} for each topology a test suite may ask for, in the format the test
 * engine reads (docs/reference/launch-spec.md).
 *
 * <p>The launches are values captured when the task graph is built, not task references, so the
 * task stays configuration-cacheable; the files are written at execution because the classpath
 * argfiles and the loader's late bindings only exist once the run tasks' own dependencies have run.
 */
public abstract class StageWrightLaunchSpecsTask extends DefaultTask {

    /** One file to write: the topology as the scene spells it, and the launches behind it. */
    static final class Entry {
        final String topology;
        final File gameDirectory;
        final boolean cleanWorld;
        final int timeoutMinutes;
        final int stallMinutes;
        final CompanionLaunch primary;
        final CompanionLaunch companion;

        Entry(String topology, File gameDirectory, boolean cleanWorld, int timeoutMinutes,
              int stallMinutes, CompanionLaunch primary, CompanionLaunch companion) {
            this.topology = topology;
            this.gameDirectory = gameDirectory;
            this.cleanWorld = cleanWorld;
            this.timeoutMinutes = timeoutMinutes;
            this.stallMinutes = stallMinutes;
            this.primary = primary;
            this.companion = companion;
        }
    }

    /** The directory handed to the test JVM as {@code -Dstagewright.launches}. */
    @OutputDirectory
    public abstract DirectoryProperty getLaunchesDirectory();

    @Internal
    abstract ListProperty<Entry> getEntries();

    public StageWrightLaunchSpecsTask() {
        // The files hold paths and classpath argfiles that depend on state Gradle does not track.
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    void write() {
        File dir = getLaunchesDirectory().get().getAsFile();
        File argFiles = new File(dir, "classpath");
        for (Entry entry : getEntries().get()) {
            JsonObject json = new JsonObject()
                    .add("topology", entry.topology)
                    .add("gameDirectory", entry.gameDirectory.getAbsolutePath())
                    .add("cleanWorld", entry.cleanWorld)
                    .add("timeoutMinutes", entry.timeoutMinutes)
                    .add("stallMinutes", entry.stallMinutes)
                    .add("primary", process(entry.primary, new File(argFiles, entry.topology + ".primary.args")));
            if (entry.companion != null) {
                json.add("companion", process(entry.companion,
                        new File(argFiles, entry.topology + ".companion.args")));
            }
            File out = new File(dir, entry.topology + ".json");
            try {
                Files.createDirectories(out.getParentFile().toPath());
                Files.writeString(out.toPath(), json.toString(PrettyPrint.indentWithSpaces(2)) + "\n",
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write the launch spec " + out, e);
            }
        }
    }

    private static JsonObject process(CompanionLaunch launch, File classpathArgFile) {
        JsonArray jvmArgs = new JsonArray();
        launch.launchJvmArgs(classpathArgFile).forEach(jvmArgs::add);
        JsonArray args = new JsonArray();
        launch.programArgs().forEach(args::add);
        // Only what the run sets beyond the environment it inherited: the test worker passes that on to the game
        // anyway, and the rest of it — credentials included — has no business in a file under build/.
        JsonObject environment = new JsonObject();
        Map<String, String> inherited = System.getenv();
        for (Map.Entry<String, String> e : launch.environment().entrySet()) {
            if (!e.getValue().equals(inherited.get(e.getKey()))) environment.add(e.getKey(), e.getValue());
        }
        return new JsonObject()
                .add("java", launch.javaExecutable())
                .add("jvmArgs", jvmArgs)
                .add("mainClass", launch.mainClassName())
                .add("args", args)
                .add("workingDirectory", launch.workingDirectory().getAbsolutePath())
                .add("environment", environment);
    }
}
