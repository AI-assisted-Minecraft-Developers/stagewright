package net.magicterra.stagewright.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.magicterra.stagewright.StageWrightCommon;

/**
 * Writes the {@code TESTKIT_ENDPOINT} descriptor (schema v1) that {@code stagewright-junit}'s
 * {@code StageWright.attach()} reads, when {@code -Dstagewright.endpoint=<path>} asks for one.
 *
 * <p><b>The game writes it, not the launcher.</b> Only this JVM knows the moment the endpoint is
 * genuinely usable — RPC listening, world loaded, player in it — and only it knows the port it
 * actually got, which is not the port anyone asked for whenever the pinned one was taken. The
 * Python holds this replaces polled a log file for that moment from outside and were wrong about
 * it often enough to have a retry loop.
 *
 * <p><b>One writer per JVM, and the run task's JVM is the writer.</b> A client run (the integrated
 * topology, and any {@code runClient} a developer holds by hand) writes a client-face descriptor
 * from {@code ClientDirector}; a dedicated server writes a server-face one from
 * {@link StageWrightCommon#onServerStarted}. Both faces are legitimate attach targets and the
 * {@code topology} field says which one this is — the UI tests want the client, the instrument
 * contract wants a bare dedicated server. The integrated topology has both in one JVM, so the
 * server side stands down there ({@code isDedicatedServer()}) and the client writes the single
 * descriptor: same port either way, but the client writes it later, when there is a world.
 *
 * <p>The port is read from worlddriver's {@code worlddriver-rpc.port}, not from
 * {@code -Dworlddriver.rpcPort}: the property is what was requested, the file is what was bound.
 * Reading the file also keeps this class from linking against the driver, which is what lets
 * StageWright arm in a runtime that has no driver at all.
 */
public final class EndpointDescriptor {

    /** Where to write the descriptor. Unset (the normal case) means write nothing. */
    public static final String PROPERTY = "stagewright.endpoint";

    /** Which topology this run is, recorded verbatim for the reader. */
    static final String TOPOLOGY_PROPERTY = "stagewright.topology";

    /** worlddriver's record of the RPC port it actually bound, relative to this JVM's run dir. */
    static final String PORT_FILE = "worlddriver-rpc.port";

    /** The same, for the MCP HTTP face. Bare RPC does not carry the schema catalog: schemas are
     *  advertised through MCP's {@code tools/list}, so anything asserting about a tool's declared
     *  shape has to reach that port instead. */
    static final String MCP_PORT_FILE = "worlddriver-mcp.port";

    /** The frozen schema the junit module parses. */
    private static final int SCHEMA_VERSION = 1;

    /** First writer wins, so a JVM that reaches both call sites publishes one descriptor. */
    private static volatile boolean written;

    private EndpointDescriptor() {}

    /**
     * Write the descriptor if this run asked for one. Idempotent; never throws — a hold whose
     * descriptor could not be written reports it in the log and keeps holding, because the game is
     * still perfectly attachable by hand from the port file.
     *
     * @param loader    the loader name this JVM is running under, for the descriptor's {@code loader}
     * @param worldName the world this endpoint is in, for the descriptor's {@code worldName}
     */
    public static void writeIfRequested(String loader, String worldName) {
        String target = System.getProperty(PROPERTY);
        if (target == null || target.isBlank() || written) return;
        written = true;

        Path out = Path.of(target.trim());
        Integer port = readPort(PORT_FILE);
        if (port == null) {
            StageWrightCommon.LOG.error("[{}] -D{} asked for an endpoint descriptor, but {} does not"
                    + " exist in {} — is worlddriver on this runtime's classpath?",
                    StageWrightCommon.MOD_ID, PROPERTY, PORT_FILE, Path.of("").toAbsolutePath());
            return;
        }

        String host = System.getProperty("worlddriver.rpcHost", "127.0.0.1");
        Integer mcpPort = readPort(MCP_PORT_FILE);
        // The MCP server binds from its own property, so a reader dialling rpcHost for it would be
        // wrong exactly when someone moved it. Written only when set: unset, both share one default.
        String mcpHost = System.getProperty("worlddriver.mcpHost");
        String json = "{\"version\":" + SCHEMA_VERSION
                + ",\"topology\":\"" + ResultsJsonl.escape(System.getProperty(TOPOLOGY_PROPERTY, "unknown")) + '"'
                + ",\"loader\":\"" + ResultsJsonl.escape(loader == null ? "unknown" : loader) + '"'
                + ",\"rpcHost\":\"" + ResultsJsonl.escape(host) + '"'
                + ",\"rpcPort\":" + port
                + ",\"worldName\":\"" + ResultsJsonl.escape(worldName == null ? "" : worldName) + '"'
                + ",\"holdPid\":" + ProcessHandle.current().pid()
                + ",\"writtenAtEpochMs\":" + System.currentTimeMillis()
                + (mcpPort == null ? "" : ",\"mcpPort\":" + mcpPort)
                + (mcpPort == null || mcpHost == null || mcpHost.isBlank() ? ""
                        : ",\"mcpHost\":\"" + ResultsJsonl.escape(mcpHost.trim()) + '"')
                + "}\n";
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, json, StandardCharsets.UTF_8);
            StageWrightCommon.LOG.info("[{}] endpoint descriptor written to {} — attach with"
                    + " TESTKIT_ENDPOINT={}", StageWrightCommon.MOD_ID, out.toAbsolutePath(),
                    out.toAbsolutePath());
        } catch (IOException e) {
            StageWrightCommon.LOG.error("[{}] could not write the endpoint descriptor to {}: {}",
                    StageWrightCommon.MOD_ID, out.toAbsolutePath(), e.toString());
        }
    }

    /** A port worlddriver recorded, or null when the file is absent or not a number. */
    private static Integer readPort(String file) {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) return null;
        try {
            return Integer.valueOf(Files.readString(p, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            StageWrightCommon.LOG.error("[{}] {} is unreadable: {}", StageWrightCommon.MOD_ID,
                    p.toAbsolutePath(), e.toString());
            return null;
        }
    }
}
