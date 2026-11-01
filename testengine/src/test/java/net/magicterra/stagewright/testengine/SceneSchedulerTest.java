package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.ConfigurationParameters;

/** How many scenes run at once, and which never run together. */
class SceneSchedulerTest {

    /** Counts how many jobs of a kind run at once, and the most there ever were. */
    private static final class Gauge {
        private final AtomicInteger now = new AtomicInteger();

        private final AtomicInteger most = new AtomicInteger();

        Runnable job(List<String> log, String name) {
            return () -> {
                most.accumulateAndGet(now.incrementAndGet(), Math::max);
                log.add("start " + name);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.add("end " + name);
                now.decrementAndGet();
            };
        }
    }

    private static List<SceneScheduler.Job> jobs(int n, SceneScheduler.Needs needs, Gauge gauge, List<String> log) {
        List<SceneScheduler.Job> jobs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            jobs.add(new SceneScheduler.Job(needs, gauge.job(log, needs + "#" + i)));
        }
        return jobs;
    }

    @Test
    void aLimitOfOneRunsThemInOrderOnTheCallingThread() throws InterruptedException {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Thread caller = Thread.currentThread();
        List<SceneScheduler.Job> jobs = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            jobs.add(new SceneScheduler.Job(SceneScheduler.Needs.NOTHING, () -> {
                assertEquals(caller, Thread.currentThread());
                log.add(name);
            }));
        }
        new SceneScheduler(1, 1).run(jobs);
        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void neverMoreThanTheLimitRunAtOnce() throws InterruptedException {
        Gauge gauge = new Gauge();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        new SceneScheduler(3, 1).run(jobs(8, SceneScheduler.Needs.NOTHING, gauge, log));
        assertEquals(3, gauge.most.get());
        assertEquals(16, log.size(), "every job ran");
    }

    @Test
    void clientsHaveALimitOfTheirOwnWhileServersFillTheRest() throws InterruptedException {
        Gauge clients = new Gauge();
        Gauge servers = new Gauge();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<SceneScheduler.Job> all = new ArrayList<>();
        all.addAll(jobs(4, new SceneScheduler.Needs(true, null), clients, log));
        all.addAll(jobs(4, SceneScheduler.Needs.NOTHING, servers, log));
        new SceneScheduler(3, 1).run(all);
        assertEquals(1, clients.most.get());
        assertTrue(servers.most.get() >= 2, "a waiting client does not hold a place the servers could use");
    }

    @Test
    void scenesOfATopologyKeepingItsWorldNeverOverlap() throws InterruptedException {
        Gauge kept = new Gauge();
        Gauge other = new Gauge();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<SceneScheduler.Job> all = new ArrayList<>(jobs(3, new SceneScheduler.Needs(false, "/run"), kept, log));
        all.addAll(jobs(2, SceneScheduler.Needs.NOTHING, other, log));
        new SceneScheduler(4, 1).run(all);
        assertEquals(1, kept.most.get());
        assertEquals(2, other.most.get());
    }

    @Test
    void theClientLimitNeverExceedsTheSceneLimit() throws InterruptedException {
        Gauge clients = new Gauge();
        new SceneScheduler(2, 5)
                .run(jobs(
                        4,
                        new SceneScheduler.Needs(true, null),
                        clients,
                        Collections.synchronizedList(new ArrayList<>())));
        assertEquals(2, clients.most.get());
    }

    @Test
    void limitsComeFromTheConfigurationAndAreCheckedThere() {
        Function<Map<String, String>, ConfigurationParameters> params = map -> new ConfigurationParameters() {
            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return get(key).map(Boolean::parseBoolean);
            }

            @Override
            @SuppressWarnings("deprecation")
            public int size() {
                return map.size();
            }

            @Override
            public Set<String> keySet() {
                return map.keySet();
            }
        };
        SceneScheduler.from(params.apply(Map.of()));
        SceneScheduler.from(params.apply(Map.of(SceneScheduler.SCENES, "4", SceneScheduler.CLIENTS, "2")));
        String message = assertThrows(
                        IllegalArgumentException.class,
                        () -> SceneScheduler.from(params.apply(Map.of(SceneScheduler.SCENES, "two"))))
                .getMessage();
        assertTrue(message.contains(SceneScheduler.SCENES), message);
        assertThrows(
                IllegalArgumentException.class,
                () -> SceneScheduler.from(params.apply(Map.of(SceneScheduler.CLIENTS, "0"))));
    }
}
