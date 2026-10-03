package net.magicterra.stagewright.driver.endpoint;

import net.magicterra.stagewright.harness.EndpointDescriptor;
import net.magicterra.worlddriver.WorldDriverCommon;

/**
 * Publishes live ports, never the requested port or a stale port file from a prior run.
 *
 * <p>Never throws: a client calls this every tick, so a refusal here would crash the game. The
 * caller learns whether anything is still owed and decides whether it can try again.
 */
public final class DriverEndpoint {
    private DriverEndpoint() {}

    /** @return false while a requested descriptor is still unpublished */
    public static boolean publish(String loader, String worldName) {
        if (!EndpointDescriptor.pending()) return true;
        int rpcPort = WorldDriverCommon.rpcPort();
        if (rpcPort < 1) {
            EndpointDescriptor.cannotPublish("-D" + EndpointDescriptor.PROPERTY
                    + " asked for an endpoint descriptor, but WorldDriver RPC is not listening");
            return false;
        }
        int mcpPort = WorldDriverCommon.mcpPort();
        EndpointDescriptor.writeIfRequested(loader, worldName,
                System.getProperty("worlddriver.rpcHost", "127.0.0.1"), rpcPort,
                System.getProperty("worlddriver.mcpHost", "127.0.0.1"),
                mcpPort < 1 ? null : mcpPort);
        return !EndpointDescriptor.pending();
    }
}
