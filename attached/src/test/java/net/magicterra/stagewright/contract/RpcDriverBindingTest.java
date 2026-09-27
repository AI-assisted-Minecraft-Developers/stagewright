package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class RpcDriverBindingTest {

    @Test
    void aLostConnectionFailsTheSceneSayingSo() {
        WebSocket closed = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> method.getReturnType() == CompletableFuture.class
                        ? CompletableFuture.completedFuture(proxy) : null);
        StageWrightRpc.Reader reader = new StageWrightRpc.Reader();
        try (StageWrightRpc rpc = new StageWrightRpc(HttpClient.newHttpClient(), closed, reader)) {
            reader.onClose(closed, WebSocket.NORMAL_CLOSURE, "gone");
            SceneFailure failure = assertThrows(SceneFailure.class,
                    () -> new RpcDriverBinding(rpc, 2_000).route("mc.system.version", Map.of()));
            assertTrue(failure.getMessage().contains("transport error"), failure.getMessage());
        }
    }
}
