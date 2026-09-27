package net.magicterra.stagewright.junit;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

/**
 * Runs a live class that is wrong on purpose and requires JUnit to report it as failed; why the
 * canary classes that call this stay off {@link StageWrightExtension} is in
 * {@code docs/reference/instrument-contract.md}, "The canaries".
 */
public final class Canaries {

    /** Tag on every wrong-on-purpose target, so the build's own test run leaves them out. */
    public static final String TARGET = "deliberately-wrong";

    private static final String ARMED = "stagewright.canary.armed";

    private Canaries() {}

    /** The same attached handle the extension injects, reached without the extension. */
    public static StageWright live() {
        return StageWrightExtension.shared();
    }

    /** {@code target} must hold one test that fails with a message containing "deliberately-wrong". */
    public static void failOnPurpose(Class<?> target) {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .configurationParameter(ARMED, "true")
                .selectors(selectClass(target))
                .execute();
        results.containerEvents().assertStatistics(s -> s.skipped(0).aborted(0).failed(0));
        results.testEvents().assertStatistics(s -> s.started(1).skipped(0).aborted(0).succeeded(0).failed(1));
        results.testEvents().assertThatEvents().haveExactly(1, event(test(), finishedWithFailure(
                instanceOf(AssertionError.class), message(m -> m.contains("deliberately-wrong")))));
    }

    /**
     * The extension's face gate on its own, for a canary class that must not carry the extension.
     * Delegating keeps the skip reason naming both faces and the one decision in one place.
     */
    public static final class FaceGate implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return new StageWrightExtension().evaluateExecutionCondition(context);
        }
    }

    /** Enables a target only inside {@link #failOnPurpose}, which passes the flag to its own run. */
    public static final class Armed implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return context.getConfigurationParameter(ARMED, Boolean::parseBoolean).orElse(false)
                    ? ConditionEvaluationResult.enabled("run by Canaries.failOnPurpose")
                    : ConditionEvaluationResult.disabled("wrong on purpose; runs only through Canaries.failOnPurpose");
        }
    }
}
