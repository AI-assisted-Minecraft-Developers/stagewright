package net.magicterra.testkit.neoforge;

import net.magicterra.testkit.TestkitCommon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("mc_testkit")
public final class TestkitNeoForge {
    public TestkitNeoForge() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        TestkitCommon.onServerStarted(event.getServer(), "neoforge");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        TestkitCommon.onServerTick(event.getServer());
    }
}
