package net.magicterra.stagewright.cli.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Sha1 {

    private Sha1() {}

    static String of(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String of(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /** Whether {@code file} exists and is what {@code sha1}/{@code size} say; either may be unknown. */
    static boolean matches(Path file, String sha1, long size) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        if (size >= 0 && Files.size(file) != size) return false;
        return sha1 == null || sha1.equalsIgnoreCase(of(file));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-1", e);
        }
    }
}
