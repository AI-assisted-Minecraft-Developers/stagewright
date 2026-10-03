package net.magicterra.stagewright.driver.endpoint;

import net.magicterra.stagewright.driver.DriverRuntime;
import net.magicterra.stagewright.harness.EndpointDescriptor;
import net.magicterra.worlddriver.WorldDriverCommon;

/** Publishes live ports, never the requested port or a stale port file from a prior run. */
public final class DriverEndpoint {
    private DriverEndpoint() {}

    public static void publish(String loader, String worldName) {
        String target = System.getProperty(EndpointDescriptor.PROPERTY);
        if (target == null || target.isBlank()) return;
        DriverRuntime.requireApi();
        int rpcPort = WorldDriverCommon.rpcPort();
        if (rpcPort < 1) throw new IllegalStateException("StageWright: WorldDriver RPC is not listening");
        int mcpPort = WorldDriverCommon.mcpPort();
        EndpointDescriptor.writeIfRequested(loader, worldName,
                System.getProperty("worlddriver.rpcHost", "127.0.0.1"), rpcPort,
                System.getProperty("worlddriver.mcpHost", "127.0.0.1"),
                mcpPort < 1 ? null : mcpPort);
    }
}
