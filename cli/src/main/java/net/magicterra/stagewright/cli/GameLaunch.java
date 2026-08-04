package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Works out how to start the server sitting in a pack's directory.
 *
 * <p>A modpack author does not have a launch command; they have a folder that an installer wrote.
 * Asking them to supply the command would push the one genuinely fiddly detail back onto the person
 * least equipped to know it — the NeoForge incantation is two {@code @argfile} references whose
 * paths carry a version number, and getting it wrong produces a JVM error rather than anything about
 * Minecraft. Detection is the product; {@code --launch} exists for the layouts detection has not
 * met yet.
 */
final class GameLaunch {

    private GameLaunch() {}

    /**
     * The command to start the server in {@code gameDir}, or null if the layout is unrecognised.
     *
     * <p>The java executable is index 0 so the caller can insert system properties immediately after
     * it — before any {@code @argfile}, which is where the JVM stops accepting them.
     */
    static List<String> detect(Path gameDir, String javaBinary) {
        List<String> forge = modLauncher(gameDir, javaBinary);
        if (forge != null) return forge;
        return fabricOrVanilla(gameDir, javaBinary);
    }

    /**
     * Which loader this pack runs on, or {@code null} when nothing here says.
     *
     * <p>Same evidence {@link #detect} launches from, read for its other meaning: the argument files
     * under {@code libraries/net/{neoforged,minecraftforge}} exist only because that installer ran,
     * and a top-level fabric/quilt jar likewise. Deliberately NOT inferred from what is already in
     * {@code mods/} — a pack mid-migration has both loaders' mods sitting there, and the answer has
     * to be what will actually boot.
     */
    static String loader(Path gameDir) {
        if (Files.isDirectory(gameDir.resolve("libraries/net/neoforged/neoforge"))) return "neoforge";
        if (Files.isDirectory(gameDir.resolve("libraries/net/minecraftforge/forge"))) return "forge";
        try (Stream<Path> top = Files.list(gameDir)) {
            boolean fabric = top.map(p -> p.getFileName().toString().toLowerCase())
                    .anyMatch(n -> n.endsWith(".jar") && !n.contains("installer")
                            && (n.contains("fabric") || n.contains("quilt")));
            if (fabric) return "fabric";
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + gameDir, e);
        }
        return null;
    }

    /**
     * NeoForge and Forge: the installer leaves per-version argument files under {@code libraries/}
     * and a {@code user_jvm_args.txt} beside the run scripts.
     *
     * <p>The platform suffix is not cosmetic — the two files differ in classpath separator, so
     * picking the wrong one yields a classpath the JVM reads as a single enormous entry and a
     * {@code ClassNotFoundException} for the launcher's main class.
     */
    private static List<String> modLauncher(Path gameDir, String javaBinary) {
        String argsName = System.getProperty("os.name", "").toLowerCase().startsWith("win")
                ? "win_args.txt" : "unix_args.txt";
        for (String vendor : new String[] {"net/neoforged/neoforge", "net/minecraftforge/forge"}) {
            Path base = gameDir.resolve("libraries").resolve(vendor);
            if (!Files.isDirectory(base)) continue;
            Path args = newestVersionFile(base, argsName);
            if (args == null) continue;

            List<String> command = new ArrayList<>();
            command.add(javaBinary);
            Path jvmArgs = gameDir.resolve("user_jvm_args.txt");
            if (Files.isRegularFile(jvmArgs)) command.add("@" + jvmArgs);
            command.add("@" + args);
            command.add("nogui");
            return command;
        }
        return null;
    }

    /**
     * Fabric, Quilt, or a plain vanilla server: one jar to hand to {@code -jar}.
     *
     * <p>Newest-first by modification time rather than by name, because a pack directory frequently
     * holds more than one — an author who upgraded loaders has both, and the old one still launches
     * a server that loads none of the pack's mods.
     */
    private static List<String> fabricOrVanilla(Path gameDir, String javaBinary) {
        Path jar = null;
        try (Stream<Path> top = Files.list(gameDir)) {
            jar = top.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".jar")
                                && (n.contains("fabric") || n.contains("quilt") || n.contains("server"))
                                && !n.contains("installer");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + gameDir, e);
        }
        if (jar == null) return null;
        return new ArrayList<>(List.of(javaBinary, "-jar", jar.toString(), "nogui"));
    }

    /** The named file under the newest-looking version directory of {@code base}. */
    private static Path newestVersionFile(Path base, String name) {
        try (Stream<Path> versions = Files.list(base)) {
            return versions.filter(Files::isDirectory)
                    .map(v -> v.resolve(name))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(p -> p.getParent().getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + base, e);
        }
    }
}
