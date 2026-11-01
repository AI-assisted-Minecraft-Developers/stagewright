# The Gradle plugin

`net.magicterra.stagewright` gives each test suite a project declares a `stagewrightTest<Name>` task
that runs the suite on StageWright's [test engine](../design/test-engine.md), which starts a game per
scene. Each game's launch is read off a dev-run task the loader plugin built — one the plugin declares
for the suite, or one a topology in the build names.

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
    id 'net.magicterra.stagewright' version '0.1.0-alpha.0+1.21.1'
}
```

The plugin is built against Java 21. It declares one runtime dependency, the engine, which it exposes
as an `api` dependency so a consumer resolving the plugin from a repository resolves the engine from
the same one.

### Which versions it works with

| Tool | Works | Does not work |
|---|---|---|
| Gradle | 8.14, which StageWright and WorldDriver build with; 8.9 and 8.14.3 in projects that use the plugin | 9 when the runs belong to another project than the one the suite is declared in, as in a multi-loader build with `loaderProject = ':fabric'`: "Resolution of the configuration … was attempted without an exclusive lock". A single-loader project with the plugin in the loader project itself has run its suite on 9.3.1 |
| ModDevGradle | 2.x (2.0.95 and 2.0.141 have run suites) | 1.x: the build fails with `No signature of method … RunModel$Inject.getLoadedMods()` |
| Fabric Loom | 1.13, which WorldDriver builds with | 1.19 and newer need Gradle 9.8, so they wait for the Gradle 9 fix |
| NeoForge | 21.1.209 and newer in projects that use the plugin; WorldDriver builds on 21.1.230 | Older WorldDriver builds fail to start the client on early 21.1 releases (21.1.8 and 21.1.80 were seen); WorldDriver now loads on every 21.1 release |

The Gradle 9 limit is open work in `TODO.md`. A project on Gradle 9 used to need
`junit-platform-launcher` added to the `stagewrightTestEngine` configuration by hand; the plugin now adds it.

## Test suites: `tests { }`

A suite runs JUnit tests through StageWright's [test engine](../design/test-engine.md), which starts
a game per scene from launch specs.

On a single-loader project, under loom or ModDevGradle, the whole of it:

```groovy
// build.gradle — beside `id 'fabric-loom'`, `id 'dev.architectury.loom'` or `id 'net.neoforged.moddev'`
plugins { id 'net.magicterra.stagewright' version '0.1.0-alpha.0+1.21.1' }

repositories {
    maven { url 'https://nexus.gardel.top/repository/maven-releases'; content { includeGroup 'net.magicterra' } }
}

stagewright { tests { fabric { } } }   // the suite's name is yours: `neoforge { }` reads better there
```

Tests go in `src/testmod/java` and `src/testmod/resources`, and `./gradlew stagewrightTestFabric` runs
them. A Fabric Loom project in Mojang names with Fabric API, and a ModDevGradle project with one mod,
have each passed this way with no other change.
The repository is where StageWright and WorldDriver come from; the plugin adds none. A build that keeps
repositories in `dependencyResolutionManagement` lists the same one there.

The rest is conventions, each of which a suite may set:

| Property | Convention |
|---|---|
| `loaderProject` | the project the plugin is applied to, when it applies loom or ModDevGradle |
| `runSourceSet` | `testmod`, created seeing `main` when the project has none |
| `classes`, `classpath` | that source set's output and compile classpath |
| `testDependencies` | true: the `stagewrightTestCompile` configuration goes on that source set's `compileOnly` |
| `includeWorldDriver` | true: WorldDriver is among the defaults of both configurations below |
| `worldDriverVersion` | the WorldDriver build this StageWright was built against |
| `serverPort`, `timeoutMinutes` | unset: a free port for each scene; 20 |
| `maxParallelScenes`, `maxParallelClients` | 1 and 1: scenes run one after another (see [Running scenes at once](#running-scenes-at-once)) |
| `leaks` | `'fail'`: a game forced down after its run fails its scene; `'report'` only reports it (see the topology table below) |

What a suite needs comes as the defaults of two configurations of the plugin's own, in its loader
project. Declaring anything in one replaces that configuration's defaults, so a build can change any
coordinate there without the plugin's help:

| Configuration | Defaults |
|---|---|
| `modStagewrightTestRuntime` under loom, `stagewrightTestRuntime` under ModDevGradle | StageWright's loader mod and WorldDriver's; neither POM names a dependency |
| `stagewrightTestCompile` | StageWright's api and WorldDriver's (`:dev`, Mojang names), and JUnit Jupiter's api |

```groovy
dependencies {
    // In place of the defaults: say, a WorldDriver api in other mappings.
    // Not transitive: its POM names WorldDriver's api in Mojang names.
    stagewrightTestCompile('net.magicterra:mc_stagewright-api:0.1.0-alpha.0+1.21.1:dev') { transitive = false }
    stagewrightTestCompile 'com.example:worlddriver-api-yarn:1.0'
}
```

StageWright links against one line of WorldDriver, the one its loader metadata declares, from the
build it was built against up to the next breaking release. A WorldDriver outside it, from
`worldDriverVersion` or declared in either configuration, fails dependency resolution with the range
in the message, rather than the game failing to start. Raising `worldDriverVersion` within the line
needs no new plugin.

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
goes in `modLocalRuntime`. It is transitive except for the loader's own groups — `net.fabricmc`,
`net.fabricmc.fabric-api`, `net.neoforged` — which are the project's: a second Fabric Loader stops the
game.

Loom keeps a remapped mod under its coordinates, not its contents, so a snapshot republished to
mavenLocal under the same version would reach the runs as the build before. As it is applied, the plugin
deletes every remapped StageWright or WorldDriver jar in the build's loom caches that is older than the
jar last published for that module, and loom remaps it again. A daemon still holding such a jar open
fails the build asking for `./gradlew --stop`, rather than running old code.

Under ModDevGradle nothing is remapped: the two mods are plain jars in `stagewrightTestRuntime`, which
the run source set's `runtimeOnly` extends, so Gradle settles them with the project's own dependencies
on one classpath. NeoForge loads classes only from the mods in `neoForge.mods`, each mod a module, and
tests usually share packages with the mod they test, which two modules may not. So the plugin adds a mod
`stagewright_tests`: the project's mod that holds `main`, with the run source set added. The suite's
runs load it in place of that mod, and every other run, `runClient` included, loads every mod but it,
unless the build sets that run's `loadedMods`. The run source set belongs in no mod of the build's own.

`loaderProject` names the loom or ModDevGradle project the games run from. The plugin declares the
suite's runs in that project's `loom.runs` or `neoForge.runs`, so the loader plugin still builds each
launch, and gives a scene three topologies to name:

| Topology | Runs | Directory under `<loaderProject>/run-stagewright/<name>/` |
|---|---|---|
| `dedicatedServer` | `stagewright<Name>DedicatedServer` | `dedicated-server` |
| `dedicatedServerWithClient` | `stagewright<Name>DedicatedServerWithClient`, holding its tests until `stagewright<Name>JoiningClient` joins | `with-client`, and `joining-client` for the client |
| `integratedServer` | `stagewright<Name>IntegratedServer`, a client that creates the singleplayer world `StageWright` and opens it, holding its tests until its player is in | `integrated-server` |

On `integratedServer` the client creates the world from the scene's `world:` declaration as a dedicated
server would from `server.properties`: `preset`, `seed`, `structures`, `gamemode`, `difficulty` and the
datapacks. Undeclared, it is a dedicated server's default, survival on easy with the fixed seed 5471.

Each scene's games run in a directory of their own, made from the topology's as the scene starts:
`<topology directory>/stagewright/runs/<scene>/`. The settings at its top level and its `config/` are
copied, the world, `saves/`, `logs/` and `crash-reports/` are left out, so every scene starts on a new
world, and everything else — `mods/`, a loader's caches — is linked to the topology's one level down: the
directory is its own, each entry in it a link. What a scene
writes, its `files`, its `server.properties`, whatever its game writes, stays in its own directory: the
next scene starts from the topology's directory as it was. A scene whose tests all passed keeps only its
`stagewright/` (logs, `store.json`, the game's JUnit XML); a scene with a failure keeps its whole
directory, world and settings included, until it next runs. Creating links needs Developer Mode or
administrator rights on Windows.

The runs start from the run source set, so under loom `runClient` goes without the tests; they get no
IDE run configuration. `jvmArgs` and `systemProperties` go on all of them, the servers listen on
`serverPort`, and `timeoutMinutes` (default 20) bounds one scene. The engine accepts the
EULA in a server's directory itself, so nothing has to be prepared beforehand.

Left unset, `serverPort` is a free port the engine picks as each scene starts, reported as the scene's
`serverPort` entry, so builds on one machine can run their suites at once. The generated server run then
carries `--port {stagewright.freePort}`, which only the engine fills in, and the joining client is told the
port as `-Dstagewright.client.port`; start those runs through the test task, or set `serverPort` to start
them by hand. A server that cannot bind its port fails its scene saying so, with the cause from its log. Under another loader
plugin, declare the runs there and map them instead: `topologies.put('dedicatedServerWithClient',
'myTopology')` maps the name a scene declares to a topology declared in `topologies { }`; an unknown
target fails the build naming the suite and the topology.

```groovy
stagewright {
    topologies {
        myServer {
            runTask          = ':neoforge:runMyServer'      // full path for a task in another project
            companionRunTask = ':neoforge:runMyClient'      // optional: a client that joins it
        }
    }
    tests { neoforge { topologies.put('dedicatedServerWithClient', 'myServer') } }
}
```

A topology declares no task of its own; it only names the runs a suite's launch specs are read from.

| Property | Type | Default | Notes |
|---|---|---|---|
| `runTask` | `String` | none | **Required.** A `JavaExec` run task: a plain name in this project, or a full path. |
| `companionRunTask` | `String` | none | A second run whose game runs beside the first. Must resolve to a `JavaExec`. |
| `gameDirectory` | directory | `<projectDir>/run-stagewright-<topologyName>` | Recorded in the launch spec. |
| `cleanWorld` | `boolean` | `true` | Whether each scene starts on a new world. `false` hands every scene the topology's world as the scene before left it, and runs those scenes one at a time. |
| `timeoutMinutes` | `int` | `20` | The longest one scene's games may run. |
| `stallMinutes` | `int` | `5` | How long a game may go without its phase, ticks delivered or loaded classes changing before the scene is ended. See [supervision](../reference/supervision-protocol.md). |
| `client` | `boolean` | `false` | The run task is a game client rather than a dedicated server. The plugin never starts a display. |
| `leaks` | `String` | `'fail'` | What a game the supervision agent had to force down after its run does to its scene. `'fail'` fails it; `'report'` leaves the verdict to the tests, for a launcher whose own threads never end, such as Architectury Loom's transformer in a dev run. The held-up threads are reported either way. A generated topology takes the suite's `leaks`. |

Per suite `<name>` the plugin registers:

| Task | What it does |
|---|---|
| `stagewrightLaunchSpecs<Name>` | Writes `build/stagewright/launches/<name>/<sceneTopology>.json` from each topology's run tasks, generated or mapped ([format](../reference/launch-spec.md)). The classpath goes into an argument file beside them, referenced from `jvmArgs`. Runs the run tasks' own prerequisites first; never up to date. |
| `stagewrightTest<Name>` | A `Test` task on the JUnit Platform with only the `stagewright` engine, passing `-Dstagewright.launches` pointing at that directory. `--tests` matches a script test as `<path>.<name>` (`stagewright/tests/validation/42_combat.js.42_combat`); a pattern containing `/` gets a leading `*` before the run, since Gradle would otherwise hand the engine no class to start from. Never up to date; not part of `check`, because it starts games. |

### Running scenes at once

Most of a short scene's time is its game starting. A suite with several scenes can run more than one at
once:

```groovy
stagewright { tests { neoforge {
    maxParallelScenes = 3    // scenes at once
    maxParallelClients = 1   // of those, scenes that start a game client
} } }
```

Each scene already has a directory of its own and, unless `serverPort` is set, a port of its own, so
nothing else changes. Setting `serverPort` with `maxParallelScenes` above 1 fails the task, since every
server would listen on it. A mapped topology whose run fixes a port the same way has to stop doing so
first.

`maxParallelClients` counts the scenes on `integratedServer` and `dedicatedServerWithClient`. It stays at 1
unless you know better: the clients share one display, only one window has the focus, and Minecraft lowers
the frame rate of the others, which slows their game ticks as well. Two scenes of a topology with
`cleanWorld = false` never run at once, since they share its world.

Each game takes its own memory (the heap in its `-Xmx`, and more besides) and processor time. Count what
one game takes, and do not run more at once than the machine holds; a game starved of either is slower to
start, and a test with a tight tick budget can fail where it passes alone. To set the limits for one
machine rather than the build, use the Gradle properties `stagewright.parallel.scenes` and
`stagewright.parallel.clients`, which override the suite's:

```bash
./gradlew stagewrightTestNeoforge -Pstagewright.parallel.scenes=4
```

Scenes start in the order the engine found them, each as soon as a place is free, and the results arrive in
the order the scenes end.

### Reading the results

The test task prints one line per scene as it finishes, passing or not:

```
[stagewright] survival: 12 passed, 1 skipped
[stagewright] nether: 4 passed, 0 skipped, 1 failed
```

The JUnit XML under `build/test-results/stagewrightTest<Name>/` has one file per scene and test class (or
script file), named for both, with Gradle's spelling of the slash: `TEST-survival#2fcom.example.FurnaceTest.xml`.
Inside it the `testsuite` name and
each `testcase`'s `classname` are `survival/com.example.FurnaceTest`, so a class that runs in several scenes
gives one clearly named file per scene. A test's own name is the method's, as before, and `--tests` still
selects by the real class and method. An IDE that runs the suite through Gradle cannot jump from a result to
its class.

### Extra JVM arguments for one machine

Some arguments belong to the machine running the tests rather than to the build: a heap size, a
temporary directory on a bigger disk, a patched native library. Pass them as the Gradle property
`stagewright.jvmArgs`, separated by spaces:

```bash
./gradlew stagewrightTestNeoforge -Pstagewright.jvmArgs="-Xmx6g -Djava.io.tmpdir=/data/tmp -Dorg.lwjgl.glfw.libname=/opt/glfw/libglfw.so"
```

or once in `~/.gradle/gradle.properties`:

```properties
stagewright.jvmArgs=-Xmx6g -Djava.io.tmpdir=/data/tmp
```

Every game the plugin starts gets them, the server and the client that joins it alike, whether its runs
are generated or mapped from `topologies { }`. They come after the run's own arguments and the suite's
`jvmArgs`, so where both set the same `-D` property, this one wins. An argument cannot contain a space.

The engine is resolved as `net.magicterra:stagewright-testengine` at the plugin's own version, from
the project's repositories, through the `stagewrightTestEngine` configuration.

The tests run on a JUnit Platform inside the game. The loader jars nest it, but loom drops nested jars
when it remaps a mod dependency, so the plugin adds it to every project whose `runTask` a suite starts
from: JUnit's launcher, reporting and Jupiter engine, at the version StageWright is built with, into
`forgeRuntimeLibrary` on a NeoForge or Forge project and `localRuntime` on a Fabric one. Projects not
under loom get nothing, and need nothing under ModDevGradle: NeoForge loads the jars a mod nests
itself.

## The `testmod` source-set convention

This is the plugin's second, opt-in facet. It exists because tests belong out of the production jar:
a mod that ships its tests bundled into `main` ships test-only code, and test-only dependencies, to
players. A test suite (above) already creates its `testmod` source set when the project has none; this flag is for a
project that wants the source set without declaring a suite.

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

**A run task must be a plain `JavaExec`, ModDevGradle's or Loom's.** Its launch is taken from that
task when the graph is built, and the plugin knows where those two loaders keep what they bind late:
the classpath, the environment and the game directory. A run task of any other kind that has
properties of its own is refused, with its class and those properties named, instead of being
launched without whatever they hold.
