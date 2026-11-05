package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.magicterra.worlddriver.api.registry.Scripting;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

/**
 * A script test's session on one of the scene's peers ({@link PeerServing}): {@code t.peer(name)}. The test engine
 * names each peer's directory, where its WorldDriver writes its RPC port, as {@code stagewright.peer.<name>.dir}, and
 * its log as {@code stagewright.peer.<name>.log}. The first session waits for the peer to come up, as a modpack server
 * takes minutes to; each session has a connection of its own, so two tests at once do not queue behind each other's
 * waits. Closed with its test, which gives back what it leased there.
 */
final class PeerConnection implements CloseableResource {

    static final String PREFIX = "stagewright.peer.";

    private static final Gson GSON = new Gson();

    private static final long CONNECT_MS = 10_000;

    /** How long a peer has to come up, counted from the first test that asks for it. */
    private static final long READY_MS = TimeUnit.MINUTES.toMillis(15);

    private static final long POLL_MS = 1_000;

    /** Longer than any step or wait there, so the peer's own limits are the ones that answer. */
    private static final long CALL_MS = TimeUnit.MINUTES.toMillis(60);

    private static final String HANDLE = "$handle";

    private static final String NAME = "name";

    private static final String WAIT_UNTIL = "waitUntil";

    /** How a message names the peer, its name and a closing quote following. */
    private static final String PEER = "peer '";

    /** What a peer's {@code t} takes a function for, which cannot be sent: each says what to use instead. */
    private static final Set<String> TAKES_A_FUNCTION = Set.of("onServer", "settle", "afterTest", "perf", WAIT_UNTIL);

    /** A frame as {@link StackTraceElement#toString()} writes it, for the peer's stack. */
    private static final Pattern FRAME = Pattern.compile("^(?:.*/)?(.+)\\.([^.(]+)\\((?:(.+?)(?::(\\d+))?)?\\)$");

    private static final Set<String> READY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final String peer;

    private final String session = UUID.randomUUID().toString();

    private final StageWrightRpc rpc;

    private final String log;

    private PeerConnection(String peer, StageWrightRpc rpc, String log) {
        this.peer = peer;
        this.rpc = rpc;
        this.log = log;
    }

    /** {@code t.peer(name)} for the test {@code test}: its session opened, and what it is given back by. */
    static Map<String, Object> open(String peer, String test, List<CloseableResource> leases) {
        String dir = System.getProperty(PREFIX + peer + ".dir");
        if (dir == null) {
            throw new IllegalArgumentException("this scene has no peer '" + peer + "': a scene file declares its"
                    + " peers under peers, each by name");
        }
        String log = System.getProperty(PREFIX + peer + ".log", "(not named)");
        PeerConnection connection = new PeerConnection(peer, connect(peer, Path.of(dir), log), log);
        JsonObject open = new JsonObject();
        open.addProperty(NAME, test);
        JsonElement root = connection.call("open", open);
        leases.add(connection);
        return connection.proxy(0, root);
    }

    /** A connection to {@code peer} once it serves, waiting for it the first time. */
    private static StageWrightRpc connect(String peer, Path dir, String log) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_MS);
        while (true) {
            Attempt attempt = attempt(peer, dir, log);
            if (attempt.rpc() != null) {
                return attempt.rpc();
            }
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException(PEER + peer + "' did not come up in "
                        + TimeUnit.MILLISECONDS.toMinutes(READY_MS) + " minutes: " + attempt.why() + "; its log is "
                        + log);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for peer '" + peer + "'", e);
            }
        }
    }

    /** A connection, or why there is none yet. */
    private record Attempt(StageWrightRpc rpc, String why) {
        static Attempt not(String why) {
            return new Attempt(null, why);
        }
    }

    /** One try at a connection to a peer that serves; a peer that could not load its scripts never will. */
    private static Attempt attempt(String peer, Path dir, String log) {
        Path portFile = dir.resolve("worlddriver-rpc.port");
        if (!Files.isRegularFile(portFile)) {
            return Attempt.not("it wrote no port file in " + dir);
        }
        StageWrightRpc rpc;
        try {
            rpc = StageWrightRpc.connect(
                    "ws://127.0.0.1:" + Files.readString(portFile).trim() + "/rpc", CONNECT_MS);
        } catch (IOException | RuntimeException e) {
            return Attempt.not(String.valueOf(e.getMessage()));
        }
        if (READY.contains(peer)) {
            return new Attempt(rpc, null);
        }
        JsonObject ready;
        try {
            ready = value(rpc.envelope(PeerServing.METHOD, op("ready"), CONNECT_MS));
        } catch (RuntimeException e) {
            rpc.close();
            return Attempt.not(String.valueOf(e.getMessage()));
        }
        if (ready == null || !ready.get("ready").getAsBoolean()) {
            rpc.close();
            return Attempt.not(ready == null ? "it does not serve " + PeerServing.METHOD + " yet" : "it is loading");
        }
        JsonArray errors = ready.getAsJsonArray("loadErrors");
        if (!errors.isEmpty()) {
            rpc.close();
            throw new IllegalStateException(
                    PEER + peer + "' could not load the scene's scripts: " + errors + "; its log is " + log);
        }
        READY.add(peer);
        return new Attempt(rpc, null);
    }

    /** The {@code value} of an answer, or null when the peer refused the call. */
    private static JsonObject value(JsonObject envelope) {
        if (!(envelope.get("result") instanceof JsonObject result)) {
            return null;
        }
        return result.get("value") instanceof JsonObject v ? v : null;
    }

    private static JsonObject op(String op) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        return o;
    }

    private static JsonObject invoke(int handle, String member, List<?> args) {
        JsonObject o = new JsonObject();
        o.addProperty("handle", handle);
        o.addProperty(NAME, member);
        o.add("args", tree(args, member));
        return o;
    }

    /** One call of {@link PeerServing#METHOD} in this session, its failure there thrown here. */
    private JsonElement call(String op, JsonObject params) {
        params.addProperty("op", op);
        params.addProperty("session", session);
        JsonObject envelope = rpc.envelope(PeerServing.METHOD, params, CALL_MS);
        if (envelope.get("error") != null) {
            throw new IllegalStateException(
                    PEER + peer + "' refused " + op + ": " + envelope.get("error") + "; its log is " + log);
        }
        JsonElement value = envelope.getAsJsonObject("result").get("value");
        if (value instanceof JsonObject o && o.get("error") instanceof JsonObject error) {
            Throwable failure = rebuild(error);
            if (failure instanceof AssertionError assertion) {
                throw assertion;
            }
            throw (IllegalStateException) failure;
        }
        return value == null ? JsonNull.INSTANCE : value;
    }

    /** The peer's failure, an assertion still one, carrying the peer's stack and where its log is. */
    private Throwable rebuild(JsonObject error) {
        String message = "on peer '" + peer + "': " + error.get("class").getAsString() + ": "
                + error.get("message").getAsString() + "\nthe peer's log is " + log;
        Throwable cause = error.get("cause") instanceof JsonObject c ? rebuild(c) : null;
        Throwable t = error.get("assertion").getAsBoolean()
                ? new AssertionError(message, cause)
                : new IllegalStateException(message, cause);
        List<StackTraceElement> frames = new ArrayList<>();
        for (JsonElement line : error.getAsJsonArray("stack")) {
            Matcher m = FRAME.matcher(line.getAsString());
            if (m.matches()) {
                frames.add(new StackTraceElement(
                        m.group(1), m.group(2), m.group(3), m.group(4) == null ? -1 : Integer.parseInt(m.group(4))));
            }
        }
        t.setStackTrace(frames.toArray(StackTraceElement[]::new));
        return t;
    }

    /** What the peer answered, as a script sees it: a handle as a map of its fields and its functions. */
    private Object local(JsonElement value) {
        if (value instanceof JsonObject o && o.has(HANDLE)) {
            return proxy(o.get(HANDLE).getAsInt(), o);
        }
        if (value instanceof JsonArray array) {
            List<Object> out = new ArrayList<>();
            array.forEach(item -> out.add(local(item)));
            return out;
        }
        if (value instanceof JsonObject o) {
            Map<String, Object> out = new LinkedHashMap<>();
            o.entrySet().forEach(e -> out.put(e.getKey(), local(e.getValue())));
            return out;
        }
        return GSON.fromJson(value, Object.class);
    }

    private Map<String, Object> proxy(int handle, JsonElement described) {
        JsonObject d = described.getAsJsonObject();
        Map<String, Object> out = new LinkedHashMap<>();
        if (d.get("fields") instanceof JsonObject fields) {
            fields.entrySet().forEach(e -> out.put(e.getKey(), local(e.getValue())));
        }
        for (JsonElement f : d.getAsJsonArray("functions")) {
            String member = f.getAsString();
            if (handle == 0 && TAKES_A_FUNCTION.contains(member)) {
                continue;
            }
            out.put(member, (Scripting.Function)
                    args -> local(call("invoke", invoke(handle, member, Arrays.asList(args)))));
        }
        if (handle == 0) {
            out.put(NAME, peer);
            // waitUntil(step, args, {within, as}): the step, a condition checked on the peer each tick.
            out.put(WAIT_UNTIL, (Scripting.Function) args -> {
                if (!(args.length > 0 && args[0] instanceof String step)) {
                    throw new IllegalArgumentException("t.peer('" + peer + "').waitUntil(step, args, options): the"
                            + " condition is the name of a step the scene's scripts register, which the peer has too");
                }
                JsonObject params = new JsonObject();
                params.addProperty(NAME, step);
                params.add("args", tree(args.length > 1 ? args[1] : null, WAIT_UNTIL));
                params.add("options", tree(args.length > 2 ? args[2] : Map.of(), WAIT_UNTIL));
                call(WAIT_UNTIL, params);
                return null;
            });
        }
        return out;
    }

    /** A script's value as JSON for the peer; a function cannot go, and says what to send instead. */
    private static JsonElement tree(Object v, String member) {
        if (v instanceof Scripting.Function) {
            throw new IllegalArgumentException(member + ": a function cannot be sent to a peer. Register it with"
                    + " step(name, (t, args) => …), which the peer loads too, and call t.peer(…).step(name, args) or"
                    + " .waitUntil(name, args, options)");
        }
        return switch (v) {
            case null -> JsonNull.INSTANCE;
            case Number n
            when n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 0x1p53 ->
                new JsonPrimitive(n.longValue());
            case Number n -> new JsonPrimitive(n);
            case Boolean b -> new JsonPrimitive(b);
            case String s -> new JsonPrimitive(s);
            case List<?> list -> {
                JsonArray out = new JsonArray();
                list.forEach(item -> out.add(tree(item, member)));
                yield out;
            }
            case Map<?, ?> map -> {
                JsonObject out = new JsonObject();
                map.forEach((k, value) -> out.add(String.valueOf(k), tree(value, member)));
                yield out;
            }
            case Object[] array -> tree(Arrays.asList(array), member);
            default ->
                throw new IllegalArgumentException(
                        member + ": a " + v.getClass().getSimpleName() + " cannot be sent to a peer");
        };
    }

    @Override
    public void close() {
        try (rpc) {
            call("close", new JsonObject());
        }
    }

    @Override
    public String toString() {
        return "the session on peer '" + peer + "'";
    }
}
