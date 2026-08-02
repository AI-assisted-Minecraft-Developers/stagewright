package net.magicterra.stagewright.scene;

/**
 * Sentinel scenes that verify the framework can still CATCH failures (spec §5 金丝雀).
 * The orchestrator judges each run dead unless every canary lands on its expected
 * outcome: MUST_FAIL -> FAIL, MUST_TIMEOUT -> TIMEOUT, MUST_SWALLOW -> registered
 * in the suite header but deliberately never executed (no scene record) — the
 * reconciler must flag exactly that omission.
 */
public enum Canary {
    NONE, MUST_FAIL, MUST_TIMEOUT, MUST_SWALLOW
}
