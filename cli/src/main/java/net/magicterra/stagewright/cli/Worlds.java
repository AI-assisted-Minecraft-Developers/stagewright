package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Which world a run plays in, and what happens to it afterwards.
 *
 * <p>Nothing is deleted that this run did not decide to delete. A client gets a world of its own,
 * created for the run and removed after a green one — the game directory may be a player's
 * {@code .minecraft}, and every other save in it is theirs. A dedicated server's world is reset or kept
 * only when the command line says which: a world already on disk and no word about it is a refusal,
 * because both guesses are wrong somewhere — resetting destroys a world someone meant to keep, and
 * keeping one silently makes scenes fail on what the last run left standing.
 */
final class Worlds {

    enum ServerWorld { RESET, KEEP }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Worlds() {}

    static ServerWorld parse(String value) {
        if (value == null) return null;
        return switch (value) {
            case "reset" -> ServerWorld.RESET;
            case "keep" -> ServerWorld.KEEP;
            default -> throw new IllegalArgumentException("--world takes reset or keep, not '" + value + "'");
        };
    }

    static void prepareServer(Path gameDir, ServerWorld policy, Consumer<String> log) {
        Path world = gameDir.resolve(levelName(gameDir));
        boolean exists = Files.isRegularFile(world.resolve("level.dat"));
        if (policy == null) {
            if (!exists) return;
            throw new EnvFailure(world + " already exists. Say what to do with it: --world reset"
                    + " deletes it and lets the server generate a fresh one, --world keep runs the"
                    + " scenes in it as it is.");
        }
        if (policy == ServerWorld.RESET && Files.exists(world)) {
            deleteTree(world);
            log.accept("reset the world at " + world);
        }
    }

    /** A new world for this client run, named so it cannot be one of the player's own. */
    static String newClientWorld() {
        return "stagewright-" + LocalDateTime.now().format(STAMP);
    }

    /** A green run's world is removed; any other is left where it is, for the reader. */
    static void finishClient(Path gameDir, String world, boolean green, Consumer<String> log) {
        Path save = gameDir.resolve("saves").resolve(world);
        if (!Files.exists(save)) return;
        if (green) {
            deleteTree(save);
        } else {
            log.accept("the run's world is kept for you to look at: " + save);
        }
    }

    /** {@code level-name} from {@code server.properties}, as the server itself reads it. */
    static String levelName(Path gameDir) {
        Path properties = gameDir.resolve("server.properties");
        if (Files.isRegularFile(properties)) {
            try {
                for (String line : Files.readAllLines(properties, StandardCharsets.ISO_8859_1)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("level-name=")) {
                        String value = trimmed.substring("level-name=".length()).trim();
                        if (!value.isEmpty()) return value;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + properties, e);
            }
        }
        return "world";
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + root, e);
        }
    }
}
