package net.magicterra.stagewright.contract;

/** Assertion/explicit failure raised inside a scene body or step. */
public final class SceneFailure extends RuntimeException {
    public SceneFailure(String message) { super(message); }

    /** A failure that something else caused, kept so a runner can tell what lies behind it. */
    public SceneFailure(String message, Throwable cause) { super(message, cause); }
}
