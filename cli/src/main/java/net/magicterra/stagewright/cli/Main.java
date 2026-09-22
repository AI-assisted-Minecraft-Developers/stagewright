package net.magicterra.stagewright.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.magicterra.stagewright.contract.AttachedRun;
import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.RpcDriverBinding;
import net.magicterra.stagewright.contract.SceneSpec;
import net.magicterra.stagewright.contract.Scripts;
import net.magicterra.stagewright.contract.StageWrightRpc;
import net.magicterra.stagewright.engine.Manifest;
import net.magicterra.stagewright.engine.RunDirectory;
import net.magicterra.stagewright.engine.Verdict;

/**
 * Run a modpack's scenes and judge them, with no build tool anywhere.
 *
 * <p>The Gradle plugin cannot be a modpack author's entry point: they have a {@code mods/} folder
 * and a launcher, not a Gradle project, and asking them to stand one up to test the pack they
 * already have is asking them to become a mod developer first. This is the same run the plugin
 * performs — same provisioning rules, same results contract, same judge, byte for byte, because all
 * three come from the engine rather than from here.
 *
 * <p>Java rather than a script, and not arbitrarily: a modpack author is running Minecraft, so a JVM
 * is the one interpreter they are guaranteed to have.
 *
 * <p>Exit codes are the orchestration contract's: 0 GREEN, 1 RED, 2 DEAD, 3 ENV.
 */
public final class Main {

    private static final int DEFAULT_TIMEOUT_MINUTES = 45;

    /**
     * How long a finished game may take to close itself before this CLI kills it.
     *
     * <p>Longer than the harness's own 30-second exit watchdog on purpose: a JVM that can go down by
     * itself always gets the chance to, and only one that cannot is killed. That the harness's
     * {@code Runtime.halt} sometimes cannot is measured rather than assumed — on a 262-mod pack the
     * warning it prints before halting is the last line in the log and the process is still there
     * afterwards, wedged where nothing inside it can act.
     */
    private static final int DRAIN_GRACE_SECONDS = 45;

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("stagewright: " + e.getMessage());
            System.err.println();
            usage(System.err);
            System.exit(3);
        } catch (Exception e) {
            System.err.println("stagewright: " + e);
            System.exit(3);
        }
    }

    private static int run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage(System.out);
            return 0;
        }

        Args.Parsed parsed = Args.parse(args);
        Map<String, String> opts = parsed.opts();
        List<String> systemProps = parsed.systemProps();
        List<Path> extraMods = parsed.extraMods();

        // Judged from files alone, so it runs no game and needs no --game-dir. It is a separate
        // invocation rather than a step of a run because the runs it reconciles are separate
        // invocations: a dedicated server and a headless client are two processes that cannot see
        // each other, and the question "did anything ever execute this scene" only has an answer
        // once both have finished.
        if (opts.containsKey("coverage")) return coverage(opts.get("coverage"));

        // Read now as well as at judging time, so a manifest the engine refuses stops the run before
        // it has spent a game boot and a whole suite on a verdict it could never give.
        expected(opts);

        Path gameDir = Path.of(require(opts, "game-dir")).toAbsolutePath().normalize();
        if (!Files.isDirectory(gameDir)) {
            throw new IllegalArgumentException("--game-dir " + gameDir + " is not a directory");
        }
        Path results = gameDir.resolve(resultsName(opts));
        Path scenes = opts.containsKey("scenes")
                ? Path.of(opts.get("scenes")).toAbsolutePath().normalize() : null;
        int timeoutMinutes = Integer.parseInt(opts.getOrDefault("timeout",
                String.valueOf(DEFAULT_TIMEOUT_MINUTES)));

        Consumer<String> log = line -> System.out.println("[stagewright] " + line);
        RunDirectory.provision(gameDir, List.of(results), !"false".equals(opts.get("clean-world")),
                scenes, log);

        // Resolved once, before anything needs it. Detection is right for a server pack, which
        // arrives with its loader already unpacked — but a client game dir on its first run holds
        // nothing at all, and the loader that will be installed into it is only knowable from
        // --loader. Asking twice let the install step answer null while the launch step, three
        // lines later, knew the answer perfectly well.
        String loader = opts.containsKey("loader") ? opts.get("loader") : GameLaunch.loader(gameDir);
        if (!opts.containsKey("no-install")) {
            ModInstall.install(gameDir, loader, extraMods, log);
        }

        if (opts.containsKey("with-client")) {
            return runServerWithClient(gameDir, results, opts, systemProps, extraMods,
                    timeoutMinutes, loader, log);
        }
        if (opts.containsKey("headlessmc") || opts.containsKey("display-client")) {
            return runClient(gameDir, results, opts, systemProps, timeoutMinutes, loader, log);
        }

        if (opts.containsKey("attached")) {
            return runAttached(gameDir, results, opts, systemProps, timeoutMinutes, loader, log);
        }

        Path runLog = gameDir.resolve("stagewright-run.log");
        CrashWatch crashes = CrashWatch.on(gameDir);
        Process game = startServer(gameDir, opts, systemProps, List.of(), runLog, log);

        // The results file, not the process, says when the run is over. Those used to be the same
        // statement — a dedicated server halts itself when the suite drains — and they stop being
        // one on any pack whose mods leave non-daemon threads behind: the suite finishes, the footer
        // lands, the harness halts, and the JVM stays in the process table anyway. Waiting on the
        // process there spends the whole --timeout on a verdict that has been sitting complete on
        // disk since minute three.
        Waited waited = awaitDoneFooter(results, timeoutMinutes, game, crashes);
        if (waited == Waited.DONE) drain(game, runLog);
        if (waited == Waited.CRASHED) {
            System.err.println("[stagewright] the server crashed before the suite finished — "
                    + crashes.describe() + ", then " + runLog);
        } else if (waited == Waited.TIMED_OUT) {
            System.err.println("[stagewright] the run exceeded " + timeoutMinutes
                    + " minutes and was killed — see " + runLog);
        }
        kill(game);
        // Deliberately NOT an early return on any of them: a killed or crashed run usually still
        // wrote records, and the missing done footer is what turns them into a RED that names how
        // far it got. Judging is strictly more informative than reporting the ending alone.
        return judge(gameDir, results, opts, runLog, crashes);
    }

    /**
     * The production topology: the pack's dedicated server, with a real client joined to it.
     *
     * <p>The other two topologies are one JVM. This one is the only shape a player ever actually
     * plays, and the only one where a mod's client half and server half have to agree over a wire —
     * so it is where desync, a packet a mod forgot to register, and anything guarded by
     * {@code isClientSide} show up. A pack that is green on the other two and red here is not an
     * unlucky pack; it is a pack whose players would have hit this.
     *
     * <p>The server is the half that matters. It runs the scenes, it writes the results, and it
     * halts itself when they drain — so waiting for it IS waiting for the run, exactly as in the
     * plain server topology. The client's whole job is to be logged in while that happens, which is
     * what {@code -Dstagewright.awaitPlayer} makes load-bearing: without a player the server sits
     * armed and starts nothing, so a client that never arrives fails as a timeout rather than as a
     * suite that quietly proved nothing.
     *
     * <p>Neither half is trusted to bring itself down. Both do — the server halts, and the client
     * closes when the server drops it — but a run that ends with an orphaned headless Minecraft
     * holding a port is a broken CI box for everybody afterwards, so both are killed in a finally.
     */
    private static int runServerWithClient(Path gameDir, Path results, Map<String, String> opts,
                                           List<String> systemProps, List<Path> extraMods,
                                           int timeoutMinutes, String loader, Consumer<String> log)
            throws IOException, InterruptedException {
        Path clientDir = Path.of(require(opts, "with-client")).toAbsolutePath().normalize();
        if (clientDir.equals(gameDir)) {
            throw new IllegalArgumentException("--with-client names the same directory as --game-dir"
                    + " — the two halves run at the same time, so they cannot share a world, a log"
                    + " or a results file");
        }
        Path hmcJar = headlessMcJar(opts);
        String javaBinary = javaBinary(opts);
        if (loader == null) {
            throw new IllegalArgumentException("nothing in " + gameDir + " says which loader this"
                    + " pack runs on, and the client half has to be built for the same one — pass"
                    + " --loader neoforge|fabric");
        }

        // The client half gets what the server half got, minus the scenes. It never arms a harness,
        // so a scene file there is dead weight at best; at worst it is a second suite nobody asked
        // for, writing over the results this run is going to be judged on.
        RunDirectory.provision(clientDir, List.of(clientDir.resolve(results.getFileName())),
                !"false".equals(opts.get("clean-world")), null, log);
        if (!opts.containsKey("no-install")) {
            ModInstall.install(clientDir, loader, extraMods, log);
        }

        String address = "127.0.0.1:" + serverPort(gameDir);
        // The joining client stays offline: it only has to be a second process on the wire, and an
        // account would make this topology need one on every box that runs the gate.
        HeadlessClient.writeConfig(clientDir, clientDir,
                List.of("-Dstagewright.client.connect=" + address), true, log);
        // Before the server starts, not after: a cold client directory downloads Minecraft here, and
        // minutes of that with a server already up would be minutes of the run's own timeout spent
        // on a server idling for a player that is still being installed.
        List<String> launcherJvm = launcherJvm(opts, log);
        String versionId = HeadlessClient.installLoader(hmcJar, clientDir, clientDir, javaBinary,
                loader, require(opts, "mc-version"), launcherJvm, log);

        Path runLog = gameDir.resolve("stagewright-run.log");
        CrashWatch crashes = CrashWatch.on(gameDir);
        Process server = startServer(gameDir, opts, systemProps,
                List.of("-Dstagewright.awaitPlayer=true"), runLog, log);
        Process client = null;
        try {
            log.accept("client half joining " + address);
            client = HeadlessClient.launch(hmcJar, clientDir, javaBinary, versionId, null,
                    launcherJvm, log);
            // The server half is the one that writes the results, so its footer ends the wait here
            // exactly as it does in the plain topology — and for the same reason: a pack that cannot
            // close its own JVM must not cost the whole --timeout after it has finished.
            Waited waited = awaitDoneFooter(results, timeoutMinutes, server, crashes);
            if (waited == Waited.DONE) drain(server, runLog);
            if (waited == Waited.CRASHED) {
                System.err.println("[stagewright] the server crashed before the suite finished — "
                        + crashes.describe() + ", then " + runLog);
            } else if (waited == Waited.TIMED_OUT) {
                System.err.println("[stagewright] the run exceeded " + timeoutMinutes
                        + " minutes and was killed — see " + runLog + " for the server and "
                        + clientDir.resolve("logs/latest.log") + " for the client. A server that"
                        + " logged 'deferring the suite until a player joins' and nothing after it"
                        + " never got its player.");
            }
        } finally {
            if (client != null) kill(client);
            kill(server);
        }

        return judge(gameDir, results, opts, runLog, crashes);
    }

    private static Path headlessMcJar(Map<String, String> opts) {
        Path jar = Path.of(require(opts, "headlessmc")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("--headlessmc " + jar + " is not a file — download"
                    + " headlessmc-launcher-<version>.jar from"
                    + " https://github.com/headlesshq/headlessmc/releases and point this at it");
        }
        return jar;
    }

    private static String javaBinary(Map<String, String> opts) {
        return opts.getOrDefault("java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    /**
     * Stop a half of a run, and everything it started.
     *
     * <p>The descendants are the point. HeadlessMC runs the game as a CHILD process and outlives it,
     * so killing the launcher alone leaves a headless Minecraft still holding the port — and the
     * next run on that box then fails to bind, which reads as a bug in the pack rather than as
     * yesterday's run never having ended. They are collected before the parent dies, because once it
     * does the tree is no longer walkable from here.
     */
    private static void kill(Process p) throws InterruptedException {
        if (!p.isAlive()) return;
        List<ProcessHandle> descendants = p.descendants().toList();
        p.destroy();
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) child.destroyForcibly();
        }
        p.waitFor(20, TimeUnit.SECONDS);
    }

    /**
     * The port the pack's server will listen on.
     *
     * <p>Read from {@code server.properties} rather than assumed, because a pack that moved off
     * 25565 would otherwise produce a client dialling a port nothing answers — which looks from the
     * outside exactly like the server failing to start.
     */
    private static int serverPort(Path gameDir) throws IOException {
        Path properties = gameDir.resolve("server.properties");
        if (Files.isRegularFile(properties)) {
            for (String line : Files.readAllLines(properties, java.nio.charset.StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("server-port=")) {
                    String value = trimmed.substring("server-port=".length()).trim();
                    if (!value.isEmpty()) return Integer.parseInt(value);
                }
            }
        }
        return 25565;
    }

    /** Start the pack's server, with any topology-specific properties added to the author's. */
    private static Process startServer(Path gameDir, Map<String, String> opts,
                                       List<String> systemProps, List<String> topologyProps,
                                       Path runLog, Consumer<String> log) throws IOException {
        List<String> props = new ArrayList<>(topologyProps);
        props.addAll(systemProps);
        List<String> command = buildCommand(gameDir, opts, props, log);
        System.out.println("[stagewright] " + String.join(" ", command));
        return new ProcessBuilder(command)
                .directory(gameDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(runLog.toFile()))
                .start();
    }

    /**
     * The integrated-server topology: a real Minecraft client, headless, in a world of its own.
     *
     * <p>A client joining someone else's server is deliberately NOT a mode here. It runs fine —
     * {@code ClientDirector} takes {@code stagewright.client.connect} and the pair works — but the
     * scenes then run on that server and write their results there, so this process could look at
     * its own game directory forever and only ever report ENV. Running both halves is
     * {@code --with-client}, and it is the only shape of that topology this CLI can hand back a
     * verdict for. This exit code always means the run it could see.
     *
     * <p>Waiting differs from the server path and cannot be borrowed from it. A dedicated server
     * halts itself when the suite drains, so waiting for the process IS waiting for the run. A
     * client sitting in a world does not necessarily end — and HeadlessMC outlives it either way,
     * holding a command prompt open on the stdin we deliberately never closed. So the signal is the
     * results file's own done footer, which is the same thing the verdict reads and the only
     * statement the run makes about being finished.
     */
    private static int runClient(Path gameDir, Path results, Map<String, String> opts,
                                 List<String> systemProps, int timeoutMinutes, String loader,
                                 Consumer<String> log)
            throws IOException, InterruptedException {
        String javaBinary = javaBinary(opts);
        List<String> props = new ArrayList<>(systemProps);
        props.add("-Dstagewright.autorun=true");
        props.add("-Dstagewright.client.world=" + opts.getOrDefault("world", "stagewright"));
        props.addAll(resultsProps(resultsName(opts)));

        // The launcher's own output. A --display-client run never goes through HeadlessMC, and a
        // pointer at "headlessmc.log" for it read as the wrong file; the name says which launch it was.
        Path hmcLog = gameDir.resolve(opts.containsKey("display-client")
                ? "stagewright-client-launch.log" : "stagewright-headlessmc.log");
        // Snapshotted before the launch, so only THIS run's report can be reported as this run's.
        CrashWatch crashes = CrashWatch.on(gameDir);
        Process hmc;
        if (opts.containsKey("display-client")) {
            // Launch the installed client ourselves, on the real display. HeadlessMC's own launch is
            // always -lwjgl (its offline account forces it), and a modpack's resource reload does not
            // survive that — see ClientLaunch. Installing is still its job; this only takes over the
            // launch, reading the version JSON it wrote.
            List<String> command = ClientLaunch.command(gameDir, opts.get("display-client"),
                    javaBinary, props);
            log.accept("launching the installed client " + opts.get("display-client")
                    + " on the real display");
            hmc = new ProcessBuilder(command)
                    .directory(gameDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(hmcLog.toFile())
                    .start();
        } else {
            Path hmcJar = headlessMcJar(opts);
            if (loader == null) {
                throw new IllegalArgumentException("nothing in " + gameDir + " says which loader to"
                        + " install — pass --loader neoforge|fabric (a fresh client game dir has no"
                        + " loader in it yet, which is normal for a first run)");
            }
            // "" means "whatever account HeadlessMC has selected"; null means run offline.
            String account = opts.containsKey("account") ? opts.get("account")
                    : opts.containsKey("online") ? "" : null;
            HeadlessClient.writeConfig(gameDir, gameDir, props, account == null, log);
            List<String> launcherJvm = launcherJvm(opts, log);
            String versionId = HeadlessClient.installLoader(hmcJar, gameDir, gameDir, javaBinary,
                    loader, require(opts, "mc-version"), launcherJvm, log);
            hmc = HeadlessClient.launch(hmcJar, gameDir, javaBinary, versionId, account,
                    launcherJvm, log);
        }

        // Killed rather than drained, unlike the server topologies. HeadlessMC deliberately outlives
        // the game it launched — it is holding a command prompt open on the stdin this never closes
        // — so waiting for it to leave is waiting for something that never happens.
        Waited waited = awaitDoneFooter(results, timeoutMinutes, hmc, crashes);
        kill(hmc);
        if (waited == Waited.TIMED_OUT) {
            System.err.println("[stagewright] the client run did not finish within " + timeoutMinutes
                    + " minutes and was still alive — see the launcher log " + hmcLog + " and "
                    + gameDir.resolve("logs/latest.log"));
        } else if (waited == Waited.CRASHED || waited == Waited.PROCESS_DIED) {
            System.err.println("[stagewright] the client crashed before the suite finished. This is"
                    + " not a slow run: " + crashes.describe() + ", then the launcher log " + hmcLog + ".");
            // A 450-mod pack found this within three minutes, so it is named rather than left to be
            // rediscovered: -lwjgl stubs every LWJGL call, so a mod that reads actual pixels while
            // loading (Supplementaries reading a palette strip through Moonlight, for one) sees an
            // all-zero image and throws. Nothing on our side can fix that — it is the price of a
            // client with no display, and the choice is to drop the mod from the client topology or
            // to run the client somewhere with a real GL context.
            //
            // Said only when it can be the answer. A crash report names the mod and the exception
            // outright, so this becomes one more thing to rule out after the reader already has the
            // truth; and --display-client runs on a real GL context, where the stub was never in
            // play and this paragraph is about a mechanism that does not exist in that run.
            if (crashes.fresh() == null && !opts.containsKey("display-client")) {
                System.err.println("[stagewright] under -lwjgl every LWJGL call is a stub, so a mod"
                        + " that reads image pixels during load crashes on an all-zero image. No"
                        + " crash report was written, which is what that failure usually looks"
                        + " like: mod loading goes down before the game can produce one.");
            }
        }
        return judge(gameDir, results, opts, hmcLog, crashes);
    }

    /** How a wait for the run's done footer ended. The four are not interchangeable. */
    private enum Waited { DONE, CRASHED, PROCESS_DIED, TIMED_OUT }

    /**
     * Wait for the suite to write its done footer, or for the game to stop being able to.
     *
     * <p>Polled rather than watched: the file is appended to by another process on another
     * filesystem path, and a second of latency on a run measured in minutes buys nothing worth the
     * complication of a watch service.
     *
     * <p><b>Which of the failures happened is reported, because they send you to different
     * places.</b> This returned a bare boolean once, and the caller printed "did not finish within 90
     * minutes" for a client that had crashed in three — a confident, well-phrased sentence pointing
     * at a budget that was never the problem, and sending the reader to look for a slow run instead
     * of a crash report sitting right there.
     *
     * <p>The crash report is checked as well as the process, because a crashed game frequently does
     * not exit. A modpack leaves non-daemon threads behind — a pool of {@code AwsEventLoop} threads
     * was enough — so the JVM that has already written its crash report and closed its window stays
     * in the process table forever, and waiting on {@code isAlive} spends the whole {@code --timeout}
     * on a game that stopped being one 45 minutes earlier.
     */
    private static Waited awaitDoneFooter(Path results, int timeoutMinutes, Process game,
                                          CrashWatch crashes)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(results)) {
                for (String line : Files.readAllLines(results, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (line.contains("\"type\":\"done\"")) return Waited.DONE;
                }
            }
            // Both checked AFTER the file, not before: a run that writes its footer and dies in the
            // same second would otherwise be reported as having died with nothing to show.
            if (crashes.fresh() != null) return Waited.CRASHED;
            if (!game.isAlive()) return Waited.PROCESS_DIED;
            Thread.sleep(1000);
        }
        return Waited.TIMED_OUT;
    }

    /**
     * Give a finished game its chance to close itself, then say why it is being killed.
     *
     * <p>Only ever called once the results file carries its done footer, which is what makes killing
     * safe: the verdict is already complete on disk and the process holds nothing else this run
     * wants. Without it a suite that has finished still costs the whole {@code --timeout}, because
     * the JVM the harness could not halt is indistinguishable from a suite still running.
     */
    private static void drain(Process game, Path runLog) throws InterruptedException {
        if (!game.isAlive()) return;
        if (game.waitFor(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS)) return;
        System.err.println("[stagewright] the suite finished and its results are complete, but the"
                + " game JVM was still up " + DRAIN_GRACE_SECONDS + "s later — a mod is holding"
                + " non-daemon threads and the harness's own halt could not bring the VM down."
                + " Killing it; the verdict below is read from the finished file. See " + runLog);
    }

    /**
     * The launch command, with our system properties inserted directly after the java binary.
     *
     * <p>Position matters: everything after an {@code @argfile} belongs to the launcher, and a
     * {@code -D} placed there is passed to Minecraft as a program argument, where it is ignored
     * silently. The run then completes normally and writes no results, which reads as "the mod is
     * missing" rather than "the property did not apply".
     */
    private static List<String> buildCommand(Path gameDir, Map<String, String> opts,
                                             List<String> systemProps, Consumer<String> log) {
        String javaBinary = opts.getOrDefault("java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString());

        List<String> base;
        if (opts.containsKey("launch")) {
            base = new ArrayList<>(List.of(opts.get("launch").split("\\s+")));
        } else {
            base = GameLaunch.detect(gameDir, javaBinary, log);
            if (base == null) {
                throw new IllegalArgumentException("cannot tell how to start the server in " + gameDir
                        + " — no NeoForge/Forge argument files under libraries/ and no server jar at"
                        + " the top level. Pass --launch \"<command>\" to say it explicitly.");
            }
        }

        List<String> command = new ArrayList<>();
        command.add(base.get(0));
        command.addAll(armingProps(opts, gameDir));
        command.addAll(systemProps);
        command.addAll(base.subList(1, base.size()));
        return command;
    }

    /**
     * How this run arms the harness: autorun, or hold — and where it writes.
     *
     * <p>The two arming modes are mutually exclusive and the reason is not a policy choice. An
     * autorun suite calls {@code server.halt(false)} the moment its scenes drain — which takes the
     * endpoint down underneath anything attached to it, mid-call. So a run with out-of-process
     * scenes must hold: the server arms, publishes its endpoint descriptor, and waits to be told to
     * run.
     */
    private static List<String> armingProps(Map<String, String> opts, Path gameDir) {
        List<String> props = new ArrayList<>(resultsProps(resultsName(opts)));
        if (!opts.containsKey("attached")) {
            props.add("-Dstagewright.autorun=true");
        } else {
            props.add("-Dstagewright.hold=true");
            props.add("-Dstagewright.endpoint="
                    + gameDir.resolve(RunDirectory.ENDPOINT_FILE).toAbsolutePath());
        }
        return props;
    }

    /** The results file this run is judged on, relative to the game directory. */
    static String resultsName(Map<String, String> opts) {
        String named = opts.getOrDefault("results", RunDirectory.DEFAULT_RESULTS_FILE).trim();
        if (named.isEmpty()) throw new IllegalArgumentException("--results needs a file name");
        return named;
    }

    /**
     * Tell the game which file to WRITE — the half of {@code --results} that was missing.
     *
     * <p>The flag used to rename only what this CLI opened. The harness went on writing
     * {@code stagewright-results.jsonl}, because nothing carried the name across the process
     * boundary, so a renamed run finished green and was judged "ENV — the run wrote no results"
     * against a path nothing was ever going to write. That verdict is the one a pack also gets when
     * the framework jar failed to load, which is where it sent every reader.
     *
     * <p>Passed on every run, including the default one, so the game's own command line records the
     * name a reader is going to go looking for.
     */
    static List<String> resultsProps(String resultsName) {
        return List.of("-D" + RunDirectory.RESULTS_PROPERTY + "=" + resultsName);
    }

    /**
     * The out-of-process topology: hold the server open and drive it from this JVM.
     *
     * <p>The order is the whole design and it is not interchangeable. Attached scenes run FIRST,
     * before {@code mc.test.run}, because the in-process suite pins the world for its own duration
     * ({@code WorldPin.applySuite} … {@code release}) — an attached scene sandwiched inside that
     * would be asserting against a world that is being changed around it, and the failure would
     * point at the scene rather than at the ordering.
     *
     * <p>Then the in-process suite is triggered and this JVM waits for the done footer, exactly as
     * the plain topology waits for the process to exit. And then the server is KILLED rather than
     * asked to leave: a hold has no self-terminating path (its whole contract is outliving its
     * suite), and unlike the Gradle chain — where nobody owns the process and a hidden
     * {@code mc.test.end} verb would be needed — this CLI started it and owns the process tree.
     * Killing what you launched is not a workaround here; it is the same shutdown path a timeout
     * already uses, and it cannot leave an orphan holding the port.
     */
    private static int runAttached(Path gameDir, Path results, Map<String, String> opts,
                                   List<String> systemProps, int timeoutMinutes, String loader,
                                   Consumer<String> log)
            throws IOException, InterruptedException {
        Path attachedDir = Path.of(opts.get("attached")).toAbsolutePath().normalize();
        Path attachedResults = gameDir.resolve("stagewright-attached-results.jsonl");
        Path endpoint = gameDir.resolve(RunDirectory.ENDPOINT_FILE);
        Path runLog = gameDir.resolve("stagewright-run.log");
        Files.deleteIfExists(attachedResults);

        CrashWatch crashes = CrashWatch.on(gameDir);
        Process game = startServer(gameDir, opts, systemProps, List.of(), runLog, log);
        int attachedCode;
        try {
            EndpointDescriptor descriptor = awaitEndpoint(endpoint, game, runLog, timeoutMinutes);
            if (descriptor == null) {
                System.err.println("[stagewright] ENV — the held server never published "
                        + endpoint + ", so there was nothing to attach to. See " + runLog);
                return 3;
            }
            System.out.println("[stagewright] attached to " + descriptor.wsUri()
                    + " (topology " + descriptor.topology() + ", loader " + descriptor.loader() + ")");

            try (StageWrightRpc rpc = attach(descriptor.wsUri())) {
                DriverBinding binding = new RpcDriverBinding(rpc, 60_000);
                List<SceneSpec> specs = Scripts.load(attachedDir,
                        // No extra globals: out here `driver` is the ONLY door to the game, and it is
                        // installed as a plain Java object below rather than as a script-visible
                        // reflection surface. A second door would be a verb one home has.
                        (cx, scope, fileName) -> { },
                        line -> System.out.println("[stagewright] " + line));
                AttachedRun run = new AttachedRun(specs, binding,
                        descriptor.loader() != null ? descriptor.loader() : loader,
                        line -> System.out.println("[stagewright] " + line),
                        System::currentTimeMillis);
                attachedCode = run.run(attachedResults) ? 0 : 1;

                // Now the in-process half, on the same live server.
                System.out.println("[stagewright] triggering the in-process suite (mc.test.run)");
                binding.route("mc.test.run", java.util.Map.of());
                Waited waited = awaitDoneFooter(results, timeoutMinutes, game, crashes);
                if (waited == Waited.TIMED_OUT) {
                    System.err.println("[stagewright] the in-process suite did not finish within "
                            + timeoutMinutes + " minutes and the server was still alive — see " + runLog);
                } else if (waited == Waited.CRASHED) {
                    System.err.println("[stagewright] the server crashed during the in-process suite"
                            + " — " + crashes.describe() + ", then " + runLog);
                } else if (waited == Waited.PROCESS_DIED) {
                    System.err.println("[stagewright] the server exited before the in-process suite"
                            + " finished — see " + runLog);
                }
            }
        } finally {
            game.destroyForcibly();
            game.waitFor(30, TimeUnit.SECONDS);
        }

        int inProcess = judge(gameDir, results, opts, runLog, crashes);
        // Worst-wins across the two files, the same rule the companion client already gets:
        // GREEN 0 < RED 1 < DEAD 2 < ENV 3. Reporting only the in-process verdict would let a red
        // attached half ride home on a green suite.
        int worst = Math.max(inProcess, attachedCode);
        System.out.println("[stagewright] ATTACHED VERDICT: " + (attachedCode == 0 ? "GREEN" : "RED")
                + "  (" + attachedResults + ")");
        System.out.println("[stagewright] VERDICT: " + (worst == 0 ? "GREEN" : "RED"));
        return worst;
    }

    /**
     * Connect to the driver's socket, retrying until it answers.
     *
     * <p>The descriptor appearing means the port is KNOWN, not that the socket is ready to complete a
     * handshake. The two are the same instant on a small pack and minutes apart on a large one: on
     * All the Mods 10 the first attempt timed out after thirty seconds while the server was still
     * working through 450 mods' worth of start-up, and a single-shot connect turned "the pack is
     * slow" into "the attach contract is broken".
     *
     * <p>Retried rather than given a longer single timeout because the two failures need different
     * answers. A refused connection means nothing is listening yet — try again shortly. A handshake
     * that hangs means something IS listening and is too busy to answer, which is also worth trying
     * again. Neither is worth a five-minute stall inside one attempt.
     */
    private static StageWrightRpc attach(String wsUri) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ATTACH_BUDGET_MS;
        RuntimeException last = null;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                return StageWrightRpc.connect(wsUri, ATTACH_ATTEMPT_MS);
            } catch (RuntimeException e) {
                last = e;
                System.out.println("[stagewright] attach attempt " + attempt + " did not take ("
                        + e.getMessage() + ") — the pack is probably still starting; retrying");
                Thread.sleep(5_000);
            }
        }
        throw last != null ? last : new IllegalStateException("could not attach to " + wsUri);
    }

    /** Total time to keep trying to attach, and how long any one attempt may hang. */
    private static final long ATTACH_BUDGET_MS = 5 * 60_000L;
    private static final long ATTACH_ATTEMPT_MS = 20_000L;

    /**
     * The string the game logs when it was asked for an endpoint and cannot produce one.
     *
     * <p>Log-scraping, which is usually a bad idea — but this is OUR line, emitted by OUR mod, and it
     * is the difference between failing in thirty seconds with the cause and failing in an hour with
     * "never published". The alternative was waiting out {@code --timeout}: on the first real
     * {@code --attached} run against a pack with no driver, the game printed the exact diagnosis at
     * second thirty and the CLI would have sat there for the remaining fifty-nine and a half minutes
     * before reporting something vaguer.
     */
    private static final String NO_DRIVER_MARKER = "-Dstagewright.endpoint asked for an endpoint";

    /**
     * Poll for the held server's endpoint descriptor.
     *
     * <p>Gives up early on either of the two things that mean it will never arrive: the process died,
     * or the game said it cannot publish one. Everything else is just a slow pack booting.
     */
    private static EndpointDescriptor awaitEndpoint(Path endpoint, Process game, Path runLog,
                                                    int timeoutMinutes)
            throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(endpoint)) {
                EndpointDescriptor d = EndpointDescriptor.read(endpoint);
                if (d != null) return d;
            }
            if (!game.isAlive()) return null;
            if (saidItCannotPublish(runLog)) {
                System.err.println("[stagewright] ENV — this pack has no driver, so there is no RPC"
                        + " socket to attach to and --attached cannot run here. The game said so"
                        + " itself: it armed, held, and then reported that worlddriver-rpc.port does"
                        + " not exist.");
                System.err.println("  --scenes needs no driver (in-process scenes use StageWright's"
                        + " own SceneContext); --attached does, because out of process the driver's"
                        + " RPC surface is the ONLY way into the game.");
                System.err.println("  Add one with --mod <worlddriver jar>, or drop --attached.");
                return null;
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private static boolean saidItCannotPublish(Path runLog) {
        if (!Files.isRegularFile(runLog)) return false;
        try (java.util.stream.Stream<String> lines =
                     Files.lines(runLog, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            return lines.anyMatch(l -> l.contains(NO_DRIVER_MARKER));
        } catch (IOException e) {
            return false;   // the log is being written to; try again next second
        }
    }

    private static int judge(Path gameDir, Path results, Map<String, String> opts, Path log,
                             CrashWatch crashes) throws IOException {
        if (!Files.isRegularFile(results)) {
            System.err.println("stagewright: ENV — the run wrote no results");
            System.err.println("  expected: " + results);
            noResultsCause(gameDir, results, crashes.fresh(),
                    ModInstall.frameworkPresent(gameDir), log).forEach(System.err::println);
            return 3;
        }

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> records = Verdict.parse(results, warnings);
        warnings.forEach(w -> System.out.println("[stagewright] " + w));

        Verdict.Result verdict = Verdict.judge(records, expected(opts));
        verdict.report().forEach(line -> System.out.println("[stagewright] " + line));
        System.out.println("[stagewright] VERDICT: " + verdict.label());
        if (verdict.code() != 0) System.out.println("[stagewright] log: " + log);
        return verdict.code();
    }

    /** The {@code --expect} manifest's names, or null when the run is not reconciled against one. */
    static List<String> expected(Map<String, String> opts) {
        return opts.containsKey("expect") ? Manifest.read(Path.of(opts.get("expect"))) : null;
    }

    /**
     * Why the run wrote no results: ONE explanation, chosen — not a menu.
     *
     * <p><b>A crash report ends the question.</b> It is the game's own account of what happened, it
     * names the mod and the exception, and every other line this method can produce is a guess about
     * a run whose cause is already sitting on disk. Printing the guesses anyway is not harmless:
     * measured on a 262-mod pack, the CLI correctly reported the crash and named the report, and
     * then followed it with three paragraphs — the framework jar may have failed to load, an
     * {@code -lwjgl} stub may have handed a mod an all-zero image, you renamed the results file —
     * none of which had anything to do with the mod that actually threw. The last thing a reader
     * sees is the thing they act on, so the true cause has to be the last thing said, which here
     * means being the only thing said.
     *
     * <p>The guesses are still right when there is no report. A game that never got far enough to
     * write one leaves nothing else to go on, and which of them applies IS decidable from the run
     * directory rather than left to the reader.
     */
    static List<String> noResultsCause(Path gameDir, Path results, Path crashReport,
                                       boolean frameworkPresent, Path log) {
        if (crashReport != null) {
            return List.of("  The game crashed before it could write them, and said why itself: "
                    + crashReport + ". Read that; nothing else here is more than a guess.");
        }

        List<String> out = new ArrayList<>();
        if (!frameworkPresent) {
            out.add("  There is no StageWright jar in " + gameDir.resolve("mods")
                    + ", so nothing in this pack could have armed. Drop --no-install to let"
                    + " this CLI install it.");
        } else {
            out.add("  A StageWright jar IS in this pack's mods folder, so it either"
                    + " failed to load or the server never reached its first tick — " + log
                    + " says which. A jar built for the other loader looks like this too.");
        }
        // Named separately because it is the one cause that produces this verdict over a run that
        // went perfectly: the game writes wherever -Dstagewright.results tells it to, and a
        // framework jar older than that property ignores it and writes the default name.
        if (!RunDirectory.DEFAULT_RESULTS_FILE.equals(results.getFileName().toString())) {
            out.add("  This run renamed the results file. The game is told the new"
                    + " name with -D" + RunDirectory.RESULTS_PROPERTY + "; a StageWright jar"
                    + " older than that property ignores it and writes "
                    + gameDir.resolve(RunDirectory.DEFAULT_RESULTS_FILE)
                    + " — check whether that file is sitting there.");
        }
        return out;
    }

    /**
     * Reconcile several finished runs against each other and report what nothing ever executed.
     *
     * <p>A missing input is ENV (3) rather than RED (1), on the same rule the rest of this CLI holds
     * to: "the run whose results I was told to read did not happen" must not be reported as "your
     * scenes are broken". It also cannot be quietly tolerated — dropping an unreadable file would
     * narrow the union of executed scenes and turn a missing run into extra UNCOVERED findings
     * against runs that were fine.
     */
    private static int coverage(String spec) throws IOException {
        List<net.magicterra.stagewright.engine.Coverage.Run> runs = new ArrayList<>();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            Path file = Path.of(trimmed).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                System.err.println("stagewright: --coverage names " + file + ", which does not exist."
                        + " Every run being reconciled must have finished and written its results.");
                return 3;
            }
            List<String> warnings = new ArrayList<>();
            List<Map<String, Object>> records = Verdict.parse(file, warnings);
            warnings.forEach(w -> System.out.println("[stagewright] " + w));
            // The whole path from wherever this was invoked, not the last two segments: two loaders'
            // run directories are named identically by design, so a short label prints
            // 'run-dogfood/stagewright-results.jsonl' twice and the report cannot say which of them
            // is missing the scene.
            Path here = Path.of("").toAbsolutePath();
            String label = file.startsWith(here)
                    ? here.relativize(file).toString().replace(File.separatorChar, '/')
                    : file.toString();
            runs.add(new net.magicterra.stagewright.engine.Coverage.Run(label, records));
        }
        net.magicterra.stagewright.engine.Coverage.Result result =
                net.magicterra.stagewright.engine.Coverage.judge(runs);
        result.report().forEach(line -> System.out.println("[stagewright] " + line));
        System.out.println("[stagewright] COVERAGE VERDICT: " + (result.code() == 0 ? "GREEN" : "RED"));
        return result.code();
    }

    /**
     * JVM arguments for the HeadlessMC process itself — not for the game.
     *
     * <p>It exists for one reason so far, and the reason is worth stating: <b>Java does not read
     * {@code HTTPS_PROXY}.</b> On a box where every other tool works through a proxy set in the
     * environment, the launcher alone goes direct, and what comes back eight minutes later is
     * "Failed to login with device code: HTTP connect timed out" — a message about Microsoft that is
     * really a message about this JVM. So an environment proxy with no {@code -D} to match is
     * warned about by name rather than left to be rediscovered.
     */
    private static List<String> launcherJvm(Map<String, String> opts, Consumer<String> log) {
        List<String> out = new ArrayList<>();
        if (opts.containsKey("launcher-jvm")) {
            for (String arg : opts.get("launcher-jvm").split("\s+")) {
                if (!arg.isBlank()) out.add(arg);
            }
        }
        boolean declared = out.stream().anyMatch(a -> a.startsWith("-Dhttps.proxy")
                || a.startsWith("-Dhttp.proxy") || a.contains("useSystemProxies"));
        String env = System.getenv("HTTPS_PROXY");
        if (env == null) env = System.getenv("https_proxy");
        if (!declared && env != null && !env.isBlank()) {
            log.accept("WARNING: HTTPS_PROXY=" + env + " is set, and Java ignores it. HeadlessMC will"
                    + " connect directly and time out on Microsoft's endpoints. Pass"
                    + " --launcher-jvm \"-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>\".");
        }
        return out;
    }

    private static String require(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null) throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }

    private static void usage(java.io.PrintStream out) {
        out.println("""
                stagewright — run a modpack's scenes and judge them

                  java -jar stagewright.jar --game-dir <dir> [options]

                  --game-dir <dir>    the pack's server directory (holds mods/ and config/)
                  --scenes <dir>      .js scene files to install into config/stagewright/scenes
                  --attached <dir>    .js scene files run OUT of process, from this JVM over RPC.
                                      Needs a driver in the pack (worlddriver provides the RPC
                                      socket); --scenes does not, because in-process scenes talk to
                                      StageWright's own SceneContext. Switches the run to a HOLD:
                                      an autorun suite halts the server when it drains, which would
                                      take the socket down underneath the attached half mid-call.
                                      Attached scenes run first, then the in-process suite; both
                                      results are judged, worst wins.
                  --coverage <files>  judge nothing but coverage: comma-separated results files from
                                      runs that have already finished, RED if any scene they register
                                      executed in none of them. Runs no game and takes no --game-dir.
                                      A scene skipping is fine in one run and a hole across all of
                                      them, which is a question no single run can be asked.
                  --expect <file>     expected-scenes manifest to reconcile against
                  --results <name>    results file name, relative to --game-dir (default
                                      stagewright-results.jsonl). Passed into the game as
                                      -Dstagewright.results, so the run writes the name this CLI
                                      then judges — a StageWright older than that property ignores
                                      it and keeps writing the default.
                  --timeout <min>     kill the run after this long (default 45). A ceiling, not a
                                      duration: the run ends when the results file carries its done
                                      footer, whether or not the game's JVM manages to exit.
                  --clean-world false keep the existing world (default: delete it; true or false only)
                  --mod <jar>         also install this mod (repeatable — e.g. the driver whose
                                      verbs your scenes call)
                  --no-install        do not touch mods/; the pack already has what it needs
                  --headlessmc <jar>  run a CLIENT topology through this headlessmc-launcher jar.
                                      It installs the loader and the assets itself. Without an
                                      account it launches -lwjgl, which stubs every LWJGL call:
                                      fine for a mod, NOT fine for a modpack, where a mod that reads
                                      image pixels while loading crashes on the all-zero image and
                                      takes mod loading down with it.
                  --online            launch with the account you logged into HeadlessMC with,
                                      instead of an offline one. This is also what gets you a
                                      RENDERING client: offline forces the LWJGL stub, so a real
                                      account is the only way that launcher produces a real GL
                                      context. Log in yourself, once, in the game dir:
                                        java -jar headlessmc-launcher.jar --command
                                        > login <your-email>      (or: login -webview)
                                        > account                 (lists them; account <id> picks)
                  --account <id>      as --online, but make this account primary first
                  --launcher-jvm "<args>"
                                      JVM args for the HeadlessMC process itself, not the game.
                                      Java ignores HTTPS_PROXY, so behind a proxy this is how the
                                      launcher reaches Microsoft:
                                        --launcher-jvm "-Dhttps.proxyHost=10.0.0.1 -Dhttps.proxyPort=7873"
                  --display-client <version>
                                      the other way to a rendering client: launch what is ALREADY
                                      installed in --game-dir under this version id, ourselves, with
                                      no launcher and no account. Needs a display — Xvfb on a
                                      headless box.
                  --mc-version <ver>  Minecraft version to install a loader for (with --headlessmc)
                  --loader <name>     neoforge|fabric to install; detected when the dir already says
                  --world <name>      singleplayer world the client creates (default stagewright)
                  --with-client <dir> also run a client, in this dir, joined to the pack's server
                  --launch "<cmd>"    start the server this way instead of detecting it
                  --java <path>       java executable to launch with
                  -D<key>=<value>     extra system properties for the game

                exit: 0 GREEN / 1 RED / 2 DEAD (the framework is broken, results void)
                      3 ENV (the game never armed)""");
    }

    private Main() {}
}
