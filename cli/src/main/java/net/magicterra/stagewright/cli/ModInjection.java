package net.magicterra.stagewright.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.magicterra.stagewright.engine.RunDirectory;

/**
 * Hands the mods a scene run needs to the loader directly, leaving the pack's {@code mods/} alone.
 *
 * <p>The pack's mods folder may be a player's own, and a jar copied into it stays loaded in their
 * everyday game until something takes it out again. Both loaders have a supported way to load a mod
 * from elsewhere instead: Fabric reads {@code -Dfabric.addMods}, and NeoForge's FML resolves
 * {@code --fml.mods} coordinates against {@code --fml.mavenRoots} — the locator it lists as
 * "maven libs". The jars are staged under the run's own {@code stagewright/} directory.
 *
 * <p>StageWright's own jar rides inside this one (about 100 KB per loader, cheaper than the paragraph
 * of instructions it replaces). Anything else — a driver mod whose verbs the scenes call, a pack's own
 * mod under test — comes in through {@code --mod}, because a framework that shipped a copy of its
 * consumers would have the dependency backwards.
 */
final class ModInjection {

    /** What to add to the game's command line: JVM arguments, and program arguments at its end. */
    record Arguments(List<String> jvm, List<String> game) {
        static final Arguments NONE = new Arguments(List.of(), List.of());
    }

    /** Where the build stages the loader jars inside this fat jar. */
    private static final String RESOURCE_DIR = "/mods/";
    private static final String FRAMEWORK = "mc_stagewright-";
    /** Any version will do: FML only needs a coordinate that resolves to the file. */
    private static final String VERSION = "0";
    private static final String GROUP = "stagewright.mods";

    private ModInjection() {}

    /**
     * Stage the framework and {@code extras} for {@code loader} and say how to load them.
     *
     * <p>Jars an older CLI copied into mods/ are taken back out first — left there, each would load a
     * second time beside the copy handed over here. An extra whose file name the pack's mods/ already
     * holds is skipped rather than loaded twice.
     */
    static Arguments prepare(Path gameDir, String loader, List<Path> extras, Consumer<String> log) {
        requireLoader(loader);
        net.magicterra.stagewright.engine.ModInstall.uninstall(gameDir, log);

        Path staged = gameDir.resolve(RunDirectory.ARTIFACT_DIR).resolve("mods");
        deleteTree(staged);
        List<Path> jars = new ArrayList<>();
        jars.add(unpackFramework(loader, staged));
        for (Path extra : extras) {
            if (!Files.isRegularFile(extra)) {
                throw new IllegalArgumentException("--mod " + extra + " is not a file");
            }
            if (Files.exists(gameDir.resolve("mods").resolve(extra.getFileName().toString()))) {
                log.accept("not loading " + extra.getFileName() + " again: the pack's mods/ already has it");
                continue;
            }
            jars.add(extra);
        }

        return switch (loader) {
            case "fabric" -> fabric(jars, log);
            case "neoforge" -> neoforge(jars, staged, log);
            default -> throw new IllegalArgumentException("StageWright runs on neoforge or fabric,"
                    + " not " + loader);
        };
    }

    /** Refuses a pack with no StageWright build this CLI carries: unknown, or Forge. */
    static void requireLoader(String loader) {
        if (loader == null) {
            throw new IllegalArgumentException("cannot tell which loader this pack runs on, so there"
                    + " is no way to pick the right StageWright build — pass --no-install if the pack"
                    + " already has it");
        }
        if (!loader.equals("neoforge") && !loader.equals("fabric")) {
            throw new IllegalArgumentException("StageWright runs on neoforge or fabric, not " + loader);
        }
    }

    private static Arguments fabric(List<Path> jars, Consumer<String> log) {
        List<String> paths = jars.stream().map(p -> p.toAbsolutePath().toString()).toList();
        log.accept("loading " + names(jars) + " through -Dfabric.addMods");
        return new Arguments(List.of("-Dfabric.addMods=" + String.join(File.pathSeparator, paths)),
                List.of());
    }

    /** Each jar laid out as {@code stagewright.mods:<name>:0} in a maven directory of its own. */
    private static Arguments neoforge(List<Path> jars, Path staged, Consumer<String> log) {
        Path root = staged.resolve("maven");
        List<String> coordinates = new ArrayList<>();
        try {
            for (Path jar : jars) {
                String artifact = artifactName(jar);
                Path target = root.resolve(GROUP.replace('.', '/')).resolve(artifact).resolve(VERSION)
                        .resolve(artifact + "-" + VERSION + ".jar");
                Files.createDirectories(target.getParent());
                Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
                coordinates.add(GROUP + ":" + artifact + ":" + VERSION);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage mods under " + root, e);
        }
        log.accept("loading " + names(jars) + " through --fml.mods");
        return new Arguments(List.of(), List.of("--fml.mavenRoots", root.toAbsolutePath().toString(),
                "--fml.mods", String.join(",", coordinates)));
    }

    /** A jar's file name as a maven artifact id: no extension, nothing a coordinate cannot hold. */
    static String artifactName(Path jar) {
        String name = jar.getFileName().toString();
        if (name.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /**
     * Unpack the loader jar this CLI carries. Named without its version, as the build stages it, so
     * nothing here has to know which version it carries and an upgrade is a rebuild rather than an edit.
     */
    private static Path unpackFramework(String loader, Path staged) {
        String name = FRAMEWORK + loader + ".jar";
        String resource = RESOURCE_DIR + name;
        try (InputStream in = ModInjection.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("this CLI carries no StageWright build for '" + loader
                        + "' (looked for " + resource + ") — pass --no-install and put one in the pack");
            }
            Files.createDirectories(staged);
            Path out = staged.resolve(name);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unpack " + resource, e);
        }
    }

    private static String names(List<Path> jars) {
        return String.join(", ", jars.stream().map(p -> p.getFileName().toString()).toList());
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear " + root, e);
        }
    }
}
