package net.magicterra.stagewright.scene;

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

    /** Free-form labels for {@code -Dstagewright.filter}. Not part of the name, not reconciled. */
    String[] tags() default {};
}
