package net.magicterra.stagewright;

import net.magicterra.worlddriver.WorldDriverCommon;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientWorldReadyTest {
    @Test void aDriverThatIsNotReadyIsLoggedNotThrownIntoTheClientTick() {
        assertNull(WorldDriverCommon.api(), "no driver boots in a unit test");
        // Every client tick asks; neither the first refusal nor the retries may escape.
        assertDoesNotThrow(StageWrightCommon::onClientWorldReady);
        assertDoesNotThrow(StageWrightCommon::onClientWorldReady);
    }
}
