package net.magicterra.stagewright.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.capability.Mods;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entry point: connects {@code StageWrightCommon}'s server and entity hooks to the NeoForge event bus. */
@Mod("mc_testkit")
public final class StageWrightNeoForge {
    /** Registers this entry point on the event bus and, on a client, the client director. */
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

    /**
     * Hand the API this runtime's mod list. Here rather than in {@code :common} because the list lives in the loader,
     * and {@code :stagewright-api} compiles against neither loader.
     */
    private static void installModList() {
        Map<String, String> byId = new LinkedHashMap<>();
        for (net.neoforged.fml.ModContainer mod :
                net.neoforged.fml.ModList.get().getSortedMods()) {
            byId.put(mod.getModId(), mod.getModInfo().getVersion().toString());
        }
        Mods.install(byId);
    }

    /** Forwards the server start to {@code StageWrightCommon}. */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        StageWrightCommon.onServerStarted(event.getServer());
    }

    /** Ahead of WorldDriver's NeoForgeServerEvents.stopping, at HIGH, which detaches it: cleanups still drive it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerStopping(ServerStoppingEvent event) {
        StageWrightCommon.onServerStopping(event.getServer());
    }

    /** Forwards each server tick, after the tick has run, to {@code StageWrightCommon}. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        StageWrightCommon.onServerTick(event.getServer());
    }

    /** Server levels only, as Fabric's ENTITY_LOAD is. */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            StageWrightCommon.onEntityJoin(event.getEntity(), event.getLevel());
        }
    }
}
