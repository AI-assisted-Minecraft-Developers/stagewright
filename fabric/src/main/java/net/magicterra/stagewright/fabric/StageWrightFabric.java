package net.magicterra.stagewright.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.magicterra.stagewright.StageWrightCommon;

public final class StageWrightFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                StageWrightCommon.onServerStarted(server, "fabric"));
        ServerTickEvents.END_SERVER_TICK.register(StageWrightCommon::onServerTick);
    }
}
