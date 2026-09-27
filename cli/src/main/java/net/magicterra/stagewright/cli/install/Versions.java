package net.magicterra.stagewright.cli.install;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The {@code versions/} and {@code libraries/} layout every launcher shares. */
public final class Versions {

    private Versions() {}

    public static Path json(Path installDir, String id) {
        return installDir.resolve("versions").resolve(id).resolve(id + ".json");
    }

    public static Path jar(Path installDir, String id) {
        return installDir.resolve("versions").resolve(id).resolve(id + ".jar");
    }

    /** The version and everything it inherits from, launched version first. */
    public static List<JsonObject> chain(Path installDir, String versionId) {
        List<JsonObject> out = new ArrayList<>();
        String id = versionId;
        while (id != null) {
            Path file = json(installDir, id);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no version '" + id + "' is installed in "
                        + installDir + " — expected " + file);
            }
            JsonObject data = read(file);
            out.add(data);
            id = data.has("inheritsFrom") ? data.get("inheritsFrom").getAsString() : null;
        }
        return out;
    }

    static JsonObject read(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * The libraries a launch uses, by identity, child first so a loader's pin beats vanilla's.
     *
     * <p>Identity is {@code group:artifact:classifier}. Without the classifier,
     * {@code org.lwjgl:lwjgl:3.3.3} hides {@code org.lwjgl:lwjgl:3.3.3:natives-windows} and the game dies
     * at {@code GLFW.<clinit>} with "Failed to locate library: lwjgl.dll", which reads like a broken
     * install rather than one missing jar.
     */
    public static List<JsonObject> libraries(List<JsonObject> chain) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonObject data : chain) {
            if (!data.has("libraries")) continue;
            for (JsonElement element : data.getAsJsonArray("libraries")) {
                JsonObject library = element.getAsJsonObject();
                if (!Rules.allowed(library)) continue;
                out.putIfAbsent(identity(library.get("name").getAsString()), library);
            }
        }
        return new ArrayList<>(out.values());
    }

    static String identity(String name) {
        String[] parts = stripExtension(name).split(":");
        return parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "");
    }

    /** {@code group:artifact:version[:classifier][@ext]} as a maven path, relative to a repository root. */
    public static String mavenPath(String name) {
        String extension = "jar";
        int at = name.indexOf('@');
        if (at >= 0) extension = name.substring(at + 1);
        String[] parts = stripExtension(name).split(":");
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2] + classifier + "." + extension;
    }

    public static Path libraryPath(Path installDir, String name) {
        return installDir.resolve("libraries").resolve(mavenPath(name));
    }

    private static String stripExtension(String name) {
        int at = name.indexOf('@');
        return at >= 0 ? name.substring(0, at) : name;
    }
}
