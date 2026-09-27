package net.magicterra.stagewright.junit;

import net.magicterra.stagewright.contract.StageWrightRpcException;
import net.magicterra.stagewright.contract.StageWrightTimeoutException;
import net.magicterra.stagewright.contract.StageWrightTransportException;

import com.google.gson.JsonObject;

/**
 * Which side of the game an endpoint is: a JVM that has a client, or one that does not.
 *
 * <p>Both are legitimate attach targets and they support disjoint assertions. UI tests need a
 * client, because {@code mc.client.*} exists nowhere else. Half the instrument contract needs the
 * opposite — that a client-only verb is REFUSED, that an empty player list reads as
 * {@code {present:false}} — and those can only be observed where there is genuinely no client.
 * Pointing either set at the wrong endpoint produces failures about the endpoint rather than about
 * the driver.
 *
 * <p><b>Detected by asking, not by reading the label.</b> The descriptor's {@code topology} is a
 * free string a consumer chose; whether {@code mc.client.screen.info} answers is the actual
 * property every face-sensitive test depends on. One probe per JVM, cached — the endpoint cannot
 * grow or lose a client while a test run is attached to it.
 */
public enum Face {

    /** This JVM runs a client: {@code mc.client.*} answers. */
    CLIENT,

    /** This JVM is a dedicated server: {@code mc.client.*} is refused as client-only. */
    SERVER;

    /** What {@code mc.client.*} says in a JVM with no client. Not the {@code mc.bot.*} wording
     *  ("client only; bot impl not registered"): matching that one rethrows every SERVER endpoint's
     *  refusal at the condition stage, erroring the whole container instead of skipping a class. */
    private static final String NO_CLIENT = "no client registered";

    private static volatile Face detected;

    /**
     * The attached endpoint's face.
     *
     * @throws StageWrightRpcException if the driver refuses the probe for any reason OTHER than
     *         there being no client — that must not be reported as SERVER, because it reads as a
     *         legitimate face and skips the tests that would have caught it.
     * @throws StageWrightTransportException if the socket could not carry the probe
     * @throws StageWrightTimeoutException if the driver does not answer the probe in time
     */
    public static Face of(StageWright tk) {
        Face cached = detected;
        if (cached != null) return cached;
        Face face;
        try {
            tk.call("mc.client.screen.info", new JsonObject());
            face = CLIENT;
        } catch (StageWrightRpcException e) {
            if (!e.error().contains(NO_CLIENT)) throw e;
            face = SERVER;
        }
        detected = face;
        return face;
    }
}
