package net.magicterra.stagewright.driver.client;

import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.magicterra.stagewright.driver.DriverRuntime;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.model.DriverEvent;

/** Event subscription owns the API instance it registered with. */
final class DriverFeed {
    private static DriverApi subscribedApi;
    private static Consumer<DriverEvent> listener;
    private static AtomicBoolean listening;

    private DriverFeed() {}

    static synchronized void listenForHurts(Queue<Map<?, ?>> sink) {
        stopListening();
        DriverApi api = DriverRuntime.requireApi();
        AtomicBoolean active = new AtomicBoolean(true);
        listening = active;
        listener = e -> {
            synchronized (DriverFeed.class) {
                if (active.get() && "player.hurt".equals(e.type) && e.data.tree() instanceof Map<?, ?> m) sink.add(m);
            }
        };
        subscribedApi = api;
        api.addEventListener(listener);
    }

    static synchronized void stopListening() {
        if (listener == null) return;
        listening.set(false);
        subscribedApi.removeEventListener(listener);
        listener = null;
        subscribedApi = null;
    }

    /** Send the /damage; null once it went out, else why the verb refused ({@code outcome:"refused"}). */
    static String damageSelf() {
        Object reply = DriverRuntime.requireApi().route("mc.client.chat.send",
                Map.of("text", "/damage @s 2 minecraft:out_of_world"));
        if (reply instanceof Map<?, ?> m && "refused".equals(m.get("outcome"))) {
            Object message = m.get("message");
            return "mc.client.chat.send refused (" + m.get("reason") + ")" + (message == null ? "" : ": " + message);
        }
        return null;
    }
}
