package net.magicterra.stagewright.jupiter;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Perf;

/** Takes a {@link Perf} measurement for {@code Game.perf}, YAML's {@code perf} step and JS's {@code t.perf}. */
final class PerfSampler {

    private PerfSampler() {}

    /** Each phase's median window by name, in phase order. */
    static Map<String, Perf.Window> measure(Game game, Perf perf) {
        List<Perf.Phase> phases = perf.phaseList();
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("perf: no phases");
        }
        if (perf.sample() < 2 || perf.repeat() < 1 || perf.warmup() < 0) {
            throw new IllegalArgumentException(
                    "perf: sample is at least 2 ticks, repeat at least 1, warmup not negative");
        }
        Map<String, Perf.Window> out = new LinkedHashMap<>();
        for (Perf.Phase phase : phases) {
            if (out.containsKey(phase.name())) {
                throw new IllegalArgumentException("perf: phase '" + phase.name() + "' twice");
            }
            phase.setup().run();
            if (perf.warmup() > 0) {
                game.ticks(perf.warmup());
            }
            List<Perf.Window> windows = new ArrayList<>();
            for (int i = 0; i < perf.repeat(); i++) {
                windows.add(window(game, perf.sample()));
            }
            out.put(phase.name(), median(windows));
        }
        return out;
    }

    /**
     * The measurement as a script sees it: each phase's window as a map, and against the first phase {@code tpsDrop}
     * (0.05 is 5% slower; negative is faster), {@code heapGrowthMib}, {@code cpuGrowth} and, with a client,
     * {@code fpsDrop}.
     */
    static Map<String, Object> report(Map<String, Perf.Window> measured) {
        Map<String, Object> out = new LinkedHashMap<>();
        Perf.Window baseline = null;
        for (Map.Entry<String, Perf.Window> e : measured.entrySet()) {
            Perf.Window w = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticks", (double) w.ticks());
            m.put("wallMs", w.wallMs());
            m.put("tps", w.tps());
            m.put("msptMean", w.msptMean());
            m.put("msptP99", w.msptP99());
            m.put("heapMib", w.heapMib());
            if (!Double.isNaN(w.cpu())) {
                m.put("cpu", w.cpu());
            }
            if (w.hasFps()) {
                m.put("fps", w.fps());
            }
            if (baseline == null) {
                baseline = w;
            } else {
                m.put("tpsDrop", w.tpsDropVs(baseline));
                m.put("heapGrowthMib", w.heapGrowthVs(baseline));
                if (m.containsKey("cpu") && !Double.isNaN(baseline.cpu())) {
                    m.put("cpuGrowth", w.cpuGrowthVs(baseline));
                }
                if (w.hasFps() && baseline.hasFps()) {
                    m.put("fpsDrop", w.fpsDropVs(baseline));
                }
            }
            out.put(e.getKey(), m);
        }
        return out;
    }

    // Retained memory is read after a collection on purpose; the pause falls between sample windows.
    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly")
    private static Perf.Window window(Game game, int sample) {
        long[] at = new long[sample + 1];
        long[] cpu = new long[2];
        long[] fps = {0, 0};
        int[] seen = {0};
        game.await()
                .within(sample + 20)
                .as("a perf window of " + sample + " ticks")
                .until(() -> {
                    int i = seen[0]++;
                    at[i] = System.nanoTime();
                    if (i == 0) {
                        cpu[0] = processCpuNanos();
                    }
                    if (i == sample) {
                        cpu[1] = processCpuNanos();
                    }
                    int frames = clientFps();
                    if (frames >= 0) {
                        fps[0] += frames;
                        fps[1]++;
                    }
                    return seen[0] > sample;
                });
        double[] mspt = new double[sample];
        for (int i = 0; i < sample; i++) {
            mspt[i] = (at[i + 1] - at[i]) / 1e6;
        }
        double wallMs = (at[sample] - at[0]) / 1e6;
        Arrays.sort(mspt);
        Runtime runtime = Runtime.getRuntime();
        // Retained, not peak: inside a client the renderer's garbage would otherwise read as the mod's growth. Taken
        // after the timings, so the pause it may cause falls between windows.
        System.gc();
        return new Perf.Window(
                sample,
                wallMs,
                Math.min(20.0, sample / (wallMs / 1000.0)),
                wallMs / sample,
                mspt[Math.min(sample - 1, (int) Math.ceil(sample * 0.99) - 1)],
                (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0),
                cpu[0] >= 0 && cpu[1] > cpu[0] ? (cpu[1] - cpu[0]) / (wallMs * 1e6) : Double.NaN,
                fps[1] > 0 ? (double) fps[0] / fps[1] : Double.NaN);
    }

    private static Perf.Window median(List<Perf.Window> windows) {
        return new Perf.Window(
                windows.get(0).ticks(),
                median(windows, Perf.Window::wallMs),
                median(windows, Perf.Window::tps),
                median(windows, Perf.Window::msptMean),
                median(windows, Perf.Window::msptP99),
                median(windows, Perf.Window::heapMib),
                median(windows, Perf.Window::cpu),
                median(windows, Perf.Window::fps));
    }

    private static double median(List<Perf.Window> windows, java.util.function.ToDoubleFunction<Perf.Window> field) {
        double[] values = windows.stream()
                .mapToDouble(field)
                .filter(v -> !Double.isNaN(v))
                .sorted()
                .toArray();
        int n = values.length;
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2;
    }

    /**
     * Cumulative process CPU time, or -1. Resolved on the exported interface rather than the bean's own class, whose
     * package {@code jdk.management} does not export: reaching it through {@code getClass()} throws under the module
     * system, and a swallowed throw there once made every CPU figure NaN.
     */
    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime()
                : -1L;
    }

    /**
     * The client's frame rate, or -1 when this JVM has no client. Reflective, so a dedicated server never links a
     * client class; and memoized, because a class loader does not cache failures: unmemoized, NeoForge re-ran the load
     * of {@code Minecraft} on every sample of a dedicated server's window and logged each attempt. Any throwable means
     * no client: Fabric refuses the class with a plain {@code RuntimeException}.
     */
    // Called only from the server thread's sampling, so the lazily cached reflective handles have one writer.
    @SuppressWarnings("PMD.NonThreadSafeSingleton")
    private static int clientFps() {
        if (fpsAbsent) {
            return -1;
        }
        try {
            if (fpsGetFps == null) {
                Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
                fpsGetInstance = mc.getMethod("getInstance");
                fpsGetFps = mc.getMethod("getFps");
            }
            Object instance = fpsGetInstance.invoke(null);
            return instance != null && fpsGetFps.invoke(instance) instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            fpsAbsent = true;
            return -1;
        }
    }

    // Unsynchronized: a racing second resolve reaches the same answer.
    private static boolean fpsAbsent;
    private static Method fpsGetInstance;
    private static Method fpsGetFps;
}
