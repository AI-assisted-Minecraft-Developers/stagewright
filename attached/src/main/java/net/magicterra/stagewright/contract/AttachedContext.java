package net.magicterra.stagewright.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scene body gets as {@code s} when it runs OUT of the game process.
 *
 * <p>Same name, same methods, same spelling as the in-process {@code SceneContext} for everything it
 * supports — that is the whole promise of a shared scene file, and it is why this class exists
 * rather than a differently-named "remote" API. What it does NOT support, it refuses BY NAME with
 * the full list of what is available, following {@code Clock.parse}'s rule: never silently degrade.
 *
 * <h2>Why refusing is the feature</h2>
 *
 * <p>The tempting design is to let the world-writing verbs through — {@code setBlock} really could
 * be a command round trip. It must not be. In-process, {@code origin()} is a GRID-ALLOCATED point:
 * the harness gives each scene its own slot 512 blocks from the next, force-loads it, sweeps it
 * afterwards and audits it for leaks. Out here there is no slot allocator, no force-load, no
 * teardown and no audit, so every attached scene would build on top of the previous one's rubble.
 * That is the exact failure {@code RunDirectory.provision} deletes the world to prevent, and its
 * comment says why: reusing one "makes scenes fail in ways that look exactly like product bugs".
 *
 * <p>{@code arena()} throwing is safe — it fails immediately and visibly. {@code setBlock} quietly
 * succeeding is not: it would poison the NEXT scene, which then fails somewhere unrelated to the
 * cause. So the read-only subset is not a first-version shortcut; it is the only version that can
 * report honestly until origin allocation has an answer out here.
 *
 * <h2>Waiting is a different verb, deliberately</h2>
 *
 * <p>There is no {@code await(...).within(ticks)}. In-process that is exact — the body, the tick and
 * the assertion are one atomic thing on one thread. Out here every call is a round trip and the game
 * ticks between any two of them, so "within 20 ticks" cannot be honoured, only approximated by wall
 * clock. And the approximation is wrong in a way that is not even a constant factor: a server
 * draining startup tick debt runs catch-up ticks in ~3ms rather than 50ms. A method that silently
 * meant something different in each home would be worse than not having it, so the wall-clock wait
 * is named {@link #waitUntil} and says so.
 */
public final class AttachedContext implements SceneReport {

    private final String sceneName;
    private final DriverBinding driver;
    private final long budgetMs;
    private final Map<String, Object> records = new LinkedHashMap<>();
    private final List<String> softViolations = new ArrayList<>();
    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private final long startedAtMs;
    private final java.util.function.LongSupplier clockMs;
    private int pollCount;

    public AttachedContext(String sceneName, DriverBinding driver, long budgetMs,
                           java.util.function.LongSupplier clockMs) {
        this.sceneName = sceneName;
        this.driver = driver;
        this.budgetMs = budgetMs;
        this.clockMs = clockMs;
        this.startedAtMs = clockMs.getAsLong();
    }

    // ---- the driver seam -------------------------------------------------------

    /** Call one driver verb. The same {@code driver(...)} a script uses in either home. */
    public Object driver(String method) {
        return driver.route(method, Map.of());
    }

    /** Call one driver verb with parameters. */
    public Object driver(String method, Map<String, Object> params) {
        return driver.route(method, params == null ? Map.of() : params);
    }

    // ---- the supported subset --------------------------------------------------

    /**
     * Run a command at the server.
     *
     * <p>Returns the same {@code CommandResult}-shaped pair the in-process home returns — the numeric
     * result and every line of feedback — because a scene that reads the feedback must read it in
     * both homes. And a REJECTED command raises {@link IllegalArgumentException}, not
     * {@link SceneFailure}: scenes legitimately use a rejection as an ANSWER ({@code data get} on an
     * absent path means the slot is empty) and catch it to return false. Normalising every RPC error
     * to SceneFailure would silently change what those try/catch blocks mean — silently, because both
     * spellings throw.
     */
    public CommandResult command(String cmd) {
        Object raw = driver.route("mc.action.runCommand", Map.of("cmd", cmd));
        if (!(raw instanceof Map<?, ?> m)) {
            throw new SceneFailure("mc.action.runCommand answered " + raw + ", which is not a result");
        }
        boolean success = Boolean.TRUE.equals(m.get("success"));
        List<String> feedback = new ArrayList<>();
        if (m.get("feedback") instanceof List<?> lines) {
            for (Object line : lines) feedback.add(String.valueOf(line));
        }
        if (!success) {
            throw new IllegalArgumentException("command '" + cmd + "' failed"
                    + (feedback.isEmpty() ? "" : ": " + String.join(" / ", feedback)));
        }
        int value = m.get("value") instanceof Number n ? n.intValue() : 0;
        return new CommandResult(value, List.copyOf(feedback));
    }

    /** A command's numeric result and its feedback lines — the same shape both homes return. */
    public record CommandResult(int result, List<String> output) {}

    /** Every mod id in the runtime, read once over RPC. */
    @SuppressWarnings("unchecked")
    public List<String> mods() {
        Object raw = driver.route("mc.system.version", Map.of());
        if (raw instanceof Map<?, ?> m && m.get("mods") instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object id : list) out.add(String.valueOf(id));
            return List.copyOf(out);
        }
        throw new SceneFailure("this runtime's driver does not report a mod list — mc.system.version"
                + " answered " + raw);
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

    @Override
    public void cleanup(Runnable action) {
        cleanups.addFirst(action);
    }

    // ---- everything the out-of-process home structurally cannot do --------------

    public Object arena()     { throw refuse("arena"); }
    public Object origin()    { throw refuse("origin"); }
    public int originX()      { throw refuse("originX"); }
    public int originY()      { throw refuse("originY"); }
    public int originZ()      { throw refuse("originZ"); }
    public Object rel(int dx, int dy, int dz) { throw refuse("rel"); }
    public void setBlock(int dx, int dy, int dz, Object block) { throw refuse("setBlock"); }
    public void floor(int size, Object block) { throw refuse("floor"); }
    public Object level()     { throw refuse("level"); }
    public Object server()    { throw refuse("server"); }
    public Object perf()      { throw refuse("perf"); }
    public Object capability(String name) { throw refuseDiscovery("capability"); }
    public Object capabilities()          { throw refuseDiscovery("capabilities"); }
    public Object probe(String className) { throw refuseDiscovery("probe"); }
    public Object equip()     { throw refusePlayerFacet("equip"); }
    public Object items()     { throw refusePlayerFacet("items"); }
    public Object menu(int dx, int dy, int dz) { throw refusePlayerFacet("menu"); }

    /**
     * Refuse a world-shaping verb, naming what IS available.
     *
     * <p>The message says which of the two reasons applies, because they have different fixes: this
     * one means "rewrite the scene against the supported subset, or run it in-process", and telling
     * an author only that something is unsupported leaves them guessing which.
     */
    private static SceneFailure refuse(String verb) {
        return new SceneFailure("'" + verb + "' does not exist out of process. It is not missing, it"
                + " is structurally absent: an arena is a grid-allocated origin in force-loaded chunks"
                + " with a sweep and a leak audit around it, and none of those are verbs — they are"
                + " the harness. A scene that needs them belongs in config/stagewright/scenes, which"
                + " runs in the game. Available here: command, mods, driver, expect, check, record,"
                + " cleanup, skip, fail, waitUntil.");
    }

    /**
     * Refuse a discovery verb, and say plainly that this is not the same as the mod being absent.
     *
     * <p>The distinction the whole capability surface rests on. {@code CapabilityProvider} is found
     * by {@code ServiceLoader} on the GAME's classpath; out here that scan finds nothing, and
     * "nothing" is exactly what an uninstalled mod also looks like. Reporting the framework's own
     * structural limit as "this pack does not have Mekanism" is the seventh instance of a bug wearing
     * an absent mod's clothes, and this message exists to make the eighth impossible.
     */
    private static SceneFailure refuseDiscovery(String verb) {
        return new SceneFailure("'" + verb + "' cannot be answered out of process — and this is NOT"
                + " the same as the mod being absent. Capability providers are discovered by"
                + " ServiceLoader on the GAME's classpath, and this JVM does not have it, so the scan"
                + " here would report an empty set for a pack that has every one of them. A scene"
                + " gated on a capability belongs in config/stagewright/scenes; use mods() out here to"
                + " branch on what is installed.");
    }

    private static SceneFailure refusePlayerFacet(String verb) {
        return new SceneFailure("'" + verb + "' needs the ServerPlayer instance itself, which does not"
                + " cross a socket — mc.observe.player carries a snapshot, not the entity. A scene"
                + " asserting on inventories, equipment or menus belongs in"
                + " config/stagewright/scenes; driver('mc.observe.player') is available here for what"
                + " the snapshot does carry.");
    }

    // ---- results ---------------------------------------------------------------

    public String sceneName() { return sceneName; }

    public Map<String, Object> records() { return Map.copyOf(records); }

    public List<String> softViolations() { return List.copyOf(softViolations); }

    public long elapsedMs() { return clockMs.getAsLong() - startedAtMs; }

    public long budgetMs() { return budgetMs; }

    /** Run teardown in reverse registration order, swallowing nothing quietly. */
    public List<String> runCleanups() {
        List<String> problems = new ArrayList<>();
        while (!cleanups.isEmpty()) {
            try {
                cleanups.removeFirst().run();
            } catch (RuntimeException e) {
                problems.add(Scripts.message(e));
            }
        }
        return problems;
    }
}
