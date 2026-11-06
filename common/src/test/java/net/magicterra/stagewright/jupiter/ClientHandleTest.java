package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.api.registry.ApiRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientHandleTest {

    @AfterEach
    void forget() {
        ClientHandle.end(null);
    }

    @Test
    void aClientThatHostsTheGameRefusesToLeaveIt() {
        ClientHandle hosting = new ClientHandle(null, new ApiRegistry(), true);
        IllegalStateException refused = assertThrows(IllegalStateException.class, hosting::disconnect);
        assertTrue(refused.getMessage().contains("joins a dedicated server"), refused.getMessage());
        assertThrows(IllegalStateException.class, hosting::reconnect);
    }

    @Test
    void aClientInTheGameRefusesToJoinItAgain() {
        ClientHandle joined = new ClientHandle(null, new ApiRegistry(), false);
        IllegalStateException refused = assertThrows(IllegalStateException.class, joined::reconnect);
        assertTrue(refused.getMessage().contains("disconnect() first"), refused.getMessage());
    }

    @Test
    void aTestThatLeftTheClientInTheGameRejoinsNothing() throws Throwable {
        // A game would be asked for, and there is none: nothing is called when nothing was disconnected.
        new ClientHandle(null, new ApiRegistry(), false).rejoinAtTheEnd().close();
        ClientHandle.rejoinIfLeftBy(null, null);
    }
}
