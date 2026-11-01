package net.magicterra.stagewright.testengine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.platform.engine.ConfigurationParameters;

/**
 * Runs scenes, up to {@code scenes} at once, of which up to {@code clients} start a game client, and never two that
 * share one topology's kept world. Scenes start in the order given, each as soon as what it needs is free; with a limit
 * of one they run one after another, as they always did.
 *
 * <p>The client limit is its own because clients share one display: only one window has the focus, and Minecraft
 * throttles the frame rate of the others, which slows their game ticks too.
 */
final class SceneScheduler {

    /** How many scenes may run at once; 1, the default, runs them one after another. */
    static final String SCENES = "stagewright.parallel.scenes";

    /** How many of those may start a game client; 1 by default. */
    static final String CLIENTS = "stagewright.parallel.clients";

    /** What one scene holds while it runs. */
    record Needs(boolean client, String keptWorld) {
        static final Needs NOTHING = new Needs(false, null);
    }

    /** One scene to run, and what it holds. */
    record Job(Needs needs, Runnable run) {}

    private final int scenes;

    private final int clients;

    private int running;

    private int runningClients;

    private final Set<String> busyWorlds = new HashSet<>();

    private RuntimeException failure;

    SceneScheduler(int scenes, int clients) {
        if (scenes < 1 || clients < 1) {
            throw new IllegalArgumentException(
                    "-D" + SCENES + " and -D" + CLIENTS + " are at least 1, not " + scenes + " and " + clients);
        }
        this.scenes = scenes;
        this.clients = Math.min(clients, scenes);
    }

    /** The limits {@code parameters} set, the system properties and {@code junit-platform.properties} included. */
    static SceneScheduler from(ConfigurationParameters parameters) {
        return new SceneScheduler(limit(parameters, SCENES), limit(parameters, CLIENTS));
    }

    private static int limit(ConfigurationParameters parameters, String key) {
        String value = parameters.get(key).orElse("1").trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("-D" + key + " is a whole number, not '" + value + "'", e);
        }
    }

    /** Run every job, returning once all have ended; on the calling thread when only one may run at a time. */
    void run(List<Job> jobs) throws InterruptedException {
        if (scenes == 1) {
            jobs.forEach(j -> j.run().run());
            return;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<Job> pending = new ArrayList<>(jobs);
        List<Thread> threads = new ArrayList<>();
        synchronized (this) {
            while (!pending.isEmpty()) {
                Job next = pending.stream().filter(this::fits).findFirst().orElse(null);
                if (next == null) {
                    wait();
                    continue;
                }
                pending.remove(next);
                take(next.needs());
                Thread thread = new Thread(() -> runAndRelease(next), "stagewright-scene-" + threads.size());
                thread.setContextClassLoader(loader);
                threads.add(thread);
                thread.start();
            }
        }
        for (Thread t : threads) {
            t.join();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void runAndRelease(Job job) {
        try {
            job.run().run();
        } catch (RuntimeException e) {
            synchronized (this) {
                if (failure == null) {
                    failure = e;
                }
            }
        } finally {
            synchronized (this) {
                running--;
                if (job.needs().client()) {
                    runningClients--;
                }
                if (job.needs().keptWorld() != null) {
                    busyWorlds.remove(job.needs().keptWorld());
                }
                notifyAll();
            }
        }
    }

    private boolean fits(Job job) {
        Needs needs = job.needs();
        return running < scenes
                && (!needs.client() || runningClients < clients)
                && (needs.keptWorld() == null || !busyWorlds.contains(needs.keptWorld()));
    }

    private void take(Needs needs) {
        running++;
        if (needs.client()) {
            runningClients++;
        }
        if (needs.keptWorld() != null) {
            busyWorlds.add(needs.keptWorld());
        }
    }
}
