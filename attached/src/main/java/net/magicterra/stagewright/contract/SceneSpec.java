package net.magicterra.stagewright.contract;

/**
 * One scene as a {@code .js} file declared it, before either home turns it into something runnable.
 *
 * <p>The neutral form. In-process this becomes a {@code Scene} with a {@code SceneContext} body and
 * runs inline on the server tick; out-of-process it becomes an entry in the attached runner's list
 * and every call inside the body is a round trip. Both start here, from the same parse of the same
 * text, which is the mechanical half of the promise that one file runs in two places.
 *
 * <p><b>{@code terrain} / {@code clock} / {@code dimension} are carried even where they cannot be
 * honoured.</b> The out-of-process home does not build arenas and does not pin the world, so it can
 * do nothing with any of them — but it must still PARSE them, because the alternative is a scene
 * file that loads in one home and is a syntax error in the other, and then the shared-file promise
 * is gone at the first option anybody writes. Carrying a value you will not act on is the cheap
 * half; what a home must never do is silently drop the option and run anyway. The attached runner
 * refuses such a scene by name, listing what it cannot honour.
 *
 * @param name        the scene's name, as it will appear in the results file
 * @param budgetTicks the in-process tick budget; meaningless out-of-process (see {@link #wallMsHint})
 * @param optional    declared with {@code scene.optional(...)} — a failure that must not gate
 * @param terrain     which world the arena wants; in-process only
 * @param clock       the time of day the scene wants pinned; in-process only
 * @param dimension   a dimension id belonging to one of the pack's mods, or null
 * @param body        the Rhino {@code Function} for the body, opaque to everything but {@link Scripts}
 * @param scope       the Rhino scope the file was evaluated in, opaque likewise
 */
public record SceneSpec(
        String name,
        int budgetTicks,
        boolean optional,
        Terrain terrain,
        Clock clock,
        String dimension,
        Object body,
        Object scope) {

    /**
     * What {@code budgetTicks} is worth as wall-clock, for a home that has no ticks.
     *
     * <p>Twenty ticks per second is the SERVER's nominal rate, and this conversion is a lie in the
     * one direction that matters: a server draining startup tick debt runs catch-up ticks in about
     * 3ms rather than 50ms, so the same budget buys two to three times as much work there. Which is
     * why this is a HINT used only as an upper bound on a wait, and why the attached results file
     * writes {@code ticks: 0} and reports real milliseconds instead of dividing wall-clock by 50 to
     * manufacture a tick count. A fabricated number would be wrong in a way nobody could see.
     */
    public long wallMsHint() {
        return budgetTicks * 50L;
    }
}
