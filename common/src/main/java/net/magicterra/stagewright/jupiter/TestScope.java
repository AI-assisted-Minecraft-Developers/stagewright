package net.magicterra.stagewright.jupiter;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.extension.ExtensionContext;
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

    private final String name;
    private final Set<String> locks;
    private final boolean isolated;
    // Pushed and run on the server thread; read in reverse so a value overridden twice ends as it started.
    private final Deque<Consumer<MinecraftServer>> undo = new ArrayDeque<>();

    private TestScope(String name, Set<String> locks, boolean isolated) {
        this.name = name;
        this.locks = locks;
        this.isolated = isolated;
    }

    /** The read-write locks the test, its class and every enclosing class declare. */
    static TestScope of(ExtensionContext context) {
        Set<String> locks = new HashSet<>();
        boolean isolated = false;
        for (ExtensionContext c = context; c != null; c = c.getParent().orElse(null)) {
            if (c.getElement().isEmpty()) continue;
            AnnotatedElement element = c.getElement().get();
            for (ResourceLock lock : AnnotationSupport.findRepeatableAnnotations(element, ResourceLock.class)) {
                if (lock.mode() == ResourceAccessMode.READ_WRITE) locks.add(lock.value());
            }
            isolated |= AnnotationSupport.isAnnotated(element, Isolated.class);
        }
        return new TestScope(context.getDisplayName(), locks, isolated);
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
        throw new IllegalStateException(what + " in " + name + " changes state every test shares: add"
                + " @ResourceLock(" + constant + ") to the test, or @Isolated to its class");
    }

    /** Server thread: remember how to put back what an override just changed. */
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
