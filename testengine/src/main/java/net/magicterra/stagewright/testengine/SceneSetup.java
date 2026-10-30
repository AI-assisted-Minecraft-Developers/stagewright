package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
                if (!e.isDirectory() && (name.equals("level.dat") || name.endsWith("/level.dat"))) {
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
     * Write {@code files} under {@code directory}, and answer what puts every one back as it was: the old text, or no
     * file. A scene's files are never the next scene's.
     */
    static Restore writeFiles(Path directory, Map<String, String> files) throws IOException {
        Map<Path, byte[]> before = new LinkedHashMap<>();
        List<Path> createdDirs = new ArrayList<>();
        Restore restore = () -> {
            for (Map.Entry<Path, byte[]> e : before.entrySet()) {
                if (e.getValue() != null) {
                    Files.write(e.getKey(), e.getValue());
                } else {
                    Files.deleteIfExists(e.getKey());
                }
            }
            // Deepest first, so a parent is empty by the time it is reached; one something else wrote into is left.
            List<Path> dirs = new ArrayList<>(createdDirs);
            dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> left = Files.list(dir)) {
                    if (left.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                }
            }
        };
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = directory.resolve(file.getKey()).normalize();
                before.put(target, Files.isRegularFile(target) ? Files.readAllBytes(target) : null);
                for (Path d = target.getParent(); d != null && !Files.exists(d); d = d.getParent()) {
                    createdDirs.add(d);
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            restore.run();
            throw e;
        }
        return restore;
    }

    /** Puts back what {@link #writeFiles} changed. */
    @FunctionalInterface
    interface Restore {
        void run() throws IOException;
    }
}
