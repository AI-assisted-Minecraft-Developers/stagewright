package net.magicterra.stagewright.neoforge;

import net.magicterra.stagewright.StageWrightCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("mc_testkit")
public final class StageWrightNeoForge {
    public StageWrightNeoForge() {
        NeoForge.EVENT_BUS.register(this);
        // Reached only on a client, and only then is StageWrightClientDirector ever loaded — the
        // class references Minecraft/TitleScreen/ConnectScreen, none of which exist on a dedicated
        // server. Naming it here rather than annotating it keeps that boundary visible at the one
        // place it matters.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            StageWrightClientDirector.install();
        }
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
