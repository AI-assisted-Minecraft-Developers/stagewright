package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.magicterra.stagewright.test.Store;

/**
 * {@link Store} as {@code stagewright/store.json} in the game's directory, written through on every put so a restart
 * finds it. The test engine outside deletes the file before a scene's first game, so a scene starts with nothing.
 */
final class StoreImpl implements Store {

    /** The file's name under the game's {@code stagewright/}; the test engine outside deletes it by this name. */
    static final String FILE = "store.json";

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final Path file;
    private JsonObject values;

    StoreImpl(Path file) {
        this.file = file;
    }

    @Override
    public synchronized void put(String key, Object value) {
        JsonObject all = values();
        all.add(key, GSON.toJsonTree(value));
        try {
            Files.createDirectories(file.getParent());
            Path next = file.resolveSibling(FILE + ".next");
            Files.writeString(next, GSON.toJson(all), StandardCharsets.UTF_8);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not keep '" + key + "' in " + file, e);
        }
    }

    @Override
    public synchronized Object get(String key) {
        JsonElement value = values().get(key);
        return value == null || value.isJsonNull() ? null : GSON.fromJson(value, Object.class);
    }

    @Override
    public synchronized <T> T get(String key, Class<T> type) {
        JsonElement value = values().get(key);
        return value == null || value.isJsonNull() ? null : GSON.fromJson(value, type);
    }

    private JsonObject values() {
        if (values != null) return values;
        try {
            values = Files.isRegularFile(file)
                    ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                            .getAsJsonObject()
                    : new JsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("could not read the store " + file, e);
        }
        return values;
    }
}
