package net.magicterra.stagewright.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Checks the required driver in the effective mod set before changing a world or downloading. */
final class DriverRequirement {
    private record Mod(String id, String version, String driverRange, String source) {}
    private DriverRequirement() {}

    static void check(Path gameDir, String loader, List<Path> extras, boolean bundledFramework) {
        ModInjection.requireLoader(loader);
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
                + loader + ", found " + frameworks.size());
        Mod framework = frameworks.get(0);
        if (framework.driverRange() == null) throw new IllegalArgumentException(framework.source()
                + " does not declare a required WorldDriver dependency; install the current StageWright");
        List<Mod> drivers = mods.stream().filter(m -> m.id().equals("worlddriver")).toList();
        if (drivers.size() != 1) throw new IllegalArgumentException("StageWright requires one compatible WorldDriver "
                + loader + " mod, found " + drivers.size() + ". Install it in " + dir + " or pass --mod <worlddriver.jar>");
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

    private static List<Mod> read(Path jar, String loader) {
        try (InputStream in = Files.newInputStream(jar)) { return metadata(in, loader, jar.toString()); }
        catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read mod metadata from " + jar + ": " + e.getMessage(), e);
        }
    }

    private static List<Mod> metadata(InputStream input, String loader, String source) throws IOException {
        String path = loader.equals("fabric") ? "fabric.mod.json" : "META-INF/neoforge.mods.toml";
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!entry.getName().equals(path)) continue;
                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (loader.equals("fabric")) {
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    String id = json.get("id").getAsString();
                    if (!id.equals("worlddriver") && !id.equals("mc_testkit")) return List.of();
                    JsonObject deps = json.getAsJsonObject("depends");
                    String range = deps != null && deps.has("worlddriver") ? deps.get("worlddriver").getAsString() : null;
                    return List.of(new Mod(id, json.get("version").getAsString(), range, source));
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
        }
        return List.of();
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
