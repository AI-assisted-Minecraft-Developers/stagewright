package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * Clears a topology's run directory of anything the previous run left that could change this one's
 * result: the world, and the stale results file.
 *
 * <p>Deleting the world is the point. Reusing one makes scenes fail in ways that look exactly like
 * product bugs — blocks a previous run placed are still standing, terrain a previous run dug is
 * still dug — and because which scenes notice varies per run, it reads as flakiness. It is not.
 *
 * <p>Deleting the stale results file matters for a different reason: if a run dies before writing
 * anything, a leftover file from the last run is still sitting there, and the verdict would be
 * passed a complete, valid, GREEN results file describing a run that did not happen.
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
        File gameDir = getGameDirectory().get().getAsFile();
        if (!gameDir.exists() && !gameDir.mkdirs()) {
            throw new UncheckedIOException(new IOException("cannot create the run directory " + gameDir));
        }

        if (Boolean.TRUE.equals(getCleanWorld().getOrElse(true))) {
            // Both layouts: a dedicated server keeps its world at <run>/world, a client keeps saves
            // under <run>/saves. Deleting the whole saves directory rather than one named world
            // means a rename of the world a topology drives cannot leave the old one behind.
            deleteTree(gameDir.toPath().resolve("world"));
            deleteTree(gameDir.toPath().resolve("saves"));
        }

        Path results = gameDir.toPath().resolve(getResultsFile().get());
        try {
            if (Files.deleteIfExists(results)) {
                getLogger().lifecycle("[stagewright] removed the previous results at {}", results);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete the stale results file " + results, e);
        }

        seedClientOptions(gameDir.toPath().resolve("options.txt"));
        seedServerEula(gameDir.toPath().resolve("eula.txt"));
        seedOfflineMode(gameDir.toPath().resolve("server.properties"));
        installSceneScripts(gameDir.toPath().resolve("config/stagewright/scenes"));
    }

    /**
     * Copy the build's scene scripts into the run directory's {@code config/stagewright/scenes}.
     *
     * <p>The run directory is generated and gitignored, so a scene file authored there is not a
     * committable artifact — but it is where the harness must find one, because that is where a
     * modpack keeps it. Declaring a checked-in source directory keeps both true: the file lives in
     * the repo and arrives at the path a real pack would use.
     *
     * <p>The target is cleared first, for the same reason the world is: a scene file deleted from
     * the source but left in the run directory keeps running, and it is reconciled against the
     * manifest like any other, so the suite stays green while testing a file nobody can find.
     */
    private void installSceneScripts(Path target) {
        deleteTree(target);
        if (!getSceneScripts().isPresent()) return;

        Path source = getSceneScripts().get().getAsFile().toPath();
        if (!Files.isDirectory(source)) {
            // Loud, because the failure it prevents is silent: no directory means no scenes loaded,
            // and a suite that runs none of them still reports GREEN.
            throw new UncheckedIOException(new IOException(
                    "sceneScripts points at " + source + ", which is not a directory"));
        }
        try (Stream<Path> files = Files.list(source)) {
            Files.createDirectories(target);
            int copied = 0;
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".js")).toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
                copied++;
            }
            getLogger().lifecycle("[stagewright] installed {} scene script(s) from {}", copied, source);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install the scene scripts from " + source, e);
        }
    }

    /**
     * Force {@code online-mode=false} in the provisioned server properties, the way ModDevGradle
     * already does for its own dev server runs.
     *
     * <p>A dev client has no Mojang session, so an online-mode server rejects it during login. The
     * client does not stop — it redials, is rejected, redials — and the server sits waiting for a
     * player that authentication will never let in. Both halves then run to their timeout and the
     * only trace is a line of {@code lost connection: Disconnected} in the server log, which reads
     * like a network fault rather than a setting.
     *
     * <p>Forced rather than defaulted, because a scene run cannot work any other way and this is the
     * gate's own directory. Every other key the file carries is left exactly as it was.
     */
    private void seedOfflineMode(Path properties) {
        try {
            if (!Files.exists(properties)) {
                Files.writeString(properties, "online-mode=false\n");
                return;
            }
            String body = Files.readString(properties);
            String patched = body.replaceAll("(?m)^online-mode\\s*=.*$", "online-mode=false");
            if (patched.equals(body) && !body.contains("online-mode=false")) {
                patched = body + (body.endsWith("\n") ? "" : "\n") + "online-mode=false\n";
            }
            if (!patched.equals(body)) {
                Files.writeString(properties, patched);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot set offline mode in " + properties, e);
        }
    }

    /**
     * Record EULA acceptance in the provisioned run directory, the way ModDevGradle already does for
     * its own dev server runs.
     *
     * <p>This levels a loader difference rather than making a new decision. Under ModDevGradle a
     * fresh server run directory boots; under loom it does not — the server prints "You need to
     * agree to the EULA", exits before any mod initialises, and the gate reports a game that never
     * armed. None of that is discoverable from the gate's own output, and it only bites where a run
     * directory is NEW: an existing one carries an acceptance from whenever a developer first ran a
     * server by hand, which is why it went unnoticed until a topology provisioned its own directory
     * for the first time.
     */
    private void seedServerEula(Path eula) {
        try {
            Files.writeString(eula, "eula=true\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + eula, e);
        }
    }

    /**
     * Write the three client settings a scene run cannot be correct without. A dedicated server
     * never reads this file, so it is written unconditionally rather than guessed at from the run
     * type.
     *
     * <p>Only three, and each earns its place:
     *
     * <ul>
     *   <li>{@code pauseOnLostFocus:false} — vanilla singleplayer pauses when the window is
     *       deactivated. On a developer's desktop that happens constantly, and the resulting pause
     *       screen does not merely stall the run: it sits underneath every later assertion, so
     *       scenes report a world that advanced no ticks and screens that are not what they should
     *       be. One focus slip turns a clean run into a page of unrelated-looking failures.</li>
     *   <li>{@code onboardAccessibility:false} — a fresh game directory otherwise opens the
     *       accessibility onboarding screen before the title screen. The client director dismisses
     *       it too; this stops it appearing at all, which is cheaper and does not depend on the
     *       director having started.</li>
     *   <li>{@code narrator:0} — nothing should attempt text-to-speech on a headless CI box.</li>
     * </ul>
     *
     * <p>Minecraft merges missing keys with its defaults, so a three-line file is a complete one.
     * Rewritten every provision rather than created once: a run that changed a setting must not
     * carry it into the next one, which is the same discipline as deleting the world.
     */
    private void seedClientOptions(Path options) {
        try {
            Files.writeString(options,
                    "pauseOnLostFocus:false\nonboardAccessibility:false\nnarrator:0\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot seed the client options at " + options, e);
        }
    }

    private void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot delete " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
        getLogger().lifecycle("[stagewright] cleaned {}", root);
    }
}
