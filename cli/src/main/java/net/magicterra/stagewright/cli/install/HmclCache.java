package net.magicterra.stagewright.cli.install;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * HMCL's content-addressed download cache: {@code <install>/cache/SHA-1/<first two>/<sha1>}.
 *
 * <p>Read and added to, never indexed. HMCL keeps two index files beside this directory —
 * {@code index.json} (libraries without a checksum, by name) and {@code etag.json} (remote metadata) —
 * and saves the first without a file lock, so writing it while HMCL is open could lose HMCL's own
 * entries. Nothing here needs either: every file this installer fetches has a known sha1, and HMCL
 * looks a library up by sha1 in this directory before it consults its index.
 */
final class HmclCache {

    private final Path root;

    HmclCache(Path installDir) {
        this.root = installDir.resolve("cache").resolve("SHA-1");
    }

    Path file(String sha1) {
        String hash = sha1.toLowerCase(Locale.ROOT);
        return root.resolve(hash.substring(0, 2)).resolve(hash);
    }

    /** Put the cached copy of {@code sha1} at {@code target}; false when the cache has no good copy. */
    boolean placeInto(String sha1, long size, Path target) throws IOException {
        Path cached = file(sha1);
        // Verified, not trusted: another launcher wrote it, and a truncated file here would otherwise
        // be linked into every install that asks for it.
        if (!Sha1.matches(cached, sha1, size)) return false;
        place(cached, target);
        return true;
    }

    /** Offer a verified file to the cache. Content-addressed, so an existing entry is already this file. */
    void store(Path verified, String sha1) throws IOException {
        Path cached = file(sha1);
        if (Files.isRegularFile(cached)) return;
        place(verified, cached);
    }

    /**
     * Make {@code to} a copy of {@code from}: a hard link where the filesystem allows one, the way HMCL
     * shares its cache, else a real copy. Written beside {@code to} and renamed over it, so a reader
     * never sees half a file.
     */
    static void place(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Path temp = to.resolveSibling(to.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            try {
                Files.createLink(temp, from);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            moveOver(temp, to);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void moveOver(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
