package net.magicterra.stagewright.testengine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.magicterra.stagewright.engine.RunDirectory;
import net.magicterra.stagewright.engine.supervise.AgentJar;
import net.magicterra.stagewright.engine.supervise.Supervisor;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;

/**
 * One scene: start its game (and the companion its topology names), select the scene's classes, relay what the game
 * reports, and when the game is gone report everything it did not as failed with the reason it stopped. A game that had
 * to be forced down after its run fails the scene itself, naming what held it up, and so does a joined client that
 * crashes, goes silent or leaves before the run is over.
 */
// Separators (",", "\n") and the message wording repeat by nature; a constant per separator would only rename them.
@SuppressWarnings("checkstyle:multiplestringliterals")
final class SceneRun {

    private static final Duration CONNECT_LIMIT = Duration.ofMinutes(2);

    private static final Duration HEARTBEAT_LIMIT = Duration.ofSeconds(60);

    private static final int AGENT_IDLE_SECONDS = 20;

    /** How long a game may take to end after its run before the agent ends it. Shortened by the engine's own tests. */
    static volatile int agentGraceSeconds = 30;

    /** Frames of each leaked thread's stack a forced-down report names: enough to tell whose pool it is. */
    private static final int LEAK_FRAMES = 4;

    private final Nodes.Scene scene;

    private final EngineExecutionListener listener;

    private final Coverage coverage;

    SceneRun(Nodes.Scene scene, EngineExecutionListener listener, Coverage coverage) {
        this.scene = scene;
        this.listener = listener;
        this.coverage = coverage;
    }

    /**
     * What scene {@code name} holds while it runs, for {@link SceneScheduler}: a game client, and the world of a
     * topology that keeps it between scenes. Nothing when its declaration or launch cannot be read, which {@link #run}
     * then reports.
     */
    static SceneScheduler.Needs needs(String name, ClassLoader loader) {
        try {
            LaunchSpec spec = LaunchSpec.load(SceneFile.load(name, loader).topology());
            return new SceneScheduler.Needs(
                    spec.companion() != null || !spec.server(),
                    spec.cleanWorld()
                            ? null
                            : spec.gameDirectory().toAbsolutePath().normalize().toString());
        } catch (RuntimeException e) {
            return SceneScheduler.Needs.NOTHING;
        }
    }

    void run(ClassLoader loader) {
        SceneFile declaration = null;
        LaunchSpec spec = null;
        Exception early = null;
        try {
            declaration = SceneFile.load(scene.name, loader);
            spec = LaunchSpec.load(declaration.topology());
        } catch (LaunchSpec.Unlaunchable e) {
            // Skipped, not failed: the run was not given what this topology needs (the CLI without --client). Its
            // tests do not count against coverage either, since they would have run had it been given.
            for (TestDescriptor d : scene.getDescendants()) {
                if (d instanceof Nodes.TestMethod m) {
                    coverage.unlaunched(m.key());
                } else if (d instanceof Nodes.ScriptTest t) {
                    coverage.unlaunched(t.key());
                }
            }
            listener.executionSkipped(scene, "scene '" + scene.name + "' was not run: " + e.getMessage());
            return;
        } catch (Exception e) {
            early = e;
        }
        listener.executionStarted(scene);
        Relay relay = new Relay(scene, listener, coverage);
        TestExecutionResult result;
        try {
            if (early != null) {
                throw early;
            }
            if (declaration.world().createsWorld() && !spec.cleanWorld()) {
                throw new IllegalStateException("scene '" + scene.name + "' declares how its world is created, but"
                        + " topology '" + spec.topology() + "' keeps the world of the run before (cleanWorld is false),"
                        + " so none of it would apply");
            }
            if (declaration.from() != null && !spec.cleanWorld()) {
                throw new IllegalStateException("scene '" + scene.name + "' starts from a save, but topology '"
                        + spec.topology() + "' keeps the world of the run before (cleanWorld is false)");
            }
            listener.reportingEntryPublished(
                    scene,
                    ReportEntry.from(
                            "world",
                            (declaration.from() == null ? "" : "from=" + declaration.from() + ", ")
                                    + declaration.world().describe()));
            result = launch(spec, declaration, relay, loader);
        } catch (Exception e) {
            relay.closeOut(scene, "the scene could not start: " + e.getMessage());
            result = TestExecutionResult.failed(e);
        }
        listener.executionFinished(scene, result);
    }

    private TestExecutionResult launch(LaunchSpec topology, SceneFile declaration, Relay relay, ClassLoader loader)
            throws IOException, InterruptedException {
        // Everything below writes into the scene's own directories, never the topology's: nothing is put back after,
        // and another scene of the topology may be running beside this one.
        LaunchSpec spec = topology.inSceneDirectories(scene.name);
        listener.reportingEntryPublished(
                scene, ReportEntry.from("directory", spec.gameDirectory().toString()));
        Path artifacts = spec.gameDirectory().resolve(RunDirectory.ARTIFACT_DIR);
        Path logs = artifacts.resolve("logs");
        Files.createDirectories(logs);
        Path modsScratch = artifacts.resolve("scene-mods");
        SceneFile.World world = declaration.world();
        TestExecutionResult result = null;
        try {
            if (declaration.from() != null) {
                SceneSetup.installSave(declaration.from(), loader, spec.worldDirectory());
            }
            SceneSetup.writeFiles(spec.primary().workingDirectory(), declaration.files());
            SceneSetup.writeFiles(clientDirectory(spec, declaration), declaration.clientFiles());
            if (!declaration.excludedMods().isEmpty()) {
                spec = withoutMods(spec, declaration.excludedMods(), modsScratch);
            }
            int serverPort = 0;
            if (spec.wantsFreePort()) {
                serverPort = freePort();
                // Beside the scene's other rewritten argument files.
                spec = spec.withServerPort(serverPort, modsScratch.resolve("server-port"));
                listener.reportingEntryPublished(scene, ReportEntry.from("serverPort", String.valueOf(serverPort)));
            }
            // A run directory nothing else prepares, such as one the plugin generated: a dedicated server there needs
            // the EULA accepted and offline mode before it starts at all.
            Path serverProperties = spec.primary().workingDirectory().resolve("server.properties");
            SceneDirectory.writable(spec.primary().workingDirectory(), serverProperties);
            RunDirectory.provision(spec.primary().workingDirectory(), spec.server());
            writeServerProperties(serverProperties, world);
            SceneWorlds worlds = new SceneWorlds(spec.worldDirectory(), artifacts.resolve("saves"));
            try {
                result = supervise(spec, world, relay, artifacts, logs, worlds, loader, serverPort);
            } finally {
                worlds.restoreFirst();
            }
            return result;
        } finally {
            boolean passed =
                    result != null && result.getStatus() == TestExecutionResult.Status.SUCCESSFUL && !relay.anyFailed();
            for (Path dir : spec.directories()) {
                SceneDirectory.finish(dir, passed);
            }
        }
    }

    /**
     * Where {@code clientFiles} go: the joining client's directory, or the game's when it is itself a client. A scene
     * that declares some on a topology with no client is refused rather than run without them.
     */
    static Path clientDirectory(LaunchSpec spec, SceneFile declaration) {
        if (spec.companion() != null) {
            return spec.companion().workingDirectory();
        }
        if (!spec.server()) {
            return spec.primary().workingDirectory();
        }
        if (!declaration.clientFiles().isEmpty()) {
            throw new IllegalStateException("scene '" + declaration.name() + "': clientFiles needs a game client, and"
                    + " topology '" + spec.topology() + "' starts a dedicated server alone");
        }
        return spec.primary().workingDirectory();
    }

    /** {@code spec} with both its games started without {@code exclude}, and a report entry saying which jars went. */
    // The console line is how a build's log shows which mods a scene dropped, beside the scene's own output.
    @SuppressWarnings("PMD.SystemPrintln")
    private LaunchSpec withoutMods(LaunchSpec spec, List<String> exclude, Path scratch) throws IOException {
        SceneMods.Applied primary = SceneMods.apply(spec.primary(), exclude, scratch.resolve("primary"));
        SceneMods.Applied companion = spec.companion() == null
                ? null
                : SceneMods.apply(spec.companion(), exclude, scratch.resolve("companion"));
        String report = "excluded " + primary.excluded();
        if (companion != null) {
            report = "server: " + report + "; client: excluded " + companion.excluded();
        }
        String unmatched = companion == null
                ? unmatched("the game", primary)
                : unmatched("the server", primary) + unmatched("the client", companion);
        if (!unmatched.isEmpty()) {
            report += unmatched;
            System.out.println("[stagewright] scene " + scene.getDisplayName() + ": mods.exclude" + unmatched);
        }
        listener.reportingEntryPublished(scene, ReportEntry.from("mods", report));
        return new LaunchSpec(
                spec.topology(),
                spec.gameDirectory(),
                spec.worldDirectory(),
                spec.cleanWorld(),
                spec.timeoutMinutes(),
                spec.stallMinutes(),
                spec.server(),
                spec.leaks(),
                primary.process(),
                companion == null ? null : companion.process());
    }

    /** "; matched no mod of {@code which}: [...]", or nothing when every name matched one. */
    private static String unmatched(String which, SceneMods.Applied applied) {
        return applied.unmatched().isEmpty() ? "" : "; matched no mod of " + which + ": " + applied.unmatched();
    }

    // The round loop below is long because each round is one game, its companion and their supervision in turn.
    @SuppressWarnings("checkstyle:javancss")
    private TestExecutionResult supervise(
            LaunchSpec spec,
            SceneFile.World world,
            Relay relay,
            Path artifacts,
            Path logs,
            SceneWorlds worlds,
            ClassLoader loader,
            int serverPort)
            throws IOException, InterruptedException {
        List<String> classes = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        for (TestDescriptor c : scene.getChildren()) {
            if (c instanceof Nodes.ScriptFile f) {
                scripts.add(f.resource);
            } else {
                classes.add(((Nodes.TestClass) c).className);
            }
        }

        // What the rounds before handed to the ones after; a scene's first round starts with neither.
        Files.deleteIfExists(artifacts.resolve("store.json"));
        Files.deleteIfExists(artifacts.resolve("rounds.json"));
        Files.deleteIfExists(artifacts.resolve("handovers.json"));
        // One entry per round whose game had to be forced down after its run.
        List<String> forced = new ArrayList<>();
        // Staged once for every round: each round's game loads the same agent.
        Path agentJar = AgentJar.stage(SceneRun.class, artifacts);
        for (int round = 0; ; round++) {
            String suffix = round == 0 ? "" : "-round" + round;
            File gameLog = logs.resolve(scene.name + suffix + "-game.log").toFile();
            File companionLog =
                    logs.resolve(scene.name + suffix + "-companion.log").toFile();
            Process companion = null;
            // The joined client carries the agent too, reporting to a supervisor of its own: one serves one process.
            try (Supervisor supervisor = Supervisor.open();
                    Supervisor joined = spec.companion() == null ? null : Supervisor.open()) {
                supervisor.onTest(relay);
                List<String> extra = new ArrayList<>(List.of(
                        supervisor.agentArgument(agentJar, agentGraceSeconds, AGENT_IDLE_SECONDS),
                        // Also what keeps the topology's own scene suite from starting: the game runs these only.
                        "-Dstagewright.junit.classes=" + String.join(",", classes),
                        "-Dstagewright.junit.scripts=" + String.join(",", scripts),
                        // A script file may register tests for other scenes too; the game runs this one's.
                        "-Dstagewright.junit.scene=" + scene.name,
                        "-Dstagewright.junit.round=" + round,
                        "-Dstagewright.junit.exitWhenDone=true",
                        "-Dstagewright.scene.world=" + world.runtimeJson()));
                // Where the companion client writes its WorldDriver port, for the tests' Client.
                if (spec.companion() != null) {
                    extra.add("-Dstagewright.companion.dir="
                            + spec.companion().workingDirectory().toAbsolutePath());
                }
                Process game = start(spec.primary(), extra, gameLog);
                if (joined != null) {
                    List<String> companionExtra = new ArrayList<>(
                            List.of(joined.agentArgument(agentJar, agentGraceSeconds, AGENT_IDLE_SECONDS)));
                    // The free port the server was given; the client's own connect address carries none that fits.
                    if (serverPort > 0) {
                        companionExtra.add("-Dstagewright.client.port=" + serverPort);
                    }
                    companion = start(spec.companion(), companionExtra, companionLog);
                }
                Duration total = Duration.ofMinutes(spec.timeoutMinutes());
                Duration stall = spec.stallMinutes() == 0 ? total : Duration.ofMinutes(spec.stallMinutes());
                Supervisor.Limits limits = new Supervisor.Limits(CONNECT_LIMIT, HEARTBEAT_LIMIT, stall, total);
                Supervisor.Ended ended;
                if (joined == null) {
                    ended = supervisor.await(game.toHandle(), limits);
                } else {
                    Supervisor.Ending ending =
                            supervisor.await(game.toHandle(), limits, joined, companion.toHandle(), limits);
                    if (ending.companion()) {
                        // The run cannot go on without its player: end the game, and say it was the client.
                        Supervisor.kill(game.toHandle());
                        String why = describeCompanion(
                                ending.ended(),
                                joined.phase(),
                                joined.ticks(),
                                joined.crashReport(),
                                joined.stacks(),
                                companionLog);
                        listener.reportingEntryPublished(scene, ReportEntry.from("companion", why));
                        appendToLog(companionLog, "the joined client ended " + why);
                        relay.closeOut(scene, "the joined client ended " + why);
                        return TestExecutionResult.failed(new AssertionError("the joined client ended " + why
                                + "\nthe game's log is " + gameLog
                                + (forced.isEmpty() ? "" : "\n" + String.join("\n", forced))));
                    }
                    ended = ending.ended();
                }
                if (ended != Supervisor.Ended.DONE) {
                    Supervisor.kill(game.toHandle());
                }
                // The supervisor's reader may still be handing over the last lines; they precede the end it saw.
                boolean exited = supervisor.awaitExit(game.toHandle(), Duration.ofSeconds(agentGraceSeconds + 10));
                // Not ended by the agent either: the next round, or the next scene, must not find it still there.
                if (!exited) {
                    Supervisor.kill(game.toHandle());
                }
                if (ended == Supervisor.Ended.DONE && (supervisor.forcedDown() || !exited)) {
                    String report = forcedDown(round, supervisor.leaks(), exited, agentGraceSeconds);
                    // Reported the same either way; only the verdict is the topology's to choose.
                    if (spec.leaks() == LaunchSpec.Leaks.FAIL) {
                        forced.add(report);
                    }
                    listener.reportingEntryPublished(scene, ReportEntry.from("forcedDown", report));
                    appendToLog(gameLog, report);
                }
                boolean again = ended == Supervisor.Ended.DONE && relay.ended() && relay.restart() == round + 1;
                if (again) {
                    // The next round opens the same world: this game is gone, its session lock with it.
                    worlds.switchTo(relay.restartWorld(), relay.restartWorldFrom(), loader);
                    relay.nextRound();
                    listener.reportingEntryPublished(
                            scene,
                            ReportEntry.from("restart", "round " + (round + 1) + " on world " + worlds.current()));
                    continue;
                }
                String why = describe(
                        ended,
                        supervisor.phase(),
                        supervisor.ticks(),
                        supervisor.crashReport(),
                        supervisor.stacks(),
                        gameLog);
                relay.closeOut(scene, why);
                if (ended == Supervisor.Ended.DONE && relay.ended()) {
                    // Every test passed or failed on its own; a game that would not end is the scene's failure.
                    return forced.isEmpty()
                            ? TestExecutionResult.successful()
                            : TestExecutionResult.failed(
                                    new AssertionError(String.join("\n", forced) + "\nits log is " + gameLog));
                }
                return TestExecutionResult.failed(new AssertionError(
                        "the game ended " + why + (forced.isEmpty() ? "" : "\n" + String.join("\n", forced))));
            } finally {
                if (companion != null) {
                    Supervisor.kill(companion.toHandle());
                }
            }
        }
    }

    private static Process start(LaunchSpec.Process p, List<String> extra, File log) throws IOException {
        Files.createDirectories(p.workingDirectory());
        ProcessBuilder builder = new ProcessBuilder(p.command(extra))
                .directory(p.workingDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log);
        builder.environment().putAll(p.environment());
        return builder.start();
    }

    /** A port nothing listens on now, for a server the scene starts next; another program may still take it first. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * What a dedicated server's log says when it could not bind its port, which only names the cause in the line after
     * {@code FAILED TO BIND TO PORT}; null when it does not say so, or there is no log.
     */
    static String bindFailure(File log) {
        if (log == null || !log.isFile()) {
            return null;
        }
        // A reader that replaces bytes it cannot decode rather than throwing: a game log is not always clean UTF-8.
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(Files.newInputStream(log.toPath()), StandardCharsets.UTF_8))) {
            boolean failed = false;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.contains("FAILED TO BIND TO PORT")) {
                    failed = true;
                } else if (failed && line.contains("The exception was:")) {
                    return line.substring(line.indexOf("The exception was:") + "The exception was:".length())
                            .trim();
                }
            }
            return failed ? "FAILED TO BIND TO PORT" : null;
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    /**
     * Why the game ended, for every test it did not finish. Where its own threads stood goes with it whenever the agent
     * had sampled them, which it does once nothing has moved for a while: for a stall that is the stuck frame.
     */
    static String describe(
            Supervisor.Ended ended,
            String phase,
            long ticks,
            String crashReport,
            List<Supervisor.ThreadStack> stacks,
            File log) {
        String detail = switch (ended) {
            case DONE -> "after its run, but without reporting every test";
            case CRASHED -> "by crashing" + (crashReport == null ? "" : ": " + crashReport);
            case STALLED -> "stalled in phase " + phase + " at tick " + ticks;
            case TIMED_OUT -> "by running past its time limit";
            case HEARTBEAT_LOST -> "by going silent";
            case NEVER_CONNECTED -> "without ever reaching its supervision agent";
            case PROCESS_DIED -> "by exiting without finishing its run";
        };
        StringBuilder out = new StringBuilder(384)
                .append('(')
                .append(ended)
                .append(") ")
                .append(detail)
                .append("; its log is ")
                .append(log);
        String bind = bindFailure(log);
        if (bind != null) {
            out.append("\nthe server could not listen on its port (")
                    .append(bind)
                    .append("): another game on this machine may hold it. A suite that leaves serverPort unset gets a"
                            + " free port for each scene");
        }
        if (ended != Supervisor.Ended.DONE && !stacks.isEmpty()) {
            out.append("\nwhere its threads were when it last stood still:");
            for (Supervisor.ThreadStack t : stacks) {
                out.append("\n  ").append(t.thread());
                for (String frame : t.frames()) {
                    out.append("\n    at ").append(frame);
                }
            }
        }
        return out.toString();
    }

    /**
     * Why the client joined to the game ended the run, which only a failure of the client's does. Its {@code DONE} is
     * the client giving up: it says so once it is out of the world, or never got into it, before the run was over.
     */
    static String describeCompanion(
            Supervisor.Ended ended,
            String phase,
            long ticks,
            String crashReport,
            List<Supervisor.ThreadStack> stacks,
            File log) {
        if (ended != Supervisor.Ended.DONE) {
            return describe(ended, phase, ticks, crashReport, stacks, log);
        }
        return "(" + ended + ") by leaving the server before the run was over, in phase " + phase + "; its log is "
                + log;
    }

    /**
     * What held a game up after its run: the threads the agent named before it halted the JVM, or that the game did not
     * end even then and was killed. Each thread with its top frames, the jars on them and the code that started it.
     */
    static String forcedDown(int round, List<Supervisor.Leak> leaks, boolean exited, int graceSeconds) {
        StringBuilder out = new StringBuilder(round == 0 ? "the game" : "round " + round + "'s game");
        if (exited) {
            out.append(" had to be forced down: its JVM was still up ")
                    .append(graceSeconds)
                    .append("s after its run");
        } else {
            out.append(" was still up ").append(graceSeconds + 10).append("s after its run and was killed");
        }
        if (leaks.isEmpty()) {
            return out.append(exited ? ", held by no thread the agent could name" : "")
                    .toString();
        }
        out.append(", held by ").append(leaks.size()).append(leaks.size() == 1 ? " thread:" : " threads:");
        for (Supervisor.Leak leak : leaks) {
            out.append("\n  ").append(leak.thread());
            List<String> stack = leak.stack();
            for (int i = 0; i < Math.min(stack.size(), LEAK_FRAMES); i++) {
                out.append("\n    at ").append(stack.get(i));
            }
            if (!leak.jars().isEmpty()) {
                out.append("\n    code from ").append(String.join(", ", leak.jars()));
            }
            if (!leak.origin().isEmpty()) {
                out.append("\n    started by ").append(leak.origin().get(0));
                if (!leak.originJars().isEmpty()) {
                    out.append(" in ").append(String.join(", ", leak.originJars()));
                }
            }
        }
        return out.toString();
    }

    /** After the game's own output, which has ended with its process, so the log says why the run failed too. */
    private static void appendToLog(File log, String text) {
        try {
            Files.writeString(
                    log.toPath(),
                    "\n[stagewright] " + text + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The report entry and the scene's failure carry it as well.
        }
    }

    /**
     * Put what applies at world creation into the game's {@code server.properties}, keeping everything else in it. A
     * dedicated server reads these when it creates the world; a client hosting its own server does not read the file at
     * all (docs/reference/scene-file.md says which settings reach which topology).
     */
    static void writeServerProperties(Path file, SceneFile.World world) throws IOException {
        Map<String, String> wanted = new LinkedHashMap<>(world.serverProperties());
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                properties.load(in);
            }
        }
        // The packs a scene names come on top of those the game already starts with, never instead of them.
        if (!world.enablePacks().isEmpty()) {
            wanted.put(
                    "initial-enabled-packs",
                    merged(properties.getProperty("initial-enabled-packs", "vanilla"), world.enablePacks()));
        }
        if (!world.disablePacks().isEmpty()) {
            wanted.put(
                    "initial-disabled-packs",
                    merged(properties.getProperty("initial-disabled-packs", ""), world.disablePacks()));
        }
        if (wanted.isEmpty()) {
            return;
        }
        properties.putAll(wanted);
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file)) {
            properties.store(out, "StageWright: world settings of the scene that runs next");
        }
    }

    private static String merged(String existing, List<String> added) {
        Set<String> all = new LinkedHashSet<>();
        for (String id : existing.split(",")) {
            if (!id.isBlank()) {
                all.add(id.trim());
            }
        }
        all.addAll(added);
        return String.join(",", all);
    }
}
