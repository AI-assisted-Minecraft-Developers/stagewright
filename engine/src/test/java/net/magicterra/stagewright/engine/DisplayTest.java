package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** When a client is refused for want of a display, and when it is let through. */
class DisplayTest {

    private static final byte[] GOOD = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** Display :0 only, and it wants {@link #GOOD}. */
    private static final Display.XServer ONLY_ZERO = (n, tcpToo, cookie) -> {
        if (n != 0) return Display.Answer.NOT_LISTENING;
        if (Arrays.equals(cookie, GOOD)) return Display.Answer.ACCEPTED;
        return new Display.Answer(Display.Answer.Kind.REFUSED,
                "Authorization required, but no authorization protocol specified");
    };

    /** With {@code home} as the client's HOME unless {@code env} names one. */
    private static String missing(Map<String, ?> env, Path home) {
        Map<String, Object> withHome = new HashMap<>(env);
        withHome.putIfAbsent("HOME", home.toString());
        return Display.missing("Linux", withHome, "host", ONLY_ZERO);
    }

    @Test
    void noDisplayAtAllIsRefusedOnLinuxOnly(@TempDir Path home) {
        assertNotNull(missing(Map.of(), home));
        assertNull(Display.missing("Windows 11", Map.of(), "host", ONLY_ZERO));
    }

    @Test
    void aLocalDisplayNobodyListensOnIsRefused(@TempDir Path home) throws IOException {
        Path auth = authority(home.resolve("xauth"), 0, GOOD);
        String why = missing(Map.of("DISPLAY", ":99", "XAUTHORITY", auth.toString()), home);
        assertNotNull(why);
        assertTrue(why.contains("/tmp/.X11-unix/X99"), why);
        for (String display : new String[] {":0", ":0.0", "unix:0"}) {
            assertNull(missing(Map.of("DISPLAY", display, "XAUTHORITY", auth.toString()), home), display);
        }
    }

    @Test
    void aWaylandSessionDoesNotExcuseADeadDisplay(@TempDir Path home) {
        assertNotNull(missing(Map.of("DISPLAY", ":99", "WAYLAND_DISPLAY", "wayland-0"), home));
        assertNull(missing(Map.of("WAYLAND_DISPLAY", "wayland-0"), home));
    }

    @Test
    void aRemoteDisplayIsNotAsked(@TempDir Path home) {
        assertNull(missing(Map.of("DISPLAY", "localhost:10.0"), home));
    }

    @Test
    void eachWayOfHavingNoCookieIsNamed(@TempDir Path home) throws IOException {
        String unset = missing(Map.of("DISPLAY", ":0"), home);
        assertTrue(unset.contains("XAUTHORITY is unset and there is no " + home.resolve(".Xauthority")), unset);
        assertTrue(unset.contains("Authorization required"), unset);

        String stale = missing(Map.of("DISPLAY", ":0", "XAUTHORITY", "/run/user/1000/xauth_gone"), home);
        assertTrue(stale.contains("XAUTHORITY=/run/user/1000/xauth_gone does not exist"), stale);

        Path other = authority(home.resolve("other"), 1, GOOD);
        String elsewhere = missing(Map.of("DISPLAY", ":0", "XAUTHORITY", other.toString()), home);
        assertTrue(elsewhere.contains("holds no MIT-MAGIC-COOKIE-1 for display :0"), elsewhere);

        Path wrong = authority(home.resolve("wrong"), 0, "not the cookie!!".getBytes(StandardCharsets.US_ASCII));
        String rejected = missing(Map.of("DISPLAY", ":0", "XAUTHORITY", wrong.toString()), home);
        assertTrue(rejected.contains("is not the one this server was started with"), rejected);

        authority(home.resolve(".Xauthority"), 0, GOOD);
        assertNull(missing(Map.of("DISPLAY", ":0"), home), "~/.Xauthority is the default");
    }

    @Test
    void aWildcardEntryWithoutADisplayNumberCoversEveryDisplay(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("xauth");
        Files.write(file, entry(65535, "", "MIT-MAGIC-COOKIE-1", GOOD));
        assertArrayEquals(GOOD, Display.cookie(file, 7, "host"));
    }

    @Test
    void onlyTheLocalEntryNamingThisHostIsPresented(@TempDir Path home) throws IOException {
        // A home shared with another machine, or kept across a rename: its :0 is not ours.
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(entry(256, "elsewhere", "0", "MIT-MAGIC-COOKIE-1",
                "not the cookie!!".getBytes(StandardCharsets.US_ASCII)));
        file.write(entry(256, "host", "0", "MIT-MAGIC-COOKIE-1", GOOD));
        Path auth = Files.write(home.resolve("xauth"), file.toByteArray());

        assertNull(missing(Map.of("DISPLAY", ":0", "XAUTHORITY", auth.toString()), home));
    }

    @Test
    void theDefaultAuthorityIsUnderTheClientsHome(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        authority(home.resolve(".Xauthority"), 0, GOOD);
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));

        assertNull(missing(Map.of("DISPLAY", ":0", "HOME", home.toString()), elsewhere));
    }

    @Test
    void theAuthorityFileIsTheOneLibXauWouldRead(@TempDir Path home) throws IOException {
        authority(home.resolve(".Xauthority"), 0, GOOD);

        // Set but empty is still XAUTHORITY: libXau opens "" and presents nothing.
        String empty = missing(Map.of("DISPLAY", ":0", "XAUTHORITY", ""), home);
        assertTrue(empty.contains("XAUTHORITY is set but empty"), empty);
        String homeless = Display.missing("Linux", Map.of("DISPLAY", ":0"), "host", ONLY_ZERO);
        assertTrue(homeless.contains("neither XAUTHORITY nor HOME is set"), homeless);
        // An empty HOME is concatenated, as libXau does: "/.Xauthority", not a relative path.
        String rootless = Display.missing("Linux", Map.of("DISPLAY", ":0", "HOME", ""), "host", ONLY_ZERO);
        assertTrue(rootless.contains("there is no /.Xauthority."), rootless);
    }

    @Test
    void aDisplayNumberIsReadAsLibxcbReadsIt(@TempDir Path home) throws IOException {
        assertEquals(1215752191, Display.displayNumber("99999999999"));   // low 32 bits of it
        assertEquals(0, Display.displayNumber("4294967296"));
        assertEquals(-1, Display.displayNumber("999999999999999999999999"));   // strtoul saturates
        assertEquals(6000, Display.tcpPort(65536));
        assertEquals(5999, Display.tcpPort(-1));

        // A mistyped number is asked where libxcb would go, and nothing answers there.
        assertNotNull(missing(Map.of("DISPLAY", ":99999999999"), home));
        // One that wraps round to :0 is :0.
        Path auth = authority(home.resolve("xauth"), 0, GOOD);
        assertNull(missing(Map.of("DISPLAY", ":4294967296", "XAUTHORITY", auth.toString()), home));
        // Below zero, past the int range or onto port 0, a number is asked where it wraps, not thrown on.
        for (int n : new int[] {-1, Integer.MAX_VALUE, 65536 - 6000}) {
            assertDoesNotThrow(() -> Display.ask(home, n, true, null), String.valueOf(n));
        }
    }

    @Test
    void aMissingSocketIsReportedInTheSystemsOwnWords(@TempDir Path sockets) {
        Display.Answer answer = Display.ask(sockets, 0, false, null);

        assertEquals(Display.Answer.Kind.NOT_LISTENING, answer.kind());
        assertTrue(answer.reason().startsWith(sockets.resolve("X0") + ": "), answer.reason());
    }

    @Test
    void theHandshakeReportsWhatTheServerSaid(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("X0");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));

            CompletableFuture<byte[]> sent = serveOnce(server, refusal("No protocol specified"));
            Display.Answer refused = Display.handshake(UnixDomainSocketAddress.of(socket), null, 3000);
            assertEquals(new Display.Answer(Display.Answer.Kind.REFUSED, "No protocol specified"), refused);
            assertEquals('l', sent.get()[0]);

            sent = serveOnce(server, new byte[] {1, 0, 11, 0, 0, 0, 0, 0});
            assertEquals(Display.Answer.ACCEPTED, Display.handshake(UnixDomainSocketAddress.of(socket), GOOD, 3000));
            byte[] setup = sent.get();
            assertEquals(18, ByteBuffer.wrap(setup, 6, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
            assertEquals("MIT-MAGIC-COOKIE-1", new String(setup, 12, 18, StandardCharsets.US_ASCII));
            assertArrayEquals(GOOD, Arrays.copyOfRange(setup, 32, 48));
        }
        Files.delete(socket);
        assertEquals(Display.Answer.Kind.NOT_LISTENING,
                Display.handshake(UnixDomainSocketAddress.of(socket), null, 3000).kind());
    }

    @Test
    void aDisplayWithNoSocketIsAskedOverTcpAsLibxcbWould(@TempDir Path sockets) throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            // The name, not the loopback constant: localhost is what libxcb and the check resolve.
            server.bind(new InetSocketAddress(InetAddress.getByName("localhost"), 0));
            int n = ((InetSocketAddress) server.getLocalAddress()).getPort() - 6000;

            // unix:N names its protocol, and libxcb then tries nothing else.
            assertEquals(Display.Answer.Kind.NOT_LISTENING, Display.ask(sockets, n, false, null).kind());

            CompletableFuture<byte[]> sent = serveOnce(server, refusal("No protocol specified"));
            assertEquals(new Display.Answer(Display.Answer.Kind.REFUSED, "No protocol specified"),
                    Display.ask(sockets, n, true, null));
            assertEquals('l', sent.get()[0]);
        }
    }

    @Test
    void whatEachAttemptRanIntoIsSaid(@TempDir Path sockets) throws IOException {
        int port;
        try (ServerSocketChannel free = ServerSocketChannel.open()) {
            free.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = ((InetSocketAddress) free.getLocalAddress()).getPort();
        }
        int n = port - 6000;
        // Something is at the socket's path, just not a server: that is not "no socket".
        Path notAServer = Files.writeString(sockets.resolve("X" + n), "");

        Display.Answer answer = Display.ask(sockets, n, true, null);

        assertEquals(Display.Answer.Kind.NOT_LISTENING, answer.kind());
        assertTrue(answer.reason().contains(notAServer + ": "), answer.reason());
        assertFalse(answer.reason().contains("no " + notAServer), answer.reason());
        assertTrue(answer.reason().contains("localhost:" + port), answer.reason());
    }

    @Test
    void theDisplaysOwnProtocolDecidesWhetherTcpIsTriedAndTheReasonIsQuoted(@TempDir Path home) {
        List<Boolean> tcp = new ArrayList<>();
        Display.XServer none = (n, tcpToo, cookie) -> {
            tcp.add(tcpToo);
            return new Display.Answer(Display.Answer.Kind.NOT_LISTENING, "/tmp/.X11-unix/X5: Permission denied");
        };
        String why = Display.missing("Linux", Map.of("DISPLAY", ":5"), "host", none);
        Display.missing("Linux", Map.of("DISPLAY", "unix:5"), "host", none);

        assertEquals(List.of(true, false), tcp);
        assertTrue(why.contains("/tmp/.X11-unix/X5: Permission denied"), why);
    }

    @Test
    void aServerWithAFullBacklogIsBusyNotAbsent(@TempDir Path tmp) throws Exception {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(tmp.resolve("X0"));
        List<SocketChannel> waiting = new ArrayList<>();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address, 1);
            // Never accepted, so the backlog fills and a blocking connect after it would wait for good.
            for (int i = 0; i < 64; i++) {
                SocketChannel filler = SocketChannel.open(StandardProtocolFamily.UNIX);
                filler.configureBlocking(false);
                waiting.add(filler);
                try {
                    filler.connect(address);
                } catch (IOException full) {
                    break;
                }
            }

            // libxcb waits on it, so the check must neither hang nor call it absent.
            Display.Answer answer = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> Display.handshake(address, null, 500));

            assertEquals(Display.Answer.NOT_ASKED, answer);
        } finally {
            for (SocketChannel filler : waiting) filler.close();
        }
    }

    /** Accepts one connection, reads its setup request, writes {@code reply}. */
    private static CompletableFuture<byte[]> serveOnce(ServerSocketChannel server, byte[] reply) {
        return CompletableFuture.supplyAsync(() -> {
            try (SocketChannel client = server.accept()) {
                ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                while (head.hasRemaining()) client.read(head);
                int rest = pad(head.getShort(6)) + pad(head.getShort(8));
                ByteBuffer all = ByteBuffer.allocate(12 + rest);
                all.put(head.array());
                while (all.hasRemaining()) client.read(all);
                client.write(ByteBuffer.wrap(reply));
                return all.array();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static byte[] refusal(String reason) {
        byte[] text = reason.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(8 + pad(text.length)).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 0).put((byte) text.length).putShort((short) 11).putShort((short) 0)
                .putShort((short) (pad(text.length) / 4)).put(text);
        return out.array();
    }

    private static int pad(int n) {
        return (n + 3) & ~3;
    }

    private static Path authority(Path file, int display, byte[] cookie) throws IOException {
        return Files.write(file, entry(256, String.valueOf(display), "MIT-MAGIC-COOKIE-1", cookie));
    }

    private static byte[] entry(int family, String number, String name, byte[] data) {
        return entry(family, "host", number, name, data);
    }

    private static byte[] entry(int family, String address, String number, String name, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) family);
        for (byte[] field : new byte[][] {address.getBytes(StandardCharsets.US_ASCII),
                number.getBytes(StandardCharsets.US_ASCII), name.getBytes(StandardCharsets.US_ASCII), data}) {
            b.putShort((short) field.length).put(field);
        }
        out.write(b.array(), 0, b.position());
        return out.toByteArray();
    }
}
