package net.magicterra.stagewright.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.CustomJavaToJsWrapper;
import net.magicterra.stagewright.contract.remote.RemoteFace;
import net.magicterra.stagewright.contract.remote.RemoteSession;
import net.magicterra.stagewright.contract.remote.Wire;

/**
 * What a scene body gets as {@code s} when it runs OUT of the game process.
 *
 * <p>Same name, same methods, same spelling as the in-process {@code SceneContext}, and that is
 * kept true by construction rather than by a list. The assertion vocabulary and the few verbs that
 * only make sense on this side ({@link #waitUntil}, {@link #cleanup}, {@link #driver}) are methods of
 * this class. Every other name a script uses is sent to the game, where it is looked up on the real
 * {@code SceneContext} the scene's arena was built for — so a method added there exists here the
 * same day, and a method that does not exist there fails here with the game's own answer.
 *
 * <h2>Why the arena is the game's</h2>
 *
 * <p>An origin in process is a GRID-ALLOCATED point: the harness gives each scene its own slot,
 * force-loads it, sweeps it afterwards and audits it for leaks. Out here none of that can be
 * reimplemented without drifting from it, so none of it is: the game allocates the arena for this
 * scene through the same scheduler code and holds the context, and this class holds a
 * {@link RemoteSession} to it. {@code setBlock} is then exactly {@code SceneContext.setBlock}, on the
 * server thread, in a plot that gets swept and audited when the scene ends.
 *
 * <h2>What still cannot cross</h2>
 *
 * <p>Ticks. In process the body, the tick and the assertion are one atomic thing on one thread; out
 * here every call is a round trip and the game ticks between any two of them. So there is no
 * {@code await(...).within(ticks)} — it would have to mean something different here, and a method
 * that silently meant something different in each home would be worse than not having it. The
 * wall-clock wait is named {@link #waitUntil} and says what it is.
 */
public final class AttachedContext implements SceneReport, CustomJavaToJsWrapper {

    private final String sceneName;
    private final DriverBinding driver;
    private final long budgetMs;
    private final Map<String, Object> records = new LinkedHashMap<>();
    private final List<String> softViolations = new ArrayList<>();
    private final Cleanups cleanups = new Cleanups(this);
    private final long startedAtMs;
    private final java.util.function.LongSupplier clockMs;
    private int pollCount;
    private RemoteSession remote;

    public AttachedContext(String sceneName, DriverBinding driver, long budgetMs,
                           java.util.function.LongSupplier clockMs) {
        this.sceneName = sceneName;
        this.driver = driver;
        this.budgetMs = budgetMs;
        this.clockMs = clockMs;
        this.startedAtMs = clockMs.getAsLong();
    }

    /** Give this context its arena in the game; until then every name that belongs to the game fails by saying so. */
    public void attach(RemoteSession session) {
        this.remote = session;
    }

    /** The scene's hold on the game, or null when it has none. */
    public RemoteSession session() {
        return remote;
    }

    /** What the script receives: this object, with the names it does not answer sent to the game. */
    @Override
    public Scriptable convertJavaToJs(Context cx, Scriptable scope, TypeInfo staticType) {
        return new RemoteFace(scope, this, cx, remote, Wire.CONTEXT);
    }

    // ---- the driver seam -------------------------------------------------------

    /** Call one driver verb. The same {@code driver(...)} a script uses in either home. */
    public Object driver(String method) {
        return driver(method, Map.of());
    }

    /**
     * Call one driver verb with parameters.
     *
     * <p>A {@code mc.test.*} verb goes by way of the game's server thread, because that is where an
     * in-process scene calls it from and several of them answer only there ({@code mc.test.bots}
     * refuses any other thread). Everything else is an ordinary driver call. The split is made on the
     * name because the registry already guarantees what the name means: only StageWright may register
     * under {@code mc.test.}, and it registers nothing else.
     */
    public Object driver(String method, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        if (managesTheRun(method)) {
            // Refused here, not sent: these end or restart the run this scene is a part of, and a scene
            // that did so by accident would take the arena it is standing in with it.
            throw new IllegalArgumentException(method + " is not for a scene to call: it manages the run the scene"
                    + " is in");
        }
        if (remote != null && method.startsWith("mc.test.")) return remote.route(method, p);
        return driver.route(method, p);
    }

    private static boolean managesTheRun(String method) {
        return method.startsWith("mc.test.attached.") || method.equals("mc.test.run");
    }

    // ---- assertions ------------------------------------------------------------

    /** Hard assertion: the first violation ends the scene. */
    public Expect expect(Object actual) {
        return new Expect(this, false, actual);
    }

    /** Soft assertion: violations are collected and reported together at the end. */
    public Expect check(Object actual) {
        return new Expect(this, true, actual);
    }

    // ---- waiting ---------------------------------------------------------------

    /**
     * Poll a condition until it holds or the wall clock runs out. Attached-only, and named for what
     * it is — see the class comment for why this is not {@code await(...).within(ticks)}.
     *
     * <p>Records how many times it polled. A wait that made twelve thousand round trips is a fact
     * about the run that should be visible in the results rather than discovered from a wall-clock
     * total, and the in-process home has no equivalent cost to hide.
     */
    public void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs, long pollMs) {
        long deadline = clockMs.getAsLong() + timeoutMs;
        int polls = 0;
        while (clockMs.getAsLong() < deadline) {
            polls++;
            if (condition.getAsBoolean()) {
                pollCount += polls;
                record("waitUntil.polls", pollCount);
                return;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SceneFailure("interrupted while waiting in scene '" + sceneName + "'");
            }
        }
        pollCount += polls;
        record("waitUntil.polls", pollCount);
        throw new SceneFailure("waited " + timeoutMs + "ms (" + polls + " polls) and the condition"
                + " never held");
    }

    // ---- SceneReport -----------------------------------------------------------

    @Override
    public void violation(String message, boolean soft) {
        if (soft) {
            softViolations.add(message);
            return;
        }
        throw new SceneFailure(message);
    }

    @Override
    public void record(String key, Object value) {
        records.put(key, value);
    }

    @Override
    public void fail(String reason) {
        throw new SceneFailure(reason);
    }

    @Override
    public void skip(String reason) {
        throw new SceneSkipped(reason);
    }

    /** One registered by a cleanup does not run, as in-process. */
    @Override
    public void cleanup(Runnable action) {
        cleanups.add(action);
    }

    // ---- the one thing that cannot cross -----------------------------------------

    public Object await(Object condition) { throw refuseTime("await"); }
    public Object advance()               { throw refuseTime("advance"); }
    public Object runBody(Object body)    { throw refuseTime("runBody"); }
    public int ticks()                    { throw refuseTime("ticks"); }

    /**
     * Refuse a verb that is about ticks, naming what to use instead.
     *
     * <p>Not forwarded, though the game would answer: {@code await} takes a function the game cannot
     * run, and {@code ticks} would answer the game-side context's tick count, which nothing here ever
     * advances — a zero that reads like a fact.
     */
    private static SceneFailure refuseTime(String verb) {
        return new SceneFailure("'" + verb + "' is counted in ticks, and out of process the game ticks between any"
                + " two calls: a tick count cannot be honoured, only approximated by the wall clock. Use"
                + " waitUntil(condition, timeoutMs, pollMs) here, or move the scene to config/stagewright/scenes"
                + " where the harness runs it between two ticks.");
    }

    // ---- results ---------------------------------------------------------------

    public String sceneName() { return sceneName; }

    public Map<String, Object> records() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(records)); }

    public List<String> softViolations() { return List.copyOf(softViolations); }

    public long elapsedMs() { return clockMs.getAsLong() - startedAtMs; }

    public long budgetMs() { return budgetMs; }

    /** Run teardown in reverse registration order; each failure is warned about and returned. */
    public List<String> runCleanups(java.util.function.Consumer<String> warn) {
        return cleanups.run(warn);
    }
}
