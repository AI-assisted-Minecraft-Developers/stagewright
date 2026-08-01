package net.magicterra.stagewright.junit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM self-tests — no game, no socket. Exercises the endpoint parser, the
 * attach fail-fast, the wire codec, and the awaitCondition poll loop. Runs live-free:
 * the gradle {@code test} task passes an empty {@code TESTKIT_ENDPOINT}, and the
 * attach test additionally clears {@code stagewright.endpoint}.
 */
class SelfTest {

    @Test
    void endpointParseRoundTripAllKeys() {
        // A T1 (integrated_plus_client) descriptor: the frozen eight keys, NO serverRpcPort.
        String json = "{"
                + "\"version\":1,"
                + "\"topology\":\"integrated_plus_client\","
                + "\"loader\":\"fabric\","
                + "\"rpcHost\":\"127.0.0.1\","
                + "\"rpcPort\":39843,"
                + "\"worldName\":\"StageWrightT1\","
                + "\"holdPid\":12345,"
                + "\"writtenAtEpochMs\":1750000000000"
                + "}";
        Endpoint e = Endpoint.parse(json);
        assertEquals(1, e.version());
        assertEquals("integrated_plus_client", e.topology());
        assertEquals("fabric", e.loader());
        assertEquals("127.0.0.1", e.rpcHost());
        assertEquals(39843, e.rpcPort());
        assertEquals("StageWrightT1", e.worldName());
        assertEquals(12345L, e.holdPid());
        assertEquals(1750000000000L, e.writtenAtEpochMs());
        assertEquals("ws://127.0.0.1:39843/rpc", e.wsUri());
        // Optional serverRpcPort ABSENT on a T1 endpoint -> null (must still parse cleanly).
        assertNull(e.serverRpcPort(), "T1 endpoint must parse with serverRpcPort == null");
    }

    @Test
    void endpointParseT2WithServerRpcPort() {
        // A T2 (dedicated_plus_client) descriptor: rpcPort=CLIENT face, serverRpcPort=SERVER.
        String json = "{"
                + "\"version\":1,"
                + "\"topology\":\"dedicated_plus_client\","
                + "\"loader\":\"fabric\","
                + "\"rpcHost\":\"127.0.0.1\","
                + "\"rpcPort\":39843,"
                + "\"worldName\":\"world\","
                + "\"holdPid\":12345,"
                + "\"writtenAtEpochMs\":1750000000000,"
                + "\"serverRpcPort\":39777"
                + "}";
        Endpoint e = Endpoint.parse(json);
        assertEquals("dedicated_plus_client", e.topology());
        assertEquals(39843, e.rpcPort());
        assertEquals(Integer.valueOf(39777), e.serverRpcPort());
        // wsUri() is topology-agnostic — always the CLIENT face (rpcPort).
        assertEquals("ws://127.0.0.1:39843/rpc", e.wsUri());
    }

    @Test
    void endpointToleratesUnknownKeys() {
        // Forward compat: a future schema addition must not break an older reader. Unknown keys
        // (here "futureField") are simply not read; the known keys parse identically.
        String json = "{"
                + "\"version\":1,"
                + "\"topology\":\"dedicated_plus_client\","
                + "\"loader\":\"neoforge\","
                + "\"rpcHost\":\"127.0.0.1\","
                + "\"rpcPort\":40001,"
                + "\"worldName\":\"world\","
                + "\"holdPid\":999,"
                + "\"writtenAtEpochMs\":1750000000000,"
                + "\"serverRpcPort\":40002,"
                + "\"futureField\":\"ignored\",\"anotherUnknown\":[1,2,3]"
                + "}";
        Endpoint e = Endpoint.parse(json);
        assertEquals("neoforge", e.loader());
        assertEquals(40001, e.rpcPort());
        assertEquals(Integer.valueOf(40002), e.serverRpcPort());
    }

    @Test
    void endpointMissingKeyFailsFast() {
        // drop writtenAtEpochMs — a missing key must throw, not default silently
        String json = "{"
                + "\"version\":1,\"topology\":\"integrated_plus_client\",\"loader\":\"fabric\","
                + "\"rpcHost\":\"127.0.0.1\",\"rpcPort\":39843,\"worldName\":\"StageWrightT1\","
                + "\"holdPid\":12345"
                + "}";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Endpoint.parse(json));
        assertTrue(ex.getMessage().contains("writtenAtEpochMs"), ex.getMessage());
    }

    // The two attach-fail-fast tests below exercise the "no endpoint configured" branch,
    // which is only reachable when TESTKIT_ENDPOINT is UNSET (attach() reads the env first
    // and, when it names a real live endpoint, succeeds — defeating the assertThrows). The
    // live-acceptance command sets TESTKIT_ENDPOINT, so gate these off when it is present.
    // This is the symmetric counterpart to the ui.* scenes' @EnabledIfEnvironmentVariable:
    // env-off ⇒ fail-fast self-tests run; env-on ⇒ live scenes run.
    @Test
    @DisabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
    void attachWithNoEndpointThrowsWithHint() {
        // TESTKIT_ENDPOINT is passed empty by the gradle test task; clear the property too.
        String saved = System.getProperty("stagewright.endpoint");
        System.clearProperty("stagewright.endpoint");
        try {
            StageWrightAttachException ex = assertThrows(StageWrightAttachException.class, StageWright::attach);
            assertTrue(ex.getMessage().contains("python3 scripts/stagewright/t1.py --hold"),
                    "attach failure must carry the operator hint, got: " + ex.getMessage());
        } finally {
            if (saved != null) {
                System.setProperty("stagewright.endpoint", saved);
            }
        }
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
    void attachWithMissingFileThrowsWithHint() {
        String saved = System.getProperty("stagewright.endpoint");
        System.setProperty("stagewright.endpoint", "/nonexistent/testkit-endpoint.json");
        try {
            StageWrightAttachException ex = assertThrows(StageWrightAttachException.class, StageWright::attach);
            assertTrue(ex.getMessage().contains(StageWrightAttachException.HINT), ex.getMessage());
            assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
        } finally {
            if (saved != null) {
                System.setProperty("stagewright.endpoint", saved);
            } else {
                System.clearProperty("stagewright.endpoint");
            }
        }
    }

    @Test
    void envelopeEncodeDecodeRoundTrip() {
        JsonObject params = new JsonObject();
        params.addProperty("cmd", "setblock 0 200 0 minecraft:gold_block");

        JsonObject req = StageWrightRpc.encodeRequest(7, "mc.action.runCommand", params);
        assertEquals(7, req.get("id").getAsLong());
        assertEquals("mc.action.runCommand", req.get("method").getAsString());
        assertEquals("setblock 0 200 0 minecraft:gold_block",
                req.getAsJsonObject("params").get("cmd").getAsString());

        // re-serialize -> re-parse: the codec must round-trip identically
        String wire = req.toString();
        JsonObject back = StageWrightRpc.decodeEnvelope(wire);
        assertEquals(req, back);

        // success envelope -> result object
        JsonObject okEnv = StageWrightRpc.decodeEnvelope("{\"id\":7,\"result\":{\"ok\":true,\"success\":true}}");
        JsonObject result = StageWrightRpc.resultOf("mc.action.runCommand", okEnv);
        assertTrue(result.get("ok").getAsBoolean());

        // bare-primitive result -> wrapped under "result"
        JsonObject cursorEnv = StageWrightRpc.decodeEnvelope("{\"id\":8,\"result\":42}");
        JsonObject wrapped = StageWrightRpc.resultOf("mc.observe.cursor", cursorEnv);
        assertEquals(42, wrapped.get("result").getAsInt());

        // error envelope -> StageWrightRpcException carrying method + raw error
        JsonObject errEnv = StageWrightRpc.decodeEnvelope("{\"id\":9,\"error\":\"unknown method: mc.no.such\"}");
        StageWrightRpcException ex = assertThrows(StageWrightRpcException.class,
                () -> StageWrightRpc.resultOf("mc.no.such", errEnv));
        assertEquals("mc.no.such", ex.method());
        assertEquals("unknown method: mc.no.such", ex.error());
    }

    @Test
    void awaitConditionTimesOutWithTimeoutException() {
        // predicate never true -> must throw StageWrightTimeoutException (NOT AssertionError)
        assertThrows(StageWrightTimeoutException.class,
                () -> StageWright.pollUntil(() -> false, Duration.ofMillis(120)));
    }

    @Test
    void awaitConditionReturnsPromptlyWhenPredicateTrue() {
        // true on the 3rd poll -> returns without throwing, well inside the timeout
        AtomicInteger calls = new AtomicInteger();
        long t0 = System.nanoTime();
        StageWright.pollUntil(() -> calls.incrementAndGet() >= 3, Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(calls.get() >= 3, "predicate should have been polled at least 3 times");
        assertTrue(elapsedMs < 2_000L, "should have returned promptly, took " + elapsedMs + "ms");
    }

    @Test
    void awaitConditionTrueOnFirstPollDoesNotSleep() {
        long t0 = System.nanoTime();
        StageWright.pollUntil(() -> true, Duration.ofMillis(1));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 50L, "true-on-first-poll must not sleep, took " + elapsedMs + "ms");
    }

    @Test
    void timeoutExceptionIsNotAssertionError() {
        // canaries rely on this distinction
        StageWrightTimeoutException ex = new StageWrightTimeoutException("x");
        assertFalse(AssertionError.class.isInstance(ex));
        assertTrue(RuntimeException.class.isInstance(ex));
    }
}
