package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the client topologies through <a href="https://github.com/headlesshq/headlessmc">HeadlessMC</a>.
 *
 * <p>A pack author can already test what a dedicated server can reach. Everything that only exists
 * on a client — a GUI a mod adds, a keybind, a screen a machine opens, the whole client half of a
 * client/server split — needs a real client, and a real client on a build box needs someone to
 * solve assets, natives, a JVM, and an account. HeadlessMC solves exactly that, so we do not.
 *
 * <p>Shelled out to rather than linked in. Its in-memory launching needs a second wrapper jar, and
 * linking would put a third party's version churn on our classpath for a benefit we do not need —
 * all we want is a client process with our system properties on it. Entering a world and connecting
 * to a server is already {@code ClientDirector}'s job, so none of HeadlessMC's own quick-play or
 * server-list features are in play.
 *
 * <p>Not bundled, either: the launcher is 12 MB against this CLI's 0.2 MB, and it is the one part of
 * the story that is somebody else's software with its own release cadence. The author downloads it
 * once and names it with {@code --headlessmc}.
 */
final class HeadlessClient {

    /** The offline profile both client launch paths use, so a scene asserting on the player's name
     *  reads the same in either — and so a results file says which player it ran as. */
    static final String USERNAME = "StageWright";

    /** Where HeadlessMC keeps config, relative to the directory it is run from. */
    private static final String CONFIG_DIR = "HeadlessMC";

    private HeadlessClient() {}

    /**
     * Write the config HeadlessMC reads before every run.
     *
     * <p>Regenerated rather than merged. This file is the whole interface between us and the
     * launcher, an author who hand-edited it would be overridden silently on the next run either
     * way, and a stale key here fails as "the game ignored our properties" — which reads as the mod
     * being broken.
     *
     * <p>{@code hmc.mcdir} and not {@code hmc.gamedir} is what isolates an install: {@code mcdir}
     * owns {@code versions/}, {@code libraries/} and {@code assets/}, while {@code gamedir} is only
     * where the game runs. Setting {@code gamedir} alone leaves the launcher reading the machine's
     * real {@code .minecraft} — measured, and on a developer's box that is their actual game.
     */
    static void writeConfig(Path workDir, Path gameDir, List<String> systemProps,
                            boolean offline, Consumer<String> log) {
        Path config = workDir.resolve(CONFIG_DIR).resolve("config.properties");
        StringBuilder sb = new StringBuilder();
        sb.append("# Written by stagewright before every client run. Edits here do not survive.\n");
        sb.append("hmc.mcdir=").append(forProperties(gameDir)).append('\n');
        sb.append("hmc.gamedir=").append(forProperties(gameDir)).append('\n');
        // hmc.offline is not only "no account" — it FORCES the LWJGL stub, and says so on the way
        // past: "You are offline, game will start in headless mode!". So an account is not a nicety
        // here; it is the only way to get a rendering client out of this launcher, and a modpack
        // needs one because a 450-mod resource reload does real work through those entry points.
        if (offline) {
            sb.append("hmc.offline=true\n");
            sb.append("hmc.offline.username=").append(USERNAME).append('\n');
        }
        if (!systemProps.isEmpty()) {
            sb.append("hmc.jvmargs=").append(String.join(" ", systemProps)).append('\n');
        }
        try {
            Files.createDirectories(config.getParent());
            Files.writeString(config, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + config, e);
        }
        log.accept("headlessmc config at " + config);
    }

    /**
     * Start the launcher and tell it to launch, leaving its stdin open.
     *
     * <p>Three things about this are not obvious and each one silently produces a run that never
     * happens:
     *
     * <ul>
     *   <li>The launcher does NOT accept {@code launch …} as process arguments. Passed that way it
     *       prints its version table and exits successfully, which looks like the game starting and
     *       ending instantly. The command has to arrive on stdin under {@code --command}.</li>
     *   <li>stdin has to STAY open. Following the launch with {@code exit}, or passing
     *       {@code -quit}, tears the game down before it has started.</li>
     *   <li>{@code -lwjgl} is what makes it headless — every LWJGL entry point becomes a stub, so no
     *       display and no Xvfb. {@code -offline} is what lets it run without a Minecraft account.
     *       The two are not independent: offline <b>implies</b> the stub, so the only rendering
     *       client this launcher will produce is one launched with a real account.</li>
     * </ul>
     *
     * @param account the account id to make primary first, {@code ""} to use whichever HeadlessMC
     *                already has selected, or null for an offline run. Logging in is the user's job
     *                and stays the user's job: it is an interactive password or webview prompt, and
     *                a test runner has no business standing between somebody and their own login.
     */
    static Process launch(Path hmcJar, Path workDir, String javaBinary, String versionId,
                          String account, List<String> launcherJvm, Consumer<String> log) {
        List<String> command = new ArrayList<>(List.of(javaBinary));
        command.addAll(launcherJvm);
        command.addAll(List.of("-jar", hmcJar.toString(), "--command"));
        String launch = account == null
                ? "launch " + versionId + " -lwjgl -offline"
                : "launch " + versionId;
        log.accept(String.join(" ", command) + "  <<< " + launch);
        try {
            Process p = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(
                            workDir.resolve("stagewright-headlessmc.log").toFile()))
                    .start();
            // Deliberately not closed: see above.
            Writer in = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
            if (account != null && !account.isBlank()) {
                in.write("account " + account + "\n");
            }
            in.write(launch + "\n");
            in.flush();
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start HeadlessMC from " + hmcJar, e);
        }
    }

    /**
     * Install a loader into the isolated game directory, if it is not already there.
     *
     * <p>Returns the version id to launch. HeadlessMC names the installed version after the loader
     * build it resolved ({@code neoforge-21.1.248}), which we cannot know in advance — so the id is
     * read back off disk rather than guessed.
     */
    static String installLoader(Path hmcJar, Path workDir, Path gameDir, String javaBinary,
                                String loader, String mcVersion, List<String> launcherJvm,
                                Consumer<String> log) {
        String existing = installedVersion(gameDir, loader);
        if (existing != null) {
            log.accept("using the " + loader + " already installed in this game dir: " + existing);
            return existing;
        }
        log.accept("installing " + loader + " " + mcVersion + " (this downloads the game — minutes,"
                + " once per game dir)");
        run(hmcJar, workDir, javaBinary, launcherJvm, loader + " " + mcVersion);
        String installed = installedVersion(gameDir, loader);
        if (installed == null) {
            throw new IllegalStateException("HeadlessMC did not install " + loader + " " + mcVersion
                    + " — see " + workDir.resolve("stagewright-headlessmc.log"));
        }
        log.accept("installed " + installed);
        return installed;
    }

    /** The newest {@code versions/} entry whose name starts with the loader, or null. */
    private static String installedVersion(Path gameDir, String loader) {
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        try (var dirs = Files.list(versions)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(loader + "-"))
                    .max(String::compareTo)
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + versions, e);
        }
    }

    /** One command, run to completion — for installs, which end on their own. */
    private static void run(Path hmcJar, Path workDir, String javaBinary, List<String> launcherJvm,
                            String command) {
        List<String> argv = new ArrayList<>(List.of(javaBinary));
        argv.addAll(launcherJvm);
        argv.addAll(List.of("-jar", hmcJar.toString(), "--command"));
        try {
            Process p = new ProcessBuilder(argv)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            workDir.resolve("stagewright-headlessmc.log").toFile()))
                    .start();
            try (Writer in = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                in.write(command + "\nexit\n");
            }
            p.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run HeadlessMC command '" + command + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running '" + command + "'", e);
        }
    }

    /** A path as a .properties value: forward slashes, so no escaping question arises on Windows. */
    private static String forProperties(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
