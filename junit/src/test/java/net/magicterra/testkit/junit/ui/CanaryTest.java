package net.magicterra.testkit.junit.ui;

import com.google.gson.JsonObject;
import net.magicterra.testkit.junit.Testkit;
import net.magicterra.testkit.junit.TestkitExtension;
import net.magicterra.testkit.junit.TestkitTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static net.magicterra.testkit.junit.ui.UiSupport.hasScreen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two canary sentinels — they FAIL ON PURPOSE inside {@code assertThrows} so the
 * harness proves its two teeth against a live endpoint:
 * <ul>
 *   <li>{@code canary.mustFail} — a deliberately-wrong expectation about a REAL
 *       {@code screen.info} read raises {@link AssertionError}: assertions bite.</li>
 *   <li>{@code canary.mustTimeout} — {@code awaitCondition(() -> false, 200ms)} raises
 *       {@link TestkitTimeoutException} (NOT an {@link AssertionError}): timeouts bite,
 *       and the exception TYPE distinguishes a timeout from an assertion failure.</li>
 * </ul>
 * If either canary ever stops throwing, the whole live suite's green is meaningless —
 * that is the point of running them alongside the real scenes.
 */
@ExtendWith(TestkitExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
class CanaryTest {

    @Test
    void mustFail(Testkit tk) {
        // Establish a known REAL state, then assert something false about it.
        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), Duration.ofSeconds(5));
        JsonObject info = tk.screenInfo();               // real read: hasScreen == false
        assertTrue(info.has("hasScreen"), "precondition: screen.info carries hasScreen");

        // The wrong expectation: claim a screen IS open when the live read says it is not.
        AssertionError bit = assertThrows(AssertionError.class,
                () -> assertTrue(hasScreen(info),
                        "deliberately-wrong: no screen is open right now"));
        assertTrue(bit.getMessage() != null && bit.getMessage().contains("deliberately-wrong"),
                "the assertion that bit should be our deliberate one, got: " + bit.getMessage());
    }

    @Test
    void mustTimeout(Testkit tk) {
        // A never-true predicate must raise TestkitTimeoutException — distinct type from
        // AssertionError, which is what lets a canary tell timeout apart from a bad assert.
        TestkitTimeoutException ex = assertThrows(TestkitTimeoutException.class,
                () -> tk.awaitCondition(() -> false, Duration.ofMillis(200)));
        assertEquals(false, AssertionError.class.isInstance(ex),
                "timeout must NOT be an AssertionError");
    }
}
