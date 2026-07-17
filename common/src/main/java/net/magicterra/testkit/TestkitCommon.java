package net.magicterra.testkit;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import net.magicterra.testkit.harness.ResultsJsonl;
import net.magicterra.testkit.harness.TestkitHarness;
import net.magicterra.testkit.scene.Scenes;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Common core of mc-testkit. Loader entries forward server lifecycle + tick here. */
public final class TestkitCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    /** Results file, relative to the server's working directory (the loom runDir). */
    private static final String OUT_FILE = "testkit-results.jsonl";

    private static volatile TestkitHarness harness;

    private TestkitCommon() {}

    public static void onServerStarted(MinecraftServer server, String loader) {
        if (harness != null) {
            LOG.warn("[{}] harness already armed — ignoring duplicate onServerStarted", MOD_ID);
            return;
        }
        if (!Boolean.getBoolean("testkit.autorun")) {
            LOG.info("[{}] present but idle (testkit.autorun not set)", MOD_ID);
            return;
        }
        harness = new TestkitHarness(server, loader, Scenes.all(), new ResultsJsonl(Path.of(OUT_FILE)));
    }

    public static void onServerTick(MinecraftServer server) {
        TestkitHarness h = harness;
        if (h != null) h.tick();
    }
}
