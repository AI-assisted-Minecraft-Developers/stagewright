package net.magicterra.stagewright.test;

import java.util.ArrayList;
import java.util.List;

/**
 * A measurement for {@link Game#perf}: phases run one after another in the same run, each after its own setup, so a
 * test compares a feature against a baseline taken minutes earlier on the same machine rather than against a
 * constant that encodes whoever wrote it.
 *
 * <pre>{@code
 * Map<String, Perf.Window> r = game.perf(Perf.phases()
 *         .phase("baseline", () -> {})
 *         .phase("loaded", () -> buildFiveHundredWaystones(game)));
 * assertThat(r.get("loaded").tpsDropVs(r.get("baseline"))).isLessThan(0.05);
 * }</pre>
 *
 * <p>Each phase waits {@link #warmup} ticks, then samples {@link #repeat} windows of {@link #sample} ticks and
 * reports their median. The samples are taken by a wait's own condition on the server thread, so nothing is added to
 * the tick when no test measures.
 */
public final class Perf {

    /** One phase: its name, and what to do before measuring it (nothing for a baseline). */
    public record Phase(String name, Runnable setup) {}

    /**
     * One phase's median window.
     *
     * @param ticks    server ticks in a window
     * @param wallMs   wall time of a window
     * @param tps      ticks per second, capped at 20, the rate the server paces itself to
     * @param msptMean mean interval between ticks, in ms
     * @param msptP99  99th-percentile interval, in ms: where a stutter shows while the mean stays flat
     * @param heapMib  used heap at window close after a collection hint: retained, not peak
     * @param cpu      process CPU time over wall time, as a fraction of one core (not divided by the core count, so
     *                 the same regression reads the same on every host); NaN where the JVM does not report it
     * @param fps      mean client frame rate, or NaN when this JVM has no client: every dedicated server, the
     *                 server half of {@code dedicatedServerWithClient} included. Only {@code integratedServer}
     *                 measures it.
     */
    public record Window(int ticks, double wallMs, double tps, double msptMean, double msptP99, double heapMib,
                         double cpu, double fps) {

        /** Fractional TPS drop against a baseline: 0.05 is 5% slower, negative is faster. */
        public double tpsDropVs(Window baseline) {
            return (baseline.tps - tps) / baseline.tps;
        }

        /** Growth in retained heap over a baseline, in MiB: catches the order-of-magnitude leak, not a small one. */
        public double heapGrowthVs(Window baseline) {
            return heapMib - baseline.heapMib;
        }

        /** Growth in CPU over a baseline, in cores. */
        public double cpuGrowthVs(Window baseline) {
            return cpu - baseline.cpu;
        }

        /** Fractional frame-rate drop against a baseline; NaN without a client, so guard on {@link #hasFps}. */
        public double fpsDropVs(Window baseline) {
            return baseline.fps > 0 ? (baseline.fps - fps) / baseline.fps : Double.NaN;
        }

        /** Whether this window saw a client frame rate. */
        public boolean hasFps() {
            return !Double.isNaN(fps);
        }
    }

    private final List<Phase> phases = new ArrayList<>();
    private int warmup = 200;
    private int sample = 600;
    private int repeat = 3;

    private Perf() {}

    /** An empty measurement; add at least one {@link #phase}, the first being the baseline. */
    public static Perf phases() {
        return new Perf();
    }

    /** Add a phase measured after {@code setup} runs on this thread. */
    public Perf phase(String name, Runnable setup) {
        phases.add(new Phase(name, setup));
        return this;
    }

    /** Ticks to let pass after a phase's setup before its first window; 200 by default. */
    public Perf warmup(int ticks) {
        this.warmup = ticks;
        return this;
    }

    /** Ticks in one window, at least 2; 600 by default. */
    public Perf sample(int ticks) {
        this.sample = ticks;
        return this;
    }

    /** Windows per phase, whose median is reported; 3 by default. */
    public Perf repeat(int windows) {
        this.repeat = windows;
        return this;
    }

    public List<Phase> phaseList() {
        return List.copyOf(phases);
    }

    public int warmup() {
        return warmup;
    }

    public int sample() {
        return sample;
    }

    public int repeat() {
        return repeat;
    }
}
