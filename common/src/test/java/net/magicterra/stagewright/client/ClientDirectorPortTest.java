package net.magicterra.stagewright.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The joining client dials the port the test engine gave the server, whatever its connect address says. */
class ClientDirectorPortTest {

    @Test
    void thePortReplacesTheAddresssOwnOrIsAdded() {
        assertEquals("127.0.0.1:40123", ClientDirector.withPort("127.0.0.1", "40123"));
        assertEquals("127.0.0.1:40123", ClientDirector.withPort("127.0.0.1:25621", "40123"));
        assertEquals("[::1]:40123", ClientDirector.withPort("[::1]", "40123"));
        assertEquals("[::1]:40123", ClientDirector.withPort("[::1]:25621", "40123"));
    }

    @Test
    void withoutOneTheAddressStandsAsItIs() {
        assertEquals("127.0.0.1:25621", ClientDirector.withPort("127.0.0.1:25621", null));
        assertEquals("127.0.0.1:25621", ClientDirector.withPort("127.0.0.1:25621", " "));
    }
}
