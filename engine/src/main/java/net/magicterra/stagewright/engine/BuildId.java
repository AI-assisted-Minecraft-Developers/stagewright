package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Names the code a run tested, so results from different code are never reconciled as one suite.
 * Taken by the launcher from the source, not by the game from its jars: Fabric and NeoForge runs of
 * one source are reconciled together, and their jars never match.
 */
public final class BuildId {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private BuildId() {}

    /** {@code git:<HEAD>}, plus {@code +<digest>} when tracked files differ from HEAD; null when git
     *  tracks nothing under {@code dir}, has no commit yet, or fails. Untracked files are left out: a
     *  run directory nobody ignored fills up while the suite runs, and topologies would disagree. */
    public static String ofGitWorkTree(Path dir) {
        byte[] head = git(dir, "rev-parse", "HEAD");
        if (head == null) return null;
        // A project inside some other repository that ignores it would otherwise take that
        // repository's HEAD, which never changes as the project does.
        if (git(dir, "ls-files", "--error-unmatch", "--", ".") == null) return null;
        // Every flag pins something user config or a submodule could otherwise hide, making a real
        // change read as clean: a diff driver, a relative diff, a submodule collapsed to "-dirty".
        byte[] diff = git(dir, "-c", "diff.relative=false", "diff", "HEAD", "--binary", "--no-ext-diff",
                "--no-textconv", "--no-color", "--submodule=diff", "--ignore-submodules=untracked");
        if (diff == null) return null;
        String id = "git:" + new String(head, StandardCharsets.UTF_8).trim().substring(0, 12);
        return diff.length == 0 ? id : id + "+" + hex(digest().digest(diff));
    }

    /** Stdout of a git command that exited 0, or null for any failure, including git being absent. */
    private static byte[] git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        return run(dir, GIT_TIMEOUT, command);
    }

    /**
     * Stdout of {@code command} if it exits 0 within {@code timeout}, else null. Stdout goes to a file
     * rather than a pipe: reading a pipe to its end blocks for as long as the process runs, so the
     * timeout would only start counting after a hung command had already been waited out.
     */
    static byte[] run(Path dir, Duration timeout, List<String> command) {
        Path out = null;
        try {
            out = Files.createTempFile("stagewright-build-id", ".out");
            Process p = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(out.toFile()).start();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? Files.readAllBytes(out) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a leftover temp file is not worth failing the run over
                }
            }
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM ships SHA-256", e);
        }
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest).substring(0, 12);
    }
}
