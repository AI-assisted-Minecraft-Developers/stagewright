package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Run this test (or every test of this class) only in a game that has all of these; elsewhere it is reported as
 * disabled, naming what was missing. A test disabled this way in every scene it names fails the run: a requirement no
 * scene meets is a test that never runs, not one that passes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Requires {

    /** What the game must be. */
    Capability[] value() default {};

    /** Mod ids that must be loaded. */
    String[] mods() default {};

    enum Capability {
        /** A real client is connected: the integrated server's own, or the one a topology joins to its server. */
        CLIENT,
        /** The server is a dedicated server. */
        DEDICATED_SERVER,
        /** The server runs inside a client. */
        INTEGRATED_SERVER
    }
}
