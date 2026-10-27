package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A named step for YAML and JavaScript tests: {@code name: args} in YAML, {@code t.step(name, args)} in JavaScript.
 * The method is public and static, and takes by type what it needs of {@link Game} and a {@code Map} of the step's
 * arguments; what it returns is the step's result. Its class is found through a {@code META-INF/stagewright/steps}
 * resource on the game's class path, which lists class names one per line.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Step {

    /** The name tests take it by; it may not be a built-in step's, nor one a script registers. */
    String value();
}
