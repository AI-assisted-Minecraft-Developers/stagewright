package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.magicterra.stagewright.engine.RunDirectory;

/**
 * The directory one scene's game runs in, derived from the topology's: {@code <dir>/stagewright/scenes/<scene>/}. What
 * the game or the scene may write is the scene's own, so two scenes of one topology can run at once and neither leaves
 * its settings, world or logs to the next.
 *
 * <ul>
 *   <li>Copied: the top level's files (but jars), and {@code config/} and {@code defaultconfigs/}, which loaders
 *       rewrite as they load; two games rewriting one shared file can corrupt it.
 *   <li>Left out: the world, {@code saves/}, {@code logs/}, {@code crash-reports/} and {@code stagewright/} itself. A
 *       topology that keeps its world between scenes links the world and {@code saves/} instead.
 *   <li>Linked: everything else — {@code mods/}, {@code libraries/}, loader caches. A modpack server's directory holds
 *       hundreds of megabytes there that no scene writes; a relative path in its launch arguments, such as
 *       {@code @libraries/…/unix_args.txt}, still resolves through the link.
 * </ul>
 */
final class SceneDirectory {

    static final String SCENES = "scenes";

    private static final Set<String> COPIED_DIRECTORIES = Set.of("config", "defaultconfigs");

    private static final Set<String> LEFT_OUT = Set.of("logs", "crash-reports", RunDirectory.ARTIFACT_DIR);

    private static final Set<String> WORLDS = Set.of("world", "saves");

    /**
     * What a passed scene does not keep of its artifacts: the command-line runner's world ({@code --universe
     * stagewright}), the worlds its rounds set aside, and its rewritten launch arguments.
     */
    private static final List<String> BULKY_ARTIFACTS = List.of("world", "saves", "scene-mods");

    private SceneDirectory() {}

    /** Where {@code scene}'s copy of {@code original} goes. */
    static Path of(Path original, String scene) {
        return original.resolve(RunDirectory.ARTIFACT_DIR).resolve(SCENES).resolve(scene);
    }

    /**
     * {@code scene}'s directory made afresh from {@code original}; what an earlier run of the scene left there is gone.
     * {@code world} is the first name of the world directory under {@code original} when it is not {@code world}, so it
     * is treated as one; {@code keepWorld} links the worlds instead of leaving them out.
     */
    static Path create(Path original, String scene, String world, boolean keepWorld) throws IOException {
        Path dir = of(original, scene);
        SceneMods.deleteTree(dir);
        Files.createDirectories(dir);
        if (keepWorld && world != null) {
            // Linked even before there is one, so the world the first scene makes is the one the next opens.
            Files.createDirectories(original.resolve(world));
        }
        if (!Files.isDirectory(original)) {
            return dir;
        }
        try (Stream<Path> entries = Files.list(original)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                Path target = dir.resolve(name);
                boolean isWorld = WORLDS.contains(name) || name.equals(world);
                if (LEFT_OUT.contains(name) || isWorld && !keepWorld) {
                    continue;
                }
                if (isWorld) {
                    link(target, entry);
                } else if (Files.isDirectory(entry)) {
                    if (COPIED_DIRECTORIES.contains(name)) {
                        copyTree(entry, target);
                    } else {
                        link(target, entry);
                    }
                } else if (name.endsWith(".jar")) {
                    link(target, entry);
                } else {
                    Files.copy(entry, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return dir;
    }

    /**
     * Make {@code file} under {@code dir} safe to write: every linked directory on the way to it becomes a real one
     * whose entries link to the shared ones, and a linked file itself is removed, so writing it changes this scene
     * only.
     */
    static void writable(Path dir, Path file) throws IOException {
        Path relative = dir.relativize(file.normalize());
        if (relative.startsWith("..")) {
            throw new IllegalArgumentException(file + " is not under " + dir);
        }
        Path at = dir;
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            at = at.resolve(relative.getName(i));
            if (Files.isSymbolicLink(at)) {
                Path shared = Files.readSymbolicLink(at);
                Files.delete(at);
                Files.createDirectories(at);
                if (Files.isDirectory(shared)) {
                    try (Stream<Path> entries = Files.list(shared)) {
                        for (Path entry : entries.toList()) {
                            link(at.resolve(entry.getFileName().toString()), entry);
                        }
                    }
                }
            } else if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
        }
        if (Files.isSymbolicLink(file)) {
            Files.delete(file);
        }
    }

    /**
     * After the scene: a scene that passed keeps only its artifacts, {@code stagewright/}; one that failed keeps
     * everything, world and settings, until it next runs, to be looked into.
     */
    static void finish(Path dir, boolean passed) throws IOException {
        if (!passed || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                if (!RunDirectory.ARTIFACT_DIR.equals(entry.getFileName().toString())) {
                    SceneMods.deleteTree(entry);
                }
            }
        }
        for (String bulky : BULKY_ARTIFACTS) {
            SceneMods.deleteTree(dir.resolve(RunDirectory.ARTIFACT_DIR).resolve(bulky));
        }
    }

    private static void link(Path link, Path target) throws IOException {
        Files.createSymbolicLink(link, target.toAbsolutePath());
    }

    private static void copyTree(Path link, Path to) throws IOException {
        // Through a link to the directory itself, as the command-line runner's client directory has for config/: a walk
        // started on the link would see the link alone.
        Path from = link.toRealPath();
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
