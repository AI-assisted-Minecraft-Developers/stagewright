package net.magicterra.stagewright.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.harness.ResultsJsonl;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneOutcome;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.model.DriverEvent;
import net.minecraft.client.Minecraft;

/**
 * Assertions that can only be made from inside a real client attached to a real dedicated server,
 * run in the CLIENT's JVM and reported through their own results file.
 *
 * <h2>Why this exists at all</h2>
 * Every scene body runs on the SERVER thread. On the integrated topology that still reaches
 * client-only state, because both ends share a JVM. On {@code dedicatedServerWithClient} they do
 * not, and a whole class of assertion becomes unreachable from a scene: anything whose subject is
 * the BOUNDARY between the two processes. That is not a gap worth working around with a socket back
 * into the client — {@link ClientDirector}'s javadoc explains why the previous incarnation of this
 * framework deleted exactly that seam. The client asserts its own side, in-process, and its verdict
 * is merged by the same {@code Verdict} the server's results go through.
 *
 * <h2>The probe</h2>
 * {@code client.damageSourceAcrossTheWire} is the half of the instrument contract's damage-source
 * check that no scene can cover. {@code player.hurt} is emitted client-side by the driver's
 * {@code ClientEventDetector}, mirroring the server's DamageSource off
 * {@code ClientboundDamageEventPacket}. On an integrated server the two ends exchange those packets
 * through an in-memory connection that never serialises them, so the scene
 * {@code wd.hurtCarriesItsSource} proves the attribution logic but not the wire. Here the packet is
 * encoded, sent over a socket, and decoded before anything reads it — which is the difference
 * between "works in singleplayer" and "works on a server", the most expensive bug class a modpack
 * has.
 *
 * <p>It listens through {@link net.magicterra.worlddriver.api.DriverApi#addEventListener}, the same
 * fan-out that feeds live push subscribers, so what is asserted is what an agent attached to this
 * client would actually have received — not a re-read of the client state the event was derived
 * from, which would pass even if the event were never emitted.
 *
 * <h2>Self-contained on purpose</h2>
 * The probe causes its own damage rather than waiting for a server-side scene to do it. Two
 * harnesses in two processes agreeing on when something should have happened is a synchronisation
 * problem with no handshake to solve it, and the failure mode — one side waiting out its budget
 * because the other took a different path — reports as a timeout on the wrong side of the run.
 * Driving both halves from here costs one {@code /damage} and removes the question.
 */
public final class ClientProbes {

    /** Beside the server's {@code stagewright-results.jsonl}, in the CLIENT's run directory. */
    private static final String OUT_FILE = "stagewright-client-results.jsonl";

    private static final String PROBE = "client.damageSourceAcrossTheWire";

    /** Ticks after entering the world before the probe fires. Covers the join settling and, more
     *  importantly, the i-frames of anything that hurt this player on the way in — out_of_world
     *  bypasses the invulnerable FLAG but not the damage COOLDOWN, so a hit inside the window is
     *  refused unless it exceeds the last one. */
    private static final int SETTLE_TICKS = 60;

    /** How long to wait for the event after the damage lands. Generous: it is one round trip, but a
     *  budget that is merely adequate turns an unlucky tick into a red run. */
    private static final int WAIT_TICKS = 400;

    private static final int BUDGET_TICKS = SETTLE_TICKS + WAIT_TICKS;

    private enum Phase { OFF, SETTLING, WAITING, DONE }

    private static Phase phase = Phase.OFF;
    private static int ticks;
    private static long startedMs;
    private static String loader = "unknown";

    /** Events arrive on the driver's dispatch thread, so this crosses threads. */
    private static final ConcurrentLinkedQueue<Map<?, ?>> hurts = new ConcurrentLinkedQueue<>();
    private static Consumer<DriverEvent> listener;

    private ClientProbes() {}

    /**
     * Start probing. Called once the client is in a world, and only on the topology that needs it —
     * on the integrated topology the equivalent scene runs in this same JVM already, and running
     * both would report the same fact twice under two names.
     */
    public static synchronized void arm(String loaderName) {
        if (phase != Phase.OFF) return;
        loader = loaderName;
        phase = Phase.SETTLING;
        ticks = 0;
        startedMs = System.currentTimeMillis();
        listener = e -> {
            if ("player.hurt".equals(e.type) && e.data instanceof Map<?, ?> m) hurts.add(m);
        };
        WorldDriverCommon.api().addEventListener(listener);
        StageWrightCommon.LOG.info("[{}] client probes armed ({})", StageWrightCommon.MOD_ID, PROBE);
    }

    /** One client tick. Cheap and safe to call in every phase, including before {@link #arm}. */
    public static synchronized void tick() {
        switch (phase) {
            case SETTLING -> {
                if (++ticks < SETTLE_TICKS) return;
                hurts.clear();          // anything from the join is not what this measures
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) {
                    report(SceneOutcome.FAIL, "no client player to damage", Map.of());
                    return;
                }
                // Through the driver's own client verb rather than the connection directly: it is
                // the surface a pack author has, and it is what the retired python check drove.
                WorldDriverCommon.api().route("mc.client.chat.send",
                        Map.of("text", "/damage @s 2 minecraft:out_of_world"));
                phase = Phase.WAITING;
            }
            case WAITING -> {
                for (Map<?, ?> hurt : hurts) {
                    Object source = hurt.get("source");
                    if (source instanceof String s && !s.isEmpty()) {
                        report(SceneOutcome.PASS, "", Map.of("source", s,
                                "lost", String.valueOf(hurt.get("lost"))));
                        return;
                    }
                }
                if (++ticks - SETTLE_TICKS < WAIT_TICKS) return;
                report(hurts.isEmpty() ? SceneOutcome.TIMEOUT : SceneOutcome.FAIL,
                        hurts.isEmpty()
                                ? "no player.hurt reached this client within " + WAIT_TICKS
                                  + " ticks of a /damage that the server accepted"
                                : "player.hurt arrived without source attribution — an HP delta"
                                  + " survived the wire but the DamageSource did not: " + hurts.peek(),
                        Map.of());
            }
            default -> { }
        }
    }

    /**
     * Write a verdict for a probe that never resolved, so a client that is dropped mid-probe still
     * produces a judgeable file. A missing results file reads as "the harness never armed", which
     * would blame the framework for what is usually the server ending the run early.
     */
    public static synchronized void finishIfUnresolved() {
        if (phase == Phase.OFF || phase == Phase.DONE) return;
        report(SceneOutcome.TIMEOUT, "the client left the world before the probe resolved (phase="
                + phase + ")", Map.of());
    }

    private static void report(SceneOutcome outcome, String reason, Map<String, Object> data) {
        phase = Phase.DONE;
        if (listener != null) {
            WorldDriverCommon.api().removeEventListener(listener);
            listener = null;
        }
        ResultsJsonl out = new ResultsJsonl(Path.of(OUT_FILE));
        // A header/footer pair so this file is judged by the same contract as the server's, rather
        // than being a bespoke format some second parser has to learn.
        out.writeSuiteHeader(loader, List.of(Scene.of(PROBE, BUDGET_TICKS, ctx -> { })));
        out.writeScene(PROBE, outcome, ticks, System.currentTimeMillis() - startedMs, reason, data);
        out.writeDone(1);
        StageWrightCommon.LOG.info("[{}] client probe {} -> {} {}",
                StageWrightCommon.MOD_ID, PROBE, outcome, reason);
    }
}
