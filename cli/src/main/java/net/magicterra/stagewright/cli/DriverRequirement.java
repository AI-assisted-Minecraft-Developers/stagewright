package net.magicterra.stagewright.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Checks the required driver in the effective mod set before changing a world or downloading. */
final class DriverRequirement {
    private record Mod(String id, String version, String driverRange, String source) {}
    private static final String FABRIC_METADATA = "fabric.mod.json";
    private static final String NEOFORGE_METADATA = "META-INF/neoforge.mods.toml";
    private DriverRequirement() {}

    /**
     * A null {@code loader} is a pack whose loader could not be told, which only --no-install lets
     * through: each jar is then read by whichever metadata file it carries. A loader that is told
     * and has no StageWright build is refused either way.
     */
    static void check(Path gameDir, String loader, List<Path> extras, boolean bundledFramework) {
        if (loader != null || bundledFramework) ModInjection.requireLoader(loader);
        String loaderName = loader == null ? "this pack's loader" : loader;
        List<Mod> mods = new ArrayList<>();
        Path dir = gameDir.resolve("mods");
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path jar : files.filter(DriverRequirement::isJar).sorted().toList()) {
                    mods.addAll(read(jar, loader));
                }
            } catch (IOException e) { throw new UncheckedIOException("cannot inspect " + dir, e); }
        }
        for (Path extra : extras) {
            if (!Files.isRegularFile(extra)) throw new IllegalArgumentException("--mod " + extra + " is not a file");
            if (!Files.exists(dir.resolve(extra.getFileName()))) mods.addAll(read(extra, loader));
        }
        List<Mod> frameworks = mods.stream().filter(m -> m.id().equals("mc_testkit")).toList();
        if (frameworks.isEmpty() && bundledFramework) {
            String resource = "/mods/mc_stagewright-" + loader + ".jar";
            try (InputStream in = DriverRequirement.class.getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("this CLI carries no " + resource);
                frameworks = metadata(in, loader, resource).stream().filter(m -> m.id().equals("mc_testkit")).toList();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        if (frameworks.size() != 1) throw new IllegalArgumentException("expected one StageWright mod for "
                + loaderName + ", found " + frameworks.size());
        Mod framework = frameworks.get(0);
        if (framework.driverRange() == null) throw new IllegalArgumentException(framework.source()
                + " does not declare a required WorldDriver dependency; install the current StageWright");
        List<Mod> drivers = mods.stream().filter(m -> m.id().equals("worlddriver")).toList();
        if (drivers.size() != 1) throw new IllegalArgumentException("StageWright requires one compatible WorldDriver mod for "
                + loaderName + ", found " + drivers.size() + ". Install it in " + dir + " or pass --mod <worlddriver.jar>");
        Mod driver = drivers.get(0);
        if (!accepts(framework.driverRange(), driver.version())) throw new IllegalArgumentException(
                "WorldDriver " + driver.version() + " in " + driver.source() + " is incompatible with "
                + framework.source() + ": requires " + framework.driverRange());
    }

    static boolean isFramework(Path jar, String loader) {
        return read(jar, loader).stream().anyMatch(m -> m.id().equals("mc_testkit"));
    }

    static List<Path> frameworkJars(Path gameDir, String loader) {
        Path dir = gameDir.resolve("mods");
        if (!Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.filter(DriverRequirement::isJar).filter(p -> isFramework(p, loader)).sorted().toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
    }

    /**
     * By entry name through the central directory: a pack's jars need not be stream-readable. A
     * file that is no zip at all is not one of our two mods, so the loader judges it, not this.
     *
     * <p>A run asks about the same jars several times (preflight, staging, which jars are
     * StageWright), so each answer is kept for as long as the file's size and mtime are unchanged.
     */
    private static List<Mod> read(Path jar, String loader) {
        Read key;
        try {
            key = new Read(jar.toAbsolutePath().normalize(), Files.size(jar),
                    Files.getLastModifiedTime(jar).toMillis(), loader);
        } catch (IOException e) { throw new UncheckedIOException("cannot open " + jar, e); }
        return READS.computeIfAbsent(key, k -> open(jar, loader));
    }

    private record Read(Path jar, long size, long modified, String loader) {}
    private static final Map<Read, List<Mod>> READS = new ConcurrentHashMap<>();

    private static List<Mod> open(Path jar, String loader) {
        ZipFile opened;
        try { opened = new ZipFile(jar.toFile()); }
        catch (ZipException notAZip) { return List.of(); }
        catch (IOException e) { throw new UncheckedIOException("cannot open " + jar, e); }
        try (ZipFile zip = opened) {
            for (String path : metadataPaths(loader)) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    return parse(path, new String(in.readAllBytes(), StandardCharsets.UTF_8), jar.toString());
                }
            }
            return List.of();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read mod metadata from " + jar + ": " + e.getMessage(), e);
        }
    }

    /** The CLI's own bundled jar, which is only reachable as a stream. */
    private static List<Mod> metadata(InputStream input, String loader, String source) throws IOException {
        List<String> paths = metadataPaths(loader);
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!paths.contains(entry.getName())) continue;
                return parse(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8), source);
            }
        }
        return List.of();
    }

    private static List<String> metadataPaths(String loader) {
        if (loader == null) return List.of(FABRIC_METADATA, NEOFORGE_METADATA);
        return List.of(loader.equals("fabric") ? FABRIC_METADATA : NEOFORGE_METADATA);
    }

    /**
     * Only our two mods are read; metadata this cannot read as one of them, in whatever shape, is
     * that mod's loader's business. A missing field of ours reads as null and is refused by name.
     */
    private static List<Mod> parse(String path, String text, String source) {
        if (path.equals(FABRIC_METADATA)) {
            JsonElement root;
            try { root = JsonParser.parseString(text.startsWith("﻿") ? text.substring(1) : text); }
            catch (JsonParseException unreadable) { return List.of(); }
            if (!root.isJsonObject()) return List.of();
            JsonObject json = root.getAsJsonObject();
            String id = string(json, "id");
            if (!"worlddriver".equals(id) && !"mc_testkit".equals(id)) return List.of();
            String range = json.get("depends") instanceof JsonObject deps ? string(deps, "worlddriver") : null;
            return List.of(new Mod(id, string(json, "version"), range, source));
        }
        List<Mod> result = new ArrayList<>();
        for (String block : text.split("(?m)^\\s*\\[\\[")) {
            if (!block.startsWith("mods]]")) continue;
            String id = value(block, "modId");
            if (!"worlddriver".equals(id) && !"mc_testkit".equals(id)) continue;
            String range = null;
            for (String dep : text.split("(?m)^\\s*\\[\\[")) {
                if (dep.startsWith("dependencies." + id + "]]" )
                        && "worlddriver".equals(value(dep, "modId"))
                        && "required".equals(value(dep, "type"))) range = value(dep, "versionRange");
            }
            result.add(new Mod(id, value(block, "version"), range, source));
        }
        return result;
    }

    private static String string(JsonObject json, String key) {
        return json.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static String value(String block, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]+)\"").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    /** Release-line ranges generated by StageWright's build; arbitrary predicates are refused. */
    static boolean accepts(String range, String version) {
        Matcher bounds = Pattern.compile("(?:>=|\\[)([0-9.]+)-0(?: <|,)([0-9.]+)-0\\)?").matcher(range);
        if (!bounds.matches()) throw new IllegalArgumentException("unsupported WorldDriver release range: " + range);
        Matcher actual = Pattern.compile("([0-9]+\\.[0-9]+\\.[0-9]+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?").matcher(version == null ? "" : version);
        if (!actual.matches()) return false;
        // StageWright candidates use numbered or local build releases. Leave other prerelease
        // ordering to the loader by refusing unsupported candidates before starting the game.
        String prerelease = actual.group(2);
        if (prerelease != null && !prerelease.equals("0") && !prerelease.startsWith("build.")) return false;
        return compare(actual.group(1), bounds.group(1)) >= 0 && compare(actual.group(1), bounds.group(2)) < 0;
    }

    private static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (c != 0) return c;
        }
        return 0;
    }
}
