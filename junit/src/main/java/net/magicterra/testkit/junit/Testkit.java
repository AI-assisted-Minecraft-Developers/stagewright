package net.magicterra.testkit.junit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Typed facade over a live agent-driver endpoint for out-of-process JUnit 5 UI
 * tests. Obtain one with {@link #attach()} (or, in a test, inject it via
 * {@link TestkitExtension}); every method drives the driver over the bare-RPC
 * websocket.
 *
 * <p><b>Serial lease.</b> There is one attach per JVM. This facade assumes the
 * single-test-thread, one-call-at-a-time discipline documented on {@link TestkitRpc}.
 */
public final class Testkit implements AutoCloseable {
    /** Default per-call timeout for facade verbs (ms). */
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;
    /** Liveness-probe timeout during attach (ms). */
    private static final long PROBE_TIMEOUT_MS = 5_000L;
    /** Websocket handshake timeout during attach (ms). */
    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    /** awaitCondition poll interval (ms). */
    private static final long POLL_INTERVAL_MS = 50L;

    private final TestkitRpc rpc;
    private final Endpoint endpoint;

    Testkit(TestkitRpc rpc, Endpoint endpoint) {
        this.rpc = rpc;
        this.endpoint = endpoint;
    }

    /**
     * Attach to the live endpoint named by the {@code TESTKIT_ENDPOINT} environment
     * variable, else the {@code testkit.endpoint} system property.
     *
     * <p>Fails fast with {@link TestkitAttachException} (whose message contains the
     * hint {@code python3 scripts/testkit/t1.py --hold}) when: neither source is set,
     * the descriptor file is missing/unreadable/corrupt, or the liveness probe (one
     * {@code mc.system.version} call, 5s timeout) does not answer.
     */
    public static Testkit attach() {
        String pathStr = System.getenv("TESTKIT_ENDPOINT");
        if (isBlank(pathStr)) {
            pathStr = System.getProperty("testkit.endpoint");
        }
        if (isBlank(pathStr)) {
            throw new TestkitAttachException(
                    "no endpoint: neither TESTKIT_ENDPOINT env nor testkit.endpoint system property is set");
        }
        Path path = Path.of(pathStr.trim());
        if (!Files.isRegularFile(path)) {
            throw new TestkitAttachException("endpoint descriptor file not found at " + path);
        }
        Endpoint endpoint;
        try {
            endpoint = Endpoint.read(path);
        } catch (RuntimeException e) {
            throw new TestkitAttachException("endpoint descriptor at " + path + " is unreadable/corrupt: "
                    + e.getMessage(), e);
        }

        TestkitRpc rpc;
        try {
            rpc = TestkitRpc.connect(endpoint.wsUri(), CONNECT_TIMEOUT_MS);
        } catch (RuntimeException e) {
            throw new TestkitAttachException("cannot connect to endpoint " + endpoint.wsUri(), e);
        }
        // liveness probe: one mc.system.version with a 5s timeout
        try {
            rpc.call("mc.system.version", new JsonObject(), PROBE_TIMEOUT_MS);
        } catch (RuntimeException e) {
            rpc.close();
            throw new TestkitAttachException("endpoint " + endpoint.wsUri()
                    + " did not answer the liveness probe (mc.system.version)", e);
        }
        return new Testkit(rpc, endpoint);
    }

    /** The descriptor this instance attached to. */
    public Endpoint endpoint() {
        return endpoint;
    }

    /** The underlying bare-RPC client, for calls the facade does not wrap. */
    public TestkitRpc rpc() {
        return rpc;
    }

    /** Raw call at the default per-call timeout. */
    public JsonObject call(String method, JsonObject params) {
        return rpc.call(method, params, DEFAULT_TIMEOUT_MS);
    }

    // ------------------------------------------------------------- verbs -------

    /**
     * Run a vanilla command via {@code mc.action.runCommand} and assert it
     * dispatched successfully. Throws {@link TestkitRpcException} if the driver
     * reports {@code ok:false} or a Brigadier {@code success:false} (staging
     * commands must succeed; mirrors instrument.py's {@code _cmd}).
     */
    public void exec(String cmd) {
        JsonObject params = new JsonObject();
        params.addProperty("cmd", cmd);
        JsonObject r = call("mc.action.runCommand", params);
        if (!bool(r, "ok")) {
            throw new TestkitRpcException("mc.action.runCommand", "command dispatch failed: " + cmd + " -> " + r);
        }
        JsonElement success = r.get("success");
        if (success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                && !success.getAsBoolean()) {
            throw new TestkitRpcException("mc.action.runCommand", "command failed (success:false): " + cmd + " -> " + r);
        }
    }

    /** {@code mc.observe.player} — server-authoritative player snapshot. */
    public JsonObject observePlayer() {
        return call("mc.observe.player", new JsonObject());
    }

    /** {@code mc.client.screen.info} — cheap current-screen probe. */
    public JsonObject screenInfo() {
        return call("mc.client.screen.info", new JsonObject());
    }

    /** {@code mc.client.screen.tree} — full widget tree of the current screen. */
    public JsonObject screenTree() {
        return call("mc.client.screen.tree", new JsonObject());
    }

    /**
     * {@code mc.client.input.key} — synth a key (ENTER/ESCAPE/TAB/arrows/F1..F25/
     * A..Z/0..9). Default action is {@code click} (press+release).
     */
    public void key(String key) {
        JsonObject params = new JsonObject();
        params.addProperty("key", key);
        call("mc.client.input.key", params);
    }

    /** {@code mc.client.input.typeText} — type each codepoint into the focused widget. */
    public void typeText(String text) {
        JsonObject params = new JsonObject();
        params.addProperty("text", text);
        call("mc.client.input.typeText", params);
    }

    /** {@code mc.client.input.click} — left-click at logical screen coords {@code (x,y)}. */
    public void click(int x, int y) {
        click(x, y, 0);
    }

    /** {@code mc.client.input.click} — click at {@code (x,y)} with button 0=L,1=R,2=M. */
    public void click(int x, int y, int button) {
        JsonObject params = new JsonObject();
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("button", button);
        call("mc.client.input.click", params);
    }

    /** {@code mc.test.reset} — reset transient client instrumentation (screen/keys/chat). */
    public void reset() {
        call("mc.test.reset", new JsonObject());
    }

    /**
     * Block, polling {@code pred} every 50ms, until it returns {@code true}. Throws
     * {@link TestkitTimeoutException} — NOT an {@link AssertionError} — if the timeout
     * elapses first. Never hangs.
     *
     * <p>Deadline is checked BETWEEN polls: a predicate that itself issues a slow RPC
     * can overshoot the budget by up to one RPC timeout before the deadline is seen.
     */
    public void awaitCondition(Supplier<Boolean> pred, Duration timeout) {
        pollUntil(pred, timeout);
    }

    /**
     * Transport-free polling core of {@link #awaitCondition}, exposed package-private
     * so SelfTest can exercise it without a live endpoint.
     */
    static void pollUntil(Supplier<Boolean> pred, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (Boolean.TRUE.equals(pred.get())) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new TestkitTimeoutException("condition not satisfied within " + timeout.toMillis() + "ms");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TestkitTimeoutException("interrupted while awaiting condition");
            }
        }
    }

    @Override
    public void close() {
        rpc.close();
    }

    // ------------------------------------------------------------- helpers -----

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
