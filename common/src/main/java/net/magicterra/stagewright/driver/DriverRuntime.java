package net.magicterra.stagewright.driver;

import net.magicterra.stagewright.driver.verbs.TestMethods;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/** Required driver connection. Installation and readiness are distinct states. */
public final class DriverRuntime {
    private static volatile ApiRegistry registeredApi;

    private DriverRuntime() {}

    /** The WorldDriver API, or an {@link IllegalStateException} while WorldDriver is not yet ready. */
    public static ApiRegistry requireApi() {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) {
            throw new IllegalStateException("StageWright: WorldDriver API is not ready");
        }
        return api;
    }

    /**
     * Register once per API instance; publish the marker only after all registrations succeed. A client asks every
     * tick, so an API already registered is answered without the lock.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // The marker is identity: one registration per API instance.
    public static void registerVerbs() {
        ApiRegistry api = requireApi();
        if (api == registeredApi) {
            return;
        }
        synchronized (DriverRuntime.class) {
            if (api == registeredApi) {
                return;
            }
            TestMethods.register(api);
            registeredApi = api;
        }
    }
}
