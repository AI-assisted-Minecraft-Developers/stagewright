package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The attach URI for every bind address the driver accepts. {@code rpcHost} is where the socket
 * bound, and a wildcard or a bare IPv6 literal is not something a URI can dial.
 */
class EndpointDescriptorTest {

    private static String ws(String host) {
        return new EndpointDescriptor("t", "fabric", host, 39801).wsUri();
    }

    @Test
    void loopbackAndNamesAreUsedAsTheyAre() {
        assertEquals("ws://127.0.0.1:39801/rpc", ws("127.0.0.1"));
        assertEquals("ws://localhost:39801/rpc", ws("localhost"));
    }

    @Test
    void anIpv6LiteralIsBracketedOnce() {
        assertEquals("ws://[::1]:39801/rpc", ws("::1"));
        assertEquals("ws://[::1]:39801/rpc", ws("[::1]"));
    }

    @Test
    void aWildcardBecomesTheSameFamilysLoopback() {
        assertEquals("ws://127.0.0.1:39801/rpc", ws("0.0.0.0"));
        assertEquals("ws://[::1]:39801/rpc", ws("::"));
    }
}
