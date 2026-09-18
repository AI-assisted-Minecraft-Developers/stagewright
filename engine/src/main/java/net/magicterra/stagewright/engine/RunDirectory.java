package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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

    /** The TESTKIT_ENDPOINT descriptor a held run publishes, relative to the run directory. Matched
     *  by the plugin's hold task and by the game's {@code EndpointDescriptor}. */
    public static final String ENDPOINT_FILE = "stagewright-endpoint.json";

    /** Where the run says it has got to, beside each results file. Written in-game by the stall
     *  watchdog's {@code Heartbeat}; the name is repeated there because the game module and this one
     *  share no code — they are different processes and, deliberately, different dependency graphs. */
    public static final String PROGRESS_FILE = "stagewright-progress.json";

    /** The file a run is judged on unless its supervisor renames it. Repeated on the game side
     *  ({@code StageWrightCommon}) for the same reason {@link #PROGRESS_FILE} is. */
    public static final String DEFAULT_RESULTS_FILE = "stagewright-results.jsonl";

    /**
     * The system property that carries a renamed results file INTO the game.
     *
     * <p>Renaming was a read-side-only setting until this existed: the CLI's {@code --results} and
     * the plugin's {@code resultsFile} both changed which file the verdict opened, while the harness
     * went on writing the default name because nothing ever told it otherwise. The run then finished
     * green, wrote a complete results file, and was judged as "the run wrote no results" against a
     * path nothing was ever going to write — which is the same ENV verdict a pack gets when the
     * framework jar failed to load, and sends the reader to check mod loading.
     */
    public static final String RESULTS_PROPERTY = "stagewright.results";

    private RunDirectory() {}

    /**
     * Make {@code gameDir} fit to run in.
     *
     * @param gameDir      the run's working directory; created if absent
     * @param staleResults every results file this run will be judged on, deleted if present. Full
     *                     paths rather than names under {@code gameDir}, and a list rather than one:
     *                     a topology with a companion client is judged on a second file in the
     *                     companion's own run directory, which is not under this one
     * @param cleanWorld   delete the world before running
     * @param sceneScripts a directory of {@code .js} scene files to install, or null
     * @param log          receives one line per action worth reporting
     */
    public static void provision(Path gameDir, List<Path> staleResults, boolean cleanWorld,
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

        // Deleting the stale results files matters for a different reason than the world: if a run
        // dies before writing anything, a leftover file from the last run is still sitting there,
        // and the verdict would be passed a complete, valid, GREEN results file describing a run
        // that did not happen.
        //
        // Every file the verdict reads, not just the one in this directory. The companion client's
        // results live in the companion's own run directory, so while this took a single name under
        // gameDir it was structurally unable to clear them — and a companion that started and then
        // died before writing its header left yesterday's complete GREEN in place to be judged.
        // The narrow signature was the bug; a caller cannot pass what the parameter cannot express.
        for (Path results : staleResults) {
            try {
                if (Files.deleteIfExists(results)) {
                    log.accept("removed the previous results at " + results);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot delete the stale results file " + results, e);
            }
            // And the heartbeat beside it, which lies in a way the results file cannot: a run that
            // dies before writing a header leaves no results to mislead anyone, but a leftover
            // heartbeat still names a scene, and the verdict would report the previous run's
            // position as this one's last known place. Derived from the results path rather than
            // listed separately so the companion's directory is covered by the same widening that
            // brought its results here.
            Path progress = results.toAbsolutePath().resolveSibling(PROGRESS_FILE);
            try {
                Files.deleteIfExists(progress);
            } catch (IOException e) {
                // Not fatal: this file is only ever read to add detail to a failure. Refusing to
                // start a run over it would be a worse trade than losing that detail.
                log.accept("could not remove the stale heartbeat at " + progress + ": " + e);
            }
        }

        // Same argument, one step worse: a stale endpoint descriptor names a port. Left behind, an
        // out-of-process test attaches to whatever now answers there — nothing, or somebody else's
        // game — instead of failing fast on a descriptor that is not there yet.
        Path endpoint = gameDir.resolve(ENDPOINT_FILE);
        try {
            if (Files.deleteIfExists(endpoint)) {
                log.accept("removed the previous endpoint descriptor at " + endpoint);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete the stale endpoint descriptor " + endpoint, e);
        }

        seedClientOptions(gameDir.resolve("options.txt"));
        seedServerEula(gameDir.resolve("eula.txt"));
        seedOfflineMode(gameDir.resolve("server.properties"));
        installAuthoredContent(gameDir, sceneScripts, log);
    }

    /**
     * Copy a pack's authored StageWright content into the run directory, by extension:
     * {@code .js} to {@code config/stagewright/scenes}, {@code .json} to
     * {@code config/stagewright/capabilities}.
     *
     * <p>One source directory for both, deliberately. A pack author has a folder — the whole premise
     * of the CLI is that they have nothing else — and a capability descriptor is authored in the same
     * sitting as the scene that needs it, usually to make that scene work at all. Splitting them into
     * two flags and two directories would put a build-tool's filing system on somebody who does not
     * have a build tool. Extension is unambiguous and needs no explaining; {@code expected-scenes.txt}
     * already sits in the same folder and is likewise picked out by its own.
     *
     * <p>The run directory is generated and gitignored, so a file authored there is not a committable
     * artifact — but it is where the harness must find one, because that is where a modpack keeps it.
     * Naming a checked-in source directory keeps both true: the file lives in the repo and arrives at
     * the path a real pack would use.
     *
     * <p>Both targets are cleared first, for the same reason the world is: a file deleted from the
     * source but left in the run directory keeps being loaded, and scenes are reconciled against the
     * manifest like any other, so the suite stays green while testing a file nobody can find.
     */
    private static void installAuthoredContent(Path gameDir, Path source, Consumer<String> log) {
        Path scenes = gameDir.resolve("config/stagewright/scenes");
        Path capabilities = gameDir.resolve("config/stagewright/capabilities");
        deleteTree(scenes);
        deleteTree(capabilities);
        if (source == null) return;

        if (!Files.isDirectory(source)) {
            // Loud, because the failure it prevents is silent: no directory means no scenes loaded,
            // and a suite that runs none of them still reports GREEN.
            throw new UncheckedIOException(new IOException(
                    "sceneScripts points at " + source + ", which is not a directory"));
        }
        int copiedScenes = copyByExtension(source, scenes, ".js");
        int copiedCaps = copyByExtension(source, capabilities, ".json");
        log.accept("installed " + copiedScenes + " scene script(s) and " + copiedCaps
                + " capability descriptor(s) from " + source);
    }

    private static int copyByExtension(Path source, Path target, String extension) {
        try (Stream<Path> files = Files.list(source)) {
            List<Path> matching =
                    files.filter(p -> p.getFileName().toString().endsWith(extension)).toList();
            if (matching.isEmpty()) return 0;
            Files.createDirectories(target);
            for (Path file : matching) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
            return matching.size();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install the " + extension + " files from " + source, e);
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
     *
     * <p>{@code sync-chunk-writes=false} is the third forced key, and it is about heap, not speed.
     * A suite stages every arena in fresh chunks, so a run generates a few hundred chunks per scene,
     * and each one is handed to the level's single {@code IOWorker} thread to write. With the
     * vanilla default of {@code true} every region write is an fsync, the thread falls behind from
     * the first scene, and the backlog is held on the heap: the queued task, the chunk's NBT, and the
     * region file it belongs to. Measured on both loaders in one afternoon — 170,000 queued writes
     * and 9,000 unwritten chunks by scene 53, 460,000 and 29,000 by scene 125 — until the 2 GB
     * server heap ran out around scene 120 on NeoForge, whose baseline is the larger. The symptom is
     * a run of {@code ENV_FAIL … only 0 reached entity-ticking} followed by an
     * {@code OutOfMemoryError} above it, which reads like a scene bug and is not. With the flag off
     * the same runs hold under 1 GB throughout and the queue is empty at every sample. Durability is
     * what the flag buys, and a gate's world is deleted before the next run.
     */
    private static void seedOfflineMode(Path properties) {
        force(properties, "online-mode", "false");
        force(properties, "level-seed", FIXED_SEED);
        force(properties, "sync-chunk-writes", "false");
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
     * Write the four client settings a scene run cannot be correct without. A dedicated server
     * never reads this file, so it is written unconditionally rather than guessed at from the run
     * type.
     *
     * <p>Only four, and each earns its place:
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
     *   <li>{@code enableVsync:false} — with vsync on, the client's only thread blocks in
     *       {@code glfwSwapBuffers} waiting for the compositor, and a compositor that is not
     *       presenting the window (screen asleep, another workspace, a remote session) hands out
     *       frames at about 1 Hz. Minecraft runs at most ten game ticks per frame, so the client
     *       falls to ten ticks a second while the integrated server keeps twenty: every body the
     *       client drives then needs twice the server ticks to do anything, and scenes with a tick
     *       budget fail in a body-shaped way — arrival late, a climb that "stalls", a craft that
     *       times out. Measured, not theorised: three jstacks of a 1 fps run sat in
     *       {@code RenderSystem.flipFrame} having burned 0.04 ms of CPU between them, and the
     *       failing scenes came in at almost exactly 2x their green tick counts.</li>
     * </ul>
     *
     * <p>Minecraft merges missing keys with its defaults, so a four-line file is a complete one.
     * Rewritten every provision rather than created once: a run that changed a setting must not
     * carry it into the next one, which is the same discipline as deleting the world.
     */
    private static void seedClientOptions(Path options) {
        try {
            Files.writeString(options,
                    "pauseOnLostFocus:false\nonboardAccessibility:false\nnarrator:0\nenableVsync:false\n");
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
