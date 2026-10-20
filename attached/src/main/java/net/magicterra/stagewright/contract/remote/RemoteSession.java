package net.magicterra.stagewright.contract.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.Reasons;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.magicterra.stagewright.contract.Terrain;

/**
 * One scene's hold on the game: an arena the game allocated, and a context in it that this side
 * calls into.
 *
 * <p>The context is the same {@code SceneContext} an in-process scene gets, built by the same
 * scheduler code over the same grid, so everything the game does for a scene it does for this one —
 * the arena in force-loaded chunks, the clock, the sweep and the leak audit when it ends. What this
 * class adds is only the telephone: a call goes over the driver, runs on the server thread, and
 * what it answers is turned back into something a script can hold.
 */
public final class RemoteSession {
    /** How often to ask whether the arena has arrived. A tick is 50 ms; sooner buys nothing. */
    private static final long POLL_MS = 25;

    private final DriverBinding driver;
    private final int id;
    private final String scene;
    private final RemoteCodec codec = new RemoteCodec();

    private RemoteSession(DriverBinding driver, int id, String scene) {
        this.driver = driver;
        this.id = id;
        this.scene = scene;
    }

    /** What a scene left behind, as the game found it when it let the arena go. */
    public record Closed(List<String> leaks, Map<String, Object> records, List<String> softViolations,
                         List<String> cleanupFailures) {
        public static Closed none() {
            return new Closed(List.of(), Map.of(), List.of(), List.of());
        }
    }

    /** The options of {@code scene(...)} that decide what world it stands in. */
    public record Options(Terrain terrain, Clock clock, String dimension, int chunkRadius) {}

    /**
     * Ask the game for an arena and wait for it.
     *
     * <p>Two calls rather than one that waits: the arena takes real ticks to arrive, and a request
     * that holds its caller for that long would put the arrival time inside every transport's
     * timeout. The game decides when waiting has stopped helping, exactly as it does in process, so
     * this side only has to keep asking.
     *
     * @throws SceneSkipped when the scene names a dimension this runtime does not have — an absent
     *         mod is not a defect, as in process
     * @throws SceneFailure when the arena never became usable
     */
    public static RemoteSession open(DriverBinding driver, String scene, Options options, LongSupplier clockMs,
                                     long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scene", scene);
        params.put("terrain", options.terrain().name().toLowerCase(java.util.Locale.ROOT));
        params.put("clock", options.clock().name().toLowerCase(java.util.Locale.ROOT));
        if (options.dimension() != null) params.put("dimension", options.dimension());
        params.put("chunkRadius", options.chunkRadius());
        Map<String, Object> opened = map(driver.route(Wire.OPEN, params), Wire.OPEN);
        RemoteSession session = new RemoteSession(driver, number(opened.get("session"), "session"), scene);

        long deadline = clockMs.getAsLong() + timeoutMs;
        while (true) {
            Map<String, Object> status = map(driver.route(Wire.STATUS, Map.of("session", session.id)), Wire.STATUS);
            String state = String.valueOf(status.get("state"));
            String message = status.get("message") == null ? "" : String.valueOf(status.get("message"));
            switch (state) {
                case Wire.READY -> {
                    return session;
                }
                case Wire.SKIPPED -> throw new SceneSkipped(message);
                case Wire.FAILED -> throw new SceneFailure(message);
                case Wire.PREPARING -> { }
                default -> throw new SceneFailure(Wire.STATUS + " answered state '" + state + "', which this runner"
                        + " does not know — the game and the runner speak different versions of the protocol");
            }
            if (clockMs.getAsLong() >= deadline) {
                session.closeQuietly();
                throw new SceneFailure("the arena for '" + scene + "' did not arrive within " + timeoutMs
                        + " ms, and the game had not given up on it either");
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.closeQuietly();
                throw new SceneFailure("interrupted while waiting for the arena of '" + scene + "'");
            }
        }
    }

    /**
     * Call {@code method} on the scene's context ({@link Wire#CONTEXT}) or on an object it handed out.
     *
     * @param args as a script passed them; sent as the wire form of each
     * @return the answer, in the form a script can hold
     */
    public Object call(int target, String method, Object[] args) {
        List<Object> wire = new ArrayList<>(args.length);
        for (Object a : args) wire.add(argument(codec.encode(a, this)));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("session", id);
        params.put("target", target);
        params.put("method", method);
        params.put("args", wire);
        Map<String, Object> reply = map(driver.route(Wire.CALL, params), Wire.CALL);
        if (Boolean.TRUE.equals(reply.get("ok"))) return codec.decode(reply.get("value"), this);
        throw failure(reply, method);
    }

    /** The game's schema holds an argument list of values, and null is not one: it goes as a marker. */
    private static Object argument(Object encoded) {
        if (encoded != null) return encoded;
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put(Wire.NULL, true);
        return marker;
    }

    /** Call a driver method on the server thread, as an in-process scene's {@code driver(...)} does. */
    public Object route(String method, Map<String, Object> params) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("session", id);
        wire.put("method", method);
        wire.put("params", params);
        Map<String, Object> reply = map(driver.route(Wire.ROUTE, wire), Wire.ROUTE);
        if (Boolean.TRUE.equals(reply.get("ok"))) return codec.decode(reply.get("value"), this);
        throw failure(reply, method);
    }

    /** Let the arena go. The game runs what the scene left to clean, sweeps, audits and answers. */
    public Closed close() {
        Map<String, Object> reply = map(driver.route(Wire.CLOSE, Map.of("session", id)), Wire.CLOSE);
        List<String> leaks = strings(reply.get("leaks"));
        List<String> soft = strings(reply.get("softViolations"));
        Map<String, Object> records = new LinkedHashMap<>();
        if (reply.get("records") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> records.put(String.valueOf(k), v));
        }
        return new Closed(leaks, records, soft, strings(reply.get("cleanupFailures")));
    }

    /** {@link #close}, for the path that is already failing and must not mask why. */
    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // The failure being reported is the one that matters; a dead connection fails here too.
        }
    }

    int id() {
        return id;
    }

    String scene() {
        return scene;
    }

    RemoteRef ref(int handle, String type) {
        return new RemoteRef(this, handle, type);
    }

    /** The exception a failed call ended in, as the same type an in-process scene would have seen. */
    private RuntimeException failure(Map<String, Object> reply, String method) {
        String kind = String.valueOf(reply.get("kind"));
        String message = reply.get("message") == null ? "" : String.valueOf(reply.get("message"));
        return switch (kind) {
            case Wire.KIND_SKIPPED -> new SceneSkipped(message);
            case Wire.KIND_FAILED -> new SceneFailure(message);
            case Wire.KIND_ILLEGAL_ARGUMENT -> new IllegalArgumentException(message);
            case Wire.KIND_UNSUPPORTED -> new SceneFailure("'" + method + "' cannot be called from out of"
                    + " process: " + message);
            default -> new SceneFailure(Reasons.explainIntermediary(message));
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String method) {
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new SceneFailure(method + " answered " + raw + ", which is not a result");
    }

    private static int number(Object v, String what) {
        if (v instanceof Number n) return n.intValue();
        throw new SceneFailure("the game's answer carried no " + what + " (" + v + ")");
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }
}
