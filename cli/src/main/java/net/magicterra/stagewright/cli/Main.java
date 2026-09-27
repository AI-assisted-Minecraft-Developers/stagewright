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

import net.magicterra.stagewright.cli.install.ClientSpec;
import net.magicterra.stagewright.cli.install.Downloader;
import net.magicterra.stagewright.cli.install.Installer;
import net.magicterra.stagewright.cli.install.MinecraftDir;
import net.magicterra.stagewright.cli.install.Mirror;
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
 * <p>Nothing it does to the game directory outlives the run except what the run is about: the game
 * directory may be a player's own {@code .minecraft}, so mods are handed to the loader rather than
 * copied into {@code mods/}, scenes are read from where they are, and everything the run writes goes
 * under {@code stagewright/}.
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

    /** The server's console, and a client's, under the run's artifact directory. */
    private static final String RUN_LOG = "stagewright-run.log";
    private static final String CLIENT_LOG = "stagewright-client-launch.log";

    /** What {@code --results} names when it is not given: the harness's own default, as a file name. */
    static final String DEFAULT_RESULTS_NAME =
            Path.of(RunDirectory.DEFAULT_RESULTS_FILE).getFileName().toString();

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (EnvFailure e) {
            System.err.println("stagewright: ENV — " + e.getMessage());
            System.exit(3);
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

    /** Everything a topology needs from the command line, read once. */
    private record Run(Map<String, String> opts, List<String> systemProps, List<Path> extraMods,
                       Path gameDir, Path results, int timeoutMinutes, int stallMinutes,
                       Consumer<String> log) {

        Path artifacts() {
            return gameDir.resolve(RunDirectory.ARTIFACT_DIR);
        }

        Path progress() {
            return results.resolveSibling(RunDirectory.PROGRESS_FILE);
        }
    }

    static int run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage(System.out);
            return 0;
        }
        if ("--version".equals(args[0])) {
            BuildInfo.describe().forEach(System.out::println);
            return 0;
        }

        Args.Parsed parsed = Args.parse(args);
        Map<String, String> opts = parsed.opts();

        // Judged from files alone, so it runs no game and needs no --game-dir. It is a separate
        // invocation rather than a step of a run because the runs it reconciles are separate
        // invocations: a dedicated server and a client are two processes that cannot see each
        // other, and the question "did anything ever execute this scene" only has an answer once
        // both have finished.
        if (opts.containsKey("coverage")) return coverage(opts.get("coverage"));

        // Read now as well as at judging time, so a manifest the engine refuses stops the run before
        // it has spent a game boot and a whole suite on a verdict it could never give.
        expected(opts);
        // Before the world reset and the install: a refusal leaves the game directory as it was.
        requireScenesDir(opts);
        int timeoutMinutes = minutes(opts, "timeout", DEFAULT_TIMEOUT_MINUTES);
        int stallMinutes = minutes(opts, "stall-timeout", Stall.DEFAULT_MINUTES);
        if (opts.containsKey("no-install") && !parsed.extraMods().isEmpty()) {
            throw new IllegalArgumentException("--mod and --no-install together: --no-install loads"
                    + " nothing, so " + parsed.extraMods().get(0).getFileName() + " would not load either");
        }
        for (Path mod : parsed.extraMods()) {
            if (!Files.isRegularFile(mod)) {
                throw new IllegalArgumentException("--mod " + mod + " is not a file");
            }
        }

        ClientSpec client = opts.containsKey("client") ? ClientSpec.parse(opts.get("client")) : null;
        Worlds.ServerWorld serverWorld = Worlds.parse(opts.get("world"));
        Path gameDir = Path.of(require(opts, "game-dir")).toAbsolutePath().normalize();
        boolean clientOnly = client != null && !opts.containsKey("with-client");
        if (clientOnly) {
            // A client's game directory may be new; the install it runs from is elsewhere.
            Files.createDirectories(gameDir);
            if (serverWorld != null) {
                throw new IllegalArgumentException("--world is for a dedicated server's world; a client"
                        + " gets a new world of its own every run");
            }
        } else if (!Files.isDirectory(gameDir)) {
            throw new IllegalArgumentException("--game-dir " + gameDir + " is not a directory");
        }

        Run run = new Run(opts, parsed.systemProps(), parsed.extraMods(), gameDir,
                gameDir.resolve(RunDirectory.ARTIFACT_DIR).resolve(resultsName(opts)),
                timeoutMinutes, stallMinutes,
                line -> System.out.println("[stagewright] " + line));
        BuildInfo.describe().forEach(run.log());

        if (opts.containsKey("with-client")) {
            if (client == null) {
                throw new IllegalArgumentException("--with-client needs --client to say which client to"
                        + " install and run, e.g. --client neoforge:1.21.1:21.1.248");
            }
            return runServerWithClient(run, client, serverWorld);
        }
        if (client != null) return runClient(run, client);
        return runServer(run, serverWorld);
    }

    /** The dedicated-server topology, and with {@code --attached} its out-of-process variant. */
    private static int runServer(Run run, Worlds.ServerWorld serverWorld)
            throws IOException, InterruptedException {
        // First: a refusal must leave the directory exactly as it found it.
        List<String> launch = serverLaunch(run);
        String loader = GameLaunch.loader(run.gameDir());
        if (!run.opts().containsKey("no-install")) ModInjection.requireLoader(loader);
        Worlds.prepareServer(run.gameDir(), serverWorld, run.log());
        RunDirectory.provision(run.gameDir(), List.of(run.results()), false, true, run.log());
        ModInjection.Arguments mods = inject(run, run.gameDir(), loader);
        if (run.opts().containsKey("attached")) return runAttached(run, launch, mods);

        Path runLog = run.artifacts().resolve(RUN_LOG);
        CrashWatch crashes = CrashWatch.on(run.gameDir());
        long started = System.currentTimeMillis();
        Process game = startServer(run, launch, List.of(), mods, runLog);

        // The results file, not the process, says when the run is over. Those used to be the same
        // statement — a dedicated server halts itself when the suite drains — and they stop being
        // one on any pack whose mods leave non-daemon threads behind: the suite finishes, the footer
        // lands, the harness halts, and the JVM stays in the process table anyway. Waiting on the
        // process there spends the whole --timeout on a verdict that has been sitting complete on
        // disk since minute three.
        Waited waited = awaitDoneFooter(run, game, crashes,
                new Stall(List.of(runLog, run.results(), run.progress()), run.stallMinutes()));
        if (waited == Waited.DONE) drain(game, runLog);
        explain(waited, run, "server", game, crashes, runLog, run.gameDir(), started);
        kill(game);
        // Deliberately NOT an early return on any of them: a killed or crashed run usually still
        // wrote records, and the missing done footer is what turns them into a RED that names how
        // far it got. Judging is strictly more informative than reporting the ending alone.
        return judge(run, runLog, crashes, waited).code();
    }

    /**
     * The integrated-server topology: a real Minecraft client, on the real display, in a world of
     * its own.
     *
     * <p>A client joining someone else's server is deliberately NOT a mode here. It runs fine —
     * {@code ClientDirector} takes {@code stagewright.client.connect} and the pair works — but the
     * scenes then run on that server and write their results there, so this process could look at
     * its own game directory forever and only ever report ENV. Running both halves is
     * {@code --with-client}, and it is the only shape of that topology this CLI can hand back a
     * verdict for.
     *
     * <p>The world is new every run and is removed after a green one: every other save in the game
     * directory may be a player's.
     */
    private static int runClient(Run run, ClientSpec client) throws IOException, InterruptedException {
        // Before the install: a vanilla client is refused without a download.
        String loader = requireModLoader(run, client);
        ClientPreflight.check(run.gameDir(), run.log());
        Path installDir = installDir(run.opts());
        String javaBinary = javaBinary(run.opts());
        String versionId = install(client, installDir, run, javaBinary);

        RunDirectory.provision(run.gameDir(), List.of(run.results()), false, false, run.log());
        ModInjection.Arguments mods = inject(run, run.gameDir(), loader);
        String world = Worlds.newClientWorld();
        List<String> props = new ArrayList<>(run.systemProps());
        props.add("-Dstagewright.autorun=true");
        props.add("-Dstagewright.client.world=" + world);
        props.addAll(runProps(run));
        props.addAll(mods.jvm());
        List<String> command = ClientLaunch.command(installDir, run.gameDir(), versionId, javaBinary,
                props, mods.game(), username(run.opts()));

        Path launchLog = run.artifacts().resolve(CLIENT_LOG);
        CrashWatch crashes = CrashWatch.on(run.gameDir());
        long started = System.currentTimeMillis();
        run.log().accept("launching " + versionId + " from " + installDir + " in " + run.gameDir()
                + ", world " + world);
        Process game = start(command, run.gameDir(), launchLog);

        Waited waited = awaitDoneFooter(run, game, crashes, new Stall(List.of(launchLog,
                run.gameDir().resolve("logs/latest.log"), run.results(), run.progress()),
                run.stallMinutes()));
        // Drained like a server: the director closes the client once the suite is over, and the
        // world has to be saved and closed before it can be removed.
        if (waited == Waited.DONE) drain(game, launchLog);
        explain(waited, run, "client", game, crashes, launchLog, run.gameDir(), started);
        kill(game);
        Verdict.Result verdict = judge(run, launchLog, crashes, waited);
        Worlds.finishClient(run.gameDir(), world, verdict.code() == 0, run.log());
        return verdict.code();
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
     * <p>The server runs the scenes, writes the results, and halts itself when they drain — so
     * waiting for it IS waiting for the run, exactly as in the plain server topology. The client has
     * to be logged in while that happens, which is what {@code -Dstagewright.awaitPlayer} makes
     * load-bearing: without a player the server sits armed and starts nothing, so a client that never
     * arrives fails as a timeout rather than as a suite that quietly proved nothing. It also runs
     * its own probe and writes its own results, judged beside the server's — see
     * {@link #judgeWithClient}.
     *
     * <p>Neither half is trusted to bring itself down. Both do — the server halts, and the client
     * closes when the server drops it — but a run that ends with an orphaned Minecraft holding a port
     * is a broken CI box for everybody afterwards, so both are killed in a finally.
     */
    private static int runServerWithClient(Run run, ClientSpec client, Worlds.ServerWorld serverWorld)
            throws IOException, InterruptedException {
        Path clientDir = Path.of(require(run.opts(), "with-client")).toAbsolutePath().normalize();
        if (clientDir.equals(run.gameDir())) {
            throw new IllegalArgumentException("--with-client names the same directory as --game-dir"
                    + " — the two halves run at the same time, so they cannot share a world, a log"
                    + " or a results file");
        }
        // First: a refusal must leave both directories as it found them, and cost no install.
        List<String> launch = serverLaunch(run);
        String loader = GameLaunch.loader(run.gameDir());
        if (loader != null && !run.opts().containsKey("no-install")) ModInjection.requireLoader(loader);
        String clientLoader = requireModLoader(run, client);
        if (loader != null && clientLoader != null && !loader.equals(clientLoader)) {
            throw new IllegalArgumentException("the pack's server runs " + loader + " and --client asks"
                    + " for " + clientLoader + " — both halves must run the same loader");
        }
        if (loader == null) loader = clientLoader;
        ClientPreflight.check(clientDir, run.log());
        Worlds.prepareServer(run.gameDir(), serverWorld, run.log());
        Files.createDirectories(clientDir);

        // Before the server starts, not after: a cold install downloads Minecraft here, and minutes of
        // that with a server already up would be minutes of the run's own timeout spent on a server
        // idling for a player that is still being installed.
        Path installDir = installDir(run.opts());
        String javaBinary = javaBinary(run.opts());
        String versionId = install(client, installDir, run, javaBinary);

        RunDirectory.provision(run.gameDir(), List.of(run.results()), false, true, run.log());
        ModInjection.Arguments serverMods = inject(run, run.gameDir(), loader);
        // The client half gets what the server half got, minus the scenes: a loader that finds a
        // different mod list on each end of a connection refuses it.
        RunDirectory.provision(clientDir, clientStaleResults(clientDir), false, false, run.log());
        ModInjection.Arguments clientMods = inject(run, clientDir, loader);

        String address = "127.0.0.1:" + serverPort(run.gameDir());
        List<String> clientProps = companionProps(run.systemProps(), address, clientMods.jvm());
        List<String> clientCommand = ClientLaunch.command(installDir, clientDir, versionId, javaBinary,
                clientProps, clientMods.game(), username(run.opts()));

        Path runLog = run.artifacts().resolve(RUN_LOG);
        Path clientLog = clientDir.resolve(RunDirectory.ARTIFACT_DIR).resolve(CLIENT_LOG);
        CrashWatch crashes = CrashWatch.on(run.gameDir());
        long started = System.currentTimeMillis();
        Process server = startServer(run, launch, List.of("-Dstagewright.awaitPlayer=true"), serverMods,
                runLog);
        Process clientProcess = null;
        Waited waited = null;
        try {
            run.log().accept("client half joining " + address);
            clientProcess = start(clientCommand, clientDir, clientLog);
            // The server half is the one that writes the results, so its footer ends the wait here
            // exactly as it does in the plain topology — and for the same reason: a pack that cannot
            // close its own JVM must not cost the whole --timeout after it has finished.
            waited = awaitDoneFooter(run, server, crashes, new Stall(List.of(runLog,
                    clientLog, run.results(), run.progress()), run.stallMinutes()));
            if (waited == Waited.DONE) drain(server, runLog);
            explain(waited, run, "server", server, crashes, runLog, run.gameDir(), started);
            String hint = companionHint(waited, clientLog);
            if (hint != null) System.err.println(hint);
        } finally {
            if (clientProcess != null) kill(clientProcess);
            kill(server);
        }

        return judgeWithClient(judge(run, runLog, crashes, waited), clientDir, System.out::println);
    }

    /**
     * Where to look when the pair ended without a footer, or null when it did not. A server waiting for
     * its player writes nothing, so a client that never arrives is usually ended by the stall watchdog.
     */
    static String companionHint(Waited waited, Path clientLog) {
        if (waited != Waited.TIMED_OUT && waited != Waited.STALLED) return null;
        return "[stagewright] the client half's log is " + clientLog + ". A server that logged"
                + " 'deferring the suite until a player joins' and nothing after it never got its player.";
    }

    /** What provisioning must clear in the client half's directory: the probe results it writes
     *  there, and with them the heartbeat beside them. */
    static List<Path> clientStaleResults(Path clientDir) {
        return List.of(clientDir.resolve(RunDirectory.CLIENT_RESULTS_FILE));
    }

    /**
     * The run's code once the client half's own probe results are judged beside the server's.
     *
     * <p>By the engine's companion rule, the one the plugin applies to {@code companionResultsFile},
     * so the same pair of files cannot be GREEN here and RED under Gradle. Worst wins.
     */
    static int judgeWithClient(Verdict.Result server, Path clientDir, Consumer<String> out)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        Verdict.Result client = Verdict.judgeCompanion(
                clientDir.resolve(RunDirectory.CLIENT_RESULTS_FILE), warnings);
        warnings.forEach(w -> out.accept("[stagewright] client: " + w));
        client.report().forEach(line -> out.accept("[stagewright] client: " + line));
        out.accept("[stagewright] CLIENT VERDICT: " + client.label());
        Verdict.Result worst = Verdict.worst(server, client);
        // Keeps the server's FILTERED suffix whichever half is worse: the run was narrowed either way.
        out.accept("[stagewright] VERDICT (server and client): "
                + new Verdict.Result(worst.code(), List.of(), server.filtered()).label()
                + (worst == client && client.code() > server.code() ? "  (from the client half)" : ""));
        return worst.code();
    }

    private static Path installDir(Map<String, String> opts) {
        return opts.containsKey("install-dir")
                ? Path.of(opts.get("install-dir")).toAbsolutePath().normalize()
                : MinecraftDir.defaultDir();
    }

    private static String install(ClientSpec client, Path installDir, Run run, String javaBinary)
            throws IOException, InterruptedException {
        Mirror mirror = Mirror.parse(run.opts().getOrDefault("mirror", "none"));
        try (Downloader downloader = new Downloader(mirror, installDir)) {
            return new Installer(installDir, downloader, javaBinary, run.log()).install(client);
        }
    }

    /** The joining client's JVM properties: the user's too, since it is the half with a window. */
    static List<String> companionProps(List<String> systemProps, String address, List<String> modJvm) {
        List<String> props = new ArrayList<>(systemProps);
        props.add("-Dstagewright.client.connect=" + address);
        props.addAll(modJvm);
        return props;
    }

    private static String requireModLoader(Run run, ClientSpec client) {
        if (client.modLoader() == null && !run.opts().containsKey("no-install")) {
            throw new IllegalArgumentException("StageWright is a neoforge or fabric mod, so a vanilla"
                    + " client cannot run scenes — name a loader in --client");
        }
        return client.modLoader();
    }

    private static ModInjection.Arguments inject(Run run, Path dir, String loader) {
        if (run.opts().containsKey("no-install")) return ModInjection.Arguments.NONE;
        return ModInjection.prepare(dir, loader, run.extraMods(), run.log());
    }

    private static String username(Map<String, String> opts) {
        return opts.getOrDefault("username", ClientLaunch.DEFAULT_USERNAME);
    }

    private static String javaBinary(Map<String, String> opts) {
        return opts.getOrDefault("java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static Process start(List<String> command, Path dir, Path log) throws IOException {
        Files.createDirectories(log.getParent());
        System.out.println("[stagewright] " + String.join(" ", command));
        return new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();
    }

    /**
     * Stop a half of a run, and everything it started.
     *
     * <p>The descendants are collected before the parent dies, because once it does the tree is no
     * longer walkable from here — and a child left behind holds the port, so the next run on that box
     * fails to bind and reads as a bug in the pack rather than as yesterday's run never having ended.
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
    private static Process startServer(Run run, List<String> launch, List<String> topologyProps,
                                       ModInjection.Arguments mods,
                                       Path runLog) throws IOException {
        List<String> props = new ArrayList<>(topologyProps);
        props.addAll(run.systemProps());
        props.addAll(mods.jvm());
        List<String> command = buildCommand(run, launch, props, mods.game());
        return start(command, run.gameDir(), runLog);
    }

    /** How a wait for the run's done footer ended. None of them is interchangeable with another. */
    enum Waited { DONE, CRASHED, PROCESS_DIED, STALLED, TIMED_OUT }

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
    private static Waited awaitDoneFooter(Run run, Process game, CrashWatch crashes, Stall stall)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        long limit = TimeUnit.MINUTES.toNanos(run.timeoutMinutes());
        while (!expired(start, limit, System.nanoTime())) {
            if (Files.isRegularFile(run.results())) {
                for (String line : Files.readAllLines(run.results(), java.nio.charset.StandardCharsets.UTF_8)) {
                    if (line.contains("\"type\":\"done\"")) return Waited.DONE;
                }
            }
            // All checked AFTER the file, not before: a run that writes its footer and dies in the
            // same second would otherwise be reported as having died with nothing to show.
            if (crashes.fresh() != null) return Waited.CRASHED;
            if (!game.isAlive()) return Waited.PROCESS_DIED;
            if (stall.stalled()) return Waited.STALLED;
            Thread.sleep(1000);
        }
        return Waited.TIMED_OUT;
    }

    /** Compared as a difference: a start plus a huge {@code --timeout} overflows to the past. */
    static boolean expired(long startNanos, long limitNanos, long nowNanos) {
        return nowNanos - startNanos >= limitNanos;
    }

    /** Say how a run that did not finish ended, and — for a failed boot — what went wrong first. */
    private static void explain(Waited waited, Run run, String half, Process game, CrashWatch crashes,
                                Path log, Path gameDir, long started) {
        switch (waited) {
            case DONE -> { return; }
            case CRASHED, PROCESS_DIED -> System.err.println("[stagewright] the " + half
                    + " stopped before the suite finished. This is not a slow run: " + crashes.describe()
                    + ", then " + log + ".");
            case STALLED -> System.err.println("[stagewright] the " + half + " made no progress for "
                    + run.stallMinutes() + (run.stallMinutes() == 1 ? " minute" : " minutes")
                    + " (" + log + " stopped growing) and was killed: "
                    + Stall.describe(game.toHandle()) + ".");
            case TIMED_OUT -> System.err.println("[stagewright] the run exceeded " + run.timeoutMinutes()
                    + " minutes and was killed — see " + log);
        }
        FirstCause.find(gameDir, started).forEach(System.err::println);
    }

    /**
     * Give a finished game its chance to close itself, then say why it is being killed.
     *
     * <p>Only ever called once the results file carries its done footer, which is what makes killing
     * safe: the verdict is already complete on disk and the process holds nothing else this run
     * wants. Without it a suite that has finished still costs the whole {@code --timeout}, because
     * the JVM the harness could not halt is indistinguishable from a suite still running.
     */
    private static void drain(Process game, Path log) throws InterruptedException {
        if (!game.isAlive()) return;
        if (game.waitFor(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS)) return;
        System.err.println("[stagewright] the suite finished and its results are complete, but the"
                + " game JVM was still up " + DRAIN_GRACE_SECONDS + "s later — a mod is holding"
                + " non-daemon threads and the harness's own halt could not bring the VM down."
                + " Killing it; the verdict below is read from the finished file. See " + log);
    }

    /**
     * The server's launch command: our JVM arguments after the java binary and the pack's own
     * {@code @user_jvm_args.txt}, and the loader's program arguments at the very end.
     *
     * <p>Position matters: everything after the loader's {@code @argfile} belongs to the launcher, and
     * a {@code -D} placed there is passed to Minecraft as a program argument, where it is ignored
     * silently. The run then completes normally and writes no results, which reads as "the mod is
     * missing" rather than "the property did not apply". See {@link #serverCommand} for the other
     * side.
     */
    private static List<String> buildCommand(Run run, List<String> base, List<String> systemProps,
                                             List<String> gameArgs) {
        List<String> ours = new ArrayList<>(armingProps(run));
        ours.addAll(systemProps);
        return serverCommand(base, ours, gameArgs);
    }

    /**
     * {@code base} with our JVM arguments after the java binary — and after the pack's own
     * {@code @user_jvm_args.txt} when it comes next, since the JVM keeps the last {@code -Xmx} or
     * {@code -D} it is given and the pack's would otherwise silently undo ours.
     */
    static List<String> serverCommand(List<String> base, List<String> jvmArgs, List<String> gameArgs) {
        int at = base.size() > 1 && base.get(1).startsWith("@") && base.get(1).endsWith("user_jvm_args.txt")
                ? 2 : 1;
        List<String> command = new ArrayList<>(base.subList(0, at));
        command.addAll(jvmArgs);
        command.addAll(base.subList(at, base.size()));
        command.addAll(gameArgs);
        return command;
    }

    /** How to start the pack's server: {@code --launch}, or what its directory shows. */
    private static List<String> serverLaunch(Run run) {
        if (run.opts().containsKey("launch")) {
            return new ArrayList<>(List.of(run.opts().get("launch").split("\\s+")));
        }
        List<String> base = GameLaunch.detect(run.gameDir(), javaBinary(run.opts()), run.log());
        if (base == null) {
            throw new IllegalArgumentException("cannot tell how to start the server in "
                    + run.gameDir() + " — no NeoForge/Forge argument files under libraries/ and no"
                    + " server jar at the top level. Pass --launch \"<command>\" to say it explicitly.");
        }
        return base;
    }

    /**
     * How this run arms the harness: autorun, or hold — and where it reads and writes.
     *
     * <p>The two arming modes are mutually exclusive and the reason is not a policy choice. An
     * autorun suite calls {@code server.halt(false)} the moment its scenes drain — which takes the
     * endpoint down underneath anything attached to it, mid-call. So a run with out-of-process
     * scenes must hold: the server arms, publishes its endpoint descriptor, and waits to be told to
     * run.
     */
    private static List<String> armingProps(Run run) {
        List<String> props = new ArrayList<>(runProps(run));
        if (!run.opts().containsKey("attached")) {
            props.add("-Dstagewright.autorun=true");
        } else {
            props.add("-Dstagewright.hold=true");
            props.add("-Dstagewright.endpoint="
                    + run.gameDir().resolve(RunDirectory.ENDPOINT_FILE).toAbsolutePath());
        }
        return props;
    }

    /** Where this run's results go and where its scenes come from, for the game's command line. */
    private static List<String> runProps(Run run) {
        List<String> props = new ArrayList<>(resultsProps(resultsName(run.opts())));
        Path scenes = scenesPath(run.opts());
        if (scenes != null) props.add("-D" + RunDirectory.SCENES_DIR_PROPERTY + "=" + scenes);
        return props;
    }

    /** A whole number of minutes above zero: at zero, the run is over before the game has loaded. */
    static int minutes(Map<String, String> opts, String key, int fallback) {
        String value = opts.get(key);
        if (value == null) return fallback;
        int minutes;
        try {
            minutes = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " takes a whole number of minutes, not " + value);
        }
        if (minutes < 1) {
            throw new IllegalArgumentException("--" + key + " must be at least 1 minute, not " + value);
        }
        return minutes;
    }

    /** Refuses a {@code --scenes} that is not a directory holding a scene. */
    static void requireScenesDir(Map<String, String> opts) {
        Path scenes = scenesPath(opts);
        if (scenes == null) return;
        if (!Files.isDirectory(scenes)) {
            // Loud, because the failure it prevents is silent: no directory means no scenes
            // loaded, and a suite that runs none of them still reports GREEN.
            throw new IllegalArgumentException("--scenes " + scenes + " is not a directory");
        }
        if (!holdsASceneFile(scenes)) {
            // The same silence as a missing directory: the suite runs without the scenes it was
            // pointed at and reports GREEN.
            throw new IllegalArgumentException("--scenes " + scenes + " holds no .js scene file");
        }
    }

    /**
     * The {@code --scenes} directory as the game is told it and as the verdict compares it, unchecked:
     * {@link #requireScenesDir} checked it before the run, and the game refuses one gone by the time
     * it assembles the suite.
     */
    static Path scenesPath(Map<String, String> opts) {
        return opts.containsKey("scenes") ? Path.of(opts.get("scenes")).toAbsolutePath().normalize() : null;
    }

    static boolean holdsASceneFile(Path dir) {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".js"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot list --scenes " + dir, e);
        }
    }

    /** The results file this run is judged on, as a name inside the run's artifact directory. */
    static String resultsName(Map<String, String> opts) {
        String named = opts.getOrDefault("results", DEFAULT_RESULTS_NAME).trim();
        if (named.isEmpty()) throw new IllegalArgumentException("--results needs a file name");
        if (named.contains("/") || named.contains(File.separator)) {
            throw new IllegalArgumentException("--results takes a file name, which goes under "
                    + RunDirectory.ARTIFACT_DIR + "/ — not '" + named + "'");
        }
        return named;
    }

    /**
     * Tell the game which file to WRITE — the half of {@code --results} that was missing.
     *
     * <p>Renaming only what this CLI opens is not enough: nothing else carries the name across the
     * process boundary, so the harness would write the default name, and a renamed run that finished
     * green would be judged "ENV — the run wrote no results" against a path nothing was going to
     * write. That verdict is the one a pack also gets when the framework jar failed to load, which is
     * where it would send every reader.
     *
     * <p>Passed on every run, including the default one, so the game's own command line records the
     * name a reader is going to go looking for.
     */
    static List<String> resultsProps(String resultsName) {
        return List.of("-D" + RunDirectory.RESULTS_PROPERTY + "=" + RunDirectory.ARTIFACT_DIR + "/"
                + resultsName);
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
    private static int runAttached(Run run, List<String> launch, ModInjection.Arguments mods)
            throws IOException, InterruptedException {
        Path attachedDir = Path.of(run.opts().get("attached")).toAbsolutePath().normalize();
        Path attachedResults = run.artifacts().resolve("stagewright-attached-results.jsonl");
        Path endpoint = run.gameDir().resolve(RunDirectory.ENDPOINT_FILE);
        Path runLog = run.artifacts().resolve(RUN_LOG);
        Files.deleteIfExists(attachedResults);

        CrashWatch crashes = CrashWatch.on(run.gameDir());
        long started = System.currentTimeMillis();
        Process game = startServer(run, launch, List.of(), mods, runLog);
        int attachedCode;
        Waited waited;
        try {
            EndpointDescriptor descriptor = awaitEndpoint(endpoint, game, runLog, run.timeoutMinutes());
            if (descriptor == null) {
                System.err.println("[stagewright] ENV — the held server never published "
                        + endpoint + ", so there was nothing to attach to. See " + runLog);
                return 3;
            }
            System.out.println("[stagewright] attached to " + descriptor.wsUri()
                    + " (topology " + descriptor.topology() + ", loader " + descriptor.loader() + ")");

            try (StageWrightRpc rpc = attach(descriptor.wsUri())) {
                DriverBinding binding = new RpcDriverBinding(rpc, 60_000);
                attachedCode = runAttachedScenes(attachedDir, binding,
                        descriptor.loader() != null ? descriptor.loader() : GameLaunch.loader(run.gameDir()),
                        attachedResults, line -> System.out.println("[stagewright] " + line));

                // Now the in-process half, on the same live server.
                System.out.println("[stagewright] triggering the in-process suite (mc.test.run)");
                binding.route("mc.test.run", java.util.Map.of());
                waited = awaitDoneFooter(run, game, crashes,
                        new Stall(List.of(runLog, run.results(), run.progress()), run.stallMinutes()));
                explain(waited, run, "server", game, crashes, runLog, run.gameDir(), started);
            }
        } finally {
            game.destroyForcibly();
            game.waitFor(30, TimeUnit.SECONDS);
        }

        int inProcess = judge(run, runLog, crashes, waited).code();
        // Worst-wins across the two files, the same rule the companion client already gets:
        // GREEN 0 < RED 1 < DEAD 2 < ENV 3. Reporting only the in-process verdict would let a red
        // attached half ride home on a green suite.
        int worst = Math.max(inProcess, attachedCode);
        attachedSummary(inProcess, attachedCode, attachedResults).forEach(System.out::println);
        return worst;
    }

    /**
     * Load and run the attached half's scene files; 0 when nothing failed, 1 otherwise.
     *
     * <p>A file that does not load is RED: it is the author's defect, and letting the exception
     * reach {@code main} would exit 3 — the code that says the host never came up.
     */
    static int runAttachedScenes(Path attachedDir, DriverBinding binding, String loader,
                                 Path attachedResults, Consumer<String> log) {
        List<SceneSpec> specs;
        try {
            specs = Scripts.load(attachedDir,
                    // No extra globals: out here `driver` is the ONLY door to the game, and it is
                    // installed as a plain Java object below rather than as a script-visible
                    // reflection surface. A second door would be a verb one home has.
                    (cx, scope, fileName) -> { },
                    log);
        } catch (RuntimeException e) {
            log.accept("RED: the attached scenes could not be loaded, so none of them ran — "
                    + e.getMessage());
            return 1;
        }
        AttachedRun run = new AttachedRun(specs, binding, loader, log, System::currentTimeMillis);
        return run.run(attachedResults) ? 0 : 1;
    }

    /**
     * The closing lines of an {@code --attached} run: the attached half's verdict, then the run's.
     * Labelled by code, never collapsed to GREEN/RED: a DEAD or ENV half must not end the output
     * reading as a code defect.
     */
    static List<String> attachedSummary(int inProcess, int attachedCode, Path attachedResults) {
        int worst = Math.max(inProcess, attachedCode);
        return List.of(
                "[stagewright] ATTACHED VERDICT: " + Verdict.LABELS[attachedCode]
                        + "  (" + attachedResults + ")",
                "[stagewright] VERDICT (in-process and attached): " + Verdict.LABELS[worst]);
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

    private static Verdict.Result judge(Run run, Path log, CrashWatch crashes, Waited waited)
            throws IOException {
        if (!Files.isRegularFile(run.results())) {
            System.err.println("stagewright: ENV — the run wrote no results");
            System.err.println("  expected: " + run.results());
            // A stall has already been explained, with the state the process was in. The guesses
            // below would follow it with "it never loaded" about a game that loaded and then waited.
            if (waited == Waited.STALLED) return new Verdict.Result(3, List.of("the run wrote no results"));
            boolean injected = !run.opts().containsKey("no-install");
            noResultsCause(run.gameDir(), crashes.fresh(), injected,
                    injected || net.magicterra.stagewright.engine.ModInstall.frameworkPresent(run.gameDir()),
                    log).forEach(System.err::println);
            return new Verdict.Result(3, List.of("the run wrote no results"));
        }

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> records = Verdict.parse(run.results(), warnings);
        warnings.forEach(w -> System.out.println("[stagewright] " + w));

        Verdict.Result verdict = verdictOf(records, run.opts());
        verdict.report().forEach(line -> System.out.println("[stagewright] " + line));
        System.out.println("[stagewright] VERDICT: " + verdict.label());
        if (verdict.code() != 0) System.out.println("[stagewright] log: " + log);
        return verdict;
    }

    /** The verdict over a run's records, ENV first when they show {@code --scenes} went unread. */
    static Verdict.Result verdictOf(List<Map<String, Object>> records, Map<String, String> opts) {
        String unread = scenesNotRead(records, scenesPath(opts));
        if (unread != null) return new Verdict.Result(3, List.of(unread));
        return Verdict.judge(records, expected(opts));
    }

    /**
     * Why the run shows it did not read {@code --scenes}, or null when it did or none was given.
     *
     * <p>A StageWright older than {@code stagewright.scenesDir} ignores it and runs without those
     * scenes, and its verdict over the rest is GREEN. Its header names no scenes directory; one that
     * read the property names it.
     */
    static String scenesNotRead(List<Map<String, Object>> records, Path scenes) {
        if (scenes == null) return null;
        for (Map<String, Object> r : records) {
            if (!"suite".equals(r.get("type"))) continue;
            Object read = r.get("scenesDir");
            if (read != null && Path.of(read.toString()).equals(scenes)) return null;
            return "the StageWright that ran did not read --scenes " + scenes
                    + (read == null ? ": it is older than " + RunDirectory.SCENES_DIR_PROPERTY
                                    : ": it read " + read + " instead")
                    + ", so none of those scenes ran. With --no-install the pack's own StageWright is"
                    + " the one that runs; update it, or drop --no-install.";
        }
        return null;
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
     * then followed it with three paragraphs of guesses, none of which had anything to do with the
     * mod that actually threw. The last thing a reader sees is the thing they act on, so the true
     * cause has to be the last thing said, which here means being the only thing said.
     *
     * <p>The guesses are still right when there is no report. A game that never got far enough to
     * write one leaves nothing else to go on, and which of them applies IS decidable from the run
     * rather than left to the reader.
     *
     * @param injected         this CLI handed StageWright to the loader itself
     * @param frameworkPresent StageWright was handed over, or sits in the pack's own mods/
     */
    static List<String> noResultsCause(Path gameDir, Path crashReport, boolean injected,
                                       boolean frameworkPresent, Path log) {
        if (crashReport != null) {
            return List.of("  The game crashed before it could write them, and said why itself: "
                    + crashReport + ". Read that; nothing else here is more than a guess.");
        }

        List<String> out = new ArrayList<>();
        if (!frameworkPresent) {
            out.add("  --no-install was given and there is no StageWright jar in "
                    + gameDir.resolve("mods") + ", so nothing in this pack could have armed. Drop"
                    + " --no-install to let this CLI load it.");
        } else if (injected) {
            out.add("  This CLI handed StageWright to the loader, so it either failed to load or the"
                    + " game never reached its first tick — " + log + " says which. A pack on the"
                    + " other loader looks like this too.");
        } else {
            out.add("  A StageWright jar IS in this pack's mods folder, so it either failed to load"
                    + " or the game never reached its first tick — " + log + " says which. A jar built"
                    + " for the other loader looks like this too.");
        }
        // Named separately because it is the one cause that produces this verdict over a run that
        // went perfectly: the game writes wherever -Dstagewright.results tells it to, and only the
        // pack's own jar can be older than that property, which then writes the old default name.
        if (!injected) {
            out.add("  The game is told where to write with -D" + RunDirectory.RESULTS_PROPERTY
                    + "; a StageWright jar older than that property ignores it and writes "
                    + gameDir.resolve(Path.of(RunDirectory.DEFAULT_RESULTS_FILE).getFileName())
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
        System.out.println("[stagewright] COVERAGE VERDICT: " + Verdict.LABELS[result.code()]);
        return result.code();
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
                  java -jar stagewright.jar --version   what this jar was built from

                Three topologies, picked by what you pass:
                  (neither --client nor --with-client)   the pack's dedicated server in --game-dir
                  --client <spec>                        a client in --game-dir, in a world of its own
                  --client <spec> --with-client <dir>    the server in --game-dir, a client in <dir>
                                                         joined to it

                The run's own files land under <game-dir>/stagewright/. A client's game directory
                is otherwise left as it was, so --game-dir may be your own .minecraft; a server's
                also gets its EULA and forced server.properties keys, and --world decides its world.
                Mods are handed to the loader, never copied into mods/.

                  --game-dir <dir>    the pack's server directory, or the client's game directory
                  --scenes <dir>      .js scenes and .json capability descriptors, read from here
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
                                      executed in none of them, ENV if any of them was a filtered
                                      run, none of them registered a scene, or their headers name
                                      different builds. This runner's own runs record no build;
                                      the Gradle plugin's do, in a git work tree. Runs no game and
                                      takes no --game-dir.
                                      Only the files given are reconciled: list each run's
                                      stagewright-attached-results.jsonl and client results too.
                  --expect <file>     expected-scenes manifest to reconcile against
                  --results <name>    results file name, under <game-dir>/stagewright/ (default
                                      stagewright-results.jsonl). Passed into the game as
                                      -Dstagewright.results, so the run writes the name this CLI
                                      then judges.
                  --timeout <min>     kill the run after this long (default 45, at least 1). A
                                      ceiling, not a duration: the run ends when the results file
                                      carries its done footer, whether or not the game's JVM manages
                                      to exit.
                  --stall-timeout <min>
                                      end the run once nothing it writes has grown for this long
                                      (default 5, at least 1), and say whether the game was spinning
                                      or asleep. ENV if it wrote no results; else judged on them, so
                                      RED with no done footer
                  --world reset|keep  what to do with a dedicated server's existing world. Required
                                      when one exists: reset deletes it, keep runs in it as it is
                  --mod <jar>         also load this mod (repeatable — e.g. the driver whose verbs your
                                      scenes call). Skipped if the pack's mods/ already has that file
                  --no-install        load nothing; the pack already has StageWright in its mods/.
                                      Not with --mod
                  --launch "<cmd>"    start the server this way instead of detecting it
                  --java <path>       java executable to launch with
                  -D<key>=<value>, -X<option>
                                      passed to the game's JVM as given (e.g. -Xmx8G,
                                      -Dorg.lwjgl.glfw.libname=<so>); with --with-client, to both
                                      halves

                Client:
                  --client <spec>     neoforge:<mc>:<version> | fabric:<mc>:<version> | vanilla:<mc>,
                                      e.g. neoforge:1.21.1:21.1.248 — installed if it is not already
                  --with-client <dir> also run a client, in this dir, joined to the pack's server.
                                      Its own probe results are judged too; worst wins.
                  --install-dir <dir> where versions/, libraries/ and assets/ live (default: the
                                      official launcher's .minecraft). Downloads are verified and
                                      shared with HMCL through its cache/SHA-1 directory
                  --username <name>   the offline player's name (default StageWright)
                  --mirror bmclapi    try BMCLAPI before the official servers
                  A client needs a display (DISPLAY or WAYLAND_DISPLAY); this tool does not start one.
                  HTTPS_PROXY is honoured for downloads.

                exit: 0 GREEN / 1 RED / 2 DEAD (the framework is broken, results void)
                      3 ENV (the game never armed)""");
    }

    private Main() {}
}
