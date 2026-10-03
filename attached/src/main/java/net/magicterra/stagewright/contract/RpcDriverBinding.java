package net.magicterra.stagewright.contract;

import java.util.Map;

import com.google.gson.JsonObject;

/**
 * {@link DriverBinding} over the RPC websocket — the out-of-process half of {@code driver(...)}.
 *
 * <p>Thin on purpose. It converts JVM values to JSON on the way out and JSON back to plain JVM
 * values on the way in, and does nothing else: no verb list, no schema, no retries. WorldDriver
 * already collapsed every verb to one {@code route(method, params)} and asserts that its three
 * existing transports return byte-identical results; this is a fourth caller of that same route, and
 * a fourth transport that started reinterpreting results would break the property those assertions
 * exist to hold.
 *
 * <p><b>Results come back as plain maps and lists, not as gson nodes.</b> A scene that received a
 * {@code JsonObject} would have to call gson methods on it, and the in-process home hands back a
 * {@code Map} — so the same line of script would need two spellings. Converting here is what makes
 * {@code driver('mc.observe.player').pos.y} mean the same thing in both homes.
 */
public final class RpcDriverBinding implements DriverBinding {

    private final StageWrightRpc rpc;
    private final long timeoutMs;

    public RpcDriverBinding(StageWrightRpc rpc, long timeoutMs) {
        this.rpc = rpc;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Object route(String method, Map<String, Object> params) {
        JsonObject json = DriverJson.params(params);
        try {
            return DriverJson.fromJson(rpc.call(method, json, timeoutMs));
        } catch (StageWrightTransportException e) {
            // Kept as the cause so the runner can stop at a lost connection, not just this scene.
            throw new SceneFailure("driver('" + method + "') " + e.error(), e);
        } catch (StageWrightRpcException e) {
            // The driver refused. That is a scene failure carrying the driver's own words — not a
            // transport problem and not something to translate, because the message is usually the
            // most specific thing anyone will ever learn about why the verb said no.
            throw new SceneFailure("driver('" + method + "') failed: " + e.getMessage());
        } catch (StageWrightTimeoutException e) {
            throw new SceneFailure("driver('" + method + "') did not answer within " + timeoutMs
                    + "ms — the game is alive enough to hold the socket open and not alive enough to"
                    + " answer, which usually means the server thread is wedged rather than busy");
        }
    }

}
