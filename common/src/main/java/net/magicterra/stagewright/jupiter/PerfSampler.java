package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.test.Game;

/**
 * The {@code perf} step: phases measured one after another in the same run, each after its own setup, so a test
 * compares a feature against a baseline taken minutes earlier on the same machine rather than against a constant
 * that encodes whoever wrote it. Each phase waits {@code warmup} ticks, then samples {@code repeat} windows of
 * {@code sample} ticks and reports their median. The tick times are taken by the wait's own condition on the server
 * thread, so nothing is added to the tick when no test measures.
 */
final class PerfSampler {

    /** One phase: its name, and what to do before measuring it (nothing for a baseline). */
    record Phase(String name, Runnable setup) {}

    private PerfSampler() {}

    /**
     * Each phase's median window, by name: {@code ticks}, {@code wallMs}, {@code tps} (capped at 20), {@code msptMean},
     * {@code msptP99}, {@code heapMib} (retained, after a collection hint), and against the first phase
     * {@code tpsDrop} (0.05 is 5% slower; negative is faster) and {@code heapGrowthMib}.
     */
    static Map<String, Object> measure(Game game, List<Phase> phases, int warmup, int sample, int repeat) {
        if (phases.isEmpty()) throw new IllegalArgumentException("perf: no phases");
        if (sample < 2 || repeat < 1 || warmup < 0) {
            throw new IllegalArgumentException("perf: sample is at least 2 ticks, repeat at least 1, warmup not negative");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Double> baseline = null;
        for (Phase phase : phases) {
            if (out.containsKey(phase.name())) throw new IllegalArgumentException("perf: phase '" + phase.name() + "' twice");
            phase.setup().run();
            if (warmup > 0) game.ticks(warmup);
            List<Map<String, Double>> windows = new ArrayList<>();
            for (int i = 0; i < repeat; i++) windows.add(window(game, sample));
            Map<String, Double> median = median(windows);
            Map<String, Object> reported = new LinkedHashMap<>(median);
            if (baseline == null) {
                baseline = median;
            } else {
                reported.put("tpsDrop", (baseline.get("tps") - median.get("tps")) / baseline.get("tps"));
                reported.put("heapGrowthMib", median.get("heapMib") - baseline.get("heapMib"));
            }
            out.put(phase.name(), reported);
        }
        return out;
    }

    private static Map<String, Double> window(Game game, int sample) {
        long[] at = new long[sample + 1];
        int[] seen = {0};
        game.await().within(sample + 20).as("a perf window of " + sample + " ticks").until(() -> {
            at[seen[0]++] = System.nanoTime();
            return seen[0] > sample;
        });
        double[] mspt = new double[sample];
        for (int i = 0; i < sample; i++) mspt[i] = (at[i + 1] - at[i]) / 1e6;
        double wallMs = (at[sample] - at[0]) / 1e6;
        Arrays.sort(mspt);
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("ticks", (double) sample);
        w.put("wallMs", wallMs);
        w.put("tps", Math.min(20.0, sample / (wallMs / 1000.0)));
        w.put("msptMean", wallMs / sample);
        w.put("msptP99", mspt[Math.min(sample - 1, (int) Math.ceil(sample * 0.99) - 1)]);
        w.put("heapMib", (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0));
        return w;
    }

    private static Map<String, Double> median(List<Map<String, Double>> windows) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : windows.get(0).keySet()) {
            double[] values = windows.stream().mapToDouble(w -> w.get(key)).sorted().toArray();
            int n = values.length;
            out.put(key, n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2);
        }
        return out;
    }
}
