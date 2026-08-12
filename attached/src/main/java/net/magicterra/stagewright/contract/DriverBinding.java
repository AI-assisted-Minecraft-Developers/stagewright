package net.magicterra.stagewright.contract;

import java.util.Map;

/**
 * How a scene reaches the driver's verb surface — {@code driver('mc.observe.player')} in a script.
 *
 * <p>One method, because the driver already collapsed to one method. WorldDriver's whole design
 * rests on {@code DriverApi.route(method, params)} being the single source of truth that MCP, the
 * RPC websocket and the in-JVM Rhino sandbox all funnel into, with a validation suite asserting all
 * three return byte-identical results. The out-of-process home is a FOURTH caller of that same
 * route, so it inherits the discipline rather than re-deriving a verb surface: in-process the
 * binding reflects into the driver in the same JVM, out-of-process it is a websocket round trip, and
 * the scene cannot tell.
 *
 * <p>That is also why this interface must never grow a second method. A verb that exists here but
 * not in {@code route} would be a verb one home has and the other does not, which is the exact
 * failure mode the shared-file promise is supposed to exclude.
 */
@FunctionalInterface
public interface DriverBinding {

    /**
     * Call one driver verb.
     *
     * @param method the dotted verb name, e.g. {@code mc.observe.player}
     * @param params the verb's parameters; may be empty, never null
     * @return whatever the verb answered, already converted to plain JVM types (maps, lists,
     *         strings, numbers, booleans) so a script sees the same shape in both homes
     * @throws SceneFailure if the driver refused the call, with the driver's own message
     */
    Object route(String method, Map<String, Object> params);
}
