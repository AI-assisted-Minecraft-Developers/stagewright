package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.GsonBuilder;

import net.magicterra.stagewright.engine.RunDirectory;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

/**
 * {@code stagewright test <game-dir>}: the YAML and JavaScript tests under {@code <game-dir>/stagewright/}, with the
 * scenes beside them, run in the pack's own dedicated server the way the Gradle plugin runs a mod's. The tests and
 * scenes go into the game as a mod of their own, staged with StageWright as {@link ModInjection} stages it; the CLI
 * writes the launch spec the plugin would have written and runs the same JUnit engine in this JVM, which writes
 * JUnit XML. The server plays in {@code stagewright/world}, which each scene starts afresh; the pack's own world is
 * left alone.
 */
final class TestCommand {

    /** The mod the tests and scenes ride in. */
    static final String MOD_ID = "stagewright_pack_tests";
    private static final String TOPOLOGY = "dedicatedServer";
    private static final Set<String> VALUED = Set.of("launch", "java", "reports", "timeout", "stall-timeout");

    private TestCommand() {}

    /** Exit code: 0 when every test passed, 1 when one did not. */
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
                if (!name.equals("mod") && !VALUED.contains(name)) throw new IllegalArgumentException("unknown option '" + a + "'");
                if (i + 1 >= args.size()) throw new IllegalArgumentException(a + " needs a value");
                String value = args.get(++i);
                if (name.equals("mod")) extraMods.add(Path.of(value).toAbsolutePath().normalize());
                else opts.put(name, value);
            } else if (gameDir == null) {
                gameDir = Path.of(a).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("unexpected argument '" + a + "'");
            }
        }
        if (gameDir == null) throw new IllegalArgumentException("stagewright test needs the pack's server directory");
        if (!Files.isDirectory(gameDir)) throw new IllegalArgumentException(gameDir + " is not a directory");
        Path source = gameDir.resolve(RunDirectory.ARTIFACT_DIR);
        if (!Files.isDirectory(source.resolve("tests"))) {
            throw new IllegalArgumentException("no tests: " + source.resolve("tests") + " is not a directory");
        }
        Consumer<String> log = line -> System.out.println("[stagewright] " + line);

        String loader = GameLaunch.loader(gameDir);
        ModInjection.requireLoader(loader);
        String java = opts.getOrDefault("java", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> launch = opts.containsKey("launch")
                ? new ArrayList<>(List.of(opts.get("launch").split("\\s+")))
                : GameLaunch.detect(gameDir, java, log);
        if (launch == null) {
            throw new IllegalArgumentException("cannot tell how to start the server in " + gameDir + " — pass --launch"
                    + " \"<command>\"");
        }

        Path work = source.resolve("build");
        deleteTree(work);
        Path root = work.resolve("root");
        for (String dir : List.of("scenes", "tests")) copyTree(source.resolve(dir), root.resolve("stagewright").resolve(dir));
        Path jar = modJar(root, work.resolve(MOD_ID + ".jar"));
        List<Path> mods = new ArrayList<>(extraMods);
        mods.add(jar);
        ModInjection.Arguments injected = ModInjection.prepare(gameDir, loader, mods, log);

        Path launches = work.resolve("launches");
        Files.createDirectories(launches);
        Files.writeString(launches.resolve(TOPOLOGY + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(spec(gameDir, launch, systemProps, injected, opts)));
        System.setProperty("stagewright.launches", launches.toString());

        Path reports = Path.of(opts.getOrDefault("reports", source.resolve("reports").toString())).toAbsolutePath();
        return execute(root, reports, log);
    }

    /** What {@code launches/dedicatedServer.json} holds; the format is docs/reference/launch-spec.md. */
    static Map<String, Object> spec(Path gameDir, List<String> launch, List<String> systemProps,
                                    ModInjection.Arguments injected, Map<String, String> opts) {
        List<String> command = new ArrayList<>(launch.subList(1, launch.size()));
        command.remove("nogui");
        List<String> jvmArgs = new ArrayList<>(systemProps);
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
        // The world the scenes play in, never the pack's: cleanWorld deletes it before each scene.
        args.addAll(List.of("--universe", gameDir.resolve(RunDirectory.ARTIFACT_DIR).toString(), "--world", "world"));
        args.addAll(injected.game());

        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("java", launch.get(0));
        primary.put("jvmArgs", jvmArgs);
        if (mainClass != null) primary.put("mainClass", mainClass);
        primary.put("args", args);
        primary.put("workingDirectory", gameDir.toString());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("topology", TOPOLOGY);
        spec.put("gameDirectory", gameDir.toString());
        spec.put("worldDirectory", RunDirectory.ARTIFACT_DIR + "/world");
        spec.put("cleanWorld", true);
        spec.put("server", true);
        if (opts.containsKey("timeout")) spec.put("timeoutMinutes", Integer.parseInt(opts.get("timeout")));
        if (opts.containsKey("stall-timeout")) spec.put("stallMinutes", Integer.parseInt(opts.get("stall-timeout")));
        spec.put("primary", primary);
        return spec;
    }

    private static String mainClassOf(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            String main = file.getManifest() == null ? null : file.getManifest().getMainAttributes().getValue("Main-Class");
            if (main == null) throw new IllegalArgumentException(jar + " names no Main-Class, so it cannot be started");
            return main;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + jar, e);
        }
    }

    /** The tests and scenes as a mod either loader takes: resources only, so it declares no entry point. */
    static Path modJar(Path root, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
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
            if (!Files.isDirectory(root)) return jar;
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
    private static int execute(Path root, Path reports, Consumer<String> log) throws IOException {
        Files.createDirectories(reports);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader tests = new URLClassLoader(new URL[] {root.toUri().toURL()}, previous)) {
            // The engine reads scene declarations and scripts as resources of the test class path.
            Thread.currentThread().setContextClassLoader(tests);
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClasspathRoots(Set.of(root)))
                    .filters(org.junit.platform.launcher.EngineFilter.includeEngines("stagewright"))
                    .build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            PrintWriter out = new PrintWriter(System.out, true);
            launcher.execute(request, summary, new LegacyXmlReportGeneratingListener(reports, out));
            TestExecutionSummary result = summary.getSummary();
            result.printTo(out);
            result.printFailuresTo(out, 20);
            log.accept("JUnit XML in " + reports);
            return result.getTotalFailureCount() == 0 && result.getContainersFailedCount() == 0 ? 0 : 1;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
