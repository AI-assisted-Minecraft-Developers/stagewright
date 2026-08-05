package net.magicterra.stagewright.junit;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.Optional;

/**
 * JUnit 5 extension that attaches to a live endpoint once per JVM and injects the
 * shared {@link StageWright} into test constructors/methods.
 *
 * <pre>{@code
 * @ExtendWith(StageWrightExtension.class)
 * class SomeUiTest {
 *     @Test void opensInventory(StageWright tk) { ... }
 * }
 * }</pre>
 *
 * <p><b>Serial lease.</b> {@link StageWright#attach()} runs exactly once per JVM (the
 * singleton below). A failed attach is a LOUD container-level error, never a skip:
 * {@link #beforeAll} lets {@link StageWrightAttachException} propagate so the whole
 * container is reported as errored — the intended behaviour is "you forgot to start a hold",
 * which must not be silently swallowed as a disabled test.
 *
 * <p><b>Face gating.</b> A class carrying {@link RequiresFace} is skipped when the attached
 * endpoint is the other face — a UI test against a dedicated server, or a no-player assertion
 * against a client. That is the one skip this extension issues, and it names both faces so it reads
 * as "wrong hold", not as missing coverage. With no endpoint configured at all, nothing is skipped
 * here: {@code @EnabledIfEnvironmentVariable} has already disabled the class, and probing would
 * mean attaching in order to decide whether to attach.
 */
public final class StageWrightExtension
        implements BeforeAllCallback, ParameterResolver, ExecutionCondition {

    private static final Object LOCK = new Object();
    private static volatile StageWright instance;
    private static volatile StageWrightAttachException attachFailure;
    private static volatile boolean attempted;

    /** Attach the shared singleton once; re-throw the same failure on later calls. */
    static StageWright shared() {
        StageWright local = instance;
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
                instance = StageWright.attach();
                return instance;
            } catch (StageWrightAttachException e) {
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
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<RequiresFace> required = context.getElement()
                .map(e -> e.getAnnotation(RequiresFace.class))
                .filter(a -> a != null);
        if (required.isEmpty()) {
            return ConditionEvaluationResult.enabled("no face requirement");
        }
        if (!StageWright.endpointConfigured()) {
            // Nothing to probe, and probing anyway would attach — turning "no hold is running" into
            // a container initialisation ERROR on every live class instead of the ordinary disable
            // that @EnabledIfEnvironmentVariable is about to issue.
            return ConditionEvaluationResult.enabled("no endpoint configured; the env gate decides");
        }
        Face want = required.get().value();
        Face have = Face.of(shared());
        return want == have
                ? ConditionEvaluationResult.enabled("endpoint face is " + have)
                : ConditionEvaluationResult.disabled("needs a " + want + "-face endpoint; the one"
                        + " held is " + have + " — start the other topology's Hold task");
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == StageWright.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return shared();
    }
}
