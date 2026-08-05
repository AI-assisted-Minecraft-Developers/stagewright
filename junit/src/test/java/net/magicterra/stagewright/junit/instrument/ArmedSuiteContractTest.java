package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWrightExtension;
import net.magicterra.stagewright.junit.StageWrightRpcException;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static net.magicterra.stagewright.junit.instrument.Contract.flag;
import static net.magicterra.stagewright.junit.instrument.Contract.num;
import static net.magicterra.stagewright.junit.instrument.Contract.refuses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The on-demand suite trigger's idempotency guard — the one contract check that cannot be made
 * without changing the world it is made in.
 *
 * <p><b>Why this class runs last, by construction.</b> Proving that a SECOND {@code mc.test.run} is
 * refused requires a first one to be accepted, and an accepted one starts the whole scene suite:
 * mobs get summoned and killed, time gets set, a test player may join. Every other instrument test
 * asserts about a world nobody else is touching — {@code absentPlayerIsReportedNotThrown} most
 * literally — so running this one first would break them, intermittently, in ways that read as
 * driver bugs. {@code junit-platform.properties} turns on class ordering and this carries the
 * highest {@code @Order}, which makes "last" a property of the build rather than of luck.
 *
 * <p>After this test the endpoint is still up (a held server does not halt when a suite drains) but
 * it is busy, and its world is no longer pristine. Anything attaching afterwards should restart the
 * hold.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
@Order(Integer.MAX_VALUE)
class ArmedSuiteContractTest {

    /**
     * One accept, then a loud refusal. Never two accepts: the second would mean the guard is dead
     * and a concurrent run could clobber the first one's results mid-write.
     */
    @Test
    void theSuiteTriggerRunsOnceAndRefusesTheSecondCall(StageWright tk) {
        // ONE call, inspected — not a probe followed by the real thing. There is exactly one accept
        // available on this endpoint and a probe would spend it, making the assertion below fail
        // against a perfectly good server.
        JsonObject accepted;
        try {
            accepted = tk.call("mc.test.run");
        } catch (StageWrightRpcException e) {
            if (e.error().contains("already")) {
                // Said plainly, because the alternative reads as a driver regression: this endpoint
                // has been used by a previous run of this very test. The guard is doing its job;
                // there is just no un-triggered suite left to accept. Restart the hold.
                throw new AssertionError("this endpoint's suite has already been triggered — restart"
                        + " the hold before re-running the instrument contract (got: " + e.error() + ")");
            }
            throw e;
        }
        assertTrue(flag(accepted, "accepted"),
                "mc.test.run must accept on an armed-but-idle server: " + accepted);
        assertTrue(num(accepted, "scenes") > 0,
                "an accepted run must report the scene count it is about to execute: " + accepted);

        refuses(tk, "mc.test.run", "already");
    }
}
