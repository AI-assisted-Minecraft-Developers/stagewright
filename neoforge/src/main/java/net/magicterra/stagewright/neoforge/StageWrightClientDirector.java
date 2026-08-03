package net.magicterra.stagewright.neoforge;

import net.magicterra.stagewright.client.ClientDirector;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge's half of the client director: a tick source, and nothing else.
 *
 * <p>The director itself lives in {@code :common} — driving a client into a world is vanilla
 * Minecraft on both loaders, and the only genuine difference is which event delivers the tick.
 */
public final class StageWrightClientDirector {

    private StageWrightClientDirector() {}

    /** Install the director if a directive was given. Called from the mod entry on the client only. */
    public static void install() {
        if (!ClientDirector.arm()) {
            return;
        }
        // Explicit event class. The single-argument overload infers the event type from the lambda,
        // and a METHOD REFERENCE carries no generic signature for it to read — registration then
        // fails to bind and the listener silently never fires, which is exactly what happened: the
        // client sat at the title screen, ticking normally, while nothing drove it.
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> ClientDirector.tick());
    }
}
