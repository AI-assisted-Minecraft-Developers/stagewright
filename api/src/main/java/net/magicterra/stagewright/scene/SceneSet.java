package net.magicterra.stagewright.scene;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Namespace for every {@link SceneDef} method on the annotated class: a scene declared as
 * {@code magnetPullsWithinRadius} inside {@code @SceneSet("sb")} registers as
 * {@code sb.magnetPullsWithinRadius}.
 *
 * <p>The namespace is not decoration. The orchestrator's manifest reconciliation derives which
 * scenes it is entitled to judge from the prefixes present in the expected-scenes file, which is
 * what keeps StageWright's own built-ins (which carry no dot) out of a consumer's verdict. A
 * consumer that skips the namespace gets scenes that no manifest can bound.
 *
 * <p>Optional: an unannotated class yields bare method names, which is fine for StageWright's own
 * built-ins and wrong for everyone else.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SceneSet {
    /** Namespace prefix, without the dot. */
    String value();
}
