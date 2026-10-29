package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;

/** A test's call outwaits a game thread too busy to take it, and only that. */
class UntilStartedTest {

    private static final ApiException WITHDRAWN = ApiException.of(ApiError.Code.NOT_EXECUTED,
            "server thread did not start the task within 8000ms (busy, loading or paused); it was withdrawn");

    @Test
    void aWithdrawnCallIsMadeAgainUntilTheServerTakesIt() {
        AtomicInteger calls = new AtomicInteger();
        JsonElement answer = ScriptContext.untilStarted(() -> {
            if (calls.incrementAndGet() < 3) throw WITHDRAWN;
            return new JsonPrimitive("ok");
        }, 60_000);
        assertEquals("ok", answer.getAsString());
        assertEquals(3, calls.get());
    }

    @Test
    void anyOtherRefusalIsTheAnswer() {
        AtomicInteger calls = new AtomicInteger();
        ApiException refused = ApiException.of(ApiError.Code.OUTCOME_UNKNOWN, "started but did not finish");
        assertSame(refused, assertThrows(ApiException.class, () -> ScriptContext.untilStarted(() -> {
            calls.incrementAndGet();
            throw refused;
        }, 60_000)));
        assertEquals(1, calls.get());
    }

    @Test
    void theWithdrawalIsTheAnswerOncePatienceRunsOut() {
        assertSame(WITHDRAWN, assertThrows(ApiException.class, () -> ScriptContext.untilStarted(() -> {
            throw WITHDRAWN;
        }, 0)));
    }
}
