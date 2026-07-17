package net.magicterra.testkit.junit;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that attaches to a live endpoint once per JVM and injects the
 * shared {@link Testkit} into test constructors/methods.
 *
 * <pre>{@code
 * @ExtendWith(TestkitExtension.class)
 * class SomeUiTest {
 *     @Test void opensInventory(Testkit tk) { ... }
 * }
 * }</pre>
 *
 * <p><b>Serial lease.</b> {@link Testkit#attach()} runs exactly once per JVM (the
 * singleton below). A failed attach is a LOUD container-level error, never a skip:
 * {@link #beforeAll} lets {@link TestkitAttachException} propagate so the whole
 * container is reported as errored — the intended behaviour is "you forgot to start
 * {@code t1.py --hold}", which must not be silently swallowed as a disabled test.
 */
public final class TestkitExtension implements BeforeAllCallback, ParameterResolver {

    private static final Object LOCK = new Object();
    private static volatile Testkit instance;
    private static volatile TestkitAttachException attachFailure;
    private static volatile boolean attempted;

    /** Attach the shared singleton once; re-throw the same failure on later calls. */
    static Testkit shared() {
        Testkit local = instance;
        if (local != null) {
            return local;
        }
        synchronized (LOCK) {
            if (instance != null) {
                return instance;
            }
            if (attempted) {
                // a prior attach failed — surface the same loud error, do not retry
                throw attachFailure;
            }
            attempted = true;
            try {
                instance = Testkit.attach();
                return instance;
            } catch (TestkitAttachException e) {
                attachFailure = e;
                throw e;
            }
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        shared(); // attach eagerly so failure is a container error, not a per-test surprise
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == Testkit.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return shared();
    }
}
