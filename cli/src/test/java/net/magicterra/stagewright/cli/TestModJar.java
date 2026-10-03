package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class TestModJar {
    private TestModJar() {}

    static Path driver(Path jar, String marker) throws IOException {
        return create(jar, "worlddriver", "0.1.0-build.1+1.21.1", marker);
    }

    static Path create(Path jar, String id, String version, String marker) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(out, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + id
                    + "\",\"version\":\"" + version + "\",\"depends\":{\"worlddriver\":\">=0.1.0-0 <0.2.0-0\"}}");
            entry(out, "META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"" + id
                    + "\"\nversion=\"" + version + "\"\n[[dependencies." + id
                    + "]]\nmodId=\"worlddriver\"\ntype=\"required\"\nversionRange=\"[0.1.0-0,0.2.0-0)\"\n");
            entry(out, "marker.txt", marker);
        }
        return jar;
    }

    /** A jar holding only {@code fabric.mod.json}, optionally as a stored entry whose local header
     *  claims a data descriptor — readable through the central directory only. */
    static Path withMetadata(Path jar, String fabricModJson, boolean storedWithDescriptor) throws IOException {
        Files.createDirectories(jar.getParent());
        byte[] text = fabricModJson.getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            ZipEntry entry = new ZipEntry("fabric.mod.json");
            if (storedWithDescriptor) {
                CRC32 crc = new CRC32();
                crc.update(text);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(text.length);
                entry.setCrc(crc.getValue());
            }
            out.putNextEntry(entry);
            out.write(text);
            out.closeEntry();
        }
        if (storedWithDescriptor) {
            byte[] bytes = Files.readAllBytes(jar);
            bytes[6] |= 0x08; // the first local header's general-purpose flag, bit 3
            Files.write(jar, bytes);
        }
        return jar;
    }

    static String marker(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("marker.txt")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void entry(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
