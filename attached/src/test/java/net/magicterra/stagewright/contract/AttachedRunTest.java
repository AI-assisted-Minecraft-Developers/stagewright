package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        DriverBinding none = (method, params) -> {
            throw new SceneFailure("no driver in this test: " + method);
        };
        return run(dir, js, none, log, allGood);
    }

    private static List<Map<String, Object>> run(Path dir, String js, DriverBinding driver, List<String> log,
                                                 boolean[] allGood) throws IOException {
        Path scenes = Files.createDirectories(dir.resolve("scenes"));
        Files.writeString(scenes.resolve("scenes.js"), js, StandardCharsets.UTF_8);
        List<SceneSpec> specs = Scripts.load(scenes, (cx, scope, file) -> { }, log::add);
        Path results = dir.resolve("attached-results.jsonl");
        allGood[0] = new AttachedRun(specs, driver, "fabric", log::add, System::currentTimeMillis)
                .run(results);

        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = GSON.fromJson(line, Map.class);
            if ("scene".equals(rec.get("type"))) records.add(rec);
            if ("done".equals(rec.get("type"))) assertEquals((double) records.size(), rec.get("scenes"));
        }
        return records;
    }

    /** A driver whose socket has gone, as {@link RpcDriverBinding} reports it; counts what reached it. */
    private static DriverBinding gone(List<String> calls) {
        return (method, params) -> {
            calls.add(method);
            throw new SceneFailure("driver('" + method + "') transport error: closed",
                    new StageWrightTransportException(method, "transport error: closed"));
        };
    }

    private static final String TWO_SCENES_AFTER = """
            scene('pack.second', 20, function (s) { s.driver('mc.second'); });
            scene.optional('pack.third', 20, function (s) { s.driver('mc.third'); });
            """;

    @Test
    void aLostConnectionEndsTheRunAndWhatWasLeftIsRecordedAsNotRun(@TempDir Path dir) throws IOException {
        List<String> calls = new ArrayList<>();
        List<String> log = new ArrayList<>();
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.first', 20, function (s) { s.driver('mc.first'); });
                """ + TWO_SCENES_AFTER, gone(calls), log, allGood);

        assertEquals(List.of("mc.first"), calls, "nothing is sent on a connection known to be gone");
        assertEquals(3, records.size());
        assertEquals("FAIL", records.get(0).get("outcome"));
        assertEquals("driver('mc.first') transport error: closed", records.get(0).get("reason"),
                "a failure that already names the loss is not told it twice");
        assertFalse(records.get(0).containsKey("bodyRan"), "the scene that lost it did run");
        for (Map<String, Object> rec : records.subList(1, 3)) {
            assertEquals("FAIL", rec.get("outcome"));
            assertEquals(Boolean.FALSE, rec.get("bodyRan"), "coverage must count it as a hole");
            assertEquals("not run: the connection to the driver was lost during 'pack.first'"
                    + " (transport error: closed)", rec.get("reason"));
        }
        assertFalse(allGood[0]);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("RED: the connection to the driver was lost during"
                + " 'pack.first'") && l.contains("the 2 scene(s) after it")), log.toString());
    }

    @Test
    void aLostConnectionIsInTheFileNotOnlyInTheReturnValue(@TempDir Path dir) throws IOException {
        // All optional, so the records alone would judge GREEN.
        run(dir, """
                scene.optional('pack.first', 20, function (s) { s.driver('mc.first'); });
                scene.optional('pack.second', 20, function (s) { s.driver('mc.second'); });
                """, gone(new ArrayList<>()), new ArrayList<>(), new boolean[1]);

        List<String> lines = Files.readAllLines(dir.resolve("attached-results.jsonl"), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> done = GSON.fromJson(lines.get(lines.size() - 1), Map.class);
        assertEquals("the connection to the driver was lost during 'pack.first' (transport error: closed)",
                done.get("cutShort"));
    }

    @Test
    void aRunThatHeldItsConnectionWritesNoCutShort(@TempDir Path dir) throws IOException {
        run(dir, "scene('pack.first', 20, function (s) { });", (method, params) -> null,
                new ArrayList<>(), new boolean[1]);
        List<String> lines = Files.readAllLines(dir.resolve("attached-results.jsonl"), StandardCharsets.UTF_8);
        assertFalse(lines.get(lines.size() - 1).contains("cutShort"), lines.get(lines.size() - 1));
    }

    @Test
    void aLostConnectionABodyCaughtStillEndsTheRun(@TempDir Path dir) throws IOException {
        List<String> calls = new ArrayList<>();
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene.optional('pack.first', 20, function (s) {
                    try { s.driver('mc.first'); } catch (e) { }
                });
                """ + TWO_SCENES_AFTER, gone(calls), new ArrayList<>(), allGood);

        assertEquals("FAIL", records.get(0).get("outcome"), "a pass over a dead connection measured nothing");
        assertEquals(Boolean.FALSE, records.get(1).get("bodyRan"));
        assertEquals(Boolean.FALSE, records.get(2).get("bodyRan"));
        assertFalse(allGood[0], "a run that lost its driver did not do what it was given");
    }

    @Test
    void theSceneThatLostTheConnectionFailsThoughItCaughtTheFailureAndWasLast(@TempDir Path dir) throws IOException {
        // Caught and taken for "absent", the failure would otherwise leave a pass, and a GREEN run.
        List<String> log = new ArrayList<>();
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene.optional('pack.only', 20, function (s) {
                    try { s.driver('mc.only'); } catch (e) { s.skip('nothing there'); }
                });
                """, gone(new ArrayList<>()), log, allGood);

        assertEquals("FAIL", records.get(0).get("outcome"));
        assertNull(records.get(0).get("skipped"), "a skip decided over a dead connection is no skip");
        assertEquals("the connection to the driver was lost during this scene (transport error: closed)",
                records.get(0).get("reason"));
        assertFalse(allGood[0]);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("RED: the connection to the driver was lost")
                && l.endsWith("no scene was left to run.")), log.toString());
    }

    @Test
    void halfASurrogatePairReachesTheDriverAndDoesNotEndTheRun(@TempDir Path dir) throws IOException {
        StageWrightRpc.Reader reader = new StageWrightRpc.Reader();
        List<String> said = new ArrayList<>();
        // Fails text it cannot encode as the JDK's socket does, with an IOException; answers the rest.
        WebSocket likeTheJdks = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("sendText")) {
                        return method.getReturnType() == CompletableFuture.class
                                ? CompletableFuture.completedFuture(proxy) : null;
                    }
                    String text = args[0].toString();
                    if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
                        return CompletableFuture.failedFuture(new IOException("Malformed text message"));
                    }
                    JsonObject request = JsonParser.parseString(text).getAsJsonObject();
                    said.add(request.getAsJsonObject("params").get("text").getAsString());
                    reader.onText((WebSocket) proxy, "{\"id\":" + request.get("id") + ",\"result\":{}}", true);
                    return CompletableFuture.completedFuture(proxy);
                });
        boolean[] allGood = new boolean[1];
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), likeTheJdks, reader)) {
            List<Map<String, Object>> records = run(dir, """
                    scene('pack.first', 20, function (s) {
                        s.driver('mc.chat.say', { text: '\\uD83D\\uDCA5'.substring(0, 1) });
                    });
                    scene('pack.second', 20, function (s) { s.driver('mc.chat.say', { text: 'ok' }); });
                    """, new RpcDriverBinding(rpc, 2_000), new ArrayList<>(), allGood);

            assertEquals(List.of("\uD83D", "ok"), said, "the driver gets the same string in both homes");
            assertEquals("PASS", records.get(0).get("outcome"), String.valueOf(records.get(0)));
            assertEquals("PASS", records.get(1).get("outcome"));
            assertTrue(allGood[0]);
        }
    }

    @Test
    void aSceneThatFailedForAnotherReasonAlsoSaysTheConnectionWasLost(@TempDir Path dir) throws IOException {
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.only', 20, function (s) {
                    try { s.driver('mc.only'); } catch (e) { }
                    s.check('iron').as('the ore underfoot').isEqualTo('gold');
                });
                """, gone(new ArrayList<>()), new ArrayList<>(), allGood);

        String reason = String.valueOf(records.get(0).get("reason"));
        assertTrue(reason.contains("the ore underfoot"), reason);
        assertTrue(reason.endsWith("; the connection to the driver was lost during this scene"
                + " (transport error: closed)"), reason);
    }

    @Test
    void aCallTheDriverRefusedDoesNotEndTheRun(@TempDir Path dir) throws IOException {
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.first', 20, function (s) { s.driver('mc.first'); });
                """ + TWO_SCENES_AFTER, new ArrayList<>(), allGood);

        assertEquals(3, records.size());
        for (Map<String, Object> rec : records) {
            assertFalse(rec.containsKey("bodyRan"), String.valueOf(rec));
        }
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
    void aSceneRefusedBeforeItsBodySaysTheBodyNeverRan(@TempDir Path dir) throws IOException {
        // A FAIL counts as coverage unless the record says otherwise, and this one tested nothing.
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.refused', 20, function (s) { }, { dimension: 'pack:elsewhere' });
                scene('pack.failed', 20, function (s) { throw new Error('measured wrong'); });
                """, new ArrayList<>(), allGood);

        assertEquals("FAIL", records.get(0).get("outcome"));
        assertEquals(Boolean.FALSE, records.get(0).get("bodyRan"));
        assertEquals("FAIL", records.get(1).get("outcome"));
        assertFalse(records.get(1).containsKey("bodyRan"), "a body that ran is the default");
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

    @Test
    void aSkipWhoseCleanupFailsIsAFailThatStillSaysItSkipped(@TempDir Path dir) throws IOException {
        // FAIL, because the scene broke the world the next one starts in; still skipped, because
        // it tested nothing and coverage must not count it. The reason is the author's own words.
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.skipThenBrokenCleanup', 20, function (s) {
                    s.cleanup(function () { s.fail('the config override would not come off'); });
                    s.skip('curios is not installed');
                });
                """, new ArrayList<>(), allGood);

        Map<String, Object> rec = records.get(0);
        assertEquals("FAIL", rec.get("outcome"));
        assertEquals(Boolean.TRUE, rec.get("skipped"));
        assertEquals("cleanup failed: the config override would not come off", rec.get("reason"));
        assertFalse(allGood[0]);
    }

    @Test
    void anErrorFromABodyOrACleanupIsAFailedScene(@TempDir Path dir) throws IOException {
        // Escaping, it would end the run with no results file, and the CLI would report a crash.
        DriverBinding broken = (method, params) -> {
            throw new AssertionError("the driver broke on " + method);
        };
        boolean[] allGood = new boolean[1];
        List<Map<String, Object>> records = run(dir, """
                scene('pack.bodyError', 20, function (s) { s.driver('mc.body'); });
                scene('pack.cleanupError', 20, function (s) {
                    s.cleanup(function () { s.record('restored', true); });
                    s.cleanup(function () { s.driver('mc.cleanup'); });
                });
                scene('pack.after', 20, function (s) { });
                """, broken, new ArrayList<>(), allGood);

        assertEquals(3, records.size());
        assertEquals("FAIL", records.get(0).get("outcome"));
        assertEquals("unexpected AssertionError: the driver broke on mc.body", records.get(0).get("reason"));
        assertEquals("FAIL", records.get(1).get("outcome"));
        assertEquals("cleanup failed: the driver broke on mc.cleanup", records.get(1).get("reason"));
        assertEquals(Map.of("restored", true), withoutWallMs(records.get(1).get("data")),
                "the cleanup after the one that threw still ran");
        assertEquals("PASS", records.get(2).get("outcome"));
        assertFalse(allGood[0]);
    }

    private static Map<?, ?> withoutWallMs(Object data) {
        Map<?, ?> copy = new java.util.HashMap<>((Map<?, ?>) data);
        copy.remove("wallMs");
        return copy;
    }

    @Test
    void theHeaderRecordsWhenTheRunStarted(@TempDir Path dir) throws IOException {
        Path scenes = Files.createDirectories(dir.resolve("scenes"));
        Files.writeString(scenes.resolve("a.js"), "scene('pack.a', 20, function (s) { });",
                StandardCharsets.UTF_8);
        Path results = dir.resolve("attached-results.jsonl");
        new AttachedRun(Scripts.load(scenes, (cx, scope, file) -> { }, l -> { }),
                (method, params) -> null, "fabric", l -> { }, () -> 1_750_000_000_000L)
                .run(results);

        @SuppressWarnings("unchecked")
        Map<String, Object> header = GSON.fromJson(Files.readAllLines(results).get(0), Map.class);
        assertEquals(1.75e12, header.get("startedAt"));
    }
}
