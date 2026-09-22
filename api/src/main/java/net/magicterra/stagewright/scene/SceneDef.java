package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.Terrain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a scene. The method must be {@code static}, take a single {@link SceneContext},
 * and return {@code void}; {@link Stages#scan} rejects anything else loudly rather than skipping it,
 * because a scene that silently fails to register is a hole in the suite that reads as GREEN.
 *
 * <p>The scene's NAME comes from the method name, prefixed by the class's {@link SceneSet}. That is
 * the whole point of the annotation over {@code Scene.of("sb.magnetPulls", 200, X::magnetPulls)},
 * where the name is written twice and the two copies can drift apart under a rename.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SceneDef {

    /** Tick budget. Exceeding it is a TIMEOUT, not a FAIL — the distinction the orchestrator reports. */
    int budget() default 200;

    /** Forced-chunk window, (2r+1)² chunks around the origin. r=1 covers dx/dz in [-16, 31]. */
    int chunkRadius() default 1;

    /** Pin to a fixed origin slot (>= 1024). Required for byte-determinism-sensitive scenes: auto
     *  slots shift as the suite grows, and double-precision physics differs by position. */
    int originSlot() default -1;

    /** {@code false} records FAIL/TIMEOUT without breaking GREEN — for faithful sensors of known
     *  product bugs. Being SWALLOWED still REDs regardless. */
    boolean required() default true;

    /** The ground the arena is built on. Defaults to the run world's empty sky at the grid
     *  altitude — ask for {@link Terrain#SUPERFLAT} or {@link Terrain#GENERATED} to stand on
     *  actual terrain instead. */
    Terrain terrain() default Terrain.RUN_WORLD;

    /** The time of day the scene runs at. Defaults to the frozen midnight the whole run is pinned
     *  to — ask for {@link Clock#NOON} when daylight is the subject, {@link Clock#RUNNING} when the
     *  passage of time is. */
    Clock clock() default Clock.MIDNIGHT;

    /**
     * Run this scene's arena in a dimension another mod registers, e.g.
     * {@code "twilightforest:twilight_forest"}. Empty (the default) means the run's own world.
     *
     * <p>Mutually exclusive with {@link #terrain()} — a terrain IS a dimension StageWright ships, so
     * asking for both asks for the arena to be in two places, and {@link Scene} rejects it at
     * construction rather than silently honouring one.
     *
     * <p>A dimension that is not present reports a recorded SKIP naming it, not a failure: the mod
     * that owns it is simply not in this runtime. That is the opposite of a missing {@code terrain}
     * dimension, which means StageWright's own datapack failed to load and is an ENV_FAIL.
     */
    String dimension() default "";

    /**
     * This scene's subject IS the skip: it asks for something this runtime does not have and the
     * recorded SKIP is the assertion. The verdict then requires it, and calls the run DEAD if the
     * scene executes instead.
     *
     * <p>Only for scenes that can never be satisfied where they live — a {@code nosuchmod:} id, a
     * facet whose mod the suite's own runtime deliberately excludes. <b>Not</b> a way to excuse a
     * scene that skips because the topology is thin: that scene should execute somewhere, and
     * marking it here would suppress exactly the coverage report that would have said so.
     */
    boolean mustSkip() default false;

    /** Free-form labels for the reader. Nothing selects or reconciles by them; a run is narrowed by
     *  scene name alone, through {@code -Pstagewright.scenes}. */
    String[] tags() default {};
}
