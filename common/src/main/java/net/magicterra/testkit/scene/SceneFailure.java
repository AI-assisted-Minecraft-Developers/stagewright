package net.magicterra.testkit.scene;

/** Assertion/explicit failure raised inside a scene body or step. */
public final class SceneFailure extends RuntimeException {
    public SceneFailure(String message) { super(message); }
}
