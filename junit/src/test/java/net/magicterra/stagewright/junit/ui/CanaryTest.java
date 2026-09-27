package net.magicterra.stagewright.junit.ui;

import net.magicterra.stagewright.junit.Canaries;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWrightExtension;
import net.magicterra.stagewright.contract.StageWrightTimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static net.magicterra.stagewright.junit.ui.UiSupport.hasScreen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two canary sentinels — they FAIL ON PURPOSE so the harness proves its two teeth
 * against a live endpoint:
 * <ul>
 *   <li>{@code canary.mustFail} — {@link DeliberatelyWrong} asserts a screen is open right
 *       after a REAL {@code screen.info} read says none is, and {@link Canaries} requires
 *       JUnit to report that test as failed: on the client face the extension neither skips
 *       nor swallows a real failure.</li>
 *   <li>{@code canary.mustTimeout} — {@code awaitCondition(() -> false, 200ms)} raises
 *       {@link StageWrightTimeoutException} (NOT an {@link AssertionError}): timeouts bite,
 *       and the exception TYPE distinguishes a timeout from an assertion failure.</li>
 * </ul>
 * If either canary ever stops biting, the whole live suite's green is meaningless. This class is
 * kept off {@link StageWrightExtension} so that {@code mustFail} can check the extension itself.
 */
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@ExtendWith(Canaries.FaceGate.class)
@RequiresFace(Face.CLIENT)
class CanaryTest {

    @Test
    void mustFail() {
        assertTrue(Canaries.live().screenInfo().has("hasScreen"), "precondition: screen.info carries hasScreen");
        Canaries.failOnPurpose(DeliberatelyWrong.class);
    }

    @Tag(Canaries.TARGET)
    @ExtendWith(Canaries.Armed.class)
    @ExtendWith(StageWrightExtension.class)
    @RequiresFace(Face.CLIENT)
    static class DeliberatelyWrong {
        @Test
        void claimsAScreenIsOpen(StageWright tk) {
            tk.reset();
            tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), Duration.ofSeconds(5));
            assertTrue(hasScreen(tk.screenInfo()), "deliberately-wrong: no screen is open right now");
        }
    }

    @Test
    void mustTimeout() {
        // A never-true predicate must raise StageWrightTimeoutException — distinct type from
        // AssertionError, which is what lets a canary tell timeout apart from a bad assert.
        StageWrightTimeoutException ex = assertThrows(StageWrightTimeoutException.class,
                () -> Canaries.live().awaitCondition(() -> false, Duration.ofMillis(200)));
        assertEquals(false, AssertionError.class.isInstance(ex),
                "timeout must NOT be an AssertionError");
    }
}
