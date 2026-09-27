package net.magicterra.stagewright.junit.instrument;

import net.magicterra.stagewright.junit.Canaries;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.StageWrightExtension;
import net.magicterra.stagewright.contract.StageWrightTimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static net.magicterra.stagewright.junit.instrument.Contract.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server face's own teeth. Everything else in this package asserts about the driver; this
 * asserts that those assertions can fail at all.
 *
 * <p><b>Why a second canary class.</b> {@code ui.CanaryTest} does this job for the client face, and
 * it is gated to a client endpoint — so a run against a dedicated server, which is exactly how the
 * instrument contract is meant to be run, would have carried no self-check whatsoever. The suite it
 * replaces shipped two canaries for that reason: a contract suite whose judging is broken reports
 * a clean green over a driver that answers nothing correctly, and the only thing that distinguishes
 * that from real success is a check designed to fail.
 *
 * <p>Kept off the extension so {@link #assertionsBite} can check that the extension reports a real
 * failure; the other canaries check helpers inside {@code assertThrows}.
 */
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@ExtendWith(Canaries.FaceGate.class)
@RequiresFace(Face.SERVER)
class ContractCanaryTest {

    /** An assertion about a REAL read, made deliberately wrong, must fail the test that makes it. */
    @Test
    void assertionsBite() {
        assertEquals("worlddriver", str(Canaries.live().call("mc.system.version"), "modid"),
                "precondition: the endpoint is a driver");
        Canaries.failOnPurpose(DeliberatelyWrong.class);
    }

    @Tag(Canaries.TARGET)
    @ExtendWith(Canaries.Armed.class)
    @ExtendWith(StageWrightExtension.class)
    @RequiresFace(Face.SERVER)
    static class DeliberatelyWrong {
        @Test
        void claimsTheWrongModid(StageWright tk) {
            assertEquals("definitely-not-the-modid", str(tk.call("mc.system.version"), "modid"),
                    "deliberately-wrong: the live read says worlddriver");
        }
    }

    /**
     * A never-true predicate must raise a TIMEOUT, and the type must not be an
     * {@link AssertionError} — that distinction is what lets a reader tell "the driver answered
     * something wrong" apart from "the driver never answered".
     */
    @Test
    void timeoutsBiteAndAreNotAssertionFailures() {
        StageWrightTimeoutException ex = assertThrows(StageWrightTimeoutException.class,
                () -> Canaries.live().awaitCondition(() -> false, Duration.ofMillis(200)));
        // Reflectively, not with instanceof: the two types are unrelated, so `ex instanceof
        // AssertionError` does not compile. That is the stronger guarantee — the separation this
        // asserts is enforced by the compiler — but it still has to be STATED somewhere a reader
        // looking for it will find it.
        assertFalse(AssertionError.class.isInstance(ex), "a timeout must not be an AssertionError");
    }

    /**
     * The negative-path helper the whole contract rests on must itself fail when a call it expected
     * to be refused is instead accepted. Without this, a driver that accepted every bad param would
     * turn every {@code refuses(...)} in this package into a silent pass.
     */
    @Test
    void refusesBitesWhenTheCallSucceeds() {
        AssertionError bit = assertThrows(AssertionError.class,
                () -> Contract.refuses(Canaries.live(), "mc.system.version", "this call actually succeeds"));
        assertTrue(bit.getMessage() != null && bit.getMessage().contains("accepted params it must reject"),
                "refuses() should have reported an unexpected success, got: " + bit.getMessage());
    }
}
