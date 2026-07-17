package net.magicterra.testkit.junit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronous bare-RPC client over a plain {@link WebSocket} (path {@code /rpc}).
 *
 * <p>Wire format matches the driver's hand-rolled envelope (NOT JSON-RPC 2.0), the
 * same one {@code scripts/testkit/instrument.py} and {@code rpc.py} speak:
 * <pre>
 *   request:  {"id":N,"method":"mc.x.y","params":{…}}
 *   success:  {"id":N,"result":…}
 *   error:    {"id":N,"error":"&lt;string&gt;"}
 * </pre>
 *
 * <p><b>Concurrency contract:</b> this client is designed for a <i>serial lease</i>
 * — one test thread issuing one call at a time (there is one attach per JVM, per
 * {@link TestkitExtension}). It is thread-safe enough for that: ids come from an
 * {@link AtomicLong} and the in-flight table is a {@link ConcurrentHashMap}, so the
 * websocket reader thread can complete a call issued by the test thread. It does NOT
 * guarantee ordering or fairness under genuinely concurrent callers; do not share one
 * instance across parallel calls.
 */
public final class TestkitRpc implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final WebSocket webSocket;
    private final AtomicLong ids = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private TestkitRpc(HttpClient httpClient, WebSocket webSocket) {
        this.httpClient = httpClient;
        this.webSocket = webSocket;
    }

    /**
     * Connect to {@code wsUri} (e.g. {@code ws://127.0.0.1:39801/rpc}), completing
     * the websocket handshake before returning. Throws {@link TestkitRpcException}
     * if the connection cannot be established within {@code connectTimeoutMs}.
     */
    public static TestkitRpc connect(String wsUri, long connectTimeoutMs) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        Reader reader = new Reader();
        try {
            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .buildAsync(URI.create(wsUri), reader)
                    .get(connectTimeoutMs, TimeUnit.MILLISECONDS);
            TestkitRpc rpc = new TestkitRpc(client, ws);
            reader.bind(rpc.pending);
            return rpc;
        } catch (TimeoutException e) {
            throw new TestkitRpcException("<connect>", "websocket handshake to " + wsUri
                    + " timed out after " + connectTimeoutMs + "ms");
        } catch (ExecutionException e) {
            throw new TestkitRpcException("<connect>", "websocket handshake to " + wsUri
                    + " failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TestkitRpcException("<connect>", "interrupted while connecting to " + wsUri);
        }
    }

    /**
     * Issue one request and block for its reply. On an error envelope throws
     * {@link TestkitRpcException}; on timeout throws {@link TestkitTimeoutException}
     * (never hangs). Ids increase monotonically per call.
     */
    public JsonObject call(String method, JsonObject params, long timeoutMs) {
        long id = ids.incrementAndGet();
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            String payload = GSON.toJson(encodeRequest(id, method, params));
            webSocket.sendText(payload, true);
            JsonObject envelope = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
            return resultOf(method, envelope);
        } catch (TimeoutException e) {
            throw new TestkitTimeoutException("RPC call " + method + " timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            throw new TestkitRpcException(method, "transport error: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TestkitRpcException(method, "interrupted while awaiting reply");
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void close() {
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        } catch (RuntimeException ignored) {
            // best-effort close
        }
        // fail any still-pending calls so a blocked caller does not hang
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("rpc closed")));
        pending.clear();
        // release the HttpClient selector thread (Java 21 AutoCloseable); without
        // this it lives until JVM exit — blocks briefly while in-flight ops drain
        httpClient.close();
    }

    // ---------------------------------------------------------------- codec ----
    // Pure functions, split from transport so SelfTest can round-trip the wire
    // format without opening a socket.

    /** Build a request envelope {@code {"id":id,"method":method,"params":params}}. */
    static JsonObject encodeRequest(long id, String method, JsonObject params) {
        JsonObject req = new JsonObject();
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params != null ? params : new JsonObject());
        return req;
    }

    /** Parse a reply envelope from its JSON text. */
    static JsonObject decodeEnvelope(String text) {
        JsonElement e = JsonParser.parseString(text);
        if (e == null || !e.isJsonObject()) {
            throw new TestkitRpcException("<decode>", "reply is not a JSON object: " + text);
        }
        return e.getAsJsonObject();
    }

    /**
     * Interpret a reply envelope: an {@code error} member (non-null) throws
     * {@link TestkitRpcException}; otherwise the {@code result} is returned as a
     * {@link JsonObject}. A non-object / absent result is wrapped as
     * {@code {"result": <value>}} so the frozen {@code JsonObject} return type holds
     * for methods that reply with a bare primitive or array.
     */
    static JsonObject resultOf(String method, JsonObject envelope) {
        JsonElement err = envelope.get("error");
        if (err != null && !err.isJsonNull()) {
            throw new TestkitRpcException(method, err.isJsonPrimitive() ? err.getAsString() : err.toString());
        }
        JsonElement result = envelope.get("result");
        if (result != null && result.isJsonObject()) {
            return result.getAsJsonObject();
        }
        JsonObject wrapped = new JsonObject();
        wrapped.add("result", result != null ? result : com.google.gson.JsonNull.INSTANCE);
        return wrapped;
    }

    // -------------------------------------------------------------- reader -----

    /** Accumulates (possibly fragmented) text frames and completes pending futures. */
    private static final class Reader implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private volatile ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending;

        void bind(ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending) {
            this.pending = pending;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                dispatch(msg);
            }
            webSocket.request(1);
            return null;
        }

        private void dispatch(String msg) {
            if (pending == null) {
                return; // reply before bind() (should not happen: handshake completes first)
            }
            JsonObject env;
            try {
                env = decodeEnvelope(msg);
            } catch (RuntimeException e) {
                return; // ignore un-parseable frames (event push, keepalive, …)
            }
            JsonElement idEl = env.get("id");
            if (idEl == null || idEl.isJsonNull()) {
                return; // notification / event push — no correlation id, ignore
            }
            long id;
            try {
                id = idEl.getAsLong();
            } catch (RuntimeException e) {
                return;
            }
            CompletableFuture<JsonObject> fut = pending.remove(id);
            if (fut != null) {
                fut.complete(env);
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failAll(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failAll(new IllegalStateException("websocket closed: " + statusCode + " " + reason));
            return null;
        }

        private void failAll(Throwable cause) {
            if (pending == null) {
                return;
            }
            pending.values().forEach(f -> f.completeExceptionally(cause));
            pending.clear();
        }
    }
}
