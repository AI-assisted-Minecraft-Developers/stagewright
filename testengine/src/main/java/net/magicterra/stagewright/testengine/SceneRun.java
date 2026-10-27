package net.magicterra.stagewright.testengine;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
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

import net.magicterra.stagewright.engine.supervise.AgentJar;
import net.magicterra.stagewright.engine.supervise.Supervisor;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;

/**
 * One scene: start its game (and the companion its topology names), select the scene's classes, relay what the game
 * reports, and when the game is gone report everything it did not as failed with the reason it stopped.
 */
final class SceneRun {

    private static final Duration CONNECT_LIMIT = Duration.ofMinutes(2);
    private static final Duration HEARTBEAT_LIMIT = Duration.ofSeconds(60);
    private static final int AGENT_GRACE_SECONDS = 30;
    private static final int AGENT_IDLE_SECONDS = 20;

    private final Nodes.Scene scene;
    private final EngineExecutionListener listener;
    private final Coverage coverage;

    SceneRun(Nodes.Scene scene, EngineExecutionListener listener, Coverage coverage) {
        this.scene = scene;
        this.listener = listener;
        this.coverage = coverage;
    }

    void run(ClassLoader loader) {
        listener.executionStarted(scene);
        Relay relay = new Relay(scene, listener, coverage);
        TestExecutionResult result;
        try {
            SceneFile declaration = SceneFile.load(scene.name, loader);
            LaunchSpec spec = LaunchSpec.load(declaration.topology());
            if (declaration.world().createsWorld() && !spec.cleanWorld()) {
                throw new IllegalStateException("scene '" + scene.name + "' declares how its world is created, but"
                        + " topology '" + spec.topology() + "' keeps the world of the run before (cleanWorld is false),"
                        + " so none of it would apply");
            }
            listener.reportingEntryPublished(scene, ReportEntry.from("world", declaration.world().describe()));
            result = launch(spec, declaration.world(), relay);
        } catch (Exception e) {
            relay.closeOut(scene, "the scene could not start: " + e.getMessage());
            result = TestExecutionResult.failed(e);
        }
        listener.executionFinished(scene, result);
    }

    private TestExecutionResult launch(LaunchSpec spec, SceneFile.World world, Relay relay)
            throws IOException, InterruptedException {
        Path artifacts = spec.gameDirectory().resolve("stagewright");
        Path logs = artifacts.resolve("logs");
        Files.createDirectories(logs);
        if (spec.cleanWorld()) deleteTree(spec.gameDirectory().resolve("world"));
        // Put back afterwards, so a setting one scene declares is not the next scene's undeclared default.
        Path serverProperties = spec.primary().workingDirectory().resolve("server.properties");
        byte[] original = Files.isRegularFile(serverProperties) ? Files.readAllBytes(serverProperties) : null;
        try {
            writeServerProperties(serverProperties, world);
            return supervise(spec, world, relay, artifacts, logs);
        } finally {
            if (original != null) Files.write(serverProperties, original);
            else Files.deleteIfExists(serverProperties);
        }
    }

    private TestExecutionResult supervise(LaunchSpec spec, SceneFile.World world, Relay relay, Path artifacts,
                                          Path logs) throws IOException, InterruptedException {
        Path agentJar = AgentJar.stage(SceneRun.class, artifacts);
        File gameLog = logs.resolve(scene.name + "-game.log").toFile();

        List<String> classes = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        for (TestDescriptor c : scene.getChildren()) {
            if (c instanceof Nodes.ScriptFile f) scripts.add(f.resource);
            else classes.add(((Nodes.TestClass) c).className);
        }

        Process companion = null;
        try (Supervisor supervisor = Supervisor.open()) {
            supervisor.onTest(relay);
            List<String> extra = new ArrayList<>(List.of(
                    supervisor.agentArgument(agentJar, AGENT_GRACE_SECONDS, AGENT_IDLE_SECONDS),
                    // Also what keeps the topology's own scene suite from starting: the game runs these only.
                    "-Dstagewright.junit.classes=" + String.join(",", classes),
                    "-Dstagewright.junit.scripts=" + String.join(",", scripts),
                    // A script file may register tests for other scenes too; the game runs this one's.
                    "-Dstagewright.junit.scene=" + scene.name,
                    "-Dstagewright.junit.exitWhenDone=true",
                    "-Dstagewright.scene.world=" + world.runtimeJson()));
            // Where the companion client writes its WorldDriver port, for the tests' Client.
            if (spec.companion() != null) {
                extra.add("-Dstagewright.companion.dir=" + spec.companion().workingDirectory().toAbsolutePath());
            }
            Process game = start(spec.primary(), extra, gameLog);
            if (spec.companion() != null) {
                companion = start(spec.companion(), List.of(), logs.resolve(scene.name + "-companion.log").toFile());
            }
            Duration total = Duration.ofMinutes(spec.timeoutMinutes());
            Duration stall = spec.stallMinutes() == 0 ? total : Duration.ofMinutes(spec.stallMinutes());
            Supervisor.Ended ended = supervisor.await(game.toHandle(),
                    new Supervisor.Limits(CONNECT_LIMIT, HEARTBEAT_LIMIT, stall, total));
            if (ended != Supervisor.Ended.DONE) Supervisor.kill(game.toHandle());
            // The supervisor's reader may still be handing over the last lines; they precede the end it saw.
            supervisor.awaitExit(game.toHandle(), Duration.ofSeconds(AGENT_GRACE_SECONDS + 10));
            String why = describe(ended, supervisor, gameLog);
            relay.closeOut(scene, why);
            if (ended == Supervisor.Ended.DONE && relay.ended()) return TestExecutionResult.successful();
            return TestExecutionResult.failed(new AssertionError("the game ended " + why));
        } finally {
            if (companion != null) Supervisor.kill(companion.toHandle());
        }
    }

    private static Process start(LaunchSpec.Process p, List<String> extra, File log) throws IOException {
        Files.createDirectories(p.workingDirectory());
        ProcessBuilder builder = new ProcessBuilder(p.command(extra)).directory(p.workingDirectory().toFile())
                .redirectErrorStream(true).redirectOutput(log);
        builder.environment().putAll(p.environment());
        return builder.start();
    }

    private static String describe(Supervisor.Ended ended, Supervisor supervisor, File log) {
        String detail = switch (ended) {
            case DONE -> "after its run, but without reporting every test";
            case CRASHED -> "by crashing" + (supervisor.crashReport() == null ? "" : ": " + supervisor.crashReport());
            case STALLED -> "stalled in phase " + supervisor.phase() + " at tick " + supervisor.ticks();
            case TIMED_OUT -> "by running past its time limit";
            case HEARTBEAT_LOST -> "by going silent";
            case NEVER_CONNECTED -> "without ever reaching its supervision agent";
            case PROCESS_DIED -> "by exiting without finishing its run";
            default -> String.valueOf(ended);
        };
        return "(" + ended + ") " + detail + "; its log is " + log;
    }

    /**
     * Put what applies at world creation into the game's {@code server.properties}, keeping everything else in it.
     * A dedicated server reads these when it creates the world; a client hosting its own server does not read the
     * file at all (docs/reference/scene-file.md says which settings reach which topology).
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
            wanted.put("initial-enabled-packs", merged(properties.getProperty("initial-enabled-packs", "vanilla"),
                    world.enablePacks()));
        }
        if (!world.disablePacks().isEmpty()) {
            wanted.put("initial-disabled-packs", merged(properties.getProperty("initial-disabled-packs", ""),
                    world.disablePacks()));
        }
        if (wanted.isEmpty()) return;
        properties.putAll(wanted);
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file)) {
            properties.store(out, "StageWright: world settings of the scene that runs next");
        }
    }

    private static String merged(String existing, List<String> added) {
        Set<String> all = new LinkedHashSet<>();
        for (String id : existing.split(",")) if (!id.isBlank()) all.add(id.trim());
        all.addAll(added);
        return String.join(",", all);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
