package net.magicterra.stagewright.driver;

import net.magicterra.stagewright.driver.verbs.TestInputVerbs;
import net.magicterra.stagewright.driver.verbs.TestResetVerb;
import net.magicterra.stagewright.driver.verbs.TestRunVerb;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;

/** Required driver connection. Installation and readiness are distinct states. */
public final class DriverRuntime {
    private static volatile DriverApi registeredApi;

    private DriverRuntime() {}

    public static DriverApi requireApi() {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) throw new IllegalStateException("StageWright: WorldDriver API is not ready");
        return api;
    }

    /**
     * Register once per API instance; publish the marker only after all registrations succeed.
     * A client asks every tick, so an API already registered is answered without the lock.
     */
    public static void registerVerbs() {
        DriverApi api = requireApi();
        if (api == registeredApi) return;
        synchronized (DriverRuntime.class) {
            if (api == registeredApi) return;
            TestRunVerb.register();
            TestResetVerb.register();
            TestInputVerbs.register();
            registeredApi = api;
        }
    }
}
