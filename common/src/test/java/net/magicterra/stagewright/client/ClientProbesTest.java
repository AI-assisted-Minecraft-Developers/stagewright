package net.magicterra.stagewright.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProbesTest {

    @Test
    void aClientDroppedWhileSettlingProbedNothing() {
        // The companion's results are reconciled with the server's, so this TIMEOUT would otherwise
        // be the probe's only evidence of having run.
        assertFalse(ClientProbes.bodyEntered(ClientProbes.Phase.SETTLING));
        assertTrue(ClientProbes.bodyEntered(ClientProbes.Phase.WAITING));
    }
}
