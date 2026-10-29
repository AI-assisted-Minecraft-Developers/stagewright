package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        /** The primary is a dedicated server, so the engine prepares its directory as one. */
        final boolean server;
        /** {@code fail} or {@code report}: what a game forced down after its run does to its scene. */
        final String leaks;
        final CompanionLaunch primary;
        final CompanionLaunch companion;

        Entry(String topology, File gameDirectory, boolean cleanWorld, int timeoutMinutes,
              int stallMinutes, boolean server, String leaks, CompanionLaunch primary, CompanionLaunch companion) {
            this.leaks = leaks;
            this.topology = topology;
            this.gameDirectory = gameDirectory;
            this.cleanWorld = cleanWorld;
            this.timeoutMinutes = timeoutMinutes;
            this.stallMinutes = stallMinutes;
            this.server = server;
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
            JsonObject primary = process(entry.primary, new File(argFiles, entry.topology + ".primary.args"));
            JsonObject json = new JsonObject()
                    .add("topology", entry.topology)
                    .add("gameDirectory", entry.gameDirectory.getAbsolutePath());
            String world = worldDirectory(entry.primary.jvmArgs());
            if (world != null) json.add("worldDirectory", world);
            json.add("cleanWorld", entry.cleanWorld)
                    .add("timeoutMinutes", entry.timeoutMinutes)
                    .add("stallMinutes", entry.stallMinutes)
                    .add("server", entry.server)
                    .add("leaks", entry.leaks)
                    .add("primary", primary);
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

    /**
     * Where a client hosting its own server keeps the world the run names with {@code stagewright.client.world}, or
     * null for a dedicated server's default. A scene starting from a save puts it there.
     */
    static String worldDirectory(List<String> jvmArgs) {
        String prefix = "-Dstagewright.client.world=";
        String found = null;
        for (String arg : jvmArgs) {
            List<String> tokens = List.of(arg);
            // ModDevGradle puts a run's system properties in an argument file; the last one the JVM reads wins.
            if (arg.startsWith("@")) {
                try {
                    tokens = List.of(Files.readString(Path.of(arg.substring(1))).split("\\s+"));
                } catch (IOException | RuntimeException e) {
                    tokens = List.of();
                }
            }
            for (String t : tokens) if (t.startsWith(prefix)) found = "saves/" + t.substring(prefix.length());
        }
        return found;
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
