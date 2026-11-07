package net.magicterra.stagewright.jupiter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.BiFunction;
import net.magicterra.stagewright.driver.verbs.TestMethods;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

/**
 * The game's {@link Client}: its registry, and its session with the server, which {@link #disconnect} and
 * {@link #reconnect} end and begin again through the client's {@code mc.test.client.*} methods. The session is the
 * client's, not the handle's, so who has it off the server is kept for every handle of the run.
 */
final class ClientHandle implements Client {

    /** Ticks the server has to take a disconnected player off its list: its departure is a server task. */
    private static final int LEAVE_TICKS = 100;

    /** Ticks the server has to put a player the client reports joined on its list, which it does before that. */
    private static final int JOIN_TICKS = 100;

    /** Longer than either client method waits, so the client's own limit is the one that answers. */
    private static final long CALL_MS = 180_000;

    /** How long the server, stopping, gives a disconnected client to hear the run is over. */
    private static final long END_MS = 2_000;

    /**
     * The client's player while a test has it off the server, and that test: the one that puts it back. Null while it
     * is on.
     */
    private record Parted(String name, TestScope by) {}

    private static volatile Parted parted;

    private final Game game;

    private final ApiRegistry registry;

    /** Whether the client opened the world it is in, hosting the game's server. */
    private final boolean hosts;

    /**
     * A method by name on the joined client, over the connection {@link #registry} forwards on; null where the client
     * hosts the game, whose registry is the client's own.
     */
    private final BiFunction<String, JsonObject, JsonElement> byName;

    ClientHandle(Game game, ApiRegistry registry, BiFunction<String, JsonObject, JsonElement> byName) {
        this.game = game;
        this.registry = registry;
        this.hosts = byName == null;
        this.byName = byName;
    }

    @Override
    public ApiRegistry registry() {
        return registry;
    }

    @Override
    public Client disconnect() {
        refuseWhereItHosts("disconnect");
        if (parted != null) {
            throw new IllegalStateException("the client is not in the game: a test disconnected it");
        }
        String name = call(TestMethods.CLIENT_LEAVE).name();
        TestScope scope = TestScope.bound();
        parted = new Parted(name, scope == null ? null : scope.root());
        game.await().within(LEAVE_TICKS).as(name + " leaves the server").until(() -> gone(name));
        return this;
    }

    @Override
    public Client reconnect() {
        refuseWhereItHosts("reconnect");
        Parted away = parted;
        if (away == null) {
            throw new IllegalStateException("the client is in the game: client.disconnect() first");
        }
        try {
            call(TestMethods.CLIENT_JOIN);
            game.await()
                    .within(JOIN_TICKS)
                    .as(away.name() + " is back on the server")
                    .until(() -> !gone(away.name()));
        } finally {
            // Whatever the client answered, the server's list says whether it is back.
            if (!gone(away.name())) {
                parted = null;
            }
        }
        return this;
    }

    /**
     * Put the client back in the game if the test of {@code scope} left it disconnected; at that test's end, while it
     * still holds its locks, so no test that takes the client next finds it away. Nothing for any other test's end.
     */
    static void rejoinIfLeftBy(TestScope scope, Game game) {
        Parted away = parted;
        if (away != null && scope != null && away.by() == scope.root()) {
            ClientConnection.client(game).reconnect();
        }
    }

    /** {@link #rejoinIfLeftBy} for a script test, as a lease it gives back with its others. */
    CloseableResource rejoinAtTheEnd() {
        TestScope scope = TestScope.bound();
        return () -> rejoinIfLeftBy(scope, game);
    }

    /**
     * The run is over with its server: a client a test left disconnected is told so and closes, and the next run's
     * client starts on the server. Best effort, on the server thread as it stops.
     */
    static void end(ApiRegistry remote) {
        if (parted != null && remote != null) {
            try {
                ApiRegistry.await(
                        remote.invoke(TestMethods.CLIENT_END, new NoParams(), CallContext.standalone()), END_MS);
            } catch (RuntimeException unheard) {
                // The client's own timeout closes it then.
            }
        }
        parted = null;
    }

    /** Whether the server has no player of that name, which is the client's player gone. */
    private boolean gone(String name) {
        return game.onServerHolds(s -> s.getPlayerList().getPlayerByName(name) == null);
    }

    private void refuseWhereItHosts(String what) {
        if (hosts) {
            throw new IllegalStateException("the client opened this game's world and hosts its server, so it cannot "
                    + what + ": that needs a topology where the client joins a dedicated server");
        }
    }

    /**
     * The forwarding registry's methods are the server's; another name goes over the connection as is. Not a missing
     * name, nor an unknown {@code mc.*} one: both sides serve the same driver and harness, so the registry's own answer
     * is the client's.
     */
    @Override
    public JsonElement call(String method, JsonObject params) {
        if (byName != null && method != null && !method.startsWith("mc.") && registry.method(method) == null) {
            return byName.apply(method, params);
        }
        return registry.route(method, params);
    }

    private <R> R call(MethodSpec<NoParams, R> spec) {
        return ApiRegistry.await(registry.invoke(spec, new NoParams(), CallContext.standalone()), CALL_MS);
    }
}
