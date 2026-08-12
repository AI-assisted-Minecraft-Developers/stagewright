package net.magicterra.stagewright.client;

import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.model.DriverEvent;

/**
 * Every line in StageWright that touches worlddriver, in one class nothing loads by accident.
 *
 * <p><b>Why a class and not four call sites.</b> {@link ClientProbes} needs the driver — it reads the
 * client's own event stream and deals damage through the driver's client verb — but StageWright runs
 * in plenty of JVMs that have no driver, and a missing optional dependency has to be a decision, not
 * a crash. It could not be, while the checks lived inline: a reference to {@code WorldDriverCommon}
 * anywhere in a method makes the JVM resolve that class when the method runs, so
 * {@code if (WorldDriverCommon.api() == null)} throws {@link NoClassDefFoundError} before it can
 * answer. A field typed {@code Consumer<DriverEvent>} is the same reference in a quieter place.
 *
 * <p>Measured, on the first conformance fork to get a companion client of its own: the client joined,
 * armed the probe, and died with {@code NoClassDefFoundError: net/magicterra/worlddriver/WorldDriverCommon}
 * on the tick after — so the server's suite ran with no player and skipped twelve scenes, and neither
 * log said the two facts were the same fact.
 *
 * <p>So the presence test lives in {@link #isPresent()}, which names the class as a string and never
 * links it, and every caller checks that before touching anything else here. Class-loading laziness
 * does the rest: reach none of these methods and this class is never initialised.
 */
final class DriverFeed {

    /** Named as a string on purpose — see the class comment. */
    private static final String DRIVER_CLASS = "net.magicterra.worlddriver.WorldDriverCommon";

    private static Consumer<DriverEvent> listener;

    private DriverFeed() {}

    /**
     * Whether worlddriver is on this classpath AND has built its API.
     *
     * <p>Both halves matter and they fail in different runtimes. The class is absent in a pack that
     * simply does not ship the driver; the API is null in one that does but has not reached client
     * init yet. Neither is an error here — they are the same answer, "not now".
     */
    static boolean isPresent() {
        try {
            Class.forName(DRIVER_CLASS, false, DriverFeed.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
        return WorldDriverCommon.api() != null;
    }

    /** Start forwarding {@code player.hurt} payloads into {@code sink}. Caller has checked presence. */
    static void listenForHurts(Queue<Map<?, ?>> sink) {
        listener = e -> {
            if ("player.hurt".equals(e.type) && e.data instanceof Map<?, ?> m) sink.add(m);
        };
        WorldDriverCommon.api().addEventListener(listener);
    }

    /** Stop forwarding. Safe when nothing was started. */
    static void stopListening() {
        if (listener == null) return;
        WorldDriverCommon.api().removeEventListener(listener);
        listener = null;
    }

    /** Hurt the local player, through the driver's own client verb rather than the connection. */
    static void damageSelf() {
        WorldDriverCommon.api().route("mc.client.chat.send",
                Map.of("text", "/damage @s 2 minecraft:out_of_world"));
    }
}
