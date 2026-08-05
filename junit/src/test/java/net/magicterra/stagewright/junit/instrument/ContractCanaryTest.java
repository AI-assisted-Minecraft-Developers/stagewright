package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.StageWrightExtension;
import net.magicterra.stagewright.junit.StageWrightTimeoutException;
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
 * <p>Each canary bites inside {@code assertThrows}, so the class is GREEN when the teeth work and
 * RED the moment either stops biting.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
class ContractCanaryTest {

    /** An assertion about a REAL read, made deliberately wrong, must raise. */
    @Test
    void assertionsBite(StageWright tk) {
        JsonObject version = tk.call("mc.system.version");
        assertEquals("worlddriver", str(version, "modid"), "precondition: the endpoint is a driver");

        AssertionError bit = assertThrows(AssertionError.class,
                () -> assertEquals("definitely-not-the-modid", str(version, "modid"),
                        "deliberately-wrong: the live read says worlddriver"));
        assertTrue(bit.getMessage() != null && bit.getMessage().contains("deliberately-wrong"),
                "the assertion that bit should be our deliberate one, got: " + bit.getMessage());
    }

    /**
     * A never-true predicate must raise a TIMEOUT, and the type must not be an
     * {@link AssertionError} — that distinction is what lets a reader tell "the driver answered
     * something wrong" apart from "the driver never answered".
     */
    @Test
    void timeoutsBiteAndAreNotAssertionFailures(StageWright tk) {
        StageWrightTimeoutException ex = assertThrows(StageWrightTimeoutException.class,
                () -> tk.awaitCondition(() -> false, Duration.ofMillis(200)));
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
    void refusesBitesWhenTheCallSucceeds(StageWright tk) {
        AssertionError bit = assertThrows(AssertionError.class,
                () -> Contract.refuses(tk, "mc.system.version", "this call actually succeeds"));
        assertTrue(bit.getMessage() != null && bit.getMessage().contains("accepted params it must reject"),
                "refuses() should have reported an unexpected success, got: " + bit.getMessage());
    }
}
