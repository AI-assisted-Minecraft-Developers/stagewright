package net.magicterra.stagewright.cli;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.magicterra.stagewright.cli.install.ClientSpec;
import net.magicterra.stagewright.engine.RunDirectory;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/**
 * {@code stagewright test <game-dir>}: the YAML and JavaScript tests under {@code <game-dir>/stagewright/} (or the
 * directory {@code --tests} names), with the scenes beside them, run in the pack's own dedicated server the way the
 * Gradle plugin runs a mod's. The tests and scenes go into the game as a mod of their own, staged with StageWright as
 * {@link ModInjection} stages it; the CLI writes the launch spec the plugin would have written and runs the same JUnit
 * engine in this JVM, which writes JUnit XML. The server plays in {@code stagewright/world}, which each scene starts
 * afresh; the pack's own world is left alone.
 *
 * <p>With {@code --client}, a client is installed, and the scenes of the two topologies with a client run too:
 * {@code integratedServer} in that client, {@code dedicatedServerWithClient} with it joined to the pack's server. It
 * runs in {@code stagewright/client}, since the engine deletes a client's saves before each scene, with the server's
 * mods or those {@code --client-mods} names, and the rest of a client directory {@code --client-dir} names linked in.
 *
 * <p>The results are JUnit XML, one file per scene ({@link SceneResults}), with a line on the console as each scene
 * ends.
 */
final class TestCommand {

    /** The mod the tests and scenes ride in. */
    static final String MOD_ID = "stagewright_pack_tests";

    private static final String DEDICATED = "dedicatedServer";

    private static final String INTEGRATED = "integratedServer";

    private static final String WITH_CLIENT = "dedicatedServerWithClient";

    /** Where the server plays, relative to its directory: never the pack's own world. */
    private static final String SERVER_WORLD = RunDirectory.ARTIFACT_DIR + "/world";

    /** The singleplayer world an integrated server's scene plays in. */
    static final String CLIENT_WORLD = "StageWright";

    private static final String JAVA = "java";

    private static final String CLIENT = "client";

    private static final String TESTS = "tests";

    private static final String SCENES = "scenes";

    private static final String NOT_A_DIRECTORY = " is not a directory";

    /** The placeholder the test engine replaces with a free port (docs/reference/launch-spec.md). */
    private static final String FREE_PORT = "{stagewright.freePort}";

    private static final String PARALLEL_SCENES = "parallel-scenes";

    private static final String PARALLEL_CLIENTS = "parallel-clients";

    private static final Set<String> VALUED = Set.of(
            "launch",
            JAVA,
            "reports",
            "timeout",
            "stall-timeout",
            CLIENT,
            "client-mods",
            "client-dir",
            TESTS,
            "install-dir",
            "username",
            "mirror",
            "leaks",
            PARALLEL_SCENES,
            PARALLEL_CLIENTS);

    /**
     * What a client directory named by {@code --client-dir} keeps to itself: {@code mods/} comes from
     * {@code --client-mods}, the engine deletes the scene's save under {@code saves/}, and logs, crash reports and the
     * run's own {@code stagewright/} are this run's output, which must not land in the pack's directory.
     */
    static final Set<String> CLIENT_DIR_OWN =
            Set.of("mods", "saves", "logs", "crash-reports", RunDirectory.ARTIFACT_DIR);

    private TestCommand() {}

    /**
     * Exit code: 0 when every test passed, 1 when one did not.
     *
     * <p>One straight run of the steps, in order: each step's locals feed the next, so splitting it would only pass
     * them on.
     */
    @SuppressWarnings("checkstyle:javancss") // the steps are one ordered run; a split would only pass their locals on
    static int run(List<String> args) throws IOException {
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> systemProps = new ArrayList<>();
        List<Path> extraMods = new ArrayList<>();
        Path gameDir = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("-D") || a.startsWith("-X")) {
                systemProps.add(a);
            } else if (a.startsWith("--")) {
                String name = a.substring(2);
                if (!"mod".equals(name) && !VALUED.contains(name)) {
                    throw new IllegalArgumentException("unknown option '" + a + "'");
                }
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException(a + " needs a value");
                }
                String value = args.get(++i);
                if ("mod".equals(name)) {
                    extraMods.add(Path.of(value).toAbsolutePath().normalize());
                } else {
                    opts.put(name, value);
                }
            } else if (gameDir == null) {
                gameDir = Path.of(a).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("unexpected argument '" + a + "'");
            }
        }
        if (gameDir == null) {
            throw new IllegalArgumentException("stagewright test needs the pack's server directory");
        }
        if (!Files.isDirectory(gameDir)) {
            throw new IllegalArgumentException(gameDir + NOT_A_DIRECTORY);
        }
        leaks(opts);
        parallel(opts);
        // What the run writes stays under the server directory wherever the tests come from: they may be a
        // repository's.
        Path artifacts = gameDir.resolve(RunDirectory.ARTIFACT_DIR);
        final Path source = testsDir(gameDir, opts);
        final Path clientSource = clientDir(opts);
        // The CLI's progress lines go to stdout by design, next to the JUnit summary.
        @SuppressWarnings("PMD.SystemPrintln")
        Consumer<String> log = line -> System.out.println("[stagewright] " + line);

        String loader = GameLaunch.loader(gameDir);
        ModInjection.requireLoader(loader);
        String java = opts.getOrDefault(
                JAVA, Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> launch = opts.containsKey("launch")
                ? new ArrayList<>(List.of(opts.get("launch").split("\\s+")))
                : GameLaunch.detect(gameDir, java, log);
        if (launch == null) {
            throw new IllegalArgumentException(
                    "cannot tell how to start the server in " + gameDir + " — pass --launch" + " \"<command>\"");
        }

        Path work = artifacts.resolve("build");
        deleteTree(work);
        Path root = work.resolve("root");
        // worlds: the saves a scene's world.from names.
        for (String dir : List.of(SCENES, TESTS, "worlds")) {
            Set<Path> skipped = SCENES.equals(dir) ? oldSceneRuns(source.resolve(dir), log) : Set.of();
            int copied =
                    copyTree(source.resolve(dir), root.resolve("stagewright").resolve(dir), skipped);
            if (Files.isDirectory(source.resolve(dir))) {
                log.accept(copied + " files from " + source.resolve(dir));
            }
        }
        Path jar = modJar(root, work.resolve(MOD_ID + ".jar"));
        List<Path> mods = new ArrayList<>(extraMods);
        mods.add(jar);
        ModInjection.Arguments injected = ModInjection.prepare(gameDir, loader, mods, log);

        Path launches = work.resolve("launches");
        Files.createDirectories(launches);
        Map<String, Object> server = serverProcess(gameDir, launch, systemProps, injected, List.of());
        write(launches, topology(DEDICATED, gameDir, SERVER_WORLD, true, server, null, opts));
        if (opts.containsKey(CLIENT)) {
            ClientSpec client = ClientSpec.parse(opts.get(CLIENT));
            if (client.modLoader() == null) {
                throw new IllegalArgumentException("StageWright is a neoforge or fabric mod, so a vanilla client cannot"
                        + " run tests — name a loader in --client");
            }
            if (!client.modLoader().equals(loader)) {
                throw new IllegalArgumentException("the pack's server runs " + loader + " and --client asks for "
                        + client.modLoader() + " — both must run the same loader");
            }
            ClientPreflight.requireDisplay(System.getProperty("os.name", ""), System.getenv());
            // Its own directory, never a player's: the engine deletes the saves of a client it starts.
            Path clientDir = artifacts.resolve(CLIENT);
            Files.createDirectories(clientDir);
            linkClientDir(clientSource, clientDir, log);
            linkMods(clientMods(gameDir, clientSource, opts), clientDir.resolve("mods"), log);
            Path installDir = Main.installDir(opts);
            String versionId = install(client, installDir, opts, java, log);
            ModInjection.Arguments clientMods = ModInjection.prepare(clientDir, loader, mods, log);
            String username = Main.username(opts);
            Map<String, Object> integrated = clientProcess(
                    java,
                    ClientLaunch.parts(
                            installDir,
                            clientDir,
                            versionId,
                            clientProps(
                                    systemProps,
                                    clientMods,
                                    "stagewright.client.world=" + CLIENT_WORLD,
                                    "stagewright.awaitPlayer=true"),
                            clientMods.game(),
                            username),
                    clientDir);
            write(launches, topology(INTEGRATED, clientDir, "saves/" + CLIENT_WORLD, false, integrated, null, opts));
            Map<String, Object> joining = clientProcess(
                    java,
                    ClientLaunch.parts(
                            installDir,
                            clientDir,
                            versionId,
                            clientProps(
                                    systemProps,
                                    clientMods,
                                    // No port: the engine tells the client the free one the server was given.
                                    "stagewright.client.connect=127.0.0.1"),
                            clientMods.game(),
                            username),
                    clientDir);
            Map<String, Object> awaiting =
                    serverProcess(gameDir, launch, systemProps, injected, List.of("-Dstagewright.awaitPlayer=true"));
            write(launches, topology(WITH_CLIENT, gameDir, SERVER_WORLD, true, awaiting, joining, opts));
        } else {
            if (clientSource != null) {
                log.accept("--client-dir is unused: the run was given no --client");
            }
            for (String name : List.of(INTEGRATED, WITH_CLIENT)) {
                write(launches, unavailable(name, "the run was given no --client, so there is no client to start"));
            }
        }
        System.setProperty("stagewright.launches", launches.toString());
        // A scene whose topology this run cannot launch is reported skipped, not failed: what to install is the
        // user's choice here, not a mistake in a build.
        System.setProperty("stagewright.launches.missing", "skip");

        Path reports = Path.of(opts.getOrDefault(
                        "reports", artifacts.resolve("reports").toString()))
                .toAbsolutePath();
        return execute(root, reports, parallel(opts), log);
    }

    /**
     * {@code --parallel-scenes} and {@code --parallel-clients}, as the engine's configuration parameters: how many
     * scenes run at once, and how many of them may start a client. Each is 1 when not given.
     */
    static Map<String, String> parallel(Map<String, String> opts) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String[] option : new String[][] {
            {PARALLEL_SCENES, "stagewright.parallel.scenes"}, {PARALLEL_CLIENTS, "stagewright.parallel.clients"}
        }) {
            String value = opts.getOrDefault(option[0], "1");
            if (!value.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("--" + option[0] + " is a whole number from 1 up, not " + value);
            }
            parameters.put(option[1], value);
        }
        return parameters;
    }

    /**
     * {@code --tests}, or {@code <server-dir>/stagewright}: the directory holding {@code tests/} and {@code scenes/}.
     */
    static Path testsDir(Path gameDir, Map<String, String> opts) {
        Path dir = opts.containsKey(TESTS)
                ? Path.of(opts.get(TESTS)).toAbsolutePath().normalize()
                : gameDir.resolve(RunDirectory.ARTIFACT_DIR);
        if (!Files.isDirectory(dir.resolve(TESTS))) {
            throw new IllegalArgumentException("no tests: " + dir.resolve(TESTS) + NOT_A_DIRECTORY);
        }
        return dir;
    }

    /** {@code --client-dir}, or null when there is none. */
    static Path clientDir(Map<String, String> opts) {
        if (!opts.containsKey("client-dir")) {
            return null;
        }
        Path dir = Path.of(opts.get("client-dir")).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("--client-dir " + dir + NOT_A_DIRECTORY);
        }
        return dir;
    }

    /**
     * The jars the client loads: {@code --client-mods}, else the client directory's {@code mods/}, else the server's.
     */
    static Path clientMods(Path gameDir, Path clientSource, Map<String, String> opts) {
        if (opts.containsKey("client-mods")) {
            return Path.of(opts.get("client-mods")).toAbsolutePath().normalize();
        }
        return (clientSource != null ? clientSource : gameDir).resolve("mods");
    }

    /**
     * Give the client in {@code clientDir} every entry of {@code from} but those in {@link #CLIENT_DIR_OWN}, each as a
     * symbolic link, so the client reads the pack's {@code config/}, {@code kubejs/}, {@code options.txt} and the like
     * as they are now, and nothing of the pack is copied. What a mod writes into a linked directory lands in the
     * pack's. The links of the run before are taken out first, those of entries since removed included; with
     * {@code from} null that is all it does. A real file or directory where a link would go is refused before anything
     * changes: it is what a run without {@code --client-dir}, or a script, left there, and it may hold the only copy of
     * something. Answers how many entries were linked.
     */
    static int linkClientDir(Path from, Path clientDir, Consumer<String> log) throws IOException {
        Files.createDirectories(clientDir);
        List<Path> entries = List.of();
        if (from != null) {
            try (Stream<Path> list = Files.list(from)) {
                entries = list.filter(
                                p -> !CLIENT_DIR_OWN.contains(p.getFileName().toString()))
                        .sorted()
                        .toList();
            }
            List<String> occupied = new ArrayList<>();
            for (Path entry : entries) {
                Path target = clientDir.resolve(entry.getFileName().toString());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                    occupied.add(target.toString());
                }
            }
            if (!occupied.isEmpty()) {
                throw new EnvFailure("--client-dir " + from + " would replace what is already in the client's"
                        + " directory: " + String.join(", ", occupied) + ". The runner links these from --client-dir"
                        + " and will not delete a real file or directory: move or delete them, then run again");
            }
        }
        try (Stream<Path> list = Files.list(clientDir)) {
            for (Path p : list.toList()) {
                if (Files.isSymbolicLink(p)) {
                    Files.delete(p);
                }
            }
        }
        if (from == null) {
            return 0;
        }
        for (Path entry : entries) {
            Path target = clientDir.resolve(entry.getFileName().toString());
            try {
                Files.createSymbolicLink(target, entry.toAbsolutePath());
            } catch (IOException | UnsupportedOperationException e) {
                throw new EnvFailure(
                        "cannot link " + entry + " into " + clientDir + " (" + e.getMessage() + ") — on"
                                + " Windows, symbolic links need Developer Mode or an administrator",
                        e);
            }
        }
        log.accept("the client reads " + entries.size() + " entries of " + from + " through links");
        return entries.size();
    }

    private static String install(
            ClientSpec client, Path installDir, Map<String, String> opts, String java, Consumer<String> log)
            throws IOException {
        try {
            return Main.install(client, installDir, opts, java, log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while installing " + client, e);
        }
    }

    /**
     * The client's {@code mods/}, made anew from the jars in {@code from}: linked where the file system allows, else
     * copied. A pack's client mods are not always its server's, so {@code --client-mods} may name others.
     */
    static void linkMods(Path from, Path to, Consumer<String> log) throws IOException {
        if (!Files.isDirectory(from)) {
            throw new IllegalArgumentException("--client-mods " + from + NOT_A_DIRECTORY);
        }
        deleteTree(to);
        Files.createDirectories(to);
        List<Path> jars;
        try (Stream<Path> list = Files.list(from)) {
            jars = list.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted()
                    .toList();
        }
        for (Path jar : jars) {
            Path target = to.resolve(jar.getFileName().toString());
            try {
                Files.createLink(target, jar);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(jar, target);
            }
        }
        log.accept("the client loads the " + jars.size() + " mods in " + from);
    }

    private static void write(Path launches, Map<String, Object> spec) throws IOException {
        Files.writeString(
                launches.resolve(spec.get("topology") + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(spec));
    }

    /** A client's own properties: the user's, the topology's, and what the loader needs to find the mods. */
    static List<String> clientProps(List<String> systemProps, ModInjection.Arguments mods, String... properties) {
        List<String> props = new ArrayList<>(systemProps);
        for (String p : properties) {
            props.add("-D" + p);
        }
        props.addAll(mods.jvm());
        return props;
    }

    /** One topology's launch spec; the format is docs/reference/launch-spec.md. */
    static Map<String, Object> topology(
            String name,
            Path gameDir,
            String worldDir,
            boolean server,
            Map<String, Object> primary,
            Map<String, Object> companion,
            Map<String, String> opts) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("topology", name);
        spec.put("gameDirectory", gameDir.toString());
        spec.put("worldDirectory", worldDir);
        spec.put("cleanWorld", true);
        spec.put("server", server);
        if (opts.containsKey("timeout")) {
            spec.put("timeoutMinutes", Integer.parseInt(opts.get("timeout")));
        }
        if (opts.containsKey("stall-timeout")) {
            spec.put("stallMinutes", Integer.parseInt(opts.get("stall-timeout")));
        }
        spec.put("leaks", leaks(opts));
        spec.put("primary", primary);
        if (companion != null) {
            spec.put("companion", companion);
        }
        return spec;
    }

    /** A topology this run cannot launch: its scenes are reported skipped with {@code why}. */
    static Map<String, Object> unavailable(String name, String why) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("topology", name);
        spec.put("unavailable", why);
        return spec;
    }

    /**
     * {@code --leaks}: what a game that had to be forced down after its run does to its scene, {@code fail} (the
     * default) or {@code report}. A pack whose mod keeps an idle pool thread alive past the grace period would
     * otherwise fail every scene; the report entry is written either way.
     */
    static String leaks(Map<String, String> opts) {
        String leaks = opts.getOrDefault("leaks", "fail");
        if (!"fail".equals(leaks) && !"report".equals(leaks)) {
            throw new IllegalArgumentException("--leaks is 'fail' or 'report', not '" + leaks + "'");
        }
        return leaks;
    }

    /** An installed client, started in {@code clientDir}. */
    static Map<String, Object> clientProcess(String java, ClientLaunch.Parts parts, Path clientDir) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put(JAVA, java);
        process.put("jvmArgs", parts.jvm());
        process.put("mainClass", parts.mainClass());
        process.put("args", parts.args());
        process.put("workingDirectory", clientDir.toString());
        return process;
    }

    /** The pack's server, started as {@code launch} would, playing in {@code stagewright/world}. */
    static Map<String, Object> serverProcess(
            Path gameDir,
            List<String> launch,
            List<String> systemProps,
            ModInjection.Arguments injected,
            List<String> topologyProps) {
        List<String> command = new ArrayList<>(launch.subList(1, launch.size()));
        command.remove("nogui");
        List<String> jvmArgs = new ArrayList<>(systemProps);
        jvmArgs.addAll(topologyProps);
        jvmArgs.addAll(injected.jvm());
        String mainClass = null;
        List<String> args = new ArrayList<>();
        int jarAt = command.indexOf("-jar");
        if (jarAt >= 0 && jarAt + 1 < command.size()) {
            // As -cp and its main class, so the engine's own -D can go before it: after -jar they would be the game's.
            Path serverJar = gameDir.resolve(command.get(jarAt + 1));
            jvmArgs.addAll(command.subList(0, jarAt));
            jvmArgs.addAll(List.of("-cp", serverJar.toString()));
            mainClass = mainClassOf(serverJar);
            args.addAll(command.subList(jarAt + 2, command.size()));
        } else {
            jvmArgs.addAll(command);
        }
        args.add("nogui");
        // The world the scenes play in, never the pack's. Relative, so it is under the directory each scene runs in.
        args.addAll(List.of("--universe", RunDirectory.ARTIFACT_DIR, "--world", "world"));
        // A free port the engine picks as each scene starts, never the pack's: a server of the pack may be up on it,
        // and
        // scenes run at once each need their own.
        args.addAll(List.of("--port", FREE_PORT));
        args.addAll(injected.game());

        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put(JAVA, launch.get(0));
        primary.put("jvmArgs", jvmArgs);
        if (mainClass != null) {
            primary.put("mainClass", mainClass);
        }
        primary.put("args", args);
        primary.put("workingDirectory", gameDir.toString());
        return primary;
    }

    private static String mainClassOf(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            String main = file.getManifest() == null
                    ? null
                    : file.getManifest().getMainAttributes().getValue("Main-Class");
            if (main == null) {
                throw new IllegalArgumentException(jar + " names no Main-Class, so it cannot be started");
            }
            return main;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + jar, e);
        }
    }

    /** The tests and scenes as a mod either loader takes: resources only, so it declares no entry point. */
    static Path modJar(Path root, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "fabric.mod.json", """
                    {"schemaVersion": 1, "id": "%s", "version": "0.0.0", "name": "StageWright pack tests"}
                    """.formatted(MOD_ID));
            entry(zip, "META-INF/neoforge.mods.toml", """
                    modLoader = "lowcodefml"
                    loaderVersion = "[1,)"
                    license = "All Rights Reserved"
                    [[mods]]
                    modId = "%s"
                    version = "0.0.0"
                    displayName = "StageWright pack tests"
                    """.formatted(MOD_ID));
            if (!Files.isDirectory(root)) {
                return jar;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(root.relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, zip);
                    zip.closeEntry();
                }
            }
        }
        return jar;
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Discover under {@code root} and run, as a Gradle test task would, writing JUnit XML to {@code reports}. */
    private static int execute(Path root, Path reports, Map<String, String> parameters, Consumer<String> log)
            throws IOException {
        Files.createDirectories(reports);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader tests = new URLClassLoader(new URL[] {root.toUri().toURL()}, previous)) {
            // The engine reads scene declarations and scripts as resources of the test class path.
            Thread.currentThread().setContextClassLoader(tests);
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClasspathRoots(Set.of(root)))
                    .filters(org.junit.platform.launcher.EngineFilter.includeEngines("stagewright"))
                    .configurationParameters(parameters)
                    .build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            // Wraps System.out, which must stay open for the rest of the process, so it is not closed here.
            @SuppressWarnings("PMD.CloseResource")
            PrintWriter out = new PrintWriter(System.out, true);
            RecordProperties records = new RecordProperties();
            launcher.execute(
                    request,
                    summary,
                    new LegacyXmlReportGeneratingListener(reports, out),
                    records,
                    new SceneResults(log));
            records.writeInto(reports);
            SceneResults.split(reports);
            TestExecutionSummary result = summary.getSummary();
            result.printTo(out);
            result.printFailuresTo(out, 20);
            log.accept("JUnit XML in " + reports);
            return result.getTotalFailureCount() == 0 && result.getContainersFailedCount() == 0 ? 0 : 1;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * Copy {@code from} into {@code to}, following symbolic links, the directory itself included: a pack that links its
     * tests in from a repository of their own must not get an empty mod. Answers how many files were copied.
     */
    static int copyTree(Path from, Path to) throws IOException {
        return copyTree(from, to, Set.of());
    }

    /** {@link #copyTree(Path, Path)}, leaving out the subtrees {@code skipped} names, unread. */
    static int copyTree(Path from, Path to, Set<Path> skipped) throws IOException {
        if (!Files.isDirectory(from)) {
            return 0;
        }
        int[] files = {0};
        Files.walkFileTree(
                from, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (skipped.contains(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, to.resolve(from.relativize(file).toString()));
                        files[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
        return files[0];
    }

    /**
     * The directories in a pack's {@code scenes/} that scenes ran in before their runs moved to {@code runs/}: each
     * holds a {@code stagewright/} of its own, and its links reach the pack's mods and libraries, which packing it
     * would put into the tests' jar.
     */
    static Set<Path> oldSceneRuns(Path scenes, Consumer<String> log) throws IOException {
        if (!Files.isDirectory(scenes)) {
            return Set.of();
        }
        Set<Path> runs = new java.util.LinkedHashSet<>();
        try (Stream<Path> list = Files.list(scenes)) {
            for (Path dir : list.sorted().toList()) {
                if (Files.isDirectory(dir.resolve(RunDirectory.ARTIFACT_DIR))) {
                    runs.add(dir);
                    log.accept("WARNING: " + dir + " is where a scene ran before runs moved to stagewright/runs/."
                            + " It is not packed with the tests; delete it.");
                }
            }
        }
        return runs;
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
