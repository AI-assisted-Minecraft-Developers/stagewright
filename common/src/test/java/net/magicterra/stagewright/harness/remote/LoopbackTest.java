package net.magicterra.stagewright.harness.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.magicterra.stagewright.contract.AttachedRun;
import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.DriverJson;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.magicterra.stagewright.contract.SceneSpec;
import net.magicterra.stagewright.contract.Scripts;
import net.magicterra.stagewright.contract.remote.AttachedBridge;
import net.magicterra.stagewright.contract.remote.Wire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scene file run through the runner, over a driver binding that hands every call to the game's
 * half of the protocol ({@link Marshal}, {@link Dispatch}), with JSON in between exactly where the
 * socket would be. Neither half is faked: only the arena, which needs a world, and the socket.
 */
class LoopbackTest {
    record Stone(String id) {}

    /** An object the game keeps and a script only holds. */
    public static final class Pos {
        private final int x;
        private final int y;

        Pos(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int x() { return x; }
        public int sum() { return x + y; }
    }

    /** What a scene context offers, as far as these scenes use it. */
    public static final class Context {
        final List<String> placed = new ArrayList<>();
        final Pos shared = new Pos(4, 5);

        public void setBlock(int dx, int dy, int dz, Stone block) { placed.add(dx + "," + dy + "," + dz + "=" + block.id()); }
        public Stone blockAt(int dx, int dy, int dz) { return new Stone("minecraft:stone"); }
        public Pos pos() { return shared; }
        public List<Pos> row(int n) {
            List<Pos> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(new Pos(i, i));
            return out;
        }
        public Object echo(Object o) { return o; }
        public String nothing() { return null; }
        public double half(int n) { return n / 2.0; }
        public Map<String, Object> info() { return Map.of("name", "arena", "size", 9); }
        public int count(Object... items) { return items.length; }
        public void refuse() { throw new IllegalArgumentException("no block registered as 'pack:nope'"); }
        public void needsMod() { throw new SceneSkipped("mod not loaded"); }
        public void broken() { throw new SceneFailure("the arena is on fire"); }
    }

    private static final Marshal.Registries REGISTRIES = new Marshal.Registries() {
        @Override
        public String[] nameOf(Object o) {
            return o instanceof Stone s ? new String[] {"block", s.id()} : null;
        }

        @Override
        public Object find(String kind, String id) {
            return new Stone(id);
        }
    };

    /** The game: one session, a context, and every call taken through JSON and back. */
    private static final class Game implements DriverBinding {
        final Context context = new Context();
        final Handles handles = new Handles(context);
        final Marshal marshal = new Marshal(handles, REGISTRIES);

        @Override
        @SuppressWarnings("unchecked")
        public Object route(String method, Map<String, Object> params) {
            JsonElement in = JsonParser.parseString(DriverJson.params(params).toString());
            Map<String, Object> request = (Map<String, Object>) DriverJson.fromJson(in);
            return switch (method) {
                case Wire.BEGIN -> Map.of("pinned", true, "description", "loopback");
                case Wire.END -> Map.of("released", true);
                case Wire.OPEN -> Map.of("session", 1);
                case Wire.STATUS -> Map.of("state", Wire.READY, "message", "");
                case Wire.CLOSE -> Map.of("leaks", List.of(), "records", Map.of(), "softViolations", List.of(),
                        "cleanupFailures", List.of());
                case Wire.CALL -> call(request);
                default -> throw new IllegalStateException("not part of this test: " + method);
            };
        }

        private Object call(Map<String, Object> request) {
            JsonElement args = JsonParser.parseString(DriverJson.toJson(request.get("args")).toString());
            List<Object> decoded = new ArrayList<>();
            for (JsonElement a : args.getAsJsonArray()) decoded.add(marshal.decode(a));
            try {
                Object result = Dispatch.invoke(handles.get(((Number) request.get("target")).intValue()),
                        (String) request.get("method"), decoded);
                JsonElement wire = marshal.encode(result);
                Map<String, Object> answer = new java.util.LinkedHashMap<>();   // a null result is a value, which Map.of refuses
                answer.put("ok", true);
                answer.put("value", DriverJson.fromJson(JsonParser.parseString(wire.toString())));
                return answer;
            } catch (SceneSkipped e) {
                return Map.of("ok", false, "kind", Wire.KIND_SKIPPED, "message", e.getMessage());
            } catch (SceneFailure e) {
                return Map.of("ok", false, "kind", Wire.KIND_FAILED, "message", e.getMessage());
            } catch (IllegalArgumentException e) {
                return Map.of("ok", false, "kind", Wire.KIND_ILLEGAL_ARGUMENT, "message", e.getMessage());
            }
        }
    }

    private static List<Map<String, Object>> run(Path dir, Game game, String js) throws IOException {
        Path scenes = Files.createDirectories(dir.resolve("scenes"));
        Files.writeString(scenes.resolve("scenes.js"), js, StandardCharsets.UTF_8);
        List<String> log = new ArrayList<>();
        List<SceneSpec> specs = Scripts.load(scenes, AttachedBridge.setup(log::add), log::add);
        Path results = dir.resolve("results.jsonl");
        new AttachedRun(specs, game, "fabric", log::add, System::currentTimeMillis).run(results);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(results)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) DriverJson.fromJson(JsonParser.parseString(line));
            if ("scene".equals(row.get("type"))) out.add(row);
        }
        return out;
    }

    private static void assertPassed(List<Map<String, Object>> rows, int index) {
        assertEquals("PASS", rows.get(index).get("outcome"), String.valueOf(rows.get(index)));
    }

    @Test
    void aSceneCallsTheContextAndHoldsWhatItIsHandedBack(@TempDir Path dir) throws IOException {
        Game game = new Game();
        List<Map<String, Object>> rows = run(dir, game, """
                scene('loop.verbs', 20, function (s) {
                    s.setBlock(1, 2.0, -3, block('minecraft:stone'));
                    s.expect(s.pos().sum()).as('a method on a held object').isEqualTo(9);
                    s.expect(s.pos().x()).isEqualTo(4);
                    s.expect(s.row(3).size()).as('a list of objects').isEqualTo(3);
                    s.expect(s.half(5)).isEqualTo(2.5);
                    s.expect(s.nothing()).isNull();
                    s.expect(s.echo(null)).isNull();
                    s.expect(s.info().name).as('a map is a plain object').isEqualTo('arena');
                    s.expect(s.count(1, 'a', null)).as('varargs').isEqualTo(3);
                    s.check(s.pos().equals(s.pos())).as("the same object is equal to itself").isTrue();
                });
                """);

        assertPassed(rows, 0);
        assertEquals(List.of("1,2,-3=minecraft:stone"), game.context.placed);
    }

    @Test
    void aBlockTheGameHandedBackCanBePassedBackToIt(@TempDir Path dir) throws IOException {
        Game game = new Game();
        List<Map<String, Object>> rows = run(dir, game, """
                scene('loop.blocks', 20, function (s) {
                    s.setBlock(0, 0, 0, s.blockAt(0, 0, 0));
                });
                """);

        assertPassed(rows, 0);
        assertEquals(List.of("0,0,0=minecraft:stone"), game.context.placed);
    }

    @Test
    void theGamesRefusalsComeBackAsTheKindTheyWere(@TempDir Path dir) throws IOException {
        List<Map<String, Object>> rows = run(dir, new Game(), """
                scene('loop.skip', 20, function (s) { s.needsMod(); });
                scene('loop.broken', 20, function (s) { s.broken(); });
                scene('loop.refused', 20, function (s) { s.refuse(); });
                scene('loop.unknown', 20, function (s) { s.noSuchVerb(1); });
                """);

        assertEquals(Boolean.TRUE, rows.get(0).get("skipped"));
        assertEquals("the arena is on fire", rows.get(1).get("reason"));
        assertEquals("no block registered as 'pack:nope'", rows.get(2).get("reason"));
        assertEquals("FAIL", rows.get(3).get("outcome"));
        assertTrue(String.valueOf(rows.get(3).get("reason")).contains("noSuchVerb"), String.valueOf(rows.get(3)));
    }
}
