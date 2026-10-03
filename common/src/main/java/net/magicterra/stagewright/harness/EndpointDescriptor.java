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
 * <p>The driver integration supplies its actual bound endpoints.
 */
public final class EndpointDescriptor {

    /** Where to write the descriptor. Unset (the normal case) means write nothing. */
    public static final String PROPERTY = "stagewright.endpoint";

    /** Which topology this run is, recorded verbatim for the reader. */
    static final String TOPOLOGY_PROPERTY = "stagewright.topology";

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
    public static synchronized void writeIfRequested(String loader, String worldName,
                                                      String host, int port, String mcpHost, Integer mcpPort) {
        String target = System.getProperty(PROPERTY);
        if (target == null || target.isBlank() || written) return;
        Path out = Path.of(target.trim());
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
            written = true;
            StageWrightCommon.LOG.info("[{}] endpoint descriptor written to {} — attach with"
                    + " TESTKIT_ENDPOINT={}", StageWrightCommon.MOD_ID, out.toAbsolutePath(),
                    out.toAbsolutePath());
        } catch (IOException e) {
            StageWrightCommon.LOG.error("[{}] could not write the endpoint descriptor to {}: {}",
                    StageWrightCommon.MOD_ID, out.toAbsolutePath(), e.toString());
        }
    }

    /** A new world owns a new descriptor publication. */
    public static synchronized void reset() { written = false; }
}
