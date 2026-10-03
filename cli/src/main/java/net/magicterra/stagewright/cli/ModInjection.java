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

import net.magicterra.stagewright.cli.install.Sha1;
import net.magicterra.stagewright.engine.ModInstall;
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
     * <p>Nothing in the pack's mods/ is touched — not even what an older CLI copied there, because
     * its ledger kept only names and a pack may since have put its own jar under one. Where the pack
     * already has StageWright, or a jar named like an extra, that copy loads instead of ours (two
     * would be a duplicate mod), and the log says which file that is and whether its bytes match.
     */
    static Arguments prepare(Path gameDir, String loader, List<Path> extras, Consumer<String> log) {
        requireLoader(loader);
        DriverRequirement.check(gameDir, loader, extras, true);
        for (Path left : ModInstall.ledgered(gameDir)) {
            log.accept("NOTE: an older StageWright CLI copied " + left.getFileName() + " into mods/."
                    + " It is left there and loads as the pack's own; delete it if the pack did not"
                    + " put it there.");
        }

        Path staged = gameDir.resolve(RunDirectory.ARTIFACT_DIR).resolve("mods");
        deleteTree(staged);
        List<Path> jars = new ArrayList<>();
        Path ours = unpackFramework(loader, staged);
        List<Path> packs = DriverRequirement.frameworkJars(gameDir, loader);
        boolean extraFramework = extras.stream().anyMatch(p -> DriverRequirement.isFramework(p, loader));
        if (packs.isEmpty() && !extraFramework) {
            jars.add(ours);
        } else {
            for (Path pack : packs) log.accept(usingThePacks(pack, ours, "this CLI's StageWright"));
        }
        for (Path extra : extras) {
            if (!Files.isRegularFile(extra)) {
                throw new IllegalArgumentException("--mod " + extra + " is not a file");
            }
            Path pack = gameDir.resolve("mods").resolve(extra.getFileName().toString());
            if (Files.exists(pack)) {
                log.accept(usingThePacks(pack, extra, extra.toString()));
                continue;
            }
            jars.add(extra);
        }

        if (jars.isEmpty()) return Arguments.NONE;
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

    /** Which copy this run loads when the pack has its own — and a warning when the bytes differ. */
    private static String usingThePacks(Path pack, Path skipped, String skippedName) {
        String packSha1 = sha1(pack);
        String skippedSha1 = sha1(skipped);
        if (packSha1.equals(skippedSha1)) {
            return "using the pack's own " + pack + " (sha1 " + packSha1.substring(0, 8) + "), the same"
                    + " bytes as " + skippedName;
        }
        return "WARNING: using the pack's own " + pack + " (sha1 " + packSha1.substring(0, 8) + "), not "
                + skippedName + " (sha1 " + skippedSha1.substring(0, 8) + ") — they differ, so this run"
                + " tests the pack's copy. Take it out of mods/ to test the other.";
    }

    private static String sha1(Path file) {
        try {
            return Sha1.of(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
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
