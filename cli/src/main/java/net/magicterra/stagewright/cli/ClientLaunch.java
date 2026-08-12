package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Launch a client that is already installed in a game directory, on the real display.
 *
 * <p><b>Why this exists next to {@link HeadlessClient}, which also launches a client.</b> HeadlessMC
 * launches with {@code -lwjgl}, which replaces every LWJGL entry point with a stub. That is what
 * makes it headless, and it is fine for a suite of a dozen mods. It is not fine for a modpack: a
 * 452-mod pack's resource reload does real work through those entry points, so a mod that reads
 * image pixels while loading (Supplementaries reading a palette strip through Moonlight, measured)
 * sees an all-zero image and throws, NeoForge dispatches setup a second time trying to recover, and
 * the run dies in twenty "already registered" errors that say nothing about the cause. Removing the
 * offending mod only surfaces the next one.
 *
 * <p>The stub cannot be turned off from the outside: {@code hmc.offline=true} <i>forces</i> it — "You
 * are offline, game will start in headless mode!" So the only headed HeadlessMC launch is one with a
 * real Minecraft account ({@code --account}), which a developer's box can have and CI cannot.
 *
 * <p>This is the path for when there is no account. The two jobs split by what each is actually good
 * at: HeadlessMC installs a loader into an isolated game directory with no login, which is genuinely
 * hard and it does it well. Launching is not hard — the version JSONs it wrote say exactly what the
 * command is. This reads them.
 *
 * <p><b>It needs a display.</b> On a machine that has one, this runs the pack the way a player runs
 * it, with a real GL context and no stubs. On a headless CI box it needs an X server (Xvfb), which is
 * the same trade the Gradle plugin's {@code virtualDisplay} makes for the same reason.
 */
final class ClientLaunch {

    private ClientLaunch() {}

    /** The value Mojang's rule blocks match {@code os.name} against. */
    private static final String RULES_OS = rulesOs();

    private static String rulesOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return "windows";
        if (name.contains("mac") || name.contains("darwin")) return "osx";
        return "linux";
    }

    /**
     * Build the launch command for {@code versionId} as installed under {@code gameDir/versions}.
     *
     * @param systemProps our own {@code -D} properties, placed before the version's own JVM args so
     *                    they cannot land after the main class and be read as game arguments.
     */
    static List<String> command(Path gameDir, String versionId, String javaBinary,
                                List<String> systemProps) {
        List<JsonObject> chain = chain(gameDir, versionId);
        JsonObject launched = chain.get(0);
        JsonObject root = chain.get(chain.size() - 1);

        Map<String, String> subst = substitutions(gameDir, versionId, chain, classpath(gameDir, chain));

        List<String> jvm = new ArrayList<>();
        List<String> game = new ArrayList<>();
        // Parent first, so a NeoForge profile's arguments come after vanilla's — which is the order
        // a launcher applies them and the order --launchTarget depends on.
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonObject arguments = chain.get(i).getAsJsonObject("arguments");
            if (arguments == null) continue;
            collect(arguments.getAsJsonArray("jvm"), jvm);
            collect(arguments.getAsJsonArray("game"), game);
        }

        List<String> command = new ArrayList<>();
        command.add(javaBinary);
        command.addAll(systemProps);
        for (String arg : jvm) command.add(fill(arg, subst));
        command.add(launched.get("mainClass").getAsString());
        for (String arg : game) command.add(fill(arg, subst));

        // LWJGL extracts its natives here. Absent, it falls back to the system temp directory, which
        // works until two runs race for the same file.
        try {
            Files.createDirectories(gameDir.resolve("natives"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the natives directory", e);
        }
        if (root.has("assetIndex")) {
            // touched above via substitutions; nothing more to do
        }
        return command;
    }

    /** The version and everything it inherits from, launched version first. */
    private static List<JsonObject> chain(Path gameDir, String versionId) {
        List<JsonObject> out = new ArrayList<>();
        String id = versionId;
        while (id != null) {
            Path file = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no version '" + id + "' is installed in "
                        + gameDir + " — expected " + file + ". Install one first (--headlessmc does"
                        + " this) or name a version that is there.");
            }
            JsonObject data;
            try {
                data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file, e);
            }
            out.add(data);
            id = data.has("inheritsFrom") ? data.get("inheritsFrom").getAsString() : null;
        }
        return out;
    }

    private static List<String> classpath(Path gameDir, List<JsonObject> chain) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (JsonObject data : chain) {                 // child first: its libraries win
            JsonArray libraries = data.getAsJsonArray("libraries");
            if (libraries == null) continue;
            for (JsonElement element : libraries) {
                JsonObject library = element.getAsJsonObject();
                if (!allowed(library)) continue;
                String name = library.get("name").getAsString();
                String[] parts = name.split(":");
                // group:artifact:CLASSIFIER. Without the classifier, org.lwjgl:lwjgl:3.3.3 hides
                // org.lwjgl:lwjgl:3.3.3:natives-windows and the game dies at GLFW.<clinit> with
                // "Failed to locate library: lwjgl.dll", which reads like a broken install rather
                // than one missing jar.
                String key = parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "");
                if (!seen.add(key)) continue;
                out.add(artifact(gameDir, parts).toString());
            }
        }
        // The LAUNCHED version's jar, never the inherited vanilla one. NeoForge's own JVM args say
        // so: -DignoreList=client-extra,${version_name}.jar keeps exactly this jar off the module
        // path. Add 1.21.1.jar instead and it becomes an automatic module exporting the same
        // packages as NeoForge's patched `minecraft` module — a ResolutionException naming some JFR
        // event package, which points nowhere near the mistake.
        String id = chain.get(0).get("id").getAsString();
        out.add(gameDir.resolve("versions").resolve(id).resolve(id + ".jar").toString());
        return out;
    }

    private static Path artifact(Path gameDir, String[] coordinates) {
        Path path = gameDir.resolve("libraries");
        for (String segment : coordinates[0].split("\\.")) path = path.resolve(segment);
        String classifier = coordinates.length > 3 ? "-" + coordinates[3] : "";
        return path.resolve(coordinates[1]).resolve(coordinates[2])
                .resolve(coordinates[1] + "-" + coordinates[2] + classifier + ".jar");
    }

    /**
     * Mojang's rule blocks: absent means allowed, otherwise the last matching rule wins.
     *
     * <p>Feature rules ({@code is_demo}, {@code has_custom_resolution}, quick-play) are skipped
     * rather than evaluated, because we ask for none of those features — and a rule we cannot
     * evaluate must not be allowed to turn an argument ON.
     */
    private static boolean allowed(JsonObject entry) {
        JsonArray rules = entry.getAsJsonArray("rules");
        if (rules == null || rules.isEmpty()) return true;
        boolean verdict = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            JsonObject os = rule.getAsJsonObject("os");
            if (os != null && os.has("name") && !RULES_OS.equals(os.get("name").getAsString())) {
                continue;
            }
            if (rule.has("features")) continue;
            verdict = "allow".equals(rule.get("action").getAsString());
        }
        return verdict;
    }

    private static void collect(JsonArray arguments, List<String> sink) {
        if (arguments == null) return;
        for (JsonElement element : arguments) {
            if (element.isJsonPrimitive()) {
                sink.add(element.getAsString());
            } else if (element.isJsonObject() && allowed(element.getAsJsonObject())) {
                JsonElement value = element.getAsJsonObject().get("value");
                if (value.isJsonArray()) {
                    for (JsonElement one : value.getAsJsonArray()) sink.add(one.getAsString());
                } else {
                    sink.add(value.getAsString());
                }
            }
        }
    }

    private static Map<String, String> substitutions(Path gameDir, String versionId,
                                                     List<JsonObject> chain, List<String> classpath) {
        JsonObject root = chain.get(chain.size() - 1);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("${natives_directory}", gameDir.resolve("natives").toString());
        out.put("${launcher_name}", "stagewright");
        out.put("${launcher_version}", "1");
        out.put("${classpath}", String.join(java.io.File.pathSeparator, classpath));
        out.put("${library_directory}", gameDir.resolve("libraries").toString());
        out.put("${classpath_separator}", java.io.File.pathSeparator);
        out.put("${version_name}", versionId);
        out.put("${game_directory}", gameDir.toString());
        out.put("${assets_root}", gameDir.resolve("assets").toString());
        out.put("${assets_index_name}", root.getAsJsonObject("assetIndex").get("id").getAsString());
        out.put("${auth_player_name}", HeadlessClient.USERNAME);
        // An offline profile. Nothing we touch validates it: the pack's own server is not
        // authenticated and a scene run never joins a public one. A launcher would put a real
        // session here, and needing one is precisely what makes CI impossible.
        out.put("${auth_uuid}", "00000000-0000-0000-0000-000000000001");
        out.put("${auth_access_token}", "0");
        out.put("${clientid}", "0");
        out.put("${auth_xuid}", "0");
        out.put("${user_type}", "legacy");
        out.put("${version_type}", "release");
        out.put("${resolution_width}", "854");
        out.put("${resolution_height}", "480");
        return out;
    }

    private static String fill(String argument, Map<String, String> subst) {
        String out = argument;
        for (Map.Entry<String, String> entry : subst.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
    }
}
