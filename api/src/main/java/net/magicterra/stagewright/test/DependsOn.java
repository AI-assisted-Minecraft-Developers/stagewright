package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Run this test after the named tests, on the world state they leave behind. They run first; if any of them fails,
 * is aborted, or is not part of the run, this test is aborted with that reason instead of failing on a world that
 * is not what it expects. A cycle of dependencies fails the run before any test starts.
 *
 * <p>In a parallel run a class whose tests depend on one another runs them one at a time; a test depending on one in
 * another class waits for it, holding its own {@code @ResourceLock}s meanwhile, so the two must not share a lock.
 *
 * <p>Script tests run beside the Java tests, so a test may depend on one of those too, named in {@link #scripts}. It
 * waits for it in any run, and holds Jupiter's {@code @ResourceLock}s while it waits; a script test takes no Jupiter
 * lock, so only a Java test that script test depends on in turn must not share one with this test.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DependsOn {

    /** Test method names. */
    String[] value() default {};

    /** The class those methods are in; without it, this test's own class. */
    Class<?> in() default Void.class;

    /**
     * Script tests, each as {@code file#name}, the file being its class path resource:
     * {@code "stagewright/tests/setup.js#builds the base"}.
     */
    String[] scripts() default {};
}
