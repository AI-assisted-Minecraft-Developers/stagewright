package net.magicterra.stagewright.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The URIs a descriptor turns into, for every bind address the driver accepts.
 *
 * <p>{@code rpcHost} is where the socket BOUND, which is not always something a client can dial: a
 * wildcard accepts connections on every interface and is not itself an address, and an IPv6 literal
 * is only a URI host once it is bracketed. Either mistake surfaces as an attach failure that reads
 * like the game not being up.
 */
class EndpointUriTest {

    private static Endpoint endpoint(String rpcHost, String extra) {
        return Endpoint.parse("{\"version\":1,\"topology\":\"t\",\"loader\":\"fabric\","
                + "\"rpcHost\":\"" + rpcHost + "\",\"rpcPort\":39801,\"worldName\":\"w\","
                + "\"holdPid\":1,\"writtenAtEpochMs\":1,\"mcpPort\":39802" + extra + "}");
    }

    @Test
    void aLoopbackAddressIsUsedAsItIs() {
        assertEquals("ws://127.0.0.1:39801/rpc", endpoint("127.0.0.1", "").wsUri());
        assertEquals("http://127.0.0.1:39802/mcp", endpoint("127.0.0.1", "").mcpUri());
    }

    @Test
    void aHostNameIsUsedAsItIs() {
        assertEquals("ws://localhost:39801/rpc", endpoint("localhost", "").wsUri());
    }

    @Test
    void anIpv6LiteralIsBracketed() {
        assertEquals("ws://[::1]:39801/rpc", endpoint("::1", "").wsUri());
        assertEquals("http://[::1]:39802/mcp", endpoint("::1", "").mcpUri());
        assertEquals("ws://[fd00::5]:39801/rpc", endpoint("fd00::5", "").wsUri());
    }

    @Test
    void anAlreadyBracketedLiteralIsNotBracketedTwice() {
        assertEquals("ws://[::1]:39801/rpc", endpoint("[::1]", "").wsUri());
    }

    @Test
    void anIpv4WildcardBecomesLoopback() {
        assertEquals("ws://127.0.0.1:39801/rpc", endpoint("0.0.0.0", "").wsUri());
        assertEquals("http://127.0.0.1:39802/mcp", endpoint("0.0.0.0", "").mcpUri());
    }

    /** The MCP server binds from its own property, so it can be on a different host than RPC. */
    @Test
    void theMcpUriUsesTheMcpHostWhenTheDescriptorCarriesOne() {
        assertEquals("http://10.0.0.5:39802/mcp",
                endpoint("127.0.0.1", ",\"mcpHost\":\"10.0.0.5\"").mcpUri());
        assertEquals("http://[::1]:39802/mcp", endpoint("127.0.0.1", ",\"mcpHost\":\"::\"").mcpUri());
        assertEquals("ws://127.0.0.1:39801/rpc",
                endpoint("127.0.0.1", ",\"mcpHost\":\"10.0.0.5\"").wsUri());
    }

    @Test
    void anIpv6WildcardBecomesLoopback() {
        assertEquals("ws://[::1]:39801/rpc", endpoint("::", "").wsUri());
        assertEquals("ws://[::1]:39801/rpc", endpoint("0:0:0:0:0:0:0:0", "").wsUri());
    }
}
