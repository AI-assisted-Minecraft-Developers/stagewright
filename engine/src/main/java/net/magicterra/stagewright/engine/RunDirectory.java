package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares the directory a scene run happens in.
 *
 * <p>Identical work whether the run is a Gradle dev task or an installed modpack under the CLI: the
 * world has to go, a stale results file has to go, and a handful of settings have to be true before
 * the game starts. A second copy of these rules would drift, and the drift would show up as a
 * topology that fails for reasons no scene explains.
 *
 * <p>Logging goes through a {@link Consumer} rather than any particular logger, which is the whole
 * reason this can be shared: Gradle's logger and a CLI's stdout have nothing in common except that
 * both accept a line of text.
 */
public final class RunDirectory {

    private RunDirectory() {}

    /**
     * Make {@code gameDir} fit to run in.
     *
     * @param gameDir      the run's working directory; created if absent
     * @param resultsFile  results file name relative to {@code gameDir}, deleted if stale
     * @param cleanWorld   delete the world before running
     * @param sceneScripts a directory of {@code .js} scene files to install, or null
     * @param log          receives one line per action worth reporting
     */
    public static void provision(Path gameDir, String resultsFile, boolean cleanWorld,
                                 Path sceneScripts, Consumer<String> log) {
        if (!Files.isDirectory(gameDir)) {
            try {
                Files.createDirectories(gameDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create the run directory " + gameDir, e);
            }
        }

        if (cleanWorld) {
            // Deleting the world is the point. Reusing one makes scenes fail in ways that look
            // exactly like product bugs — blocks a previous run placed are still standing, terrain a
            // previous run dug is still dug — and because which scenes notice varies per run, it
            // reads as flakiness. It is not.
            //
            // Both layouts: a dedicated server keeps its world at <run>/world, a client keeps saves
            // under <run>/saves. Deleting the whole saves directory rather than one named world
            // means a rename of the world a topology drives cannot leave the old one behind.
            deleteTree(gameDir.resolve("world"));
            deleteTree(gameDir.resolve("saves"));
        }

        // Deleting the stale results file matters for a different reason than the world: if a run
        // dies before writing anything, a leftover file from the last run is still sitting there,
        // and the verdict would be passed a complete, valid, GREEN results file describing a run
        // that did not happen.
        Path results = gameDir.resolve(resultsFile);
        try {
            if (Files.deleteIfExists(results)) {
                log.accept("removed the previous results at " + results);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete the stale results file " + results, e);
        }

        seedClientOptions(gameDir.resolve("options.txt"));
        seedServerEula(gameDir.resolve("eula.txt"));
        seedOfflineMode(gameDir.resolve("server.properties"));
        installSceneScripts(gameDir.resolve("config/stagewright/scenes"), sceneScripts, log);
    }

    /**
     * Copy scene scripts into the run directory's {@code config/stagewright/scenes}.
     *
     * <p>The run directory is generated and gitignored, so a scene file authored there is not a
     * committable artifact — but it is where the harness must find one, because that is where a
     * modpack keeps it. Naming a checked-in source directory keeps both true: the file lives in the
     * repo and arrives at the path a real pack would use.
     *
     * <p>The target is cleared first, for the same reason the world is: a scene file deleted from
     * the source but left in the run directory keeps running, and it is reconciled against the
     * manifest like any other, so the suite stays green while testing a file nobody can find.
     */
    private static void installSceneScripts(Path target, Path source, Consumer<String> log) {
        deleteTree(target);
        if (source == null) return;

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
            log.accept("installed " + copied + " scene script(s) from " + source);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install the scene scripts from " + source, e);
        }
    }

    /**
     * Force {@code online-mode=false}, the way ModDevGradle already does for its own dev server runs.
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
    private static void seedOfflineMode(Path properties) {
        force(properties, "online-mode", "false");
        force(properties, "level-seed", FIXED_SEED);
    }

    /**
     * The world seed every run gets.
     *
     * <p>Left empty, the server rolls a fresh seed per run. That is invisible while every arena sits
     * in empty sky at {@code y=200}, and becomes the difference between a reproducible suite and a
     * flaky one the moment a scene asks for generated terrain: the same arena coordinates would be a
     * hilltop one run and a lake the next, and the scene that failed would look like a bot bug.
     *
     * <p>Must equal {@code ClientDirector.WORLD_SEED}, which pins the same thing for the topologies
     * whose world a client creates rather than this provisioner. The value is arbitrary; that the
     * two agree is not. Reproducible-per-topology is not enough — a scene asserting about the ground
     * it landed on has to see the same ground on all three, or its assertion is a statement about
     * which gate happened to run it. The constant is written twice because the two live in builds
     * that cannot see each other: this one has no Minecraft on its classpath, and the other cannot
     * run outside the game.
     */
    private static final String FIXED_SEED = "5471";

    /** Force one server.properties key, leaving every other line exactly as it was. */
    private static void force(Path properties, String key, String value) {
        String line = key + "=" + value;
        try {
            if (!Files.exists(properties)) {
                Files.writeString(properties, line + "\n");
                return;
            }
            String body = Files.readString(properties);
            String patched = body.replaceAll("(?m)^" + Pattern.quote(key) + "\\s*=.*$",
                    Matcher.quoteReplacement(line));
            if (patched.equals(body) && !body.contains(line)) {
                patched = body + (body.endsWith("\n") ? "" : "\n") + line + "\n";
            }
            if (!patched.equals(body)) {
                Files.writeString(properties, patched);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot set " + key + " in " + properties, e);
        }
    }

    /**
     * Record EULA acceptance, the way ModDevGradle already does for its own dev server runs.
     *
     * <p>This levels a loader difference rather than making a new decision. Under ModDevGradle a
     * fresh server run directory boots; under loom it does not — the server prints "You need to
     * agree to the EULA", exits before any mod initialises, and the gate reports a game that never
     * armed. None of that is discoverable from the gate's own output, and it only bites where a run
     * directory is NEW: an existing one carries an acceptance from whenever a developer first ran a
     * server by hand, which is why it went unnoticed until a topology provisioned its own directory
     * for the first time.
     */
    private static void seedServerEula(Path eula) {
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
    private static void seedClientOptions(Path options) {
        try {
            Files.writeString(options,
                    "pauseOnLostFocus:false\nonboardAccessibility:false\nnarrator:0\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot seed the client options at " + options, e);
        }
    }

    private static void deleteTree(Path root) {
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
            throw new UncheckedIOException("cannot delete " + root, e);
        }
    }
}
