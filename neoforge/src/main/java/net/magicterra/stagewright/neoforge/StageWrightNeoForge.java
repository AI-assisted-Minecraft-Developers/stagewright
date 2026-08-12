package net.magicterra.stagewright.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.scene.Mods;
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
        installModList();
        NeoForge.EVENT_BUS.register(this);
        // Reached only on a client, and only then is StageWrightClientDirector ever loaded — the
        // class references Minecraft/TitleScreen/ConnectScreen, none of which exist on a dedicated
        // server. Naming it here rather than annotating it keeps that boundary visible at the one
        // place it matters.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            StageWrightClientDirector.install();
        }
    }

    /** Hand the scene API this runtime's mod list. Here rather than in {@code :common} because the
     *  list lives in the loader, and {@code :stagewright-api} compiles against neither loader. */
    private static void installModList() {
        Map<String, String> byId = new LinkedHashMap<>();
        for (net.neoforged.fml.ModContainer mod : net.neoforged.fml.ModList.get().getSortedMods()) {
            byId.put(mod.getModId(), mod.getModInfo().getVersion().toString());
        }
        Mods.install(byId);
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
