package net.magicterra.stagewright.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.magicterra.stagewright.cli.install.Rules;
import net.magicterra.stagewright.cli.install.Versions;
import net.magicterra.stagewright.engine.RunDirectory;

/**
 * The command that launches an installed client, offline, on the real display.
 *
 * <p>Two directories, the way every launcher has them: the install ({@code versions/},
 * {@code libraries/}, {@code assets/} — shared, normally the official {@code .minecraft}) and the game
 * directory the client runs in (saves, logs, {@code mods/}). They may be the same directory.
 *
 * <p>Offline means no account and no authentication request at all: {@code user_type} is
 * {@code legacy}, which the client maps to its offline user API service, and the UUID is the one an
 * {@code online-mode=false} server derives from the same name — so the player a scene sees has the
 * same identity on both ends of the wire.
 */
final class ClientLaunch {

    /** The offline profile's default name, so a results file says which player it ran as. */
    static final String DEFAULT_USERNAME = "StageWright";

    private ClientLaunch() {}

    /**
     * @param systemProps our own {@code -D}/{@code -X} arguments: after the version's own JVM args, so
     *                    one the version also sets is ours (the JVM keeps the last), and before the
     *                    main class, after which they would be read as game arguments
     * @param gameArgs    program arguments appended after the version's own
     */
    static List<String> command(Path installDir, Path gameDir, String versionId, String javaBinary,
                                List<String> systemProps, List<String> gameArgs, String username) {
        List<JsonObject> chain = Versions.chain(installDir, versionId);
        JsonObject root = chain.get(chain.size() - 1);
        Map<String, String> subst = substitutions(installDir, gameDir, versionId, root,
                classpath(installDir, chain), username);

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
        for (String arg : jvm) command.add(fill(arg, subst));
        command.addAll(systemProps);
        command.add(chain.get(0).get("mainClass").getAsString());
        for (String arg : game) command.add(fill(arg, subst));
        command.addAll(gameArgs);
        return command;
    }

    /** The UUID an {@code online-mode=false} server gives this name — vanilla's {@code UUIDUtil}. */
    static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> classpath(Path installDir, List<JsonObject> chain) {
        List<String> out = new ArrayList<>();
        for (JsonObject library : Versions.libraries(chain)) {
            JsonObject downloads = library.getAsJsonObject("downloads");
            // A natives-only entry from before 1.19 puts nothing on the classpath.
            if (downloads != null && !downloads.has("artifact")) continue;
            out.add(Versions.libraryPath(installDir, library.get("name").getAsString()).toString());
        }
        // The LAUNCHED version's jar, never the inherited vanilla one: see Installer.gameJar.
        out.add(Versions.jar(installDir, chain.get(0).get("id").getAsString()).toString());
        return out;
    }

    /**
     * Feature rules ({@code is_demo}, {@code has_custom_resolution}, quick-play) never switch an
     * argument on here: we ask for none of those features, and {@code ClientDirector} enters the world.
     */
    private static void collect(JsonArray arguments, List<String> sink) {
        if (arguments == null) return;
        for (JsonElement element : arguments) {
            if (element.isJsonPrimitive()) {
                sink.add(element.getAsString());
            } else if (element.isJsonObject() && Rules.allowed(element.getAsJsonObject())) {
                JsonElement value = element.getAsJsonObject().get("value");
                if (value.isJsonArray()) {
                    for (JsonElement one : value.getAsJsonArray()) sink.add(one.getAsString());
                } else {
                    sink.add(value.getAsString());
                }
            }
        }
    }

    private static Map<String, String> substitutions(Path installDir, Path gameDir, String versionId,
                                                     JsonObject root, List<String> classpath,
                                                     String username) {
        Map<String, String> out = new LinkedHashMap<>();
        // LWJGL extracts its natives here. Absent, it falls back to the system temp directory, which
        // works until two runs race for the same file.
        out.put("${natives_directory}",
                gameDir.resolve(RunDirectory.ARTIFACT_DIR).resolve("natives").toString());
        out.put("${launcher_name}", "stagewright");
        out.put("${launcher_version}", "1");
        out.put("${classpath}", String.join(java.io.File.pathSeparator, classpath));
        out.put("${library_directory}", installDir.resolve("libraries").toString());
        out.put("${classpath_separator}", java.io.File.pathSeparator);
        out.put("${version_name}", versionId);
        out.put("${game_directory}", gameDir.toString());
        out.put("${assets_root}", installDir.resolve("assets").toString());
        out.put("${assets_index_name}", root.getAsJsonObject("assetIndex").get("id").getAsString());
        out.put("${auth_player_name}", username);
        out.put("${auth_uuid}", offlineUuid(username).toString().replace("-", ""));
        // Required by the client's argument parser, read by nothing offline.
        out.put("${auth_access_token}", "0");
        out.put("${clientid}", "");
        out.put("${auth_xuid}", "");
        // Anything but msa: the client then builds its offline user API service and makes no
        // authentication request at all.
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
