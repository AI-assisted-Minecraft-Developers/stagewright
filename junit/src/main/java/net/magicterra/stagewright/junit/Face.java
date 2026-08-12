package net.magicterra.stagewright.junit;

import net.magicterra.stagewright.contract.StageWrightRpcException;

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

    /**
     * What the driver says when {@code mc.client.*} is asked of a JVM with no client.
     *
     * <p>Not the {@code mc.bot.*} wording. The two families render client-absence differently —
     * bot verbs say "client only; bot impl not registered", client verbs say "no client registered"
     * — and matching the wrong one turns every SERVER endpoint into a transport error at the
     * condition stage, which errors the whole container rather than skipping a class.
     */
    private static final String NO_CLIENT = "no client registered";

    private static volatile Face detected;

    /**
     * The attached endpoint's face.
     *
     * @throws StageWrightRpcException if the probe fails for any reason OTHER than there being no
     *         client — a transport that is broken must not be silently reported as SERVER, because
     *         that reads as a legitimate face and skips the tests that would have caught it.
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
