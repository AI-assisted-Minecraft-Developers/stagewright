package net.magicterra.testkit;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Common core of mc-testkit. Loader entries forward server lifecycle + tick here. */
public final class TestkitCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    private TestkitCommon() {}

    public static void onServerStarted(MinecraftServer server, String loader) {
        LOG.info("[{}] server started (loader={}, autorun={})", MOD_ID, loader,
                Boolean.getBoolean("testkit.autorun"));
    }

    public static void onServerTick(MinecraftServer server) {
    }
}
