package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The client's side of a socket: one that went away, and a handshake that never completed. */
class StageWrightRpcTest {

    @Test
    void aCallAfterTheReaderSawTheCloseIsATransportFailure() {
        // A send that still succeeds after the reader saw the close: the peer's FIN arrived before
        // the output side noticed. Nothing will ever answer, and waiting for one reads as a wedge.
        WebSocket sendsIntoTheVoid = sendsIntoTheVoid();
        StageWrightRpc.Reader reader = new StageWrightRpc.Reader();
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), sendsIntoTheVoid,
                reader)) {
            reader.onClose(sendsIntoTheVoid, WebSocket.NORMAL_CLOSURE, "gone");
            assertThrows(StageWrightTransportException.class,
                    () -> rpc.call("mc.system.version", new JsonObject(), 2_000));
        }
    }

    @Test
    void aReplyThatOutrunsItsOwnSendDoesNotGetTheNextCallRefused() {
        StageWrightRpc.Reader reader = new StageWrightRpc.Reader();
        // Like the JDK's socket, it refuses a send while the last is unfinished. Each send finishes
        // a moment after it starts, and its reply is delivered before that.
        AtomicReference<CompletableFuture<WebSocket>> last = new AtomicReference<>(CompletableFuture.completedFuture(null));
        WebSocket slowToFinish = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("sendText")) {
                        return method.getReturnType() == CompletableFuture.class
                                ? CompletableFuture.completedFuture(proxy) : null;
                    }
                    if (!last.get().isDone()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
                    }
                    CompletableFuture<WebSocket> sent = new CompletableFuture<WebSocket>()
                            .completeOnTimeout((WebSocket) proxy, 200, TimeUnit.MILLISECONDS);
                    last.set(sent);
                    long id = JsonParser.parseString(args[0].toString()).getAsJsonObject().get("id").getAsLong();
                    reader.onText((WebSocket) proxy, "{\"id\":" + id + ",\"result\":{}}", true);
                    return sent;
                });
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), slowToFinish, reader)) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                rpc.call("mc.first", new JsonObject(), 2_000);
                rpc.call("mc.second", new JsonObject(), 2_000);
            });
        }
    }

    @Test
    void aRequestWhoseCallGaveUpIsNotSentLater() {
        // The first send stays unfinished until the server reads again, which it does only after
        // both calls have given up.
        CompletableFuture<WebSocket> serverReadsAgain = new CompletableFuture<>();
        AtomicInteger sends = new AtomicInteger();
        WebSocket stalled = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("sendText")) {
                        return method.getReturnType() == CompletableFuture.class
                                ? CompletableFuture.completedFuture(proxy) : null;
                    }
                    sends.incrementAndGet();
                    return serverReadsAgain;
                });
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), stalled,
                new StageWrightRpc.Reader())) {
            assertThrows(StageWrightTimeoutException.class, () -> rpc.call("mc.first", new JsonObject(), 200));
            assertThrows(StageWrightTimeoutException.class, () -> rpc.call("mc.second", new JsonObject(), 200));
            serverReadsAgain.complete(stalled);
            assertEquals(1, sends.get(), "a request sent now would act on whatever scene runs next");
        }
    }

    @Test
    void aSendThatFailedDoesNotFailTheNextOne() {
        StageWrightRpc.Reader reader = new StageWrightRpc.Reader();
        AtomicInteger sends = new AtomicInteger();
        WebSocket firstFails = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("sendText")) {
                        return method.getReturnType() == CompletableFuture.class
                                ? CompletableFuture.completedFuture(proxy) : null;
                    }
                    if (sends.getAndIncrement() == 0) {
                        return CompletableFuture.failedFuture(new IOException("this payload only"));
                    }
                    long id = JsonParser.parseString(args[0].toString()).getAsJsonObject().get("id").getAsLong();
                    reader.onText((WebSocket) proxy, "{\"id\":" + id + ",\"result\":{}}", true);
                    return CompletableFuture.completedFuture(proxy);
                });
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), firstFails, reader)) {
            assertThrows(StageWrightTransportException.class, () -> rpc.call("mc.first", new JsonObject(), 2_000));
            rpc.call("mc.second", new JsonObject(), 2_000);
        }
    }

    @Test
    void aSendTheSocketFailedIsATransportFailureBeforeTheReaderSeesTheClose() {
        WebSocket outputClosed = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> method.getReturnType() == CompletableFuture.class
                        ? CompletableFuture.failedFuture(new IOException("closed output")) : null);
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), outputClosed,
                new StageWrightRpc.Reader())) {
            StageWrightTransportException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> assertThrows(StageWrightTransportException.class,
                            () -> rpc.call("mc.system.version", new JsonObject(), 30_000)));
            // The socket's own words, which is what a reader of the scene's reason needs.
            assertEquals("transport error: java.io.IOException: closed output", e.error());
        }
    }

    @Test
    void aCallAfterCloseIsATransportFailureAtOnce() {
        StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), sendsIntoTheVoid(),
                new StageWrightRpc.Reader());
        rpc.close();
        // Well inside the call's own limit, which is what a close that went unrecorded would cost.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(StageWrightTransportException.class,
                () -> rpc.call("mc.system.version", new JsonObject(), 30_000)));
    }

    @Test
    void aFailedHandshakeLeavesNoSelectorThreadBehind() throws Exception {
        int refusing;
        try (ServerSocket taken = new ServerSocket(0)) {
            refusing = taken.getLocalPort();
        }
        // Held open, so the kernel queues the connection and nothing ever answers the upgrade.
        try (ServerSocket silent = new ServerSocket(0)) {
            long before = selectorThreads();
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(StageWrightTransportException.class,
                        () -> StageWrightRpc.connect("ws://127.0.0.1:" + refusing + "/rpc", 2_000));
                assertThrows(StageWrightTransportException.class,
                        () -> StageWrightRpc.connect("ws://127.0.0.1:" + silent.getLocalPort() + "/rpc", 300));
            }
            // A shut-down client's selector thread ends on its own schedule, so give it a moment.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (selectorThreads() > before && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(selectorThreads() <= before, (selectorThreads() - before) + " selector threads left behind");
        }
    }

    @Test
    void aHandshakeNobodyAnswersIsATransportFailure() throws Exception {
        // Held open for the whole test, so no other process can take the port; the kernel accepts
        // the connection into the backlog and nothing ever answers the upgrade request.
        try (ServerSocket silent = new ServerSocket(0)) {
            assertThrows(StageWrightTransportException.class,
                    () -> StageWrightRpc.connect("ws://127.0.0.1:" + silent.getLocalPort() + "/rpc", 2_000));
        }
    }

    @Test
    void aCallOnASocketThatAlreadyClosedIsATransportFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread accept = new Thread(() -> acceptThenHangUp(server));
            accept.start();
            try (StageWrightRpc rpc = StageWrightRpc.connect(
                    "ws://127.0.0.1:" + server.getLocalPort() + "/rpc", 5_000)) {
                // The first call may be in flight when the hang-up lands; the second cannot be, so it
                // is the one that shows a send on a dead socket is not waited out as a timeout.
                assertThrows(StageWrightTransportException.class,
                        () -> rpc.call("mc.system.version", new JsonObject(), 5_000));
                assertThrows(StageWrightTransportException.class,
                        () -> rpc.call("mc.system.version", new JsonObject(), 5_000));
            }
            accept.join(5_000);
        }
    }

    @Test
    void aCallQueuedBehindAnUnsentRequestTimesOutRatherThanReadingAsALostConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Socket> stopped = stopsReading(server);
            StageWrightRpc rpc = StageWrightRpc.connect("ws://127.0.0.1:" + server.getLocalPort() + "/rpc", 5_000);
            try {
                fillTheSocket(rpc);
                assertThrows(StageWrightTimeoutException.class,
                        () -> rpc.call("mc.system.version", new JsonObject(), 1_000));
            } finally {
                stopped.get(5, TimeUnit.SECONDS).close();
                rpc.close();
            }
        }
    }

    /** Accepts one connection, answers the upgrade and never reads again: a server whose thread stopped. */
    private static CompletableFuture<Socket> stopsReading(ServerSocket server) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Socket s = server.accept();
                upgrade(s);
                return s;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Leave a send pending: more than both ends' socket buffers hold, to a server that never reads. */
    private static void fillTheSocket(StageWrightRpc rpc) {
        JsonObject fill = new JsonObject();
        fill.addProperty("fill", "x".repeat(32 << 20));
        assertThrows(StageWrightTimeoutException.class, () -> rpc.call("mc.fill", fill, 1_000));
    }

    /** Complete one websocket handshake, then drop the connection without a close frame. */
    private static void acceptThenHangUp(ServerSocket server) {
        try (Socket s = server.accept()) {
            upgrade(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answer the websocket upgrade request waiting on {@code s}. */
    private static void upgrade(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                s.getInputStream(), StandardCharsets.US_ASCII));
        String key = null;
        for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
            if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
        s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
    }

    /** A socket whose every send succeeds and which never answers. */
    private static WebSocket sendsIntoTheVoid() {
        return (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> method.getReturnType() == CompletableFuture.class
                        ? CompletableFuture.completedFuture(proxy) : null);
    }

    private static long selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("HttpClient-")
                        && t.getName().endsWith("-SelectorManager"))
                .count();
    }
}
