package net.magicterra.stagewright.jupiter;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.MultipleFailuresError;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The test running on this thread: the locks it declared, and what its overrides must undo when it ends. Bound by
 * {@link GameExtension} around each test method.
 */
final class TestScope {

    private static final ThreadLocal<TestScope> CURRENT = new ThreadLocal<>();

    /** How a script names a lock: a {@link net.magicterra.stagewright.test.Locks} value without this prefix. */
    static final String LOCK_PREFIX = "stagewright.";

    private final String name;
    private final Set<String> locks;
    private final Set<String> readLocks;
    private final boolean isolated;
    private final boolean script;
    // Pushed and run on the server thread; read in reverse so a value overridden twice ends as it started.
    private final Deque<Consumer<MinecraftServer>> undo = new ArrayDeque<>();
    private final List<AssertionError> failedChecks = new ArrayList<>();
    private AutoCloseable held;

    private TestScope(String name, Set<String> locks, Set<String> readLocks, boolean isolated, boolean script) {
        this.name = name;
        this.locks = locks;
        this.readLocks = readLocks;
        this.isolated = isolated;
        this.script = script;
    }

    /** The locks the test, its class and every enclosing class declare. */
    static TestScope of(ExtensionContext context) {
        Set<String> locks = new HashSet<>();
        Set<String> reads = new HashSet<>();
        boolean isolated = false;
        for (ExtensionContext c = context; c != null; c = c.getParent().orElse(null)) {
            if (c.getElement().isEmpty()) continue;
            AnnotatedElement element = c.getElement().get();
            for (ResourceLock lock : AnnotationSupport.findRepeatableAnnotations(element, ResourceLock.class)) {
                (lock.mode() == ResourceAccessMode.READ_WRITE ? locks : reads).add(lock.value());
            }
            isolated |= AnnotationSupport.isAnnotated(element, Isolated.class);
        }
        reads.removeAll(locks);
        return new TestScope(context.getDisplayName(), locks, reads, isolated, false);
    }

    /** A script test's, with the locks its {@code locks} option names, already prefixed. */
    static TestScope ofScript(String name, Set<String> locks, boolean isolated) {
        return new TestScope(name, locks, Set.of(), isolated, true);
    }

    /**
     * Take this Java test's locks in {@link RunLocks}, which keep it from the script tests running beside it; alone
     * when it is isolated or the Java tests run one at a time, as each of them then runs alone.
     */
    void hold(boolean oneAtATime) {
        Map<String, Boolean> named = RunLocks.exclusive(locks);
        for (String read : readLocks) named.put(read, false);
        held = RunLocks.hold(named, isolated || oneAtATime);
    }

    /** Give back what {@link #hold} took, if it took anything. */
    void release() throws Exception {
        AutoCloseable h = held;
        held = null;
        if (h != null) h.close();
    }

    static void bind(TestScope scope) {
        CURRENT.set(scope);
    }

    static void unbind() {
        CURRENT.remove();
    }

    /** The test on this thread, or null. */
    static TestScope bound() {
        return CURRENT.get();
    }

    /** The test on this thread; refuses when there is none. */
    static TestScope current(String what) {
        TestScope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException(what + " is undone when its test ends, so it may only be called from a"
                    + " test method's own thread");
        }
        return scope;
    }

    /** Refuse {@code what} unless the test holds {@code lock} read-write or is isolated. */
    void require(String lock, String constant, String what) {
        if (isolated || locks.contains(lock)) return;
        String fix = script
                ? "locks: ['" + lock.substring(LOCK_PREFIX.length()) + "'] to the test's options, or isolated: true"
                : "@ResourceLock(" + constant + ") to the test, or @Isolated to its class";
        throw new IllegalStateException(what + " in " + name + " changes state every test shares: add " + fix);
    }

    /** Keep a failed soft check; {@link #checks} reports it when the test ends. */
    synchronized void failed(AssertionError check) {
        failedChecks.add(check);
    }

    /**
     * What the test's failed soft checks add up to, or null when none failed: the one failure itself, or every one
     * of them under a heading that counts them.
     */
    synchronized AssertionError checks() {
        if (failedChecks.isEmpty()) return null;
        if (failedChecks.size() == 1) return failedChecks.getFirst();
        return new MultipleFailuresError(failedChecks.size() + " checks failed", List.copyOf(failedChecks));
    }

    /** Remember how to put back what an override or a facet just changed; run on the server thread at the end. */
    synchronized void onEnd(Consumer<MinecraftServer> restore) {
        undo.push(restore);
    }

    /** Whether an override is waiting to be undone. */
    synchronized boolean changedAnything() {
        return !undo.isEmpty();
    }

    /** Server thread: undo every override, last first; the first failure is rethrown once all have run. */
    synchronized void restore(MinecraftServer server) {
        RuntimeException first = null;
        while (!undo.isEmpty()) {
            try {
                undo.pop().accept(server);
            } catch (RuntimeException e) {
                if (first == null) first = e;
                else first.addSuppressed(e);
            }
        }
        if (first != null) throw first;
    }
}
