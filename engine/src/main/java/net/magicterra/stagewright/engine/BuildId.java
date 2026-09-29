package net.magicterra.stagewright.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Names the code a run tested, so results from different code are never reconciled as one suite.
 * Taken by the launcher from the source, not by the game from its jars: Fabric and NeoForge runs of
 * one source are reconciled together, and their jars never match.
 */
public final class BuildId {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    /** Pins what config would otherwise change in the bytes a diff prints for one change, so one tree
     *  would get another id on another machine. Flags where git has them, since only a flag also
     *  overrides a diff driver's own {@code algorithm}; the order file is added per call. */
    private static final List<String> SAME_BYTES_FOR_THE_SAME_CHANGE = List.of(
            "--src-prefix=a/", "--dst-prefix=b/", "-U3", "--inter-hunk-context=0",
            "--diff-algorithm=myers", "--indent-heuristic", "--no-renames", "--full-index");

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
        Path noOrder = null;
        Path index = null;
        try {
            // An empty file rather than /dev/null, which is not a path on every platform.
            noOrder = Files.createTempFile("stagewright-build-id", ".order");
            // Every flag pins something user config or a submodule could otherwise hide, making a real
            // change read as clean: a diff driver, a relative diff, a submodule collapsed to "-dirty".
            // No --binary: its patch is as zlib compresses it; --full-index names a binary's content.
            List<String> diff = new ArrayList<>(List.of("git", "-c", "diff.relative=false",
                    "-c", "diff.suppressBlankEmpty=false", "-c", "core.quotePath=true",
                    "-c", "core.bigFileThreshold=512m", "diff", "HEAD",
                    "--no-ext-diff", "--no-textconv", "--no-color", "--submodule=diff",
                    "--ignore-submodules=untracked", "-O" + noOrder));
            diff.addAll(SAME_BYTES_FOR_THE_SAME_CHANGE);
            index = unmarkedIndex(dir);
            if (index == null) return null;
            Map<String, String> env = index.equals(NO_MARKS) ? Map.of()
                    : Map.of("GIT_INDEX_FILE", index.toAbsolutePath().toString());
            MessageDigest digest = digest();
            // Not held in memory, since a diff can be larger than the heap: git writes it to a
            // temporary file, which is hashed a chunk at a time.
            Long length = run(dir, GIT_TIMEOUT, diff, env, null, hashingInto(digest));
            if (length == null) return null;
            String id = "git:" + new String(head, StandardCharsets.UTF_8).trim().substring(0, 12);
            return length == 0 ? id : id + "+" + hex(digest.digest());
        } catch (IOException e) {
            return null;
        } finally {
            deleteQuietly(noOrder);
            if (index != null && !index.equals(NO_MARKS)) deleteIndex(index);
        }
    }

    /** What {@link #unmarkedIndex} answers when no entry is marked, so the repository's own index serves. */
    private static final Path NO_MARKS = Path.of("");

    /**
     * A copy of the index with the {@code assume-unchanged} and {@code skip-worktree} marks taken off,
     * or {@link #NO_MARKS} when nothing is marked; null when git fails. {@code git diff} takes a marked
     * file at its index content, so an edit to one would read as clean; unmarked, it is compared the
     * way every other file is, line endings, mode bits and symlinks included. A skip-worktree file a
     * sparse checkout left out keeps its mark: leaving it out is what sparse checkout is for.
     */
    private static Path unmarkedIndex(Path dir) throws IOException {
        byte[] top = git(dir, "rev-parse", "--show-toplevel");
        Path root = top == null ? null : resolved(dir, new String(top, StandardCharsets.UTF_8).trim());
        if (root == null) return null;
        // "<tag> <path>" for every index entry, the path as git stores it.
        byte[] listed = git(root, "ls-files", "-v", "-z");
        if (listed == null) return null;
        Boolean sparse = null;
        ByteArrayOutputStream unmark = new ByteArrayOutputStream();
        int from = 0;
        for (int end; from < listed.length && (end = indexOf(listed, (byte) 0, from)) >= 0; from = end + 1) {
            // S is skip-worktree, lower case assume-unchanged, and s both.
            char tag = (char) listed[from];
            boolean skipWorktree = Character.toUpperCase(tag) == 'S';
            if (!skipWorktree && !Character.isLowerCase(tag)) continue;
            byte[] path = Arrays.copyOfRange(listed, from + 2, end);
            if (skipWorktree) {
                if (sparse == null) {
                    byte[] set = git(root, "config", "--bool", "core.sparseCheckout");
                    sparse = set != null && new String(set, StandardCharsets.UTF_8).trim().equals("true");
                }
                // Only a name that decodes can be looked for; one that does not is taken as present,
                // so at worst a left-out file reads as deleted rather than an edit as clean.
                Path file = sparse ? resolved(root, new String(path, StandardCharsets.UTF_8)) : null;
                if (file != null && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            }
            unmark.write(path);
            unmark.write(0);
        }
        if (unmark.size() == 0) return NO_MARKS;
        byte[] gitIndex = git(root, "rev-parse", "--git-path", "index");
        Path real = gitIndex == null ? null : resolved(root, new String(gitIndex, StandardCharsets.UTF_8).trim());
        if (real == null) return null;
        Path copy = Files.createTempFile("stagewright-build-id", ".index");
        try {
            Files.copy(real, copy, StandardCopyOption.REPLACE_EXISTING);
            // NUL-separated on stdin: names reach git as it stored them, with no quoting and no length
            // limit. One mark per call: given both, update-index applies only the last.
            for (String mark : List.of("--no-assume-unchanged", "--no-skip-worktree")) {
                if (run(root, GIT_TIMEOUT, List.of("git", "update-index", mark, "-z", "--stdin"),
                        Map.of("GIT_INDEX_FILE", copy.toAbsolutePath().toString()),
                        unmark.toByteArray(), InputStream::readAllBytes) == null) {
                    deleteIndex(copy);
                    return null;
                }
            }
            return copy;
        } catch (IOException e) {
            deleteIndex(copy);
            throw e;
        }
    }

    /** A temporary index, and the lock an update-index killed at the timeout leaves beside it. */
    private static void deleteIndex(Path index) {
        deleteQuietly(index);
        deleteQuietly(index.resolveSibling(index.getFileName() + ".lock"));
    }

    private static int indexOf(byte[] bytes, byte b, int from) {
        for (int i = from; i < bytes.length; i++) if (bytes[i] == b) return i;
        return -1;
    }

    /** {@code path} under {@code root}, or null for a name that was not UTF-8 or the file system cannot
     *  hold: what is there cannot be read, and thrown, it would fail the run task. */
    static Path resolved(Path root, String path) {
        // What decoding git's bytes left where they were not UTF-8; resolved, it names another file.
        if (path.indexOf('\uFFFD') >= 0) return null;
        try {
            return root.resolve(path);
        } catch (InvalidPathException e) {
            return null;
        }
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
        return run(dir, timeout, command, InputStream::readAllBytes);
    }

    /** Feeds stdout to {@code digest} a chunk at a time and answers how many bytes there were. */
    static Output<Long> hashingInto(MessageDigest digest) {
        return stdout -> {
            long length = 0;
            byte[] chunk = new byte[64 * 1024];
            for (int n; (n = stdout.read(chunk)) > 0; length += n) digest.update(chunk, 0, n);
            return length;
        };
    }

    /** What to make of a command's stdout, read from the start. */
    @FunctionalInterface
    interface Output<T> {
        T read(InputStream stdout) throws IOException;
    }

    /** {@link #run} handing stdout to {@code output} rather than reading it whole. */
    static <T> T run(Path dir, Duration timeout, List<String> command, Output<T> output) {
        return run(dir, timeout, command, Map.of(), null, output);
    }

    /** {@link #run} with {@code env} added to the environment and {@code stdin}, when not null, as input. */
    static <T> T run(Path dir, Duration timeout, List<String> command, Map<String, String> env, byte[] stdin,
                     Output<T> output) {
        Path out = null;
        Path in = null;
        try {
            out = Files.createTempFile("stagewright-build-id", ".out");
            ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(out.toFile());
            builder.environment().putAll(env);
            if (stdin != null) {
                // From a file, like stdout, so a command that stops reading cannot block the write.
                in = Files.write(Files.createTempFile("stagewright-build-id", ".in"), stdin);
                builder.redirectInput(in.toFile());
            }
            Process p = builder.start();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            try (InputStream stdout = Files.newInputStream(out)) {
                return output.read(stdout);
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            deleteQuietly(out);
            deleteQuietly(in);
        }
    }

    private static void deleteQuietly(Path temporary) {
        if (temporary == null) return;
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // a leftover temp file is not worth failing the run over
        }
    }

    static MessageDigest digest() {
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
