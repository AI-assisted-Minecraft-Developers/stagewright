package net.magicterra.stagewright.driver.verbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Baseline;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;

/** The harness's settings pins and writes answer within the call; the tick list refuses any thread but the server's. */
class HarnessServiceTest {

    private final HarnessService harness = new HarnessService();

    private TestHarnessContracts.SettingsReply settings(TestHarnessContracts.SettingsOp op) {
        return ApiRegistry.await(harness.settings(op, CallContext.standalone()), 0);
    }

    private Object read(String key) {
        var values = assertInstanceOf(
                TestHarnessContracts.Values.class, settings(new TestHarnessContracts.Read(List.of(key))));
        return values.get(key);
    }

    @Test
    void aPinRestoresWhatItSavedWhateverWasWrittenAfterIt() {
        Object before = read("autoEat");
        var pin = assertInstanceOf(
                TestHarnessContracts.Pinned.class, settings(TestHarnessContracts.Pin.of(Baseline.CURRENT)));
        settings(TestHarnessContracts.Write.of(Map.of("autoEat", !(Boolean) before)));
        assertEquals(!(Boolean) before, read("autoEat"));
        settings(new TestHarnessContracts.Unpin(pin.pin()));
        assertEquals(before, read("autoEat"));
    }

    @Test
    void theTestBaselineTurnsBreakingOff() {
        var pin = assertInstanceOf(
                TestHarnessContracts.Pinned.class, settings(TestHarnessContracts.Pin.of(Baseline.TEST)));
        try {
            assertEquals(false, read("allowBreak"));
        } finally {
            settings(new TestHarnessContracts.Unpin(pin.pin()));
        }
    }

    @Test
    void anUntimedPinLiftsTheWallClockBudgetsAndUnpinPutsThemBack() {
        Object before = read("pathfinder.maxMs");
        var pin = assertInstanceOf(
                TestHarnessContracts.Pinned.class, settings(new TestHarnessContracts.Pin(Baseline.CURRENT, true)));
        assertEquals(Long.MAX_VALUE / 2, read("pathfinder.maxMs"));
        assertEquals(Long.MAX_VALUE / 2, read("pathfinder.sliceMs"));
        settings(new TestHarnessContracts.Unpin(pin.pin()));
        assertEquals(before, read("pathfinder.maxMs"));
    }

    @Test
    void anUnknownPinIsRefused() {
        ApiException e = assertThrows(ApiException.class, () -> settings(new TestHarnessContracts.Unpin(999_999)));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.error().code());
    }

    @Test
    void theTickListIsTheServerThreads() {
        ApiException e = assertThrows(
                ApiException.class,
                () -> ApiRegistry.await(harness.bots(TestHarnessContracts.Tick.once(), CallContext.standalone()), 0));
        assertEquals(ApiError.Code.CAPABILITY_UNAVAILABLE, e.error().code());
    }
}
