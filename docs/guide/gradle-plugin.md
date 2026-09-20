# The Gradle plugin

`net.magicterra.stagewright` turns each topology a project declares into one task that provisions a
clean run directory, runs that topology's own dev-run task, supervises any companion process, and
judges the results.

There is no orchestrator and no Gradle inside Gradle. The game run is an ordinary task dependency,
the companion is a process built from that run task's own resolved `JavaExec` specification, and a
build service owns both so Gradle tears them down on every exit path, including a failure and a
`Ctrl-C`.

## Applying it

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'java'
    id 'net.magicterra.stagewright' version '0.1.0'
}
```

The plugin is built against Java 21 and is developed and tested against Gradle 8.14. It declares one
runtime dependency, the judging engine, which it exposes as an `api` dependency so a consumer
resolving the plugin from a repository resolves the engine from the same one.

## Configuring it

Everything lives in one extension block. Both facets of the plugin — the topologies and the source-set
convention — are configured there.

```groovy
stagewright {
    topologies {
        dedicatedServer {
            runTask    = 'runStagewrightDedicatedServer'
            expectFile = file('src/testmod/expected-scenes.txt')
        }
        integratedServer {
            runTask        = ':fabric:runStagewrightIntegratedServer'
            expectFile     = file('src/testmod/expected-scenes.txt')
            virtualDisplay = true
        }
        dedicatedServerWithClient {
            runTask              = ':fabric:runStagewrightDedicatedServerWithClient'
            companionRunTask     = ':fabric:runStagewrightJoiningClient'
            companionResultsFile = file('fabric/run-joining-client/stagewright-client-results.jsonl')
            expectFile           = file('src/testmod/expected-scenes.txt')
        }
    }
}
```

A topology's name is yours. It becomes the task name with its first letter uppercased, the default
run directory, and the label the run carries. See [Gates](gates.md#the-tasks) for the tasks that
result and how to read what they report.

### Topology properties

| Property | Type | Default | Notes |
|---|---|---|---|
| `runTask` | `String` | none | **Required.** A plain name for a task in this project, or a full path such as `':neoforge:runDogfoodServer'` for one elsewhere — which is every multi-loader build, where the runs live on loader subprojects and the checks belong on the root. |
| `gameDirectory` | directory | `<projectDir>/run-stagewright-<topologyName>` | Where the run happens, and the anchor every relative path below resolves against. |
| `resultsFile` | `String` | `stagewright-results.jsonl` | A file **name**, not a path. Changing it also changes what the game writes, because the plugin passes the name into the run. |
| `expectFile` | file | none | The expected-scenes manifest. Present, the run reconciles both directions against it; absent, outcomes are judged and nothing is reconciled. An empty manifest is a hard error rather than a run that reconciles against nothing. |
| `companionRunTask` | `String` | none | A second run task stood up beside the first and killed afterwards. Must resolve to a `JavaExec`. |
| `companionResultsFile` | file | none | The companion's own results. Declared, its absence is an environment failure; undeclared, it is not read at all. |
| `installMods` | file collection | empty | Jars copied into the run directory's `mods/` before the game starts. |
| `sceneScripts` | directory | none | A folder of JavaScript scenes and JSON capability descriptors, installed into the run's `config/stagewright/` before it starts. |
| `cleanWorld` | `boolean` | `true` | Whether provisioning deletes the previous world. |
| `timeoutMinutes` | `int` | `20` | Applied to the **run** task, not to the check, and not to a hold. |
| `virtualDisplay` | `boolean` | `false` | Start an X virtual framebuffer for this run on headless Linux. A no-op elsewhere and where `DISPLAY` is already set. |

### `companionResultsFile` reads like an option and behaves like a requirement

Omitting it is tempting whenever the companion has nothing of its own to assert — in a third-party
mod's client there is no driver for the built-in probe to read, so the file can only ever record a
skip. That reasoning is correct about the file's *contents* and wrong about its *absence*.

Unread, a missing file is also unread. A companion that dies before joining leaves the topology
running exactly the scenes the plain dedicated-server topology already runs, and the check passes
over a run that lost its entire reason to exist. The cross-topology coverage task does not catch it
either, whenever some other topology also has a player — which is the normal case.

Declared, a missing file is an environment failure that names itself, and a skip is printed into the
output where someone will see it. Both failure modes have been paid for once each: a companion that
started and never constructed the driver, and a third-party client that died on the tick after
joining and turned twelve server-side scenes into skips three log files away from the cause.

### `installMods`

Under architectury-loom this is usually unnecessary; under ModDevGradle it is not optional in
practice. [Gates](gates.md#how-the-harness-reaches-the-game) explains why in full.

Where a topology has a companion, the same jars are installed into the companion's own run directory
too. A loader that finds a different mod list on each end refuses the connection.

The property is treated as a classpath rather than as a plain file list, so a rebuild that changes
nothing but a timestamp does not re-provision — which would delete the world for no reason.

### `sceneScripts`

`.js` files are installed into `config/stagewright/scenes` and `.json` files into
`config/stagewright/capabilities`, both inside the run directory. Both directories are cleared first,
on every run, whether or not this property is set.

Scene files need Rhino on the run's classpath. Where it is absent the run fails loudly rather than
reporting a suite that quietly contained none of them.

## What the plugin passes to the game

Only these, and only in the cases named:

| Property | When |
|---|---|
| `stagewright.results` | Only when `resultsFile` differs from the default. |
| `stagewright.scenes` | Only when `-Pstagewright.scenes=<patterns>` is on the command line. |
| `stagewright.hold`, `stagewright.topology`, `stagewright.endpoint` | Only on a hold task. |

`DISPLAY` is injected into the run's environment, and the companion's, when a virtual display was
started for the run.

Everything else a run needs — arming the harness, waiting for a player, telling a client which world
to open or which server to join — belongs to the **consuming project's own run configuration**, not to
this plugin. Those are lines in your loader plugin's run block, checked in beside the rest of the
build.

## The `testmod` source-set convention

This is the plugin's second, opt-in facet. It exists because tests belong out of the production jar:
a mod that ships its scenes bundled into `main` ships test-only code, and test-only dependencies, to
players.

```groovy
stagewright {
    testmodSourceSet = true
}
```

Default off, with zero impact when off. When `java` has been applied and the flag resolves true, the
plugin registers a source set named `testmod` whose compile **and** runtime classpaths extend
`main`'s output plus `main`'s own compile and runtime classpaths, so `testmod` code sees `main` code
and everything `main` depends on.

The registered source set is exposed read-only as `stagewright.testmodSourceSetRef`, which is `null`
when the flag is off or when `java` was never applied.

**The plugin wires the classpath and nothing else.** It never touches a loader run configuration,
never adds a dependency beyond `main`'s own, and never changes jar packaging — the `testmod` output
is added to no jar task. Attaching the source set to a dev run is the one line the consumer writes,
because the run-configuration API differs enough between loom versions and ModDevGradle that baking
it in would be premature coupling.

Registration reacts to the `java` plugin rather than requiring an ordering: it fires immediately if
`java` is already applied, later if it is applied afterwards, and never if it never is — so a
non-Java consumer with the flag left on does not crash. The create-or-not decision itself is deferred
so it reads the flag's final value regardless of whether the `stagewright { }` block is configured
before or after the `plugins { }` block finishes.

If you move classes from `main` into a `testmod` set that is folded into the same mod, they must
occupy a package `main` does not also populate. A package owned by two source sets that feed one
module is a split package, and the loader's module layer rejects it at boot.

## Known limitations

**A topology with a companion, or a virtual display, disables the configuration cache for its run
task.** The companion is built from another task's `JavaExec` specification, read inside the run
task's action, and a task reference cannot be serialised into the cache. The plugin declares the
incompatibility rather than failing the build with a stack trace a consumer cannot act on. The cost
is one cold configuration per run of such a topology.

**A run whose game JVM cannot exit costs that topology its whole timeout.** The check waits for the
process, so a pack whose mods leave non-daemon threads behind holds the task open after the suite has
already finished.
