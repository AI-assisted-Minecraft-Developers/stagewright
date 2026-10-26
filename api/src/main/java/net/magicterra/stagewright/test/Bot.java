package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A server-side player WorldDriver joins for one test, injected as a test method parameter: it is on the player list
 * as any player is, so mobs, advancements and chunk loading see it. It joins at the world spawn in survival with an
 * empty inventory and full health and hunger, and leaves when the test ends.
 */
public interface Bot extends Body {

    /** The name a parameter's bot joins under; without it, one StageWright makes up. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Name {

        /** A player name: letters, digits and underscores, at most 16. */
        String value();
    }
}
