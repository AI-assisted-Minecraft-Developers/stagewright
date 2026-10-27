package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.gradle.api.GradleException;
import org.gradle.api.Project;

/**
 * Drops loom's remapped copies of StageWright and WorldDriver that are older than what was last published to
 * mavenLocal. Loom keys a remapped mod on its coordinates, not its bytes, so a snapshot republished under the same
 * version resolves, finds an output already at that key, and the game runs the build before, with no error.
 *
 * <p>Both of loom's staging areas, since it restores the first from the second: {@code .gradle/loom-cache} and
 * {@code build/loom-cache} of every project. Per module, so republishing one does not evict the others. Self-disarming:
 * after one remap every output is newer than its source. Only a derived file loom rebuilds is ever deleted.
 */
final class StaleRemaps {

    private static final String GROUP_PATH = "net/magicterra";

    private StaleRemaps() {}

    static void evict(Project root) {
        Map<String, Long> published = published(new File(System.getProperty("user.home"), ".m2/repository/" + GROUP_PATH));
        if (published.isEmpty()) return;
        // Longest name first: `stagewright-neoforge` is a suffix of `mc_stagewright-neoforge`.
        List<String> modules = published.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        List<File> held = new ArrayList<>();
        for (Project p : root.getAllprojects()) {
            for (String cache : new String[] {".gradle/loom-cache", "build/loom-cache"}) {
                for (File stale : jars(new File(p.getProjectDir(), cache))) {
                    String path = stale.getAbsolutePath().replace('\\', '/');
                    if (!path.contains(GROUP_PATH) && !stale.getName().contains("net.magicterra")) continue;
                    String module = modules.stream().filter(stale.getName()::contains).findFirst().orElse(null);
                    if (module == null || stale.lastModified() >= published.get(module)) continue;
                    root.getLogger().lifecycle("[stagewright] dropping a {} remap older than its publish: {}", module,
                            stale.getName());
                    // Checked: a delete that quietly fails logs the fix and runs the stale code anyway. A daemon that
                    // still has the jar open from the build that remapped it cannot unlink it on Windows.
                    if (!stale.delete()) held.add(stale);
                }
            }
        }
        if (!held.isEmpty()) {
            throw new GradleException("StageWright or WorldDriver was republished after loom remapped it, and this"
                    + " Gradle daemon still has the old remap open:\n  "
                    + String.join("\n  ", held.stream().map(File::getName).toList())
                    + "\n\nRun `./gradlew --stop` and build again; otherwise the game runs the build before.");
        }
    }

    /** Module directory name to the newest jar published under it. */
    private static Map<String, Long> published(File groupDir) {
        Map<String, Long> newest = new HashMap<>();
        File[] modules = groupDir.listFiles(File::isDirectory);
        if (modules == null) return newest;
        for (File module : modules) {
            long at = jars(module).stream().mapToLong(File::lastModified).max().orElse(0L);
            if (at > 0L) newest.put(module.getName(), at);
        }
        return newest;
    }

    private static List<File> jars(File dir) {
        if (!dir.isDirectory()) return List.of();
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.filter(f -> f.toString().endsWith(".jar") && Files.isRegularFile(f)).map(Path::toFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }
}
