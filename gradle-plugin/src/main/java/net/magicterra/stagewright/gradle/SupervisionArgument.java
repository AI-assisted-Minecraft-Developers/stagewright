package net.magicterra.stagewright.gradle;

import java.io.File;
import java.time.Duration;
import java.util.List;

import org.gradle.api.provider.Provider;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * {@code -javaagent:…} for a gate's run, taken when the run starts: opening the supervisor listens on
 * a socket, which a configuration that is only being cached must not do.
 *
 * <p>Answered once, for the reason {@link BuildIdArgument} is: asked again, a run would open a second
 * supervisor and the game would report to the first.
 */
final class SupervisionArgument implements CommandLineArgumentProvider {

    private final Provider<StageWrightSupervisionService> service;
    private final String key;
    private final String topology;
    private final File artifacts;
    private final int stallMinutes;
    private transient List<String> answer;

    SupervisionArgument(Provider<StageWrightSupervisionService> service, String key, String topology,
                        File artifacts, int stallMinutes) {
        this.service = service;
        this.key = key;
        this.topology = topology;
        this.artifacts = artifacts;
        this.stallMinutes = stallMinutes;
    }

    @Override
    public synchronized Iterable<String> asArguments() {
        if (answer == null) {
            answer = List.of(service.get().begin(key, topology, artifacts.toPath(),
                    Duration.ofMinutes(stallMinutes)));
        }
        return answer;
    }
}
