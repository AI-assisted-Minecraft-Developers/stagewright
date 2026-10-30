package net.magicterra.stagewright.testengine;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
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

    private TestExecutionResult launch(LaunchSpec spec, SceneFile declaration, Relay relay, ClassLoader loader)
            throws IOException, InterruptedException {
        Path artifacts = spec.gameDirectory().resolve("stagewright");
        Path logs = artifacts.resolve("logs");
        Files.createDirectories(logs);
        // Both layouts: a dedicated server's world, and an integrated server's under the client's saves.
        if (spec.cleanWorld()) {
            deleteTree(spec.worldDirectory());
            deleteTree(spec.gameDirectory().resolve("saves"));
        }
        if (declaration.from() != null) {
            SceneSetup.installSave(declaration.from(), loader, spec.worldDirectory());
        }
        if (spec.companion() != null) {
            Files.createDirectories(spec.companion().workingDirectory());
        }
        // Put back afterwards, so a setting one scene declares is not the next scene's undeclared default.
        Path serverProperties = spec.primary().workingDirectory().resolve("server.properties");
        byte[] original = Files.isRegularFile(serverProperties) ? Files.readAllBytes(serverProperties) : null;
        SceneSetup.Restore files = SceneSetup.writeFiles(spec.primary().workingDirectory(), declaration.files());
        Path modsScratch = artifacts.resolve("scene-mods");
        SceneFile.World world = declaration.world();
        try {
            if (!declaration.excludedMods().isEmpty()) {
                spec = withoutMods(spec, declaration.excludedMods(), modsScratch);
            }
            // A run directory nothing else prepares, such as one the plugin generated: a dedicated server there needs
            // the EULA accepted and offline mode before it starts at all. Inside the put-back, like the scene's own.
            RunDirectory.provision(spec.primary().workingDirectory(), spec.server());
            writeServerProperties(serverProperties, world);
            return supervise(spec, world, relay, artifacts, logs);
        } finally {
            try {
                files.run();
                SceneMods.deleteTree(modsScratch);
            } finally {
                if (original != null) {
                    Files.write(serverProperties, original);
                } else {
                    Files.deleteIfExists(serverProperties);
                }
            }
        }
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
            LaunchSpec spec, SceneFile.World world, Relay relay, Path artifacts, Path logs)
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
                    companion = start(
                            spec.companion(),
                            List.of(joined.agentArgument(agentJar, agentGraceSeconds, AGENT_IDLE_SECONDS)),
                            companionLog);
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
                    relay.nextRound();
                    listener.reportingEntryPublished(scene, ReportEntry.from("restart", "round " + (round + 1)));
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
        StringBuilder out = new StringBuilder(128)
                .append('(')
                .append(ended)
                .append(") ")
                .append(detail)
                .append("; its log is ")
                .append(log);
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

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
