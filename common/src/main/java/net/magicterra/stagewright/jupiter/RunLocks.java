package net.magicterra.stagewright.jupiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The locks Java and script tests share. Jupiter's own {@code @ResourceLock}s keep Java tests apart, but they are
 * Jupiter's and the script tests run beside Jupiter, not in it; so every test, of either kind, also takes its locks
 * here while it runs. A lock is held read-write unless a Java test declared it read-only, and every test holds the
 * isolation lock: read, or write when it must run alone.
 *
 * <p>Taken after a test's dependencies have ended and in name order, so no test holds one of these while it waits.
 */
final class RunLocks {

    private static final ReentrantReadWriteLock ISOLATION = new ReentrantReadWriteLock();
    private static final Map<String, ReentrantReadWriteLock> NAMED = new ConcurrentHashMap<>();

    private RunLocks() {}

    /**
     * Block until the test holds every lock it named, {@code true} meaning read-write, and the isolation lock; closing
     * the answer gives them back, on the same thread.
     */
    static AutoCloseable hold(Map<String, Boolean> locks, boolean alone) {
        List<Lock> held = new ArrayList<>();
        held.add(alone ? ISOLATION.writeLock() : ISOLATION.readLock());
        new TreeMap<>(locks).forEach((name, write) -> {
            ReentrantReadWriteLock lock = NAMED.computeIfAbsent(name, k -> new ReentrantReadWriteLock());
            held.add(write ? lock.writeLock() : lock.readLock());
        });
        for (Lock lock : held) {
            lock.lock();
        }
        return () -> {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        };
    }

    /** {@code names}, every one read-write. */
    static Map<String, Boolean> exclusive(Set<String> names) {
        Map<String, Boolean> locks = new TreeMap<>();
        for (String name : names) {
            locks.put(name, true);
        }
        return locks;
    }
}
