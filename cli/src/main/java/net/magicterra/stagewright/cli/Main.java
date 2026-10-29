package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.magicterra.stagewright.cli.install.ClientSpec;
import net.magicterra.stagewright.cli.install.Downloader;
import net.magicterra.stagewright.cli.install.Installer;
import net.magicterra.stagewright.cli.install.MinecraftDir;
import net.magicterra.stagewright.cli.install.Mirror;

/**
 * Run a modpack's tests, with no build tool anywhere: {@code stagewright test <server-dir>}, see
 * {@link TestCommand}.
 *
 * <p>The Gradle plugin cannot be a modpack author's entry point: they have a {@code mods/} folder
 * and a launcher, not a Gradle project. Java rather than a script, and not arbitrarily: a modpack
 * author is running Minecraft, so a JVM is the one interpreter they are guaranteed to have.
 */
public final class Main {

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

    static int run(String[] args) throws IOException {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage(System.out);
            return 0;
        }
        if ("--version".equals(args[0])) {
            BuildInfo.describe().forEach(System.out::println);
            return 0;
        }
        if ("test".equals(args[0])) return TestCommand.run(List.of(args).subList(1, args.length));
        throw new IllegalArgumentException("unknown command '" + args[0] + "'");
    }

    static Path installDir(Map<String, String> opts) {
        return opts.containsKey("install-dir")
                ? Path.of(opts.get("install-dir")).toAbsolutePath().normalize()
                : MinecraftDir.defaultDir();
    }

    /** Install {@code client} into {@code installDir} unless it is there; answers the version to launch. */
    static String install(ClientSpec client, Path installDir, Map<String, String> opts, String javaBinary,
                          Consumer<String> log) throws IOException, InterruptedException {
        Mirror mirror = Mirror.parse(opts.getOrDefault("mirror", "none"));
        try (Downloader downloader = new Downloader(mirror, installDir)) {
            return new Installer(installDir, downloader, javaBinary, log).install(client);
        }
    }

    static String username(Map<String, String> opts) {
        return opts.getOrDefault("username", ClientLaunch.DEFAULT_USERNAME);
    }

    /**
     * The port the pack's server will listen on.
     *
     * <p>Read from {@code server.properties} rather than assumed, because a pack that moved off
     * 25565 would otherwise produce a client dialling a port nothing answers — which looks from the
     * outside exactly like the server failing to start.
     */
    static int serverPort(Path gameDir) throws IOException {
        Path properties = gameDir.resolve("server.properties");
        if (Files.isRegularFile(properties)) {
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("server-port=")) {
                    String value = trimmed.substring("server-port=".length()).trim();
                    if (!value.isEmpty()) return Integer.parseInt(value);
                }
            }
        }
        return 25565;
    }

    private static void usage(java.io.PrintStream out) {
        out.println("""
                stagewright — run a modpack's tests

                  java -jar stagewright.jar test <server-dir> [--tests <dir>] [--mod <jar>]...
                                                        [--reports <dir>] [--launch "<cmd>"] [--java <path>]
                                                        [--timeout <min>] [--stall-timeout <min>]
                                                        [--leaks fail|report] [-D…] [-X…]
                                                        [--client <spec> [--client-dir <dir>]
                                                         [--client-mods <dir>] [--install-dir <dir>]
                                                         [--username <name>] [--mirror bmclapi]]
                  java -jar stagewright.jar --version   what this jar was built from

                Runs the YAML and JavaScript tests in <server-dir>/stagewright/tests, with the scenes in
                <server-dir>/stagewright/scenes. A dedicatedServer scene runs in the pack's server, with a
                fresh world in <server-dir>/stagewright/world; the pack's own is not touched. Mods are
                handed to the loader, never copied into mods/. JUnit XML goes to --reports (default
                <server-dir>/stagewright/reports).

                  --tests <dir>       the directory holding tests/, scenes/ and worlds/ (default:
                                      <server-dir>/stagewright), so they can live outside the server
                  --mod <jar>         also load this mod (repeatable — e.g. the driver whose verbs your
                                      tests call). Skipped if the pack's mods/ already has that file
                  --launch "<cmd>"    start the server this way instead of detecting it
                  --java <path>       java executable to launch with
                  --timeout <min>     the longest one scene's games may run
                  --stall-timeout <min>
                                      end a scene's game once it has stopped moving for this long
                  --leaks fail|report a game that has to be forced down after its run, because a mod's
                                      thread keeps it up, fails its scene (fail, the default) or is
                                      only reported (report)
                  -D<key>=<value>, -X<option>
                                      passed to the games' JVMs as given (e.g. -Xmx8G)

                Client:
                  --client <spec>     neoforge:<mc>:<version> | fabric:<mc>:<version>, e.g.
                                      neoforge:1.21.1:21.1.248 — installed if it is not already. The
                                      integratedServer and dedicatedServerWithClient scenes then run
                                      too, in a client under <server-dir>/stagewright/client
                  --client-dir <dir>  a client game directory: everything in it but mods/, saves/,
                                      logs/ and crash-reports/ (config/, kubejs/, options.txt, …) is
                                      linked into the client's, never copied
                  --client-mods <dir> the jars that client loads (default: <client-dir>/mods, else the
                                      server's mods/)
                  --install-dir <dir> where versions/, libraries/ and assets/ live (default: the
                                      official launcher's .minecraft). Downloads are verified and
                                      shared with HMCL through its cache/SHA-1 directory
                  --username <name>   the offline player's name (default StageWright)
                  --mirror bmclapi    try BMCLAPI before the official servers
                  A client needs a display, and this tool does not start one: DISPLAY, or
                  WAYLAND_DISPLAY when DISPLAY is unset. A local DISPLAY must also let the client
                  in with the cookie in XAUTHORITY or $HOME/.Xauthority, or the run is ENV at once.
                  HTTPS_PROXY is honoured for downloads.

                exit: 0 every test passed / 1 one did not / 3 the run could not start""");
    }

    private Main() {}
}
