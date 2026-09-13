package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Works out how to start the server sitting in a pack's directory.
 *
 * <p>A modpack author does not have a launch command; they have a folder that an installer wrote.
 * Asking them to supply the command would push the one genuinely fiddly detail back onto the person
 * least equipped to know it — the NeoForge incantation is two {@code @argfile} references whose
 * paths carry a version number, and getting it wrong produces a JVM error rather than anything about
 * Minecraft. Detection is the product; {@code --launch} exists for the layouts detection has not
 * met yet.
 */
final class GameLaunch {

    private GameLaunch() {}

    /**
     * The command to start the server in {@code gameDir}, or null if the layout is unrecognised.
     *
     * <p>The java executable is index 0 so the caller can insert system properties immediately after
     * it — before any {@code @argfile}, which is where the JVM stops accepting them.
     *
     * @param log receives which loader version was chosen and where the choice came from. Not
     *            optional detail: a pack directory accumulates every loader it has ever had
     *            installed, so "which one did you just launch" is the first question a mismatched
     *            dependency error raises.
     */
    static List<String> detect(Path gameDir, String javaBinary, Consumer<String> log) {
        List<String> forge = modLauncher(gameDir, javaBinary, log);
        if (forge != null) return forge;
        return fabricOrVanilla(gameDir, javaBinary, log);
    }

    /**
     * Which loader this pack runs on, or {@code null} when nothing here says.
     *
     * <p>Same evidence {@link #detect} launches from, read for its other meaning: the argument files
     * under {@code libraries/net/{neoforged,minecraftforge}} exist only because that installer ran,
     * and a top-level fabric/quilt jar likewise. Deliberately NOT inferred from what is already in
     * {@code mods/} — a pack mid-migration has both loaders' mods sitting there, and the answer has
     * to be what will actually boot.
     */
    static String loader(Path gameDir) {
        if (Files.isDirectory(gameDir.resolve("libraries/net/neoforged/neoforge"))) return "neoforge";
        if (Files.isDirectory(gameDir.resolve("libraries/net/minecraftforge/forge"))) return "forge";
        try (Stream<Path> top = Files.list(gameDir)) {
            boolean fabric = top.map(p -> p.getFileName().toString().toLowerCase())
                    .anyMatch(n -> n.endsWith(".jar") && !n.contains("installer")
                            && (n.contains("fabric") || n.contains("quilt")));
            if (fabric) return "fabric";
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + gameDir, e);
        }
        return null;
    }

    /**
     * NeoForge and Forge: the installer leaves per-version argument files under {@code libraries/}
     * and a {@code user_jvm_args.txt} beside the run scripts.
     *
     * <p>The platform suffix is not cosmetic — the two files differ in classpath separator, so
     * picking the wrong one yields a classpath the JVM reads as a single enormous entry and a
     * {@code ClassNotFoundException} for the launcher's main class.
     */
    private static List<String> modLauncher(Path gameDir, String javaBinary, Consumer<String> log) {
        String argsName = System.getProperty("os.name", "").toLowerCase().startsWith("win")
                ? "win_args.txt" : "unix_args.txt";
        for (String vendor : new String[] {"net/neoforged/neoforge", "net/minecraftforge/forge"}) {
            Path base = gameDir.resolve("libraries").resolve(vendor);
            if (!Files.isDirectory(base)) continue;
            Path args = declaredVersionFile(gameDir, base, vendor, argsName, log);
            if (args == null) args = newestVersionFile(base, vendor, argsName, log);
            if (args == null) continue;

            List<String> command = new ArrayList<>();
            command.add(javaBinary);
            Path jvmArgs = gameDir.resolve("user_jvm_args.txt");
            if (Files.isRegularFile(jvmArgs)) command.add("@" + jvmArgs);
            command.add("@" + args);
            command.add("nogui");
            return command;
        }
        return null;
    }

    /**
     * Fabric, Quilt, or a plain vanilla server: one jar to hand to {@code -jar}.
     *
     * <p>Newest-first by modification time rather than by name, because a pack directory frequently
     * holds more than one — an author who upgraded loaders has both, and the old one still launches
     * a server that loads none of the pack's mods.
     */
    private static List<String> fabricOrVanilla(Path gameDir, String javaBinary, Consumer<String> log) {
        Path jar = null;
        try (Stream<Path> top = Files.list(gameDir)) {
            jar = top.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".jar")
                                && (n.contains("fabric") || n.contains("quilt") || n.contains("server"))
                                && !n.contains("installer");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + gameDir, e);
        }
        if (jar == null) return null;
        log.accept("launching " + jar.getFileName() + " (newest jar in " + gameDir + ")");
        return new ArrayList<>(List.of(javaBinary, "-jar", jar.toString(), "nogui"));
    }

    /**
     * The argument file the pack's OWN launch script names, or null when no script says.
     *
     * <p>This is the answer rather than a better guess. A NeoForge/Forge installer writes
     * {@code run.sh} and {@code run.bat} beside the game directory, each carrying one
     * {@code @libraries/…/<version>/<args>.txt}, and that version is by construction the one the
     * pack boots — the installer wrote both at the same moment. Everything under {@code libraries/}
     * is history: a pack that has been updated eight times has eight loaders installed there and
     * boots exactly one of them.
     *
     * <p>Both scripts are read, platform first. They name the same version and differ only in path
     * separator, so a Linux box with only {@code run.bat} present still gets an answer; the
     * separators are normalised before matching for the same reason.
     */
    private static Path declaredVersionFile(Path gameDir, Path base, String vendor, String name,
                                            Consumer<String> log) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("win");
        List<String> scripts = windows ? List.of("run.bat", "run.sh") : List.of("run.sh", "run.bat");
        Pattern declaration = Pattern.compile(Pattern.quote(vendor) + "/([^/\\s\"']+)/");
        for (String script : scripts) {
            Path file = gameDir.resolve(script);
            if (!Files.isRegularFile(file)) continue;
            String body;
            try {
                body = Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                continue;                       // unreadable script: fall through to the guess
            }
            Matcher matcher = declaration.matcher(body.replace('\\', '/'));
            if (!matcher.find()) continue;
            String version = matcher.group(1);
            Path args = base.resolve(version).resolve(name);
            if (!Files.isRegularFile(args)) continue;
            log.accept(vendorName(vendor) + " " + version + " — named by " + script
                    + ", which the installer wrote alongside the loader it installed");
            return args;
        }
        return null;
    }

    /**
     * The named file under the newest version directory of {@code base}, when nothing declared one.
     *
     * <p>A guess, and reported as one. It is only reached for a directory an installer did not write
     * a launch script into, and a pack directory holds every loader it has ever had — so being wrong
     * here does not fail as "wrong loader": it fails as a mod's dependency check, e.g. "jei requires
     * neoforge >= 21.1.248, current 21.5.34", which reads like an incompatible mod list.
     */
    private static Path newestVersionFile(Path base, String vendor, String name, Consumer<String> log) {
        try (Stream<Path> versions = Files.list(base)) {
            List<Path> candidates = versions.filter(Files::isDirectory)
                    .map(v -> v.resolve(name))
                    .filter(Files::isRegularFile)
                    .toList();
            if (candidates.isEmpty()) return null;
            Path best = candidates.stream()
                    .max(Comparator.comparing(p -> p.getParent().getFileName().toString(),
                            GameLaunch::compareVersions))
                    .orElseThrow();
            String chosen = best.getParent().getFileName().toString();
            // The warning belongs only where there was a choice to get wrong. On a directory holding
            // one loader, "pass --launch if this is not the one" is noise attached to the only
            // answer there was.
            String provenance = candidates.size() == 1
                    ? " — the only one installed under " + base
                    : " — the newest of " + candidates.size() + " installed under " + base
                            + ", because no run script named one. Pass --launch \"<command>\" if"
                            + " this pack boots a different one.";
            log.accept(vendorName(vendor) + " " + chosen + provenance);
            return best;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + base, e);
        }
    }

    private static String vendorName(String vendorPath) {
        return vendorPath.contains("neoforge") ? "neoforge" : "forge";
    }

    /**
     * Order two loader version directory names the way a version orders rather than the way a string
     * does.
     *
     * <p>Two ways a string compare gets this wrong, and both are live in a real pack directory:
     * {@code 21.1.9} sorts above {@code 21.1.117} because {@code '9' > '1'}, and a pre-release sorts
     * above the release it precedes because it is a prefix plus more characters. Segments are
     * compared as numbers where both sides are numbers, and a trailing word — {@code -beta},
     * {@code -rc1} — puts a version BELOW the same numbers without it, which is the semver rule and
     * the one a pack author expects.
     *
     * <p>It does not, and cannot, decide that {@code 21.1.248} beats {@code 21.5.34-beta}: 21.5 is
     * genuinely the later line, and a comparator that preferred the older one would be wrong about
     * every ordinary upgrade. Which of the two a pack actually boots is not an ordering question at
     * all, which is what {@link #declaredVersionFile} is for.
     */
    static int compareVersions(String a, String b) {
        List<String> left = segments(a);
        List<String> right = segments(b);
        int longest = Math.max(left.size(), right.size());
        for (int i = 0; i < longest; i++) {
            String x = i < left.size() ? left.get(i) : null;
            String y = i < right.size() ? right.get(i) : null;
            if (x == null || y == null) {
                // One side ran out. A trailing NUMBER is a more precise version (1.2.1 > 1.2); a
                // trailing WORD is a pre-release tag, so the side that ran out is the release (1.2 >
                // 1.2-beta).
                String extra = x == null ? y : x;
                int sign = x == null ? -1 : 1;
                return numeric(extra) ? sign : -sign;
            }
            int cmp;
            if (numeric(x) && numeric(y)) {
                cmp = new BigInteger(x).compareTo(new BigInteger(y));
            } else if (numeric(x) != numeric(y)) {
                cmp = numeric(x) ? 1 : -1;      // a number outranks a tag in the same position
            } else {
                cmp = x.compareToIgnoreCase(y);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /** Version segments: every run of characters between the separators a loader version uses. */
    private static List<String> segments(String version) {
        List<String> out = new ArrayList<>();
        for (String part : version.split("[.\\-+_]")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    private static boolean numeric(String segment) {
        if (segment.isEmpty()) return false;
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) return false;
        }
        return true;
    }
}
