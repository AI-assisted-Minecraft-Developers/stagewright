package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The client's side of a socket that went away, and of a handshake that never completed. */
class StageWrightRpcTest {

    @Test
    void aCallAfterTheReaderSawTheCloseIsATransportFailure() {
        // A send that still succeeds after the reader saw the close: the peer's FIN arrived before
        // the output side noticed. Nothing will ever answer, and waiting for one reads as a wedge.
        WebSocket sendsIntoTheVoid = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> method.getReturnType() == CompletableFuture.class
                        ? CompletableFuture.completedFuture(proxy) : null);
        StageWrightRpc.Reader reader = new StageWrightRpc.Reader();
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), sendsIntoTheVoid,
                reader)) {
            reader.onClose(sendsIntoTheVoid, WebSocket.NORMAL_CLOSURE, "gone");
            assertThrows(StageWrightTransportException.class,
                    () -> rpc.call("mc.system.version", new JsonObject(), 2_000));
        }
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

    private static long selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("HttpClient-")
                        && t.getName().endsWith("-SelectorManager"))
                .count();
    }
}
