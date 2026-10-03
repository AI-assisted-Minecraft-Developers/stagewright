package net.magicterra.stagewright.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.scene.Mods;
import net.minecraft.resources.ResourceLocation;

public final class StageWrightFabric implements ModInitializer {
    private static final ResourceLocation BEFORE_DRIVER =
            ResourceLocation.fromNamespaceAndPath("mc_testkit", "before_driver");

    @Override
    public void onInitialize() {
        installModList();
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                StageWrightCommon.onServerStarted(server, "fabric"));
        // Architectury forwards to WorldDriver's detach from the default phase and registered first,
        // so the harness's cleanups, which still drive WorldDriver, run in a phase ahead of it.
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(BEFORE_DRIVER, Event.DEFAULT_PHASE);
        ServerLifecycleEvents.SERVER_STOPPING.register(BEFORE_DRIVER, StageWrightCommon::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(StageWrightCommon::onServerTick);
    }

    /** Hand the scene API this runtime's mod list. Here rather than in {@code :common} because the
     *  list lives in the loader, and {@code :stagewright-api} compiles against neither loader. */
    private static void installModList() {
        Map<String, String> byId = new LinkedHashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            byId.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        }
        Mods.install(byId);
    }
}
