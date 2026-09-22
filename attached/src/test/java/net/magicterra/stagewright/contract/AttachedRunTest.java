package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The out-of-process runner, driven through real {@code .js} files and a driver that answers
 * nothing — what is under test is how a body's ending becomes a results line, not the game.
 */
class AttachedRunTest {

    private static final Gson GSON = new Gson();

    private static List<Map<String, Object>> run(Path dir, String js, List<String> log,
                                                 boolean[] allGood) throws IOException {
        Path scenes = Files.createDirectories(dir.resolve("scenes"));
        Files.writeString(scenes.resolve("scenes.js"), js, StandardCharsets.UTF_8);
        List<SceneSpec> specs = Scripts.load(scenes, (cx, scope, file) -> { }, log::add);
        DriverBinding none = (method, params) -> {
            throw new SceneFailure("no driver in this test: " + method);
        };
        Path results = dir.resolve("attached-results.jsonl");
        allGood[0] = new AttachedRun(specs, none, "fabric", log::add, System::currentTimeMillis)
                .run(results);

        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = GSON.fromJson(line, Map.class);
            if ("scene".equals(rec.get("type"))) records.add(rec);
        }
        return records;
    }

    @Test
    void aSoftCheckThatFailedBeforeASkipFailsTheScene(@TempDir Path dir) throws IOException {
        // The skip is real — the thing it names is absent — but the check before it already
        // measured something wrong, and a skip that swallowed that would be a green over a failure.
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.checkThenSkip', 20, function (s) {
                    s.check('iron').as('the ore underfoot').isEqualTo('gold');
                    s.skip('curios is not installed');
                });
                """, new ArrayList<>(), allGood);

        Map<String, Object> rec = records.get(0);
        assertEquals("FAIL", rec.get("outcome"));
        String reason = String.valueOf(rec.get("reason"));
        assertTrue(reason.contains("the ore underfoot"), reason);
        assertTrue(reason.contains("then skipped: curios is not installed"), reason);
        assertNull(rec.get("skipped"), "a failed scene is not a skip");
        assertFalse(allGood[0]);
    }

    @Test
    void aSkipWithNothingFailedBeforeItStaysASkip(@TempDir Path dir) throws IOException {
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.plainSkip', 20, function (s) {
                    s.check('gold').as('the ore underfoot').isEqualTo('gold');
                    s.skip('curios is not installed');
                });
                """, new ArrayList<>(), allGood);

        Map<String, Object> rec = records.get(0);
        assertEquals("PASS", rec.get("outcome"));
        assertEquals(Boolean.TRUE, rec.get("skipped"));
        assertEquals("skipped: curios is not installed", rec.get("reason"));
        assertTrue(allGood[0]);
    }
}
