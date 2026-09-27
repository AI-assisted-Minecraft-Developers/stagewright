package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The client's side of a socket that went away, without a real socket's timing. */
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
}
