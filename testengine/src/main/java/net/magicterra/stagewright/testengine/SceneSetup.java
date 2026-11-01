package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** What a scene's declaration puts into the game's directory before it starts: a save to begin from, and files. */
final class SceneSetup {

    private SceneSetup() {}

    /**
     * Put the save {@code from} names where the game looks for its world. A zip is unpacked from the folder holding its
     * {@code level.dat}, so a zip of the world folder and one of its contents both do; a directory on the class path is
     * copied.
     */
    static void installSave(String from, ClassLoader loader, Path world) throws IOException {
        String resource = SceneFile.ROOT + from;
        URL url = loader.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("world.from: no " + resource + " on the test class path");
        }
        Path directory = asDirectory(url);
        if (directory != null) {
            copyTree(directory, world);
        } else {
            unzip(url, resource, world);
        }
        if (!Files.isRegularFile(world.resolve("level.dat"))) {
            throw new IllegalStateException("world.from: " + resource + " holds no level.dat, so it is not a save");
        }
    }

    private static Path asDirectory(URL url) {
        if (!"file".equals(url.getProtocol())) {
            return null;
        }
        try {
            Path p = Path.of(url.toURI());
            return Files.isDirectory(p) ? p : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static void unzip(URL url, String resource, Path world) throws IOException {
        // The folder holding the shallowest level.dat is the world; read once to find it, once to unpack.
        String prefix = null;
        try (ZipInputStream zip = new ZipInputStream(url.openStream())) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                String name = e.getName();
                if (!e.isDirectory() && ("level.dat".equals(name) || name.endsWith("/level.dat"))) {
                    String folder = name.substring(0, name.length() - "level.dat".length());
                    if (prefix == null || folder.length() < prefix.length()) {
                        prefix = folder;
                    }
                }
            }
        }
        if (prefix == null) {
            throw new IllegalStateException(
                    "world.from: " + resource + " holds no level.dat, so it is" + " not a save");
        }
        Path root = world.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(url.openStream())) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (!e.getName().startsWith(prefix) || e.getName().length() == prefix.length()) {
                    continue;
                }
                Path target =
                        root.resolve(e.getName().substring(prefix.length())).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalStateException(
                            "world.from: " + resource + " has an entry outside its world: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    /**
     * Write {@code files} under {@code directory}, a scene's own ({@link SceneDirectory}): nothing is put back after,
     * since the next scene starts from a directory of its own. A file under a directory shared by link is written into
     * a copy of the way to it, never into the shared one.
     */
    static void writeFiles(Path directory, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = directory.resolve(file.getKey()).normalize();
            SceneDirectory.writable(directory, target);
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
    }
}
