package net.magicterra.stagewright.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.Reasons;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.magicterra.stagewright.contract.Terrain;
import net.magicterra.stagewright.contract.remote.Wire;
import net.magicterra.stagewright.driver.script.InProcessDriverBinding;
import net.magicterra.stagewright.harness.remote.Dispatch;
import net.magicterra.stagewright.harness.remote.Handles;
import net.magicterra.stagewright.harness.remote.Marshal;
import net.magicterra.stagewright.scene.SceneContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The game's half of an out-of-process run: an arena and a real {@link SceneContext} for each scene
 * the runner opens, and the runner's calls into that context.
 *
 * <p>Everything an in-process scene is given, this gives: the arena is a grid slot in force-loaded
 * chunks ({@link ArenaLease}, {@link ArenaPrep}, the same code the scheduler uses), the context is
 * built the same way, the cleanups run when it ends, and what the scene left behind is swept and
 * audited the same way. What differs is who holds the scene's body. It is a script in another
 * process, so this class only answers when asked, and each answer happens on the server thread
 * between ticks.
 *
 * <p>All of it runs on the server thread — the methods here are called from tasks the
 * {@code mc.test.attached.*} handlers queue there — so none of the state needs a lock.
 */
public final class AttachedSessions {
    /** A run the runner stopped talking to is let go after this long, so a runner that died does not leave the world pinned. */
    private static final long IDLE_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(10);

    /** True between begin and end; read from request threads, written on the server thread. */
    private static volatile boolean active;
    private static final Map<Integer, Session> sessions = new LinkedHashMap<>();
    private static int nextSession = 1;
    /** Never reset within a server's life, so no scene is given a slot a previous one has used. */
    private static int nextSlot;
    private static long lastActivityNanos;

    private AttachedSessions() {}

    public record Begun(boolean pinned, String description) {}

    public record Status(String state, String message) {}

    /** What a call answered: a value, or the failure and the kind of failure it was. */
    public record Reply(boolean ok, JsonElement value, String kind, String message) {
        static Reply value(JsonElement v) {
            return new Reply(true, v, null, null);
        }

        static Reply failed(String kind, String message) {
            return new Reply(false, JsonNull.INSTANCE, kind, message);
        }
    }

    public record Closed(List<String> leaks, Map<String, Object> records, List<String> softViolations,
                         List<String> cleanupFailures) {}

    private enum State { PREPARING, READY }

    private static final class Session {
        final int id;
        final String scene;
        final String dimension;
        final Terrain terrain;
        final Clock clock;
        final int radius;
        final BlockPos gridOrigin;
        final ServerLevel level;
        final ArenaLease lease;
        final ArenaPrep prep;
        final long openedAtMs = System.currentTimeMillis();
        State state = State.PREPARING;
        int ticksWaited;
        SceneContext ctx;
        Handles handles;
        Marshal marshal;
        /** Set once the arena can no longer become usable; the runner reads it once and the session goes. */
        String terminal;
        String terminalMessage;

        Session(int id, String scene, String dimension, Terrain terrain, Clock clock, int radius,
                BlockPos gridOrigin, ServerLevel level, ArenaLease lease, ArenaPrep prep) {
            this.id = id;
            this.scene = scene;
            this.dimension = dimension;
            this.terrain = terrain;
            this.clock = clock;
            this.radius = radius;
            this.gridOrigin = gridOrigin;
            this.level = level;
            this.lease = lease;
            this.prep = prep;
        }
    }

    /** Whether a run is open, for the paths that must not start another thing that pins the world. */
    public static boolean active() {
        return active;
    }

    // ------------------------------------------------------------------ the phase

    public static Begun begin(MinecraftServer server, int protocol) {
        if (protocol != Wire.PROTOCOL) {
            throw new IllegalStateException("the runner speaks out-of-process protocol " + protocol + ", this game"
                    + " speaks " + Wire.PROTOCOL + " — build the runner and the game from the same StageWright");
        }
        if (active) {
            throw new IllegalStateException("an out-of-process run is already open on this server; end it first."
                    + " Two runs would each believe the world was theirs to pin.");
        }
        if (StageWrightCommon.suiteStarted()) {
            throw new IllegalStateException("the in-game suite has been started on this server, and it holds the"
                    + " world pinned; wait for it to finish, or use a server that has not run it");
        }
        WorldPin.applySuite(server);
        active = true;
        touch();
        return new Begun(true, WorldPin.description());
    }

    public static void end(MinecraftServer server) {
        if (!active) return;
        for (Session s : List.copyOf(sessions.values())) {
            StageWrightCommon.LOG.warn("[{}] out-of-process scene '{}' was still open when its run ended;"
                    + " closing it", StageWrightCommon.MOD_ID, s.scene);
            finish(server, s);
        }
        sessions.clear();
        active = false;
        WorldPin.release(server);
    }

    /** The server is going away: let everything go while the world is still there to clean. */
    public static void stop(MinecraftServer server) {
        end(server);
    }

    /** One server tick: advance every arena that is still arriving, and let go of a run that went quiet. */
    public static void tick(MinecraftServer server) {
        if (!active) return;
        for (Session s : sessions.values()) {
            if (s.state == State.PREPARING && s.terminal == null) advance(server, s);
        }
        if (System.nanoTime() - lastActivityNanos > IDLE_LIMIT_NANOS) {
            StageWrightCommon.LOG.warn("[{}] no request from the out-of-process runner for {} minutes — letting its"
                    + " run go", StageWrightCommon.MOD_ID, TimeUnit.NANOSECONDS.toMinutes(IDLE_LIMIT_NANOS));
            end(server);
        }
    }

    private static void touch() {
        lastActivityNanos = System.nanoTime();
    }

    // ------------------------------------------------------------------ one scene

    public static int open(MinecraftServer server, String scene, Terrain terrain, Clock clock, String dimension,
                           int chunkRadius) {
        requireActive();
        touch();
        int id = nextSession++;
        BlockPos grid = Arenas.attachedOriginFor(nextSlot++);
        ServerLevel level = Arenas.levelFor(server, dimension, terrain);
        if (level == null) {
            Session absent = new Session(id, scene, dimension, terrain, clock, chunkRadius, grid, null, null, null);
            // The same two absences, reported the same two ways, as the scheduler reports them.
            if (dimension != null) {
                absent.terminal = Wire.SKIPPED;
                absent.terminalMessage = "dimension '" + dimension + "' is not loaded in this runtime — the mod"
                        + " that registers it is not here. Loaded: " + Arenas.loadedDimensions(server);
            } else {
                absent.terminal = Wire.FAILED;
                absent.terminalMessage = "terrain " + terrain + " needs dimension '" + terrain.dimension()
                        + "', which this server does not have — StageWright's datapack did not load. Loaded: "
                        + Arenas.loadedDimensions(server);
            }
            sessions.put(id, absent);
            return id;
        }
        ArenaLease lease = ArenaLease.acquire(server, level, grid, chunkRadius);
        ArenaPrep prep = new ArenaPrep(server, level, grid, chunkRadius, true);
        sessions.put(id, new Session(id, scene, dimension, terrain, clock, chunkRadius, grid, level, lease, prep));
        return id;
    }

    private static void advance(MinecraftServer server, Session s) {
        s.ticksWaited++;
        ArenaPrep.State arena = s.prep.poll(s.ticksWaited);
        if (arena == ArenaPrep.State.READY) {
            s.ctx = new SceneContext(s.level, Arenas.arenaOrigin(s.dimension, s.terrain, s.level, s.gridOrigin),
                    s.radius);
            // The numbers the scheduler records for every scene, here too, so a slow arena is as visible
            // out of process as it is in.
            s.ctx.record("prep.ticks", s.ticksWaited);
            s.ctx.record("prep.ms", System.currentTimeMillis() - s.openedAtMs);
            s.lease.baseline(s.ctx.origin());
            WorldPin.applyClock(server, s.clock);
            s.handles = new Handles(s.ctx);
            s.marshal = new Marshal(s.handles, new MinecraftRegistries());
            s.state = State.READY;
        } else if (arena == ArenaPrep.State.STUCK) {
            s.terminal = Wire.FAILED;
            s.terminalMessage = s.prep.stuckMessage(s.ticksWaited);
            s.prep.stop();
            s.lease.release(s.scene);
        }
    }

    public static Status status(int id) {
        requireActive();
        touch();
        Session s = session(id);
        if (s.terminal != null) {
            sessions.remove(id);
            return new Status(s.terminal, s.terminalMessage);
        }
        return new Status(s.state == State.READY ? Wire.READY : Wire.PREPARING, "");
    }

    /** One call into the scene's context or an object it handed out. */
    public static Reply call(int id, int target, String method, List<JsonElement> args) {
        requireActive();
        touch();
        Session s = ready(id);
        try {
            List<Object> decoded = new ArrayList<>(args.size());
            for (JsonElement a : args) decoded.add(s.marshal.decode(a));
            Object result = Dispatch.invoke(s.handles.get(target), method, decoded);
            if (s.ctx.pendingSteps() > 0) {
                throw new SceneFailure("'" + method + "' queued a step that waits for ticks, and out of process"
                        + " nothing drives the ticks the step would wait on. Do the waiting here, with waitUntil.");
            }
            return Reply.value(s.marshal.encode(result));
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /** A driver method run on the server thread, as an in-process scene's {@code driver(...)} runs it. */
    public static Reply route(int id, String method, Map<String, Object> params) {
        requireActive();
        touch();
        ready(id);
        if (method.startsWith("mc.test.attached.") || method.equals("mc.test.run")) {
            return Reply.failed(Wire.KIND_ILLEGAL_ARGUMENT, method + " is not for a scene to call: it manages the run"
                    + " the scene is in");
        }
        try {
            Object result = new InProcessDriverBinding().route(method, params);
            return Reply.value(net.magicterra.stagewright.contract.DriverJson.toJson(result));
        } catch (Throwable t) {
            return failure(t);
        }
    }

    public static Closed close(MinecraftServer server, int id) {
        requireActive();
        touch();
        Session s = session(id);
        sessions.remove(id);
        return finish(server, s);
    }

    /** End a scene whatever state it is in: run what it left to clean, sweep, release, audit. */
    private static Closed finish(MinecraftServer server, Session s) {
        if (s.lease == null || s.terminal != null) return new Closed(List.of(), Map.of(), List.of(), List.of());
        List<String> cleanupFailures = new ArrayList<>();
        Map<String, Object> records = Map.of();
        List<String> soft = List.of();
        if (s.ctx != null) {
            cleanupFailures.addAll(s.ctx.runCleanups(msg -> StageWrightCommon.LOG.warn("[{}] {}: {}",
                    StageWrightCommon.MOD_ID, s.scene, msg)));
            records = new LinkedHashMap<>(s.ctx.records());
            soft = s.ctx.softViolations();
        } else {
            s.prep.stop();
        }
        List<String> leaks = s.lease.release(s.scene);
        if (s.handles != null) s.handles.clear();
        return new Closed(leaks, records, soft, cleanupFailures);
    }

    // ------------------------------------------------------------------ plumbing

    private static void requireActive() {
        if (!active) {
            throw new IllegalStateException("no out-of-process run is open on this server: begin one first (or"
                    + " the last one was let go after " + TimeUnit.NANOSECONDS.toMinutes(IDLE_LIMIT_NANOS)
                    + " minutes without a request)");
        }
    }

    private static Session session(int id) {
        Session s = sessions.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no scene session " + id + " is open — it was closed, never opened,"
                    + " or reported as skipped or failed and went with that report");
        }
        return s;
    }

    private static Session ready(int id) {
        Session s = session(id);
        if (s.state != State.READY) {
            throw new IllegalStateException("the arena for '" + s.scene + "' is not ready yet; wait for status to"
                    + " say so before calling into it");
        }
        return s;
    }

    private static Reply failure(Throwable t) {
        // Whatever the method threw, as itself: a refusal stays a refusal when it crosses, so the
        // runner can turn it back into the same kind of exception an in-process scene would have caught.
        String message = Reasons.explainIntermediary(Reasons.message(t));
        if (t instanceof SceneSkipped) return Reply.failed(Wire.KIND_SKIPPED, message);
        if (t instanceof SceneFailure) return Reply.failed(Wire.KIND_FAILED, message);
        if (t instanceof Dispatch.Unresolved || t instanceof UnsupportedOperationException) {
            return Reply.failed(Wire.KIND_UNSUPPORTED, message);
        }
        if (t instanceof IllegalArgumentException) return Reply.failed(Wire.KIND_ILLEGAL_ARGUMENT, message);
        return Reply.failed(Wire.KIND_ERROR, Reasons.unexpected(t));
    }
}
