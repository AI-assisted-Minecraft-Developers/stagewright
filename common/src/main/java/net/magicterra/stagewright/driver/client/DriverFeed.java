package net.magicterra.stagewright.driver.client;

import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.magicterra.stagewright.driver.DriverRuntime;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.registry.EventFeed;

/** Event subscription owns the feed it registered with. */
final class DriverFeed {
    private static EventFeed subscribedFeed;
    private static Consumer<EventContracts.Event> listener;
    private static AtomicBoolean listening;

    private DriverFeed() {}

    static synchronized void listenForHurts(Queue<Map<?, ?>> sink) {
        stopListening();
        DriverRuntime.requireApi();
        EventFeed feed = WorldDriver.host();
        AtomicBoolean active = new AtomicBoolean(true);
        listening = active;
        listener = e -> {
            synchronized (DriverFeed.class) {
                if (active.get() && EventCatalog.PLAYER_HURT.type().equals(e.type())
                        && e.data().isPresent() && e.data().get().tree() instanceof Map<?, ?> m) sink.add(m);
            }
        };
        subscribedFeed = feed;
        feed.addEventListener(listener);
    }

    static synchronized void stopListening() {
        if (listener == null) return;
        listening.set(false);
        subscribedFeed.removeEventListener(listener);
        listener = null;
        subscribedFeed = null;
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
