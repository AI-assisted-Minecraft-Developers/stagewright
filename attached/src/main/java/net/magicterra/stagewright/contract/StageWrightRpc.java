package net.magicterra.stagewright.contract;

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
import java.util.function.BooleanSupplier;

/**
 * Synchronous bare-RPC client over a plain {@link WebSocket} (path {@code /rpc}).
 *
 * <p>Wire format matches the driver's hand-rolled envelope (NOT JSON-RPC 2.0), the
 * same one {@code scripts/stagewright/instrument.py} and {@code rpc.py} speak:
 * <pre>
 *   request:  {"id":N,"method":"mc.x.y","params":{…}}
 *   success:  {"id":N,"result":…}
 *   error:    {"id":N,"error":"&lt;string&gt;"}
 * </pre>
 *
 * <p><b>Concurrency contract:</b> this client is designed for a <i>serial lease</i>
 * — one test thread issuing one call at a time (there is one attach per JVM, per
 * {@link StageWrightExtension}). It is thread-safe enough for that: ids come from an
 * {@link AtomicLong} and the in-flight table is a {@link ConcurrentHashMap}, so the
 * websocket reader thread can complete a call issued by the test thread. It does NOT
 * guarantee ordering or fairness under genuinely concurrent callers; do not share one
 * instance across parallel calls.
 */
public final class StageWrightRpc implements AutoCloseable {
    private static final Gson GSON = new Gson();
    /** How long {@link #close} lets the close handshake and anything in flight finish. */
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);

    private final HttpClient httpClient;
    private final WebSocket webSocket;
    private final AtomicLong ids = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Reader reader;
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);

    StageWrightRpc(HttpClient httpClient, WebSocket webSocket, Reader reader) {
        this.httpClient = httpClient;
        this.webSocket = webSocket;
        this.reader = reader;
        reader.bind(pending);
    }

    /**
     * Connect to {@code wsUri} (e.g. {@code ws://127.0.0.1:39801/rpc}), completing
     * the websocket handshake before returning. Throws {@link StageWrightTransportException}
     * if the connection cannot be established within {@code connectTimeoutMs}.
     */
    public static StageWrightRpc connect(String wsUri, long connectTimeoutMs) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        Reader reader = new Reader();
        boolean connected = false;
        try {
            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .buildAsync(URI.create(wsUri), reader)
                    .get(connectTimeoutMs, TimeUnit.MILLISECONDS);
            StageWrightRpc rpc = new StageWrightRpc(client, ws, reader);
            connected = true;
            return rpc;
        } catch (TimeoutException e) {
            throw new StageWrightTransportException("<connect>", "websocket handshake to " + wsUri
                    + " timed out after " + connectTimeoutMs + "ms");
        } catch (ExecutionException e) {
            throw new StageWrightTransportException("<connect>", "websocket handshake to " + wsUri
                    + " failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StageWrightTransportException("<connect>", "interrupted while connecting to " + wsUri);
        } finally {
            // Otherwise each failed attempt keeps a selector thread until the client is collected,
            // and attach retries many times. shutdownNow, as close waits out a handshake in flight.
            if (!connected) client.shutdownNow();
        }
    }

    /**
     * Issue one request and block at most {@code timeoutMs} for its reply. Throws the refusal as
     * {@link StageWrightRpcException}, a broken socket or an interrupt as
     * {@link StageWrightTransportException}, a timeout as {@link StageWrightTimeoutException}.
     */
    public JsonObject call(String method, JsonObject params, long timeoutMs) {
        long id = ids.incrementAndGet();
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            String payload = GSON.toJson(encodeRequest(id, method, params));
            // A send on a socket whose output already closed fails here even before the reader sees
            // the close; ignored, the call would wait out its timeout and read as a wedged server.
            sendAfterTheLast(payload, () -> pending.containsKey(id)).whenComplete((ws, failed) -> {
                if (failed != null) fut.completeExceptionally(failed);
            });
            // Read after the put: a close that failAll drained before this call registered would
            // otherwise leave it waiting for an answer from a socket that has gone.
            Throwable gone = reader.closedBy();
            if (gone != null) fut.completeExceptionally(gone);
            JsonObject envelope = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
            return resultOf(method, envelope);
        } catch (TimeoutException e) {
            throw new StageWrightTimeoutException("RPC call " + method + " timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            throw new StageWrightTransportException(method, "transport error: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StageWrightTransportException(method, "interrupted while awaiting reply");
        } finally {
            pending.remove(id);
        }
    }

    /** Send {@code payload} after the send before it, unless its call has given up by then: the socket
     *  refuses a send while one is unfinished, a reply can beat its own request's send, and a request
     *  sent after its call gave up would act on whatever scene is running by then. */
    private synchronized CompletableFuture<WebSocket> sendAfterTheLast(String payload, BooleanSupplier wanted) {
        CompletableFuture<WebSocket> sent = lastSend.handle((ws, failed) -> null)
                .thenCompose(ignored -> wanted.getAsBoolean() ? webSocket.sendText(payload, true)
                        : CompletableFuture.completedFuture(webSocket));
        lastSend = sent;
        return sent;
    }

    @Override
    public void close() {
        // The reader's own drain: a call made after this sees closedBy and fails at once, and one
        // registering meanwhile is either drained here or sees closedBy.
        reader.failAll(new IllegalStateException("rpc closed"));
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        } catch (RuntimeException ignored) {
            // best-effort close
        }
        // Not httpClient.close(): it waits for every send in flight, and one queued to a server that
        // stopped reading never finishes, so the caller would hang with it.
        httpClient.shutdown();
        try {
            if (!httpClient.awaitTermination(CLOSE_GRACE)) httpClient.shutdownNow();
        } catch (InterruptedException e) {
            httpClient.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- codec ----
    // Pure functions, split from transport so SelfTest can round-trip the wire
    // format without opening a socket.

    /** Build a request envelope {@code {"id":id,"method":method,"params":params}}. */
    /** The three helpers below are the hand-rolled envelope itself — {@code {id, method, params}} out,
     *  {@code {id, result|error}} back, with notifications distinguished by the ABSENCE of an "id"
     *  key rather than by its value. They are public because that envelope is the contract two
     *  independent clients already speak (this one and {@code scripts/rpc.py}), so encoding it is a
     *  legitimate thing to ask this class for, and because the tests that pin the format must be able
     *  to reach it from wherever they live. */
    public static JsonObject encodeRequest(long id, String method, JsonObject params) {
        JsonObject req = new JsonObject();
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params != null ? params : new JsonObject());
        return req;
    }

    /** Parse a reply envelope from its JSON text. */
    public static JsonObject decodeEnvelope(String text) {
        JsonElement e = JsonParser.parseString(text);
        if (e == null || !e.isJsonObject()) {
            throw new StageWrightRpcException("<decode>", "reply is not a JSON object: " + text);
        }
        return e.getAsJsonObject();
    }

    /**
     * Interpret a reply envelope: an {@code error} member (non-null) throws
     * {@link StageWrightRpcException}; otherwise the {@code result} is returned as a
     * {@link JsonObject}. A non-object / absent result is wrapped as
     * {@code {"result": <value>}} so the frozen {@code JsonObject} return type holds
     * for methods that reply with a bare primitive or array.
     */
    public static JsonObject resultOf(String method, JsonObject envelope) {
        JsonElement err = envelope.get("error");
        if (err != null && !err.isJsonNull()) {
            throw new StageWrightRpcException(method, err.isJsonPrimitive() ? err.getAsString() : err.toString());
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
    static final class Reader implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private volatile ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending;
        private volatile Throwable closedBy;

        void bind(ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending) {
            this.pending = pending;
        }

        Throwable closedBy() {
            return closedBy;
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
            // Recorded before the drain, and drained by remove, so a call registering meanwhile is
            // either drained here or sees closedBy; clear() could drop it unfailed.
            closedBy = cause;
            if (pending == null) {
                return;
            }
            for (Long id : pending.keySet()) {
                CompletableFuture<JsonObject> f = pending.remove(id);
                if (f != null) f.completeExceptionally(cause);
            }
        }
    }
}
