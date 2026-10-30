package net.magicterra.stagewright.testengine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code mods.exclude}: a game started without some of its mods, with the pack's {@code mods/} and the build's class
 * path left as they are. A mod is found in the game's {@code mods/} folder or on its class path, and named by its id or
 * by a pattern on its jar's file name.
 *
 * <p>Fabric disables mods by id itself ({@code fabric.debug.disableModIds}), wherever they come from. NeoForge has no
 * such switch, so the game is given what it would have had without them: argument files with the jars taken out of
 * every path list, and, when one sits in {@code mods/}, a game directory of its own in which every entry links to the
 * real one but {@code mods/}, which links only the jars kept.
 */
final class SceneMods {

    /** The folder of mod jars inside a game directory. */
    private static final String MODS = "mods";

    private static final Pattern PATH_LIST = Pattern.compile("[^\\s\"']+");
    private static final Pattern MOD_ID_TOML = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MOD_ID_JSON = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private SceneMods() {}

    /**
     * The process to start instead, what was left out, and the names that matched no mod of the game, for the report.
     */
    record Applied(LaunchSpec.Process process, List<String> excluded, List<String> unmatched) {}

    /** A jar that is a mod, and the ids it declares. */
    record Mod(Path jar, Set<String> ids, boolean fabric) {}

    /**
     * {@code process} without the mods {@code exclude} names, its scratch files under {@code scratch}. A name that
     * matches no mod of the game is not an error — one scene may start a server and a client with different mods, and
     * exclude from both what only one has — but it is answered in {@link Applied#unmatched}, for the report.
     */
    static Applied apply(LaunchSpec.Process process, List<String> exclude, Path scratch) throws IOException {
        Path gameDir = gameDirectory(process);
        List<Path> modsFolder = jars(gameDir.resolve(MODS));
        Set<Path> classPath = classPathJars(process);
        List<Mod> mods = new ArrayList<>();
        for (Path jar : modsFolder) {
            mods.add(read(jar));
        }
        for (Path jar : classPath) {
            if (!modsFolder.contains(jar)) {
                mods.add(read(jar));
            }
        }
        mods.removeIf(m -> m.ids().isEmpty());

        List<Mod> excluded = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (String name : exclude) {
            PathMatcher glob = FileSystems.getDefault().getPathMatcher("glob:" + name);
            List<Mod> matched = mods.stream()
                    .filter(m -> m.ids().contains(name) || glob.matches(m.jar().getFileName()))
                    .toList();
            if (matched.isEmpty()) {
                unmatched.add(name);
            }
            for (Mod m : matched) {
                if (!excluded.contains(m)) {
                    excluded.add(m);
                }
            }
        }
        List<String> report = excluded.stream()
                .map(m -> m.jar().getFileName() + " " + m.ids())
                .toList();
        if (excluded.isEmpty()) {
            return new Applied(process, report, unmatched);
        }

        if (isFabric(process, mods)) {
            Set<String> ids = new LinkedHashSet<>();
            excluded.forEach(m -> ids.addAll(m.ids()));
            List<String> jvm = new ArrayList<>(process.jvmArgs());
            // First, so it lands among the run's own properties, before any argument file.
            jvm.add(0, "-Dfabric.debug.disableModIds=" + String.join(",", ids));
            return new Applied(with(process, jvm, process.args()), report, unmatched);
        }

        deleteTree(scratch);
        Files.createDirectories(scratch);
        Set<String> gone = new LinkedHashSet<>();
        for (Mod m : excluded) {
            gone.add(m.jar().toString());
            gone.add(m.jar().toAbsolutePath().normalize().toString());
        }
        List<String> jvm = new ArrayList<>();
        for (String arg : process.jvmArgs()) {
            jvm.add(filterArgument(arg, gone, scratch, process.workingDirectory()));
        }
        List<String> args = new ArrayList<>();
        for (String arg : process.args()) {
            args.add(filterArgument(arg, gone, scratch, process.workingDirectory()));
        }
        if (excluded.stream().anyMatch(m -> modsFolder.contains(m.jar()))) {
            Path view = gameDirectoryWithout(gameDir, excluded, scratch.resolve("game"));
            args = withGameDir(args, view);
        }
        return new Applied(with(process, jvm, args), report, unmatched);
    }

    /** Where the game keeps {@code mods/}: its {@code --gameDir}, else its working directory. */
    static Path gameDirectory(LaunchSpec.Process process) {
        List<String> args = process.args();
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals("--gameDir")) {
                return process.workingDirectory().resolve(args.get(i + 1)).normalize();
            }
        }
        return process.workingDirectory();
    }

    private static List<Path> jars(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted()
                    .toList();
        }
    }

    /** Every jar a path list in the JVM arguments or their argument files names: class path and legacy class path. */
    private static Set<Path> classPathJars(LaunchSpec.Process process) throws IOException {
        Set<Path> jars = new LinkedHashSet<>();
        for (String arg : process.jvmArgs()) {
            collectJars(arg, process.workingDirectory(), jars, 0);
        }
        return jars;
    }

    private static void collectJars(String text, Path cwd, Set<Path> jars, int depth) throws IOException {
        if (depth > 3) {
            return;
        }
        if (text.startsWith("@")) {
            Path file = cwd.resolve(text.substring(1));
            if (Files.isRegularFile(file)) {
                collectJars(Files.readString(file), cwd, jars, depth + 1);
            }
            return;
        }
        Matcher m = PATH_LIST.matcher(text);
        while (m.find()) {
            String token = m.group();
            String file = fileProperty(token);
            if (file != null && Files.isRegularFile(cwd.resolve(file))) {
                collectJars(Files.readString(cwd.resolve(file)), cwd, jars, depth + 1);
                continue;
            }
            int eq = token.indexOf('=');
            String list = token.startsWith("-D") && eq > 0 ? token.substring(eq + 1) : token;
            for (String part : list.split(Pattern.quote(File.pathSeparator))) {
                if (part.endsWith(".jar")) {
                    Path jar = cwd.resolve(part).normalize();
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    }
                }
            }
        }
    }

    /** The file a {@code -D…file=<path>} property names, such as ModDevGradle's {@code legacyClassPath.file}. */
    private static String fileProperty(String token) {
        int eq = token.indexOf('=');
        if (!token.startsWith("-D") || eq < 0 || !token.substring(0, eq).endsWith(".file")) {
            return null;
        }
        return token.substring(eq + 1);
    }

    /**
     * {@code arg} with the jars in {@code gone} taken out of every path list in it; an argument file, or a file a
     * {@code -D….file} names, is copied into {@code scratch} with them taken out, and referred to there.
     */
    static String filterArgument(String arg, Set<String> gone, Path scratch, Path cwd) throws IOException {
        if (arg.startsWith("@")) {
            Path file = cwd.resolve(arg.substring(1));
            if (!Files.isRegularFile(file)) {
                return arg;
            }
            return "@" + rewrite(file, gone, scratch, cwd);
        }
        String file = fileProperty(arg);
        if (file != null && Files.isRegularFile(cwd.resolve(file))) {
            return arg.substring(0, arg.indexOf('=') + 1) + rewrite(cwd.resolve(file), gone, scratch, cwd);
        }
        return filterText(arg, gone, scratch, cwd);
    }

    private static Path rewrite(Path file, Set<String> gone, Path scratch, Path cwd) throws IOException {
        String text = Files.readString(file);
        String filtered = filterText(text, gone, scratch, cwd);
        if (filtered.equals(text)) {
            return file;
        }
        Path copy = scratch.resolve(uniqueName(scratch, file.getFileName().toString()));
        Files.writeString(copy, filtered, StandardCharsets.UTF_8);
        return copy;
    }

    private static String uniqueName(Path dir, String name) {
        String candidate = name;
        for (int i = 1; Files.exists(dir.resolve(candidate)); i++) {
            candidate = i + "-" + name;
        }
        return candidate;
    }

    private static String filterText(String text, Set<String> gone, Path scratch, Path cwd) throws IOException {
        Matcher m = PATH_LIST.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String replaced;
            String file = fileProperty(token);
            if (file != null && Files.isRegularFile(cwd.resolve(file))) {
                replaced = token.substring(0, token.indexOf('=') + 1) + rewrite(cwd.resolve(file), gone, scratch, cwd);
            } else {
                int eq = token.startsWith("-D") ? token.indexOf('=') : -1;
                String head = eq > 0 ? token.substring(0, eq + 1) : "";
                String list = eq > 0 ? token.substring(eq + 1) : token;
                List<String> kept = new ArrayList<>();
                for (String part : list.split(Pattern.quote(File.pathSeparator), -1)) {
                    if (!gone.contains(part)
                            && !gone.contains(cwd.resolve(part).normalize().toString())) {
                        kept.add(part);
                    }
                }
                replaced = head + String.join(File.pathSeparator, kept);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A game directory that is {@code real} but for {@code mods/}: every other entry links to the real one, so what the
     * game reads and writes there is the real directory's, and {@code mods/} links only the jars kept.
     */
    private static Path gameDirectoryWithout(Path real, List<Mod> excluded, Path view) throws IOException {
        Files.createDirectories(view.resolve(MODS));
        try (Stream<Path> entries = Files.list(real)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals(MODS) || name.equals("stagewright")) {
                    continue;
                }
                Files.createSymbolicLink(view.resolve(name), entry.toAbsolutePath());
            }
        }
        Set<Path> gone = new LinkedHashSet<>();
        excluded.forEach(m -> gone.add(m.jar()));
        for (Path jar : jars(real.resolve(MODS))) {
            if (!gone.contains(jar)) {
                Files.createSymbolicLink(view.resolve(MODS).resolve(jar.getFileName()), jar.toAbsolutePath());
            }
        }
        return view;
    }

    private static List<String> withGameDir(List<String> args, Path dir) {
        List<String> out = new ArrayList<>(args);
        int at = out.indexOf("--gameDir");
        if (at >= 0 && at + 1 < out.size()) {
            out.set(at + 1, dir.toString());
        } else {
            out.add("--gameDir");
            out.add(dir.toString());
        }
        return out;
    }

    /** Whether the game runs Fabric: its main class is Fabric's, or a Fabric Loader jar is on its class path. */
    private static boolean isFabric(LaunchSpec.Process process, List<Mod> mods) throws IOException {
        if (process.mainClass() != null && process.mainClass().startsWith("net.fabricmc.")) {
            return true;
        }
        for (String arg : process.jvmArgs()) {
            if (arg.startsWith("-Dfabric.dli.main=net.fabricmc.")) {
                return true;
            }
        }
        for (Path jar : classPathJars(process)) {
            if (jar.getFileName().toString().startsWith("fabric-loader-")) {
                return true;
            }
        }
        // A pack started by a script that names neither: its mods say which loader they were written for.
        return !mods.isEmpty() && mods.stream().allMatch(Mod::fabric);
    }

    /** The mod ids {@code jar} declares, for Fabric and NeoForge; none for a jar that is no mod. */
    static Mod read(Path jar) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        boolean fabric = false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry json = zip.getEntry("fabric.mod.json");
            if (json != null) {
                fabric = true;
                Matcher m = MOD_ID_JSON.matcher(text(zip, json));
                if (m.find()) {
                    ids.add(m.group(1));
                }
            }
            for (String toml : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                ZipEntry e = zip.getEntry(toml);
                if (e == null) {
                    continue;
                }
                Matcher m = MOD_ID_TOML.matcher(text(zip, e));
                while (m.find()) {
                    ids.add(m.group(1));
                }
            }
        } catch (IOException e) {
            // Not a zip: no mod.
        }
        return new Mod(jar, ids, fabric);
    }

    private static String text(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static LaunchSpec.Process with(LaunchSpec.Process p, List<String> jvm, List<String> args) {
        return new LaunchSpec.Process(p.java(), jvm, p.mainClass(), args, p.workingDirectory(), p.environment());
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
