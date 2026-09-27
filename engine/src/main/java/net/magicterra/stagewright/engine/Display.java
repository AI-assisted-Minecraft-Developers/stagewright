package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a game client can open a window — asked by every supervisor before it starts one.
 *
 * <p>Neither supervisor provides a display. That is the machine's, the user's or the CI image's to
 * supply, and a test runner that starts X servers ends up owning their leaks, their probing and their
 * platform quirks. What a supervisor can do is refuse early: without a display GLFW fails to
 * initialise, and NeoForge then opens a dialog asking whether to visit a help page — which nobody
 * will click, so the run sits asleep until a watchdog kills it minutes later.
 *
 * <p>A local X display is asked directly, with the connection handshake GLFW itself will make: an
 * authority file that is missing, stale, or holds no cookie for that display is refused by the server
 * in the reply, and the reply's own reason is what gets reported.
 */
public final class Display {

    /** A display on this machine: {@code :0}, {@code :1.0}, {@code unix:0}. {@code host:0} is remote. */
    private static final Pattern LOCAL = Pattern.compile("^(unix)?:(\\d+)(?:\\.\\d+)?$");
    private static final Path SOCKETS = Path.of("/tmp/.X11-unix");
    private static final int TCP_PORT = 6000;
    private static final BigInteger ULONG_MAX = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
    private static final String COOKIE = "MIT-MAGIC-COOKIE-1";
    private static final int FAMILY_LOCAL = 256;
    private static final int FAMILY_WILD = 65535;
    private static final long HANDSHAKE_MILLIS = 3000;

    private Display() {}

    /**
     * What an X server said to a connection attempt: {@code reason} is the server's own when it
     * refused, and what each attempt ran into when nothing answered.
     */
    record Answer(Kind kind, String reason) {
        enum Kind { ACCEPTED, REFUSED, NOT_LISTENING, NOT_ASKED }
        static final Answer ACCEPTED = new Answer(Kind.ACCEPTED, null);
        static final Answer NOT_LISTENING = new Answer(Kind.NOT_LISTENING, null);
        static final Answer NOT_ASKED = new Answer(Kind.NOT_ASKED, null);
    }

    /**
     * Connects to local display {@code n}, presenting {@code cookie} (null for none); {@code tcpToo}
     * when the display names no protocol, so libxcb would try TCP after the socket.
     */
    interface XServer {
        Answer connect(int n, boolean tcpToo, byte[] cookie);
    }

    /**
     * Why a client cannot open a window here, or null if it can.
     *
     * @param env the environment the client will be started with, not necessarily this process's
     */
    public static String missing(String osName, Map<String, ?> env) {
        return missing(osName, env, hostname(), (n, tcpToo, cookie) -> ask(SOCKETS, n, tcpToo, cookie));
    }

    /**
     * {@code WAYLAND_DISPLAY} only stands in for a missing {@code DISPLAY}. When both are set the X
     * one is still asked: measured in a KDE Wayland session, LWJGL's own GLFW opens Minecraft's window
     * through Xwayland, and an X11-only GLFW passed as {@code org.lwjgl.glfw.libname} can do nothing
     * else — so a dead {@code DISPLAY} beside a live Wayland one still fails in {@code glfwInit}.
     */
    static String missing(String osName, Map<String, ?> env, String hostname, XServer server) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) return null;
        if (!isSet(env.get("DISPLAY"))) {
            if (isSet(env.get("WAYLAND_DISPLAY"))) return null;
            return "a client needs a display, and neither DISPLAY nor WAYLAND_DISPLAY is set. Run it in"
                    + " a desktop session, or give it one — on a headless machine that is an X server the"
                    + " environment starts, such as Xvfb in the CI image or xvfb-run around the command.";
        }
        String display = env.get("DISPLAY").toString().trim();
        Matcher local = LOCAL.matcher(display);
        if (!local.matches()) return null;
        // Read as libxcb reads it, so a mistyped number is asked where GLFW would really connect.
        int n = displayNumber(local.group(2));

        // The file libXau reads: XAUTHORITY whenever it is set, even empty, else $HOME/.Xauthority,
        // else none. The client's HOME, not the JVM's user.home, which need not match it.
        Object xauthority = env.get("XAUTHORITY");
        Object home = env.get("HOME");
        Path authority = xauthority != null ? Path.of(xauthority.toString())
                : home != null ? Path.of(home + "/.Xauthority") : null;
        byte[] cookie = authority == null ? null : cookie(authority, n, hostname);
        Answer answer = server.connect(n, local.group(1) == null, cookie);
        return switch (answer.kind()) {
            case ACCEPTED, NOT_ASKED -> null;
            case NOT_LISTENING -> "DISPLAY=" + display + ", but no X server answers on it ("
                    + (answer.reason() == null ? "nothing at " + SOCKETS.resolve("X" + n) : answer.reason())
                    + "). Point DISPLAY at the X server that is running, or start one first.";
            case REFUSED -> "the X server on DISPLAY=" + display + " refused a connection: \""
                    + answer.reason() + "\" — " + authorityProblem(env, authority, cookie, n, hostname)
                    + " Export XAUTHORITY from the desktop session (under Xwayland it is"
                    + " /run/user/$UID/xauth_*, whose name changes with every login).";
        };
    }

    private static String authorityProblem(Map<String, ?> env, Path authority, byte[] cookie, int n,
                                           String hostname) {
        if (authority == null) {
            return "neither XAUTHORITY nor HOME is set, so the client has no authority file to present.";
        }
        boolean explicit = env.get("XAUTHORITY") != null;
        if (explicit && env.get("XAUTHORITY").toString().isEmpty()) {
            return "XAUTHORITY is set but empty, so the client presents no cookie.";
        }
        if (!Files.isRegularFile(authority)) {
            return explicit
                    ? "XAUTHORITY=" + authority + " does not exist."
                    : "XAUTHORITY is unset and there is no " + authority + ".";
        }
        if (cookie == null) {
            return authority + " holds no " + COOKIE + " for display :" + n
                    + (hostname == null ? "" : " on host " + hostname) + ".";
        }
        return "the cookie in " + authority + " is not the one this server was started with.";
    }

    /**
     * The {@code MIT-MAGIC-COOKIE-1} an authority file holds for local display {@code n}, or null —
     * the entry libxcb would present. A wildcard entry counts, and a local one only if it names
     * {@code hostname}: a home shared between machines, or kept across a rename, holds entries for
     * {@code :0} that belong to another host. An entry without a display number applies to every
     * display.
     *
     * @param hostname this machine's name, or null when unknown and every local entry counts
     */
    static byte[] cookie(Path authority, int n, String hostname) {
        byte[] file;
        try {
            if (!Files.isRegularFile(authority)) return null;
            file = Files.readAllBytes(authority);
        } catch (IOException e) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        try {
            while (in.remaining() >= 2) {
                int family = Short.toUnsignedInt(in.getShort());
                String address = new String(field(in), StandardCharsets.US_ASCII);
                String number = new String(field(in), StandardCharsets.US_ASCII);
                String name = new String(field(in), StandardCharsets.US_ASCII);
                byte[] data = field(in);
                boolean here = family == FAMILY_WILD
                        || family == FAMILY_LOCAL && (hostname == null || hostname.equals(address));
                boolean thisDisplay = number.isEmpty() || number.equals(String.valueOf(n));
                if (here && thisDisplay && COOKIE.equals(name)) return data;
            }
        } catch (RuntimeException e) {
            // A truncated file: whatever came before the damage has already been searched.
        }
        return null;
    }

    /** The name libxcb looks up a local entry by — {@code gethostname()} — or null if unreadable. */
    private static String hostname() {
        try {
            return Files.readString(Path.of("/proc/sys/kernel/hostname"), StandardCharsets.US_ASCII).strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] field(ByteBuffer in) {
        byte[] out = new byte[Short.toUnsignedInt(in.getShort())];
        in.get(out);
        return out;
    }

    /**
     * Asks display {@code n} where libxcb would reach it: its socket in {@code sockets}, then, for a
     * display that names no protocol, TCP on localhost. A server only libxcb can reach is still one
     * GLFW can open a window on, and refusing it would stop a run that works.
     */
    static Answer ask(Path sockets, int n, boolean tcpToo, byte[] cookie) {
        Path socket = sockets.resolve("X" + n);
        List<String> tried = new ArrayList<>();
        // Tried even where the file seems absent: a directory this user cannot search hides it just
        // the same, and only the connect says which of the two it is.
        Answer onSocket = handshake(UnixDomainSocketAddress.of(socket), cookie, HANDSHAKE_MILLIS);
        if (onSocket.kind() != Answer.Kind.NOT_LISTENING) return onSocket;
        tried.add(socket + ": " + onSocket.reason());
        // An X server may hold the abstract socket of the same name, which Java cannot connect to,
        // so it is let through unasked.
        if (abstractSocketListed(socket)) return Answer.NOT_ASKED;
        int port = tcpPort(n);
        if (tcpToo) {
            for (InetAddress address : localhost()) {
                Answer answer = handshake(new InetSocketAddress(address, port), cookie, HANDSHAKE_MILLIS);
                if (answer.kind() != Answer.Kind.NOT_LISTENING) return answer;
                tried.add("localhost:" + port + " (" + address.getHostAddress() + "): " + answer.reason());
            }
        }
        return new Answer(Answer.Kind.NOT_LISTENING, String.join("; ", tried));
    }

    /** libxcb's display number: {@code strtoul}, saturating at 2^64 - 1, stored in an {@code int}. */
    static int displayNumber(String digits) {
        return new BigInteger(digits).min(ULONG_MAX).intValue();
    }

    /** libxcb's TCP port for display {@code n}: 6000 + n in an {@code unsigned short}, so it wraps. */
    static int tcpPort(int n) {
        return (TCP_PORT + n) & 0xFFFF;
    }

    private static List<InetAddress> localhost() {
        try {
            return List.of(InetAddress.getAllByName("localhost"));
        } catch (UnknownHostException e) {
            return List.of(InetAddress.getLoopbackAddress());
        }
    }

    /**
     * The X11 connection setup, as far as the server's verdict on it: the first eight bytes of its
     * reply say whether the connection was accepted, and a refusal carries the server's reason.
     *
     * <p>The connect blocks as libxcb's does — a server whose backlog is full is busy, not absent —
     * but nothing here waits past {@code millis}, and running out of time lets the client through
     * unasked.
     */
    static Answer handshake(SocketAddress address, byte[] cookie, long millis) {
        byte[] name = cookie == null ? new byte[0] : COOKIE.getBytes(StandardCharsets.US_ASCII);
        byte[] data = cookie == null ? new byte[0] : cookie;
        ByteBuffer setup = ByteBuffer.allocate(12 + padded(name.length) + padded(data.length))
                .order(ByteOrder.LITTLE_ENDIAN);
        setup.put((byte) 'l').put((byte) 0).putShort((short) 11).putShort((short) 0)
                .putShort((short) name.length).putShort((short) data.length).putShort((short) 0);
        setup.put(name).position(12 + padded(name.length)).put(data).position(setup.capacity()).flip();

        long deadline = System.currentTimeMillis() + millis;
        try (SocketChannel channel = address instanceof UnixDomainSocketAddress
                     ? SocketChannel.open(StandardProtocolFamily.UNIX) : SocketChannel.open();
             Selector selector = Selector.open()) {
            if (!connect(channel, address, deadline)) return Answer.NOT_ASKED;
            channel.configureBlocking(false);
            SelectionKey key = channel.register(selector, SelectionKey.OP_WRITE);
            while (setup.hasRemaining()) {
                if (channel.write(setup) == 0 && !await(selector, deadline)) return Answer.NOT_ASKED;
            }
            key.interestOps(SelectionKey.OP_READ);
            ByteBuffer head = read(channel, selector, 8, deadline);
            if (head == null) return Answer.NOT_ASKED;
            int status = head.get(0);
            if (status == 1) return Answer.ACCEPTED;
            // 0 is Failed and 2 Authenticate; anything else is not an X server answering.
            if (status != 0 && status != 2) return Answer.NOT_ASKED;
            int reasonLength = status == 0
                    ? Byte.toUnsignedInt(head.get(1))
                    : Short.toUnsignedInt(head.order(ByteOrder.LITTLE_ENDIAN).getShort(6)) * 4;
            ByteBuffer reason = read(channel, selector, reasonLength, deadline);
            String text = reason == null ? "" : new String(reason.array(), 0, reasonLength,
                    StandardCharsets.ISO_8859_1);
            return new Answer(Answer.Kind.REFUSED, text.replace("\0", "").strip());
        } catch (IOException e) {
            // Said as it is: "permission denied" is not the same as no server.
            return new Answer(Answer.Kind.NOT_LISTENING,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** A blocking connect that gives up at {@code deadline}; false if it did. */
    private static boolean connect(SocketChannel channel, SocketAddress address, long deadline)
            throws IOException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        // A platform thread: a virtual one connects without blocking underneath, and a unix socket
        // with a full backlog then fails at once instead of waiting.
        Thread connecting = new Thread(() -> {
            try {
                channel.connect(address);
                done.complete(null);
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        }, "stagewright-x11-connect");
        connecting.setDaemon(true);
        connecting.start();
        try {
            done.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;   // the caller closes the channel, which ends the connect still waiting
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ByteBuffer read(SocketChannel channel, Selector selector, int length, long deadline)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int got = channel.read(buffer);
            if (got < 0) return null;
            if (got == 0 && !await(selector, deadline)) return null;
        }
        return buffer;
    }

    /** Waits on {@code selector} until {@code deadline}; false once it has passed. */
    private static boolean await(Selector selector, long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) return false;
        selector.select(left);
        selector.selectedKeys().clear();
        return true;
    }

    private static int padded(int length) {
        return (length + 3) & ~3;
    }

    /** Whether {@code /proc/net/unix} lists {@code @socket}, the abstract twin of the file. */
    private static boolean abstractSocketListed(Path socket) {
        try {
            List<String> sockets = Files.readAllLines(Path.of("/proc/net/unix"));
            for (String line : sockets) {
                if (line.endsWith(" @" + socket)) return true;
            }
        } catch (IOException | RuntimeException e) {
            // Not Linux-shaped; the file was the only evidence there was.
        }
        return false;
    }

    private static boolean isSet(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
