package net.magicterra.stagewright.contract;

/** Terminal result of one scene. Wire values match the orchestration contract v0. */
public enum SceneOutcome {
    PASS, FAIL, TIMEOUT, ENV_FAIL
}
