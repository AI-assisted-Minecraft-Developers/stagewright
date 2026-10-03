package net.magicterra.stagewright.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.magicterra.stagewright.client.ClientDirector;
import net.magicterra.stagewright.driver.client.ClientProbes;
import net.magicterra.stagewright.harness.EndpointDescriptor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric's half of the client director: a tick source, and nothing else.
 *
 * <p>The director itself lives in {@code :common} — driving a client into a world is vanilla
 * Minecraft on both loaders, and the only genuine difference is which event delivers the tick. A
 * {@code ClientModInitializer} is never constructed on a dedicated server, which is what keeps the
 * client-only classes the director touches off that classpath.
 */
public final class StageWrightFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientProbes.reset();
            EndpointDescriptor.reset();
        });
        boolean directed = ClientDirector.arm("fabric");
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ClientDirector.onWorldReady("fabric");
            if (directed) ClientDirector.tick();
        });
    }
}
