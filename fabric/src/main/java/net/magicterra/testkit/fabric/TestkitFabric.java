package net.magicterra.testkit.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.magicterra.testkit.TestkitCommon;

public final class TestkitFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                TestkitCommon.onServerStarted(server, "fabric"));
        ServerTickEvents.END_SERVER_TICK.register(TestkitCommon::onServerTick);
    }
}
