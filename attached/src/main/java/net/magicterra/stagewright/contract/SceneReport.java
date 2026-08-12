package net.magicterra.stagewright.contract;

/**
 * How a running scene reports what happened — the half of {@code SceneContext} that has nothing to
 * do with a world.
 *
 * <p>This interface is the seam that lets ONE {@link Expect} serve both homes. In-process,
 * {@code SceneContext} implements it and the violation lands on the harness's record for that
 * scene; out-of-process, the attached runner implements it and the violation lands in
 * {@code stagewright-attached-results.jsonl}. Neither home owns a copy of the assertion vocabulary,
 * so an assertion cannot mean two things.
 *
 * <p><b>Why an interface rather than a shared base class:</b> {@code SceneContext} already exists,
 * is the published contract every consumer compiles against, and carries thirty world-facing
 * methods. Making it extend anything would put the world half and the reporting half into one
 * inheritance chain, and the whole point of this module is that the reporting half compiles with no
 * Minecraft on the classpath.
 *
 * <p>Deliberately small. Everything here is something a scene does to the RESULT of the run —
 * nothing here reads or writes the world, and nothing may be added that does. The test for a new
 * method is whether an out-of-process runner with no game attached could still implement it
 * honestly; if it could only throw, it belongs on {@code SceneContext} instead.
 */
public interface SceneReport {

    /**
     * One assertion failed. {@code soft} distinguishes {@code check} from {@code expect}: a soft
     * violation is recorded and execution continues so a body probing twenty blocks reports all
     * twenty offenders, while a hard one ends the scene immediately.
     */
    void violation(String message, boolean soft);

    /** Attach a measured value to this scene's result. The numbers that make a green run readable. */
    void record(String key, Object value);

    /** End the scene as FAILED, with a reason. Throws; never returns. */
    void fail(String reason);

    /**
     * End the scene as a recorded SKIP with a reason — the framework's central rule: a thing that is
     * not present is not a failure. Throws; never returns.
     */
    void skip(String reason);

    /** Register teardown to run when the scene ends, whichever way it ends. */
    void cleanup(Runnable action);
}
