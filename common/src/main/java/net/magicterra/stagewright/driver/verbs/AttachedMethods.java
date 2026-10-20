package net.magicterra.stagewright.driver.verbs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.DriverJson;
import net.magicterra.stagewright.contract.Terrain;
import net.magicterra.stagewright.contract.remote.Wire;
import net.magicterra.stagewright.harness.AttachedSessions;
import net.magicterra.worlddriver.api.registry.ApiHandler;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.MethodSpec.Effect;
import net.magicterra.worlddriver.api.schema.MethodSpec.ExecutionClass;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code mc.test.attached.*} methods: how a scene file that runs in another process gets an arena
 * in this one and calls into it. Hidden, for the reason the rest of {@code mc.test.*} is: a harness
 * verb is not an agent action. The wire format is described in {@code docs/reference/attached-protocol.md}.
 *
 * <p>Every handler hands its work to the server thread and answers when it has run. The registry runs a
 * handler on whatever thread the request came in on, and an arena, a scene context and a block are
 * all the server thread's.
 */
final class AttachedMethods {
    private AttachedMethods() {}

    @Named("AttachedBegin")
    public record Begin(@Doc("The protocol version the runner speaks.") @Range(min = 1) int protocol) {}

    @Named("AttachedBegun")
    public record Begun(@OnlyTrue boolean pinned,
                        @Doc("What the game pinned, as the in-game suite's results header states it.") String description) {}

    @Named("AttachedEnded")
    public record Ended(@Doc("Whether a run was open to end.") boolean released) {}

    @Named("AttachedOpen")
    public record Open(
            @Doc("The scene's name, for the log and for messages.") String scene,
            @Doc("run_world, superflat or generated.") String terrain,
            @Doc("midnight, noon or running.") String clock,
            @Doc("A dimension the scene names itself; absent for the terrain's own.") Optional<String> dimension,
            @Doc("Chunks around the arena that must be loaded and ticking.") @Range(min = 0, max = 8) int chunkRadius) {}

    @Named("AttachedOpened")
    public record Opened(@Range(min = 1) int session) {}

    @Named("AttachedSession")
    public record SessionRef(@Range(min = 1) int session) {}

    @Named("AttachedStatus")
    public record StatusReply(
            @Doc("preparing, ready, failed or skipped. The last two are final: the session goes once they are read.")
            String state,
            @Doc("Why, for failed and skipped.") String message) {}

    @Named("AttachedCall")
    public record Call(
            @Range(min = 1) int session,
            @Doc("0 is the scene's context; any other number is an object an earlier answer handed out.")
            @Range(min = 0) int target,
            String method,
            @Doc("Plain data, {\"$id\":kind,\"id\":…} for a registry entry, {\"$handle\":n} for an object.")
            List<JsonValue> args) {}

    @Named("AttachedRoute")
    public record Route(@Range(min = 1) int session, String method, @OnlyObject JsonValue params) {}

    @Named("AttachedAnswer")
    public record Answer(
            boolean ok,
            @Doc("The result, when ok. Absent for null.") Optional<JsonValue> value,
            @Doc("skipped, failed, illegalArgument, unsupported or error, when not ok.") Optional<String> kind,
            Optional<String> message) {}

    @Named("AttachedClosed")
    public record Closed(
            @Doc("One line per way the scene left the world different from how it found it.") List<String> leaks,
            @Doc("What the scene's context recorded.") @OnlyObject JsonValue records,
            @Doc("Checks that failed on the game's side of the scene.") List<String> softViolations,
            @Doc("Cleanups the game ran that threw.") List<String> cleanupFailures) {}

    private static final RecordType<Begin> BEGIN_IN = ApiTypes.record(Begin.class);
    private static final RecordType<Begun> BEGUN = ApiTypes.record(Begun.class);
    private static final RecordType<Ended> ENDED = ApiTypes.record(Ended.class);
    private static final RecordType<Open> OPEN_IN = ApiTypes.record(Open.class);
    private static final RecordType<Opened> OPENED = ApiTypes.record(Opened.class);
    private static final RecordType<SessionRef> SESSION = ApiTypes.record(SessionRef.class);
    private static final RecordType<StatusReply> STATUS_OUT = ApiTypes.record(StatusReply.class);
    private static final RecordType<Call> CALL_IN = ApiTypes.record(Call.class);
    private static final RecordType<Route> ROUTE_IN = ApiTypes.record(Route.class);
    private static final RecordType<Answer> ANSWER = ApiTypes.record(Answer.class);
    private static final RecordType<Closed> CLOSED = ApiTypes.record(Closed.class);

    static final MethodSpec<Begin, Begun> BEGIN = MethodSpec.of(Wire.BEGIN, BEGIN_IN, BEGUN,
            "Open an out-of-process run: pin the world as the in-game suite does, and accept scenes. Refused when "
                    + "the in-game suite has started or another run is open.")
            .hidden().effects(Effect.WORLD_WRITE);

    static final MethodSpec<NoParams, Ended> END = MethodSpec.of(Wire.END, CommonTypes.NO_PARAMS, ENDED,
            "End the out-of-process run: close any scene still open and give the world back.")
            .hidden().effects(Effect.WORLD_WRITE);

    static final MethodSpec<Open, Opened> OPEN = MethodSpec.of(Wire.OPEN, OPEN_IN, OPENED,
            "Allocate an arena for a scene and start loading it. Answers at once; poll status until it is ready.")
            .hidden().effects(Effect.WORLD_WRITE);

    static final MethodSpec<SessionRef, StatusReply> STATUS = MethodSpec.of(Wire.STATUS, SESSION, STATUS_OUT,
            "Whether a scene's arena has arrived.")
            .hidden().effects(Effect.SNAPSHOT_READ);

    static final MethodSpec<Call, Answer> CALL = MethodSpec.of(Wire.CALL, CALL_IN, ANSWER,
            "Call a method by name on the scene's context or on an object it handed out, on the server thread. "
                    + "A refusal comes back as an answer with ok false, not as an error.")
            .hidden().effects(Effect.WORLD_WRITE).execution(ExecutionClass.BULK);

    static final MethodSpec<Route, Answer> ROUTE = MethodSpec.of(Wire.ROUTE, ROUTE_IN, ANSWER,
            "Call a driver method on the server thread, as an in-game scene's driver(...) does.")
            .hidden().effects(Effect.WORLD_WRITE).execution(ExecutionClass.BULK);

    static final MethodSpec<SessionRef, Closed> CLOSE = MethodSpec.of(Wire.CLOSE, SESSION, CLOSED,
            "Let a scene's arena go: run what it left to clean, sweep, release the chunks, audit the world.")
            .hidden().effects(Effect.WORLD_WRITE);

    static List<ApiMethod<?, ?>> methods() {
        List<ApiMethod<?, ?>> all = new ArrayList<>();
        all.add(new ApiMethod<>(BEGIN, onServer((server, p) ->
                begun(AttachedSessions.begin(server, p.protocol())))));
        all.add(new ApiMethod<>(END, onServer((server, p) -> {
            boolean was = AttachedSessions.active();
            AttachedSessions.end(server);
            return new Ended(was);
        })));
        all.add(new ApiMethod<>(OPEN, onServer((server, p) -> new Opened(AttachedSessions.open(server, p.scene(),
                parse(() -> Terrain.parse(p.terrain()), "/terrain"), parse(() -> Clock.parse(p.clock()), "/clock"),
                p.dimension().orElse(null), p.chunkRadius())))));
        all.add(new ApiMethod<>(STATUS, onServer((server, p) -> {
            AttachedSessions.Status s = AttachedSessions.status(p.session());
            return new StatusReply(s.state(), s.message());
        })));
        all.add(new ApiMethod<>(CALL, onServer((server, p) -> answer(AttachedSessions.call(p.session(), p.target(),
                p.method(), jsonList(p.args()))))));
        all.add(new ApiMethod<>(ROUTE, onServer((server, p) -> answer(AttachedSessions.route(p.session(), p.method(),
                params(p.params()))))));
        all.add(new ApiMethod<>(CLOSE, onServer((server, p) -> closed(AttachedSessions.close(server, p.session())))));
        return all;
    }

    private static Begun begun(AttachedSessions.Begun b) {
        return new Begun(b.pinned(), b.description());
    }

    private static Answer answer(AttachedSessions.Reply r) {
        return new Answer(r.ok(), r.value() == null || r.value().isJsonNull() ? Optional.empty()
                : Optional.of(JsonValue.of(r.value())), Optional.ofNullable(r.kind()), Optional.ofNullable(r.message()));
    }

    private static Closed closed(AttachedSessions.Closed c) {
        return new Closed(c.leaks(), JsonValue.of(DriverJson.toJson(c.records())), c.softViolations(),
                c.cleanupFailures());
    }

    private static List<JsonElement> jsonList(List<JsonValue> values) {
        List<JsonElement> out = new ArrayList<>(values.size());
        for (JsonValue v : values) out.add(v.json());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(JsonValue value) {
        JsonElement json = value.json();
        Object plain = json.isJsonObject() ? DriverJson.fromJson(json) : Map.of();
        return (Map<String, Object>) plain;
    }

    private static <T> T parse(java.util.function.Supplier<T> parser, String path) {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, e.getMessage());
        }
    }

    /** Run {@code body} on the server thread and answer with what it returned or the refusal it threw. */
    private static <P, R> ApiHandler<P, R> onServer(Body<P, R> body) {
        return (p, ctx) -> {
            MinecraftServer server = StageWrightCommon.armedServer();
            if (server == null) {
                return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "the StageWright runtime is not armed on this server, so there is nowhere to run a scene"));
            }
            CompletableFuture<R> done = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    done.complete(body.run(server, p));
                } catch (ApiException e) {
                    done.completeExceptionally(e);
                } catch (IllegalStateException e) {
                    done.completeExceptionally(ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, e.getMessage()));
                } catch (IllegalArgumentException e) {
                    done.completeExceptionally(ApiException.invalidParam("/", ApiIssue.Code.INVALID_VALUE, e.getMessage()));
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            return done;
        };
    }

    @FunctionalInterface
    private interface Body<P, R> {
        R run(MinecraftServer server, P params);
    }
}
