package net.magicterra.stagewright.jupiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * The {@link Client} of this game. On an integrated server it is this JVM's own WorldDriver. Where a topology joins a
 * client to a dedicated server, it is a registry of the server's methods each forwarded over that client's RPC
 * connection, found through the port file the client writes in its working directory, which the test engine names
 * as {@value #COMPANION_DIR}. One connection per run, opened when a test first asks.
 */
final class ClientConnection {

    static final String COMPANION_DIR = "stagewright.companion.dir";
    private static final long CONNECT_MS = 10_000;
    /** Longer than any blocking caller waits, so the caller's own limit is the one that answers. */
    private static final long CALL_MS = 10 * 60_000;

    private static final ExecutorService CALLS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stagewright-client-calls");
        t.setDaemon(true);
        return t;
    });

    private static StageWrightRpc rpc;
    private static ApiRegistry remote;

    private ClientConnection() {}

    static synchronized Client client(Game game) {
        boolean dedicated = game.onServer(MinecraftServer::isDedicatedServer);
        if (!dedicated) return game::registry;
        String dir = System.getProperty(COMPANION_DIR);
        if (dir == null || !Boolean.getBoolean(JupiterRun.AWAIT_PLAYER)) {
            throw new ParameterResolutionException("no real client is joined to this dedicated server, so there is"
                    + " no Client to inject: give the test @Requires(CLIENT)");
        }
        if (remote == null) {
            Path portFile = Path.of(dir, "worlddriver-rpc.port");
            String port;
            try {
                port = Files.readString(portFile).trim();
            } catch (IOException e) {
                throw new ParameterResolutionException("the client wrote no " + portFile + ": " + e);
            }
            rpc = StageWrightRpc.connect("ws://127.0.0.1:" + port + "/rpc", CONNECT_MS);
            remote = forwarding(rpc, game.registry());
        }
        ApiRegistry registry = remote;
        return () -> registry;
    }

    /** As the server stops: the connection goes with the run. */
    static synchronized void close() {
        if (rpc != null) rpc.close();
        rpc = null;
        remote = null;
    }

    /**
     * Every method this server's registry has, each batch under its own owner and id, forwarded to the client: the
     * client runs the same mods, so it serves the same methods, StageWright's {@code mc.test.*} and other mods' included.
     * One the client lacks fails when called, with the client's own answer.
     */
    private static ApiRegistry forwarding(StageWrightRpc rpc, ApiRegistry local) {
        ApiRegistry registry = new ApiRegistry();
        for (ApiBatch batch : local.snapshot().batches().values()) {
            List<ApiMethod<?, ?>> methods = new ArrayList<>();
            for (ApiMethod<?, ?> method : batch.methods()) methods.add(forward(method.spec(), rpc));
            registry.registerBatch(new ApiBatch(batch.owner(), batch.id(), methods));
        }
        return registry;
    }

    /** {@code spec} answered by the client: the params sent as they would be by any RPC caller, the reply read back. */
    private static <P, R> ApiMethod<P, R> forward(MethodSpec<P, R> spec, StageWrightRpc rpc) {
        return new ApiMethod<>(spec, (params, context) -> CompletableFuture.supplyAsync(() -> {
            JsonElement sent = spec.input().encode(params);
            JsonObject envelope = rpc.envelope(spec.name(), sent instanceof JsonObject o ? o : new JsonObject(),
                    CALL_MS);
            JsonElement error = envelope.get("error");
            if (error instanceof JsonObject e) {
                ApiError rebuilt = ApiErrors.fromTree(e.get("data"));
                throw rebuilt != null ? new ApiException(rebuilt)
                        : ApiException.of(ApiError.Code.INTERNAL_ERROR, "the client refused " + spec.name() + ": " + e);
            }
            return spec.output().decode(envelope.get("result"));
        }, CALLS));
    }
}
