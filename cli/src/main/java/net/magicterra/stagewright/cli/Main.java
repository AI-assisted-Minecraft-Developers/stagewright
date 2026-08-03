package net.magicterra.stagewright.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static final String DEFAULT_RESULTS = "stagewright-results.jsonl";
    private static final int DEFAULT_TIMEOUT_MINUTES = 45;

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

        Map<String, String> opts = new LinkedHashMap<>();
        List<String> systemProps = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-D")) {
                systemProps.add(a);
            } else if (a.startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
                opts.put(a.substring(2), args[++i]);
            } else {
                throw new IllegalArgumentException("unexpected argument '" + a + "'");
            }
        }

        Path gameDir = Path.of(require(opts, "game-dir")).toAbsolutePath().normalize();
        if (!Files.isDirectory(gameDir)) {
            throw new IllegalArgumentException("--game-dir " + gameDir + " is not a directory");
        }
        String resultsName = opts.getOrDefault("results", DEFAULT_RESULTS);
        Path results = gameDir.resolve(resultsName);
        Path scenes = opts.containsKey("scenes")
                ? Path.of(opts.get("scenes")).toAbsolutePath().normalize() : null;
        int timeoutMinutes = Integer.parseInt(opts.getOrDefault("timeout",
                String.valueOf(DEFAULT_TIMEOUT_MINUTES)));

        RunDirectory.provision(gameDir, resultsName, !"false".equals(opts.get("clean-world")),
                scenes, line -> System.out.println("[stagewright] " + line));

        List<String> command = buildCommand(gameDir, opts, systemProps);
        System.out.println("[stagewright] " + String.join(" ", command));

        Path log = gameDir.resolve("stagewright-run.log");
        Process game = new ProcessBuilder(command)
                .directory(gameDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();

        if (!game.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            game.destroyForcibly();
            game.waitFor(30, TimeUnit.SECONDS);
            System.err.println("[stagewright] the run exceeded " + timeoutMinutes
                    + " minutes and was killed — see " + log);
            // Deliberately NOT an early return: a killed run usually still wrote records, and the
            // missing done footer is what turns them into a RED that names how far it got. Judging
            // is strictly more informative than reporting the timeout alone.
        }

        return judge(results, opts, log);
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
                                             List<String> systemProps) {
        String javaBinary = opts.getOrDefault("java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString());

        List<String> base;
        if (opts.containsKey("launch")) {
            base = new ArrayList<>(List.of(opts.get("launch").split("\\s+")));
        } else {
            base = GameLaunch.detect(gameDir, javaBinary);
            if (base == null) {
                throw new IllegalArgumentException("cannot tell how to start the server in " + gameDir
                        + " — no NeoForge/Forge argument files under libraries/ and no server jar at"
                        + " the top level. Pass --launch \"<command>\" to say it explicitly.");
            }
        }

        List<String> command = new ArrayList<>();
        command.add(base.get(0));
        command.add("-Dstagewright.autorun=true");
        command.addAll(systemProps);
        command.addAll(base.subList(1, base.size()));
        return command;
    }

    private static int judge(Path results, Map<String, String> opts, Path log) throws IOException {
        if (!Files.isRegularFile(results)) {
            System.err.println("stagewright: ENV — the run wrote no results");
            System.err.println("  expected: " + results);
            System.err.println("  The game started and exited without arming the harness. Usual"
                    + " causes: the StageWright mod jar is not in this pack's mods folder, it is"
                    + " built for the other loader, or mod loading failed before the server reached"
                    + " its first tick — " + log + " says which.");
            return 3;
        }

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> records = Verdict.parse(results, warnings);
        warnings.forEach(w -> System.out.println("[stagewright] " + w));

        List<String> expected = opts.containsKey("expect")
                ? Manifest.read(Path.of(opts.get("expect"))) : null;
        Verdict.Result verdict = Verdict.judge(records, expected);
        verdict.report().forEach(line -> System.out.println("[stagewright] " + line));
        System.out.println("[stagewright] VERDICT: " + verdict.label());
        if (verdict.code() != 0) System.out.println("[stagewright] log: " + log);
        return verdict.code();
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
                  --expect <file>     expected-scenes manifest to reconcile against
                  --results <name>    results file name (default stagewright-results.jsonl)
                  --timeout <min>     kill the run after this long (default 45)
                  --clean-world false keep the existing world (default: delete it)
                  --launch "<cmd>"    start the server this way instead of detecting it
                  --java <path>       java executable to launch with
                  -D<key>=<value>     extra system properties for the game

                exit: 0 GREEN / 1 RED / 2 DEAD (the framework is broken, results void)
                      3 ENV (the game never armed)""");
    }

    private Main() {}
}
