package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares the directory a scene's game runs in.
 *
 * <p>Identical work whether the launch came from the Gradle plugin or the CLI: a handful of settings have to be true
 * before a dedicated server starts. A second copy of these rules would drift, and the drift would show up as a topology
 * that fails for reasons no scene explains.
 */
public final class RunDirectory {

    /**
     * Every file a run produces goes here, under the run directory — which may be a player's own {@code .minecraft},
     * where a file at the top level is litter among their files.
     */
    public static final String ARTIFACT_DIR = "stagewright";

    private RunDirectory() {}

    /**
     * Make {@code gameDir} fit to run in.
     *
     * @param gameDir the run's working directory; created if absent
     * @param server a dedicated server will run here, so write the EULA acceptance and the forced
     *     {@code server.properties} keys; never for a client's game directory, which may be a player's own
     *     {@code .minecraft}
     */
    public static void provision(Path gameDir, boolean server) {
        if (!Files.isDirectory(gameDir)) {
            try {
                Files.createDirectories(gameDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create the run directory " + gameDir, e);
            }
        }

        // A client's four run settings are not written here: the game sets them in memory (RunOptions),
        // so that a run in a player's own .minecraft cannot overwrite their options.txt.
        if (server) {
            seedServerEula(gameDir.resolve("eula.txt"));
            seedOfflineMode(gameDir.resolve("server.properties"));
        }
    }

    /**
     * Force {@code online-mode=false}, the way ModDevGradle already does for its own dev server runs.
     *
     * <p>A dev client has no Mojang session, so an online-mode server rejects it during login. The client does not stop
     * — it redials, is rejected, redials — and the server sits waiting for a player that authentication will never let
     * in. Both halves then run to their timeout and the only trace is a line of {@code lost connection: Disconnected}
     * in the server log, which reads like a network fault rather than a setting.
     *
     * <p>Forced rather than defaulted, because a scene run cannot work any other way and this is the run's own
     * directory. Every other key the file carries is left exactly as it was.
     *
     * <p>{@code sync-chunk-writes=false} is the third forced key, and it is about heap, not speed. A suite stages every
     * arena in fresh chunks, so a run generates a few hundred chunks per scene, and each one is handed to the level's
     * single {@code IOWorker} thread to write. With the vanilla default of {@code true} every region write is an fsync,
     * the thread falls behind from the first scene, and the backlog is held on the heap: the queued task, the chunk's
     * NBT, and the region file it belongs to. Measured on both loaders in one afternoon — 170,000 queued writes and
     * 9,000 unwritten chunks by scene 53, 460,000 and 29,000 by scene 125 — until the 2 GB server heap ran out around
     * scene 120 on NeoForge, whose baseline is the larger. With the flag off the same runs hold under 1 GB throughout
     * and the queue is empty at every sample. Durability is what the flag buys, and a scene's world is deleted before
     * the next one.
     */
    private static void seedOfflineMode(Path properties) {
        force(properties, "online-mode", "false");
        force(properties, "level-seed", FIXED_SEED);
        force(properties, "sync-chunk-writes", "false");
    }

    /**
     * The world seed every run gets.
     *
     * <p>Left empty, the server rolls a fresh seed per run. That is invisible while every arena sits in empty sky at
     * {@code y=200}, and becomes the difference between a reproducible suite and a flaky one the moment a scene asks
     * for generated terrain: the same arena coordinates would be a hilltop one run and a lake the next, and the scene
     * that failed would look like a bot bug.
     *
     * <p>Must equal {@code WorldCreation.DEFAULT_SEED}, which pins the same thing for the topologies whose world a
     * client creates rather than this provisioner. The value is arbitrary; that the two agree is not. The constant is
     * written twice because the two live in builds that cannot see each other: this one has no Minecraft on its
     * classpath, and the other cannot run outside the game.
     */
    private static final String FIXED_SEED = "5471";

    /** Force one server.properties key, leaving every other line exactly as it was. */
    private static void force(Path properties, String key, String value) {
        String line = key + "=" + value;
        String newline = "\n";
        try {
            if (!Files.exists(properties)) {
                Files.writeString(properties, line + newline);
                return;
            }
            String body = Files.readString(properties);
            String patched = body.replaceAll("(?m)^" + Pattern.quote(key) + "\\s*=.*$", Matcher.quoteReplacement(line));
            if (patched.equals(body) && !body.contains(line)) {
                patched = body + (body.endsWith(newline) ? "" : newline) + line + newline;
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
     * <p>This levels a loader difference rather than making a new decision. Under ModDevGradle a fresh server run
     * directory boots; under loom it does not — the server prints "You need to agree to the EULA" and exits before any
     * mod initialises. It only bites where a run directory is NEW: an existing one carries an acceptance from whenever
     * a developer first ran a server by hand.
     */
    private static void seedServerEula(Path eula) {
        try {
            Files.writeString(eula, "eula=true\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + eula, e);
        }
    }
}
