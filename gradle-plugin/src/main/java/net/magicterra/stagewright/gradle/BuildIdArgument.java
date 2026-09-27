package net.magicterra.stagewright.gradle;

import org.gradle.api.provider.Provider;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * {@code -Dstagewright.build} for a run, taken when the run starts. Taking it while configuring
 * starts git in a phase the configuration cache forbids processes in, and a cache hit would replay
 * the id of whatever code was checked out when the entry was stored.
 */
final class BuildIdArgument implements CommandLineArgumentProvider {

    private final Provider<StageWrightBuildIdService> service;

    BuildIdArgument(Provider<StageWrightBuildIdService> service) {
        this.service = service;
    }

    Provider<StageWrightBuildIdService> service() {
        return service;
    }

    @Override
    public Iterable<String> asArguments() {
        return service.get().arguments();
    }
}
