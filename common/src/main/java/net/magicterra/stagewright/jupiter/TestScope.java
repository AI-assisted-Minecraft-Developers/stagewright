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
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.MultipleFailuresError;

/**
 * The test running on this thread: the locks it declared, and what its overrides must undo when it ends. Bound by
 * {@link GameExtension} around each test method.
 */
final class TestScope {

    private static final ThreadLocal<TestScope> CURRENT = new ThreadLocal<>();

    /** The test whose server task this thread is running; see {@link #lending}. */
    private static final ThreadLocal<TestScope> LENT = new ThreadLocal<>();

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

    /** Whether the test froze the server's ticks, so its waits step them; see {@code Game.freezeTicks}. */
    private volatile boolean frozeTicks;

    /**
     * The factory's scope, for a dynamic test's: what it changes and freezes is the factory's; only its checks are its
     * own.
     */
    private final TestScope parent;

    private TestScope(String name, Set<String> locks, Set<String> readLocks, boolean isolated, boolean script) {
        this(name, locks, readLocks, isolated, script, null);
    }

    private TestScope(
            String name, Set<String> locks, Set<String> readLocks, boolean isolated, boolean script, TestScope parent) {
        this.name = name;
        this.locks = locks;
        this.readLocks = readLocks;
        this.isolated = isolated;
        this.script = script;
        this.parent = parent;
    }

    /**
     * The scope of a dynamic test {@code name} this {@code @TestFactory}'s scope generated: under its locks, which the
     * factory holds until every dynamic test has ended, with checks of its own, reported as that dynamic test ends.
     */
    TestScope dynamic(String name) {
        TestScope root = parent == null ? this : parent;
        return new TestScope(name, locks, readLocks, isolated, script, root);
    }

    /** The locks the test, its class and every enclosing class declare. */
    static TestScope of(ExtensionContext context) {
        Set<String> locks = new HashSet<>();
        Set<String> reads = new HashSet<>();
        boolean isolated = false;
        for (ExtensionContext c = context; c != null; c = c.getParent().orElse(null)) {
            if (c.getElement().isEmpty()) {
                continue;
            }
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
    void hold(boolean sequential) {
        Map<String, Boolean> named = RunLocks.exclusive(locks);
        for (String read : readLocks) {
            named.put(read, false);
        }
        held = RunLocks.hold(named, isolated || sequential);
    }

    /** Give back what {@link #hold} took, if it took anything. */
    // The AutoCloseable hold is the one this scope took, and close() is what its owner expects to throw.
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.CloseResource"})
    void release() throws Exception {
        AutoCloseable h = held;
        held = null;
        if (h != null) {
            h.close();
        }
    }

    static void bind(TestScope scope) {
        CURRENT.set(scope);
    }

    static void unbind() {
        CURRENT.remove();
    }

    /** Make {@code scope} this thread's test, null for none; returns the one it replaces, to swap back. */
    static TestScope swap(TestScope scope) {
        TestScope before = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
        return before;
    }

    /** The test's display name. */
    String name() {
        return name;
    }

    /** Whether this is a script test's. */
    boolean script() {
        return script;
    }

    /** The test on this thread, or the one whose server task this thread is running; or null. */
    static TestScope bound() {
        TestScope scope = CURRENT.get();
        return scope != null ? scope : LENT.get();
    }

    /**
     * Run {@code work} on this thread as part of {@code scope}'s test, for a task the test hands the server thread:
     * what only reads the test or adds to it ({@link #bound}: a soft check, a capability's facet) works there, while
     * what must be undone or given back when the test ends ({@link #current}) is still refused. Nests: the scope lent
     * before is lent again after. With no scope, {@code work} runs as it is.
     */
    static <T> T lending(TestScope scope, Supplier<T> work) {
        if (scope == null) {
            return work.get();
        }
        TestScope before = LENT.get();
        LENT.set(scope);
        try {
            return work.get();
        } finally {
            if (before == null) {
                LENT.remove();
            } else {
                LENT.set(before);
            }
        }
    }

    /** The test that owns this thread; refuses when there is none, a thread it only lent itself to included. */
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
        if (isolated || locks.contains(lock)) {
            return;
        }
        String fix = script
                ? "locks: ['" + lock.substring(LOCK_PREFIX.length()) + "'] to the test's options, or isolated: true"
                : "@ResourceLock(" + constant + ") to the test, or @Isolated to its class";
        throw new IllegalStateException(what + " in " + name + " changes state every test shares: add " + fix);
    }

    boolean frozeTicks() {
        return parent != null ? parent.frozeTicks() : frozeTicks;
    }

    void frozeTicks(boolean frozen) {
        if (parent != null) {
            parent.frozeTicks(frozen);
        } else {
            frozeTicks = frozen;
        }
    }

    /** Keep a failed soft check; {@link #takeChecks} reports it when the test ends. */
    synchronized void failed(AssertionError check) {
        failedChecks.add(check);
    }

    /**
     * What the test's failed soft checks add up to, or null when none failed: the one failure itself, or every one of
     * them under a heading that counts them. Taken once: whoever reports them first, {@link KnownIssueExtension} as the
     * method returns or {@link GameExtension} after it, leaves nothing for the other.
     */
    synchronized AssertionError takeChecks() {
        if (failedChecks.isEmpty()) {
            return null;
        }
        List<AssertionError> failed = List.copyOf(failedChecks);
        failedChecks.clear();
        if (failed.size() == 1) {
            return failed.getFirst();
        }
        return new MultipleFailuresError(failed.size() + " checks failed", failed);
    }

    /** Remember how to put back what an override or a facet just changed; run on the server thread at the end. */
    void onEnd(Consumer<MinecraftServer> restore) {
        if (parent != null) {
            parent.onEnd(restore);
            return;
        }
        synchronized (this) {
            undo.push(restore);
        }
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
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
