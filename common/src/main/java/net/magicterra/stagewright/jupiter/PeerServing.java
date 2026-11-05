package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.MethodSpec.Effect;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.minecraft.server.MinecraftServer;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

/**
 * A peer: a second dedicated server of a scene, which runs no tests of its own but serves the scene's game. It loads
 * the scene's script files as the game does, keeping the steps they register and ignoring their tests, and answers
 * {@value #METHOD} over its WorldDriver RPC. Each test of the game that reaches this peer opens a session here, which
 * holds a {@code t} of this game's own, as a script test's: what the session leases, a bot or a plot, is given back
 * when the game's test ends and closes it.
 *
 * <p>What a session hands out is sent as JSON. A value holding functions, as {@code t.bot()} answers, stays here as a
 * handle: the game is sent its plain fields, its function names and a number, and calls them by that number. The
 * session's {@code t} is handle 0.
 */
public final class PeerServing {

    /** The peer's name in the scene, set on a peer's command line only. */
    static final String SERVE = "stagewright.peer.serve";

    static final String METHOD = "mc.test.peer";

    private static final Gson GSON = new Gson();

    private static final String HANDLE = "$handle";

    private static final String ERROR = "error";

    private static final RecordType<Call> CALL = ApiTypes.record(Call.class);

    private static final RecordType<Answer> ANSWER = ApiTypes.record(Answer.class);

    static final MethodSpec<Call, Answer> SPEC = MethodSpec.of(
                    METHOD,
                    CALL,
                    ANSWER,
                    "A scene's peer server, serving the test of the scene's game that opened the session: ready, open,"
                            + " invoke, waitUntil or close. Peers only.")
            .hidden()
            .effects(Effect.WORLD_WRITE, Effect.BODY_TASK)
            .mayWait();

    /** Each call on a thread of its own, as a test's: it may block on ticks as long as its test would. */
    private static final ExecutorService CALLS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stagewright-peer");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Scripting.Function> STEPS = Collections.synchronizedMap(new LinkedHashMap<>());

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final List<String> LOAD_ERRORS = Collections.synchronizedList(new ArrayList<>());

    private static volatile boolean started;

    private static volatile boolean ready;

    private static volatile GameImpl game;

    private PeerServing() {}

    /** A call: the op, and what it takes. */
    @Named("TestPeerCall")
    public record Call(
            @Doc("ready, open, invoke, waitUntil or close.") String op,

            @Doc("The session, one per test of the scene's game.")
            Optional<String> session,

            @Doc("open: the test's name; invoke: the member; waitUntil: the step.")
            Optional<String> name,

            @Doc("invoke: the handle, 0 for the session's t.")
            Optional<Integer> handle,

            @Doc("invoke: the arguments, a list; waitUntil: the step's argument.")
            Optional<JsonValue> args,

            @Doc("waitUntil: the options, {within, as}.") Optional<JsonValue> options) {}

    /** The answer: a value, or {@code {error: {class, message, assertion, stack}}}. */
    @Named("TestPeerAnswer")
    public record Answer(@Doc("The value, as JSON.") Optional<JsonValue> value) {}

    /** Whether this game is a peer. */
    static boolean serving() {
        return System.getProperty(SERVE) != null;
    }

    /** The {@value #METHOD} method, for {@code mc.test.*}. */
    public static ApiMethod<Call, Answer> method() {
        return new ApiMethod<>(
                SPEC,
                (call, ctx) -> CompletableFuture.supplyAsync(
                        () -> {
                            JsonValue value = answer(call);
                            // Nothing is no field: the contract has no null value.
                            return new Answer(value.isNull() ? Optional.empty() : Optional.of(value));
                        },
                        CALLS));
    }

    /** Server thread, every settled tick: the first time, load the scene's scripts on a thread of their own. */
    static void tick(MinecraftServer server) {
        if (started) {
            return;
        }
        started = true;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Thread thread = new Thread(() -> load(server), "stagewright-peer-load");
        thread.setDaemon(true);
        thread.setContextClassLoader(loader);
        thread.start();
    }

    private static void load(MinecraftServer server) {
        GameImpl peer = new GameImpl(server);
        // The scene's world settings, time and weather and the rest, as the game applies them to its own.
        try {
            peer.applySceneWorld();
        } catch (RuntimeException e) {
            LOAD_ERRORS.add("the scene's world settings: " + e.getMessage());
        }
        GameExtension.bind(peer);
        game = peer;
        Scripting scripting = GameImpl.scripting(peer);
        for (String resource : JupiterRun.split(System.getProperty(JupiterRun.SCRIPTS, ""))) {
            if (!resource.endsWith(".js")) {
                continue;
            }
            try {
                Map<String, Object> globals =
                        Map.of("test", (Scripting.Function) a -> null, "step", (Scripting.Function) PeerServing::step);
                scripting.evaluate(resource, source(resource), globals);
            } catch (RuntimeException e) {
                LOAD_ERRORS.add(resource + ": " + e.getMessage());
            }
        }
        ready = true;
        StageWrightCommon.LOG.info(
                "[{}] peer '{}' serves the scene, with steps {}",
                StageWrightCommon.MOD_ID,
                System.getProperty(SERVE),
                STEPS.keySet());
    }

    private static Object step(Object... args) {
        if (args.length != 2 || !(args[0] instanceof String name) || !(args[1] instanceof Scripting.Function body)) {
            throw new IllegalArgumentException("step(name, body) takes a name and a function");
        }
        STEPS.put(name, body);
        return null;
    }

    private static String source(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no " + resource + " on the peer's class path");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    private static JsonValue answer(Call call) {
        try {
            return JsonValue.of(
                    switch (call.op()) {
                        case "ready" -> ready();
                        case "open" -> open(call);
                        case "invoke" -> session(call).invoke(call);
                        case "waitUntil" -> session(call).waitUntil(call);
                        case "close" -> close(call);
                        default -> throw new IllegalArgumentException(METHOD + ": no op '" + call.op() + "'");
                    });
        } catch (Throwable t) {
            return JsonValue.of(failure(t));
        }
    }

    private static JsonElement ready() {
        JsonObject out = new JsonObject();
        out.addProperty("ready", ready);
        JsonArray errors = new JsonArray();
        synchronized (LOAD_ERRORS) {
            LOAD_ERRORS.forEach(errors::add);
        }
        out.add("loadErrors", errors);
        return out;
    }

    private static JsonElement open(Call call) {
        if (!ready) {
            throw new IllegalStateException("the peer has not loaded the scene's scripts yet");
        }
        String id = call.session().orElseThrow(() -> new IllegalArgumentException("open: no session"));
        String test = call.name().orElse(id);
        return SESSIONS.computeIfAbsent(id, unused -> new Session(game, test)).root();
    }

    private static Session session(Call call) {
        String id = call.session().orElseThrow(() -> new IllegalArgumentException(call.op() + ": no session"));
        Session s = SESSIONS.get(id);
        if (s == null) {
            throw new IllegalStateException("no session '" + id + "' on this peer: it was closed, or never opened");
        }
        return s;
    }

    private static JsonElement close(Call call) {
        Session s = SESSIONS.remove(call.session().orElse(""));
        if (s == null) {
            return JsonNull.INSTANCE;
        }
        Throwable failure = s.end();
        if (failure != null) {
            throw new IllegalStateException("ending the test's session: " + failure, failure);
        }
        return JsonNull.INSTANCE;
    }

    /** What the game rebuilds a failure from: its class, message, whether an assertion, and this game's stack. */
    static JsonObject failure(Throwable t) {
        JsonObject error = new JsonObject();
        error.addProperty("class", t.getClass().getName());
        error.addProperty("message", String.valueOf(t.getMessage()));
        error.addProperty("assertion", t instanceof AssertionError);
        JsonArray stack = new JsonArray();
        for (StackTraceElement frame : t.getStackTrace()) {
            stack.add(frame.toString());
        }
        error.add("stack", stack);
        if (t.getCause() != null && t.getCause() != t) {
            error.add("cause", failure(t.getCause()));
        }
        JsonObject out = new JsonObject();
        out.add(ERROR, error);
        return out;
    }

    /** One test of the scene's game, here: its {@code t}, and the handles it was given. */
    private static final class Session {
        private final ScriptContext context;

        private final TestScope scope;

        private final Map<Integer, Map<?, ?>> handles = new ConcurrentHashMap<>();

        private int next = 1;

        Session(GameImpl game, String test) {
            this.scope = TestScope.ofScript(test, Set.of(), false);
            this.context = new ScriptContext(game, STEPS, JavaSteps.none(), new PeerTest(test));
            handles.put(0, inScope(context::asScript));
        }

        /** The session's {@code t}, handle 0: its functions' names, and its plain fields. */
        JsonElement root() {
            JsonObject fields = new JsonObject();
            JsonArray functions = new JsonArray();
            handles.get(0).forEach((k, v) -> {
                if (v instanceof Scripting.Function) {
                    functions.add(String.valueOf(k));
                }
            });
            JsonObject out = new JsonObject();
            out.addProperty(HANDLE, 0);
            out.add("fields", fields);
            out.add("functions", functions);
            return out;
        }

        JsonElement invoke(Call call) {
            int id = call.handle().orElse(0);
            Map<?, ?> handle = handles.get(id);
            String member = call.name().orElseThrow(() -> new IllegalArgumentException("invoke: no member"));
            if (handle == null) {
                throw new IllegalStateException("no handle " + id + " in this session");
            }
            if (!(handle.get(member) instanceof Scripting.Function f)) {
                throw new IllegalArgumentException(
                        "no function '" + member + "' on this handle; it has " + handle.keySet());
            }
            Object[] args = arguments(call.args());
            return export(inScope(() -> f.call(args)));
        }

        JsonElement waitUntil(Call call) {
            String step = call.name().orElseThrow(() -> new IllegalArgumentException("waitUntil: no step"));
            Object args =
                    call.args().map(v -> GSON.fromJson(v.json(), Object.class)).orElse(null);
            Object options = call.options()
                    .map(v -> GSON.fromJson(v.json(), Object.class))
                    .orElse(Map.of());
            Scripting.Function condition = a -> context.step(step, args);
            Scripting.Function wait = (Scripting.Function) context.asScript().get("waitUntil");
            inScope(() -> wait.call(condition, options));
            return JsonNull.INSTANCE;
        }

        Throwable end() {
            TestScope before = TestScope.swap(scope);
            try {
                return context.end(null);
            } finally {
                TestScope.swap(before);
            }
        }

        private <T> T inScope(java.util.function.Supplier<T> work) {
            TestScope before = TestScope.swap(scope);
            try {
                return work.get();
            } finally {
                TestScope.swap(before);
            }
        }

        private static Object[] arguments(Optional<JsonValue> args) {
            if (args.isEmpty() || args.get().isNull()) {
                return new Object[0];
            }
            Object list = GSON.fromJson(args.get().json(), Object.class);
            if (!(list instanceof List<?> items)) {
                throw new IllegalArgumentException("invoke: args is a list");
            }
            return items.toArray();
        }

        /** {@code value} as JSON, a map with functions kept here as a handle. */
        private JsonElement export(Object value) {
            return switch (value) {
                case null -> JsonNull.INSTANCE;
                case Boolean b -> new JsonPrimitive(b);
                case Number n -> new JsonPrimitive(n);
                case String s -> new JsonPrimitive(s);
                case List<?> list -> {
                    JsonArray out = new JsonArray();
                    list.forEach(item -> out.add(export(item)));
                    yield out;
                }
                case Map<?, ?> map -> exportMap(map);
                default -> new JsonPrimitive(String.valueOf(value));
            };
        }

        private synchronized JsonElement exportMap(Map<?, ?> map) {
            JsonObject fields = new JsonObject();
            JsonArray functions = new JsonArray();
            map.forEach((k, v) -> {
                if (v instanceof Scripting.Function) {
                    functions.add(String.valueOf(k));
                } else {
                    fields.add(String.valueOf(k), export(v));
                }
            });
            if (functions.isEmpty()) {
                return fields;
            }
            int id = next++;
            handles.put(id, map);
            JsonObject out = new JsonObject();
            out.addProperty(HANDLE, id);
            out.add("fields", fields);
            out.add("functions", functions);
            return out;
        }
    }

    /** The descriptor a session's {@code t} reports under: the game's test, by name. */
    private static final class PeerTest extends AbstractTestDescriptor {
        PeerTest(String name) {
            super(UniqueId.forEngine("stagewright-peer").append("test", name), name);
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }
}
