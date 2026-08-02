package net.magicterra.stagewright.scene;

/** Terminal result of one scene. Wire values match the orchestration contract v0. */
public enum SceneOutcome {
    PASS, FAIL, TIMEOUT, ENV_FAIL
}
