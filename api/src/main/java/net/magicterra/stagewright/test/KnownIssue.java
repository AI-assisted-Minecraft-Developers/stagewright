package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test known to fail, kept running so it is noticed when it stops. Its failure is reported as aborted with this
 * reason and the original failure as the cause; its passing is reported as a failure, so the annotation is removed
 * once the issue is fixed rather than left to hide the next regression.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KnownIssue {

    /** Why it fails: what is broken, and where it is tracked. */
    String value();
}
