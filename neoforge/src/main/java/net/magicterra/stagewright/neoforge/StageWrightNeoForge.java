package net.magicterra.stagewright.neoforge;

import net.magicterra.stagewright.StageWrightCommon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("mc_testkit")
public final class StageWrightNeoForge {
    public StageWrightNeoForge() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        StageWrightCommon.onServerStarted(event.getServer(), "neoforge");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        StageWrightCommon.onServerTick(event.getServer());
    }
}
