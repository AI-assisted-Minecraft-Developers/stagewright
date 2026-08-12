package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Puts the mods a scene run needs into the pack being tested.
 *
 * <p>Without this the CLI is a launcher for a game that has never heard of it. The author downloads
 * one jar, points it at their pack, and gets a clean boot, no results file, and a verdict that can
 * only guess why — the most likely first-run experience and the least informative one.
 *
 * <p>Installing rather than checking, because a check has the same ending: the author still has to
 * find the right jar for their loader and their Minecraft version, and getting that pairing wrong
 * fails exactly like not having it. The CLI already knows the loader — it has to, to launch at all.
 *
 * <p>StageWright's own jar rides inside this one (about 100 KB per loader, cheaper than the
 * paragraph of instructions it replaces). Anything else — a driver mod whose verbs the scenes call,
 * a pack's own mod under test — comes in through {@code --mod}, because a framework that shipped a
 * copy of its consumers would have the dependency backwards.
 *
 * <p>What remains here is only the part that is genuinely the CLI's: unpacking a jar it carries as a
 * resource. Where the jars go, what a previous run's copies must have done to them first, and why
 * {@code mods/} rather than the classpath all live in
 * {@link net.magicterra.stagewright.engine.ModInstall}, because the Gradle plugin needs the same
 * rules and a second copy would drift.
 */
final class ModInstall {

    /** Where the build stages the loader jars inside this fat jar. */
    private static final String RESOURCE_DIR = "/mods/";

    private ModInstall() {}

    /**
     * Install the framework and any extra jars, replacing everything a previous run installed.
     *
     * @param loader the pack's loader, or null when detection could not tell
     * @param extras author-supplied jars to install alongside the framework
     * @return the files now sitting in mods/
     */
    static List<Path> install(Path gameDir, String loader, List<Path> extras, Consumer<String> log) {
        List<Path> jars = new ArrayList<>();
        jars.add(unpackFramework(loader));
        for (Path extra : extras) {
            if (!Files.isRegularFile(extra)) {
                throw new IllegalArgumentException("--mod " + extra + " is not a file");
            }
            jars.add(extra);
        }
        return net.magicterra.stagewright.engine.ModInstall.install(gameDir, jars, log);
    }

    /**
     * Unpack the loader jar this CLI carries, into a temp file the installer can copy from.
     *
     * <p>Named without its version, as the build stages it, so nothing here has to know which
     * version it carries and an upgrade is a rebuild rather than an edit.
     */
    private static Path unpackFramework(String loader) {
        if (loader == null) {
            throw new IllegalArgumentException("cannot tell which loader this pack runs on, so there"
                    + " is no way to pick the right StageWright build — pass --loader"
                    + " neoforge|fabric to say so, --mod <jar> to name the build yourself, or"
                    + " --no-install if the pack already has it");
        }
        String name = net.magicterra.stagewright.engine.ModInstall.FRAMEWORK_PREFIX + loader + ".jar";
        String resource = RESOURCE_DIR + name;
        try (InputStream in = ModInstall.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("this CLI carries no StageWright build for '" + loader
                        + "' (looked for " + resource + ") — pass --mod <jar> to supply one, or"
                        + " --no-install if the pack already has it");
            }
            Path staged = Files.createTempDirectory("stagewright-install").resolve(name);
            staged.toFile().deleteOnExit();
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            return staged;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unpack " + resource, e);
        }
    }

    /** @see net.magicterra.stagewright.engine.ModInstall#frameworkPresent(Path) */
    static boolean frameworkPresent(Path gameDir) {
        return net.magicterra.stagewright.engine.ModInstall.frameworkPresent(gameDir);
    }
}
