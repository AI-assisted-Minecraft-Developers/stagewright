package net.magicterra.stagewright.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which endpoint face a test needs. A test annotated for the other face is skipped with a
 * reason naming both faces, so the skip reads as "you held the wrong topology" rather than as
 * absent coverage.
 *
 * <p>Put it on the class, not the method: a class here is a face's worth of assertions, and mixing
 * the two in one file hides which hold a reader has to start.
 *
 * <p>Honoured by {@link StageWrightExtension}, so a class already using that extension needs
 * nothing else. Unannotated classes run against either face.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresFace {
    Face value();
}
