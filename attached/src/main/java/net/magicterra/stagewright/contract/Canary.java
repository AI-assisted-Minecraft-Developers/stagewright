package net.magicterra.stagewright.contract;

/**
 * Sentinel scenes that verify the framework can still CATCH failures (spec §5 金丝雀).
 * The orchestrator judges each run dead unless every canary lands on its expected
 * outcome: MUST_FAIL -> FAIL, MUST_TIMEOUT -> TIMEOUT, MUST_SWALLOW -> registered
 * in the suite header but deliberately never executed (no scene record) — the
 * reconciler must flag exactly that omission.
 *
 * <p>{@link #MUST_SKIP} is the fourth, and it guards a gate the other three do not: a scene whose
 * subject IS the skip — an absent capability, a dimension no mod in this runtime registers. Such a
 * scene records PASS carrying a reason, which until now was indistinguishable in the results from a
 * scene that ran and passed, so those scenes asserted nothing about themselves (the javadoc on
 * {@code dimensionAbsentIsARecordedSkip} says so in as many words). If the absence detection they
 * exercise ever broke they would execute, fall through, and stay green. MUST_SKIP turns "it skipped"
 * into the assertion it was always meant to be, and a MUST_SKIP scene that executes is judged DEAD
 * for the same reason MUST_SWALLOW is: what broke is the apparatus every other suite's skips are
 * trusted through, so this run's greens are no longer evidence of anything.
 */
public enum Canary {
    NONE, MUST_FAIL, MUST_TIMEOUT, MUST_SWALLOW, MUST_SKIP
}
