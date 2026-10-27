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
        maven {
            url 'https://nexus.gardel.top/repository/maven-releases'
            content { includeGroupByRegex 'net\\.magicterra(\\..*)?' }
        }
        gradlePluginPortal()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'java'
    id 'net.magicterra.stagewright' version '0.1.0-build.0+1.21.1'
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
            runTask    = ':fabric:runStagewrightIntegratedServer'
            expectFile = file('src/testmod/expected-scenes.txt')
            client     = true
        }
        dedicatedServerWithClient {
            runTask              = ':fabric:runStagewrightDedicatedServerWithClient'
            companionRunTask     = ':fabric:runStagewrightJoiningClient'
            companionResultsFile = file('fabric/run-joining-client/stagewright/stagewright-client-results.jsonl')
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
| `resultsFile` | `String` | `stagewright/stagewright-results.jsonl` | Relative to the run directory. Changing it also changes what the game writes, because the plugin passes the name into the run. |
| `expectFile` | file | none | The expected-scenes manifest. Present, the run reconciles both directions against it; absent, outcomes are judged and nothing is reconciled. An empty manifest is a hard error rather than a run that reconciles against nothing. |
| `companionRunTask` | `String` | none | A second run task stood up beside the first and killed afterwards. Must resolve to a `JavaExec`. |
| `companionResultsFile` | file | none | The companion's own results. Declared, its absence is an environment failure; undeclared, it is not read at all. |
| `sceneScripts` | directory | none | A folder of JavaScript scenes and JSON capability descriptors, installed into the run's `config/stagewright/` before it starts. |
| `cleanWorld` | `boolean` | `true` | Whether provisioning deletes the previous world. |
| `timeoutMinutes` | `int` | `20` | Applied to the **run** task, not to the check, and not to a hold. |
| `stallMinutes` | `int` | `5` | How long the game may go without its phase, ticks delivered or loaded classes changing before the run is ended and the reason printed. `0` turns it off. See [supervision](../reference/supervision-protocol.md). |
| `failOnLeak` | `boolean` | `false` | A JVM that outlives a finished suite is ended by the game's agent and named in a warning above the verdict; with this it is also RED. |
| `client` | `boolean` | `false` | The run task is a game client. On Linux it is refused before it starts when neither `DISPLAY` nor `WAYLAND_DISPLAY` is set, or when a local X server is not there or refuses the `XAUTHORITY` cookie; a companion is always checked. The plugin never starts a display. |

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

### The harness is a dependency, not a property

There is no property that puts the framework into a run. Declare it the way your loader plugin
declares any other runtime-only mod — `modLocalRuntime` under architectury-loom, a `localRuntime`
that `runtimeClasspath` extends under ModDevGradle (not `runtimeOnly`, which is published with your
mod) — and it reaches the run task and,
through the run task's own classpath, its companion. See
[Gates](gates.md#how-the-harness-reaches-the-game).

### `sceneScripts`

`.js` files are installed into `config/stagewright/scenes` and `.json` files into
`config/stagewright/capabilities`, both inside the run directory. Both directories are cleared first,
on every run, whether or not this property is set. A directory holding no `.js` file is refused, and
so is a `.js` file that registers no scene: either would run the suite without the scenes it was
pointed at and report green.

Scene files need Rhino on the run's classpath. Where it is absent the run fails loudly rather than
reporting a suite that quietly contained none of them.

## What the plugin passes to the game

Only these, and only in the cases named:

| Property | When |
|---|---|
| `stagewright.results` | Only when `resultsFile` differs from the default. |
| `stagewright.scenes` | Only when `-Pstagewright.scenes=<patterns>` is on the command line. |
| `stagewright.hold`, `stagewright.topology`, `stagewright.endpoint` | Only on a hold task. |
| `stagewright.build` | On every run task that is a `JavaExec`, and its companion, when the project is a git work tree. Taken once per build, when its first run that git gives one to starts, so every run in the build carries the same id, unless the tree changed, or git could not read it, before it started and something on its classpath, or in the class directories its `MOD_CLASSES` names, was written since the tree last read as that id: then it carries one that matches no other run. |

No environment variable is added. The display check reads the environment the game gets: the run
task's own plus what its loader binds at launch. A companion starts with exactly that environment of
its own task, so a variable the task removes from what it inherited stays removed.

Everything else a run needs — arming the harness, waiting for a player, telling a client which world
to open or which server to join — belongs to the **consuming project's own run configuration**, not to
this plugin. Those are lines in your loader plugin's run block, checked in beside the rest of the
build.

## Test suites: `tests { }`

A suite runs JUnit tests through StageWright's [test engine](../design/test-engine.md), which starts
a game per scene. It is separate from the gates above: a suite starts the games itself, from launch
specs, rather than depending on a topology's run task.

On a single-loader loom project, the whole of it:

```groovy
// build.gradle — beside `id 'fabric-loom'` or `id 'dev.architectury.loom'`
plugins { id 'net.magicterra.stagewright' version '0.1.0-build.0+1.21.1' }
stagewright { tests { fabric { } } }
```

Tests go in `src/testmod/java` and `src/testmod/resources`, and `./gradlew stagewrightTestFabric` runs
them. A Fabric Loom project in Mojang names with Fabric API and no other change has passed this way.
The rest is conventions, each of which a suite may set:

| Property | Convention |
|---|---|
| `loaderProject` | the project the plugin is applied to, when it applies loom |
| `runSourceSet` | `testmod`, created seeing `main` when the project has none |
| `classes`, `classpath` | that source set's output and compile classpath |
| `testDependencies` | true: StageWright's api and WorldDriver's (`:dev`, Mojang names) and JUnit Jupiter's api go on its `compileOnly` |
| `includeWorldDriver` | true: WorldDriver's loader mod goes into the runs with StageWright's, and the Architectury API it needs unless the project already declares one |
| `serverPort`, `timeoutMinutes` | 25621, 20 |

The plugin also appends the repositories these come from to every loom project and its own, each asked
for its group only: the StageWright Nexus for `net.magicterra`, Architectury's for `dev.architectury`,
and mavenLocal for a local build of StageWright. When `dependencyResolutionManagement` keeps
repositories in the settings, it adds none, and the settings have to list them.

A multi-loader build applies the plugin at the root and names each loader, pointing at the tests where
they live:

```groovy
stagewright { tests {
    ['fabric', 'neoforge'].each { loader -> create(loader) {
        classes.from({ project(':common').sourceSets.testmod.output })
        classpath.from({ project(':common').sourceSets.testmod.compileClasspath })
        loaderProject = ":${loader}"
        jvmArgs.add('-Xmx2g')
    } }
} }
```

Each loader project still needs a `testmod` source set that carries `:common`'s into its runs:
on Fabric on its runtime classpath, on NeoForge through `loom.mods` only, since FML would otherwise
make a second module of the same packages.

`classes` is the compiled test classes plus the resources (scene files) the engine scans.
`classpath` is what those classes link against. Gradle loads every class under `classes` by name in
the test JVM, without running it, and a class whose supertypes are missing there fails the task; with
`classpath` set they load, and nothing on it is started.

StageWright's loader mod, and WorldDriver's, reach the runs through a loom remap configuration of the
plugin's own, `modStagewrightTestRuntime`, on the run source set: remapped like any `mod*` dependency,
present in the generated runs and in any other run of that source set, absent from `runClient`. Nothing
goes in `modLocalRuntime`. It is not transitive: the mods carry what they need, and what Architectury
asks for — Fabric Loader, Fabric API — is the project's own.

`loaderProject` names the loom project the games run from. The plugin declares the suite's runs in
that project's `loom.runs`, so loom still builds each launch, and gives a scene two topologies to
name:

| Topology | Runs | Directory under `<loaderProject>/run-stagewright/<name>/` |
|---|---|---|
| `dedicatedServer` | `stagewright<Name>DedicatedServer` | `dedicated-server` |
| `dedicatedServerWithClient` | `stagewright<Name>DedicatedServerWithClient`, holding its tests until `stagewright<Name>JoiningClient` joins | `with-client`, and `joining-client` for the client |

The runs start from the run source set, so `runClient` goes without the tests; they get no IDE run
configuration. `jvmArgs` and `systemProperties` go on all of them, the servers listen on `serverPort`
(default 25621), and `timeoutMinutes` (default 20) bounds one scene. The engine accepts the EULA in a
server's directory itself, so nothing has to be prepared beforehand. Under a loader plugin other than
loom, declare the runs there and map them instead: `topologies.put('dedicatedServerWithClient',
'myTopology')` maps the name a scene declares to a topology declared in `topologies { }`; an unknown
target fails the build naming the suite and the topology.

Per suite `<name>` the plugin registers:

| Task | What it does |
|---|---|
| `stagewrightLaunchSpecs<Name>` | Writes `build/stagewright/launches/<name>/<sceneTopology>.json` from each topology's run tasks, generated or mapped ([format](../reference/launch-spec.md)). The classpath goes into an argument file beside them, referenced from `jvmArgs`. Runs the run tasks' own prerequisites first; never up to date. |
| `stagewrightTest<Name>` | A `Test` task on the JUnit Platform with only the `stagewright` engine, passing `-Dstagewright.launches` pointing at that directory. Never up to date; not part of `check`, because it starts games. |

The engine is resolved as `net.magicterra:stagewright-testengine` at the plugin's own version, from
the project's repositories, through the `stagewrightTestEngine` configuration.

The tests run on a JUnit Platform inside the game. The loader jars nest it, but loom drops nested jars
when it remaps a mod dependency, so the plugin adds it to every project whose `runTask` a suite starts
from: JUnit's launcher, reporting and Jupiter engine, at the version StageWright is built with, into
`forgeRuntimeLibrary` on a NeoForge or Forge project and `localRuntime` on a Fabric one. Projects not
under loom get nothing; under ModDevGradle, if a run reports that JUnit is missing, add those three
modules to its `additionalRuntimeClasspath` yourself.

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

**A companion's run task must be a plain `JavaExec`, ModDevGradle's or Loom's.** The companion's launch
is taken from that task when the graph is built, and the plugin knows where those two loaders keep what
they bind late: the classpath, the environment and the game directory. A run task of any other kind
that has properties of its own is refused, with its class and those properties named, instead of being
launched without whatever they hold.

**A hold and the companion client are not watched by the game's agent.** Only a gate's own game is. A
hold, by design, never says it is finished; a companion client that stops moving is ended when the gate
ends.
