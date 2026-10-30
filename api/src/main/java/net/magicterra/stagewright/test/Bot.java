package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A server-side player WorldDriver joins for one test, injected as a test method parameter: it is on the player list as
 * any player is, so mobs, advancements and chunk loading see it. It joins at the world spawn in survival with an empty
 * inventory and full health and hunger, and leaves when the test ends unless the test {@linkplain Keep keeps} it for a
 * later one.
 */
public interface Bot extends Body, Driven {

    @Override
    default String ref() {
        return Body.super.ref();
    }

    @Override
    Bot park();

    @Override
    Bot unpark();

    /** The name a parameter's bot joins under; without it, one StageWright makes up. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Name {

        /** A player name: letters, digits and underscores, at most 16. */
        String value();
    }

    /**
     * The parameter's bot joins {@linkplain #park parked}, before the server has ticked it once, so it stays where the
     * test puts it until the test ticks it.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Parked {}

    /**
     * The parameter's bot stays in the world when the test ends, parked, with what it has and where it stands, for a
     * test that {@code @DependsOn} this one to take as {@link Kept} under the same {@link Name}. A test that fails or
     * is aborted removes it instead, and a kept bot no test takes leaves when the run's tests are over. It must stand
     * outside the test's plots, which are put back when the test ends. A test that takes it after a restart
     * ({@code @DependsOn(afterRestart = true)}) finds it where the game before left it: the bot left that game as a
     * disconnecting player does, which saved it, and joins the next one from that save, in the same dimension, with the
     * same inventory and state.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Keep {}

    /**
     * The parameter's bot is the one a test this one {@code @DependsOn} kept under the same {@link Name}: the same
     * player, where it stood and with what it had, back on the tick list unless the parameter is also {@link Parked};
     * across a restart, the same player joined again from its save. The test fails when no passed test kept a bot of
     * that name, or another test took it first.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Kept {}
}
