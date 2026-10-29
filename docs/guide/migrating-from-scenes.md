# Moving from scenes to tests

StageWright's first way of testing was the **scene**: one test, registered with `@SceneDef` in a Java
`SceneProvider` or with `scene()` in a JavaScript file, run on the server thread tick by tick, and judged by a
topology task against an `expected-scenes.txt` list. That way is gone. This page is for a project that still has
tests written for it, and says what each old piece became.

The word *scene* survived, with a new meaning: a scene is now a **game** — a YAML file saying which topology, world,
files and mods to start — and tests are ordinary JUnit 5 classes, or YAML and JavaScript files, that name the scene
they run in. [Getting started](getting-started.md) and [Writing tests](writing-tests.md) describe the new way in full;
this page only maps the old one onto it.

Six projects were moved this way: three mods built with Gradle (Waystones, Twilight Forest, Sophisticated
Backpacks), Touhou Little Maid, the ATM10 pack and a large NeoForge server pack. What they had to change is what this
page describes.

## The pieces, old and new

| Old | New |
|---|---|
| A scene: one test, `@SceneDef` on a static method taking `SceneContext` | A JUnit `@Test` method in a class marked `@Scene("<name>")`, taking what it needs as parameters (`Game`, `Plot`, `Bot`, …) |
| `@SceneSet("ws")` and the `ws.` prefix on every scene's name | The class and method names; the scene the class runs in is `@Scene("ws")` |
| `META-INF/services/net.magicterra.stagewright.scene.SceneProvider` | Nothing. Delete it: the Gradle plugin finds the test classes itself |
| `scene('pack.name', budget, function (s) {…})` in `config/stagewright/scenes/*.js` | `test('name', { scene: 'pack' }, (t) => {…})` in `stagewright/tests/*.js` |
| `scene.optional(…)`, `required = false` | `@KnownIssue("why")` / `knownIssue: 'why'`: a failure is reported as aborted with the reason, and a pass becomes a failure, so the mark is removed once the issue is fixed |
| `expected-scenes.txt` and `UNDECLARED:` | Nothing to keep in step. Every test found is reported; a test that no scene it names can run fails the run |
| `stagewright.autorun`, `stagewright.awaitPlayer`, `stagewright.client.world`, `stagewright.client.connect` on hand-written runs | Nothing. The plugin declares the runs and the test engine passes what each game needs |
| `stagewright<Topology>` tasks, `…Provision`, `…Hold`, `stagewrightCoverage` | One `stagewrightTest<Name>` task per suite, an ordinary Gradle `Test` task |
| `-Pstagewright.scenes=ws.place*` | `--tests` on the test task, by class, method or script file |
| The verdict line, `stagewright-results.jsonl` | JUnit results: Gradle's test report, or JUnit XML from the command-line runner |
| `java -jar stagewright.jar --game-dir … --scenes … --expect …` | `java -jar stagewright.jar test <server-dir>` |
| The world held still for every scene (midnight, no mob spawning, clear weather) | The same quiet world by default, declared and changed per scene in its file's `world:` |

## A mod built with Gradle

### 1. The build

Take the plugin's version in one place and let the plugin do the wiring. Everything the old setup declared by hand
goes:

- the hand-written `testmod` source set, and `sourceSet sourceSets.testmod` inside `neoForge.mods`;
- the four hand-written runs carrying `stagewright.*` system properties;
- the `topologies { … }` block with its `expectFile` entries;
- the `testmodCompileOnly "…:mc_stagewright-api:…:dev"` and `localRuntime "…:mc_stagewright-neoforge:…"`
  dependencies.

What replaces them, on a ModDevGradle project:

```groovy
// settings.gradle
pluginManagement {
    plugins { id 'net.magicterra.stagewright' version stagewright_version }
    // … repositories, as in Getting started
}
```

```groovy
// neoforge/build.gradle
plugins {
    id 'net.neoforged.moddev'
    id 'net.magicterra.stagewright'
}

stagewright {
    tests {
        neoforge {
            serverPort = 25597   // only when two projects' suites may run at once on one machine
        }
    }
}
```

The plugin makes the `testmod` source set, puts StageWright's test API, WorldDriver's API and JUnit on its compile
classpath, declares a run for each topology and adds the mod the tests load in. [The Gradle plugin](gradle-plugin.md)
has every setting, and which Gradle, ModDevGradle and Loom versions it works with. Two things the moved projects had
to change besides:

- **ModDevGradle 1.x does not work**; the plugin needs 2.x. One project went from 1.0.14 to 2.0.95.
- **Per-machine JVM flags** (a heap size, a patched native library) used to need a hook in each build. They now go
  in the Gradle property `stagewright.jvmArgs`; see [Extra JVM arguments](gradle-plugin.md#extra-jvm-arguments-for-one-machine).

### 2. The scene files

The old topologies become scene files under `src/testmod/resources/stagewright/scenes/`, one per game a test class
should run in:

```yaml
# src/testmod/resources/stagewright/scenes/ws.yaml
topology: dedicatedServer
world:
  preset: minecraft:flat
  difficulty: peaceful
```

```yaml
# ws.integrated.yaml: the same world in a client hosting its own server
topology: integratedServer
world:
  preset: minecraft:flat
  difficulty: peaceful
```

A class that ran on all three old topologies names all three of its scenes: `@Scene({"ws", "ws.integrated",
"ws.withClient"})`. Old scene options become scene file keys: `terrain` is `world.preset`, `clock` is `world.time`,
and a gamerule is `world.gamerules`. The [scene file reference](../reference/scene-file.md) lists them.

### 3. The tests

Delete `expected-scenes.txt` and the `SceneProvider` service file, and turn each `SceneProvider` class into a test
class. A scene that needed a connected player becomes a test taking `Player`, in a class marked
`@Requires(Requires.Capability.CLIENT)`; on the dedicated server it is reported as disabled instead of passing as a
skip. Many of those tests only need a player on the server — an inventory, an advancement, a menu, a quest — and a
`Bot` does that on every topology; see [Bodies](writing-tests.md#bodies).

```java
// Before
@SceneSet("ws")
public final class WaystoneScenes implements SceneProvider {
    @SceneDef(budget = 200)
    static void placingRegisters(SceneContext s) {
        s.floor(13, Blocks.STONE);
        Optional<Waystone> placed = WaystonesAPI.placeWaystone(s.level(), s.rel(0, 1, 0), WaystoneStyles.DEFAULT);
        s.record("uid", placed.map(Waystone::getWaystoneUid).orElse(null));
        s.expect(placed.isPresent()).as("a waystone was placed").isTrue();
    }
}

// After
@Scene({"ws", "ws.integrated", "ws.withClient"})
class WaystoneTest {
    @Test
    void placingRegisters(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        plot.fill(-6, 0, -6, 6, 0, 6, Blocks.STONE);
        Optional<Waystone> placed = game.onServer(server ->
                WaystonesAPI.placeWaystone(server.getLevel(plot.dimension()), plot.at(0, 1, 0), WaystoneStyles.DEFAULT));
        reporter.publishEntry("uid", String.valueOf(placed.map(Waystone::getWaystoneUid).orElse(null)));
        assertTrue(placed.isPresent(), "a waystone was placed");
    }
}
```

### 4. Run it

```bash
./gradlew :neoforge:stagewrightTestNeoforge
./gradlew :neoforge:stagewrightTestNeoforge --tests '*WaystoneTest'
```

## A modpack with the command-line runner

The old runner took the pack's directory, a folder of `.js` scenes and capability descriptors, and a list of expected
scene names. The new one takes only the server directory and finds the rest under its `stagewright/`:

```
<server-dir>/
  stagewright/
    scenes/<name>.yaml      what was --with-client and the scene options is here now
    tests/*.js, *.yaml      the tests that were in --scenes
    worlds/                 saves a scene starts from (optional)
```

```bash
# Before
java -jar stagewright.jar --game-dir server --scenes tests/scenes --expect tests/expected-scenes.txt --mod worlddriver.jar
# After
java -jar stagewright.jar test server --mod worlddriver.jar
java -jar stagewright.jar test server --tests tests --client neoforge:1.21.1:21.1.248   # tests kept elsewhere, with a client
```

- **Capability descriptors** were read from the `--scenes` folder. They are now read only from
  `config/stagewright/capabilities/` in the game's directory: put them in the pack, or write them from the scene
  file's `files:` ([capabilities](capabilities.md#2-declare-one-in-a-json-file)).
- **A StageWright jar left in `mods/`** from the old way loads in place of the runner's own. The runner names it in a
  warning; take it out.
- **`--with-client`** is `--client <loader spec>` now, and the client's configuration comes from `--client-dir`.
  `--coverage` is gone: the run itself fails a test that no scene could run.
- A `tests/` or `scenes/` directory may be a symbolic link; the runner follows it.

## The scene API, old and new

`s` is the old `SceneContext`, `game` a `Game`, `t` a script test's handle.

| Old | Java now | Script now |
|---|---|---|
| `s.rel(dx, dy, dz)`, `s.origin()`, `s.originX()` | `plot.at(x, y, z)`, `plot.origin()` | `plot.at(x, y, z)` |
| `s.setBlock(…)`, `s.floor(size, block)` | `plot.place(x, y, z, block)`, `plot.fill(x1, y1, z1, x2, y2, z2, block)` | `plot.place(x, y, z, id)`, `plot.fill(…)` |
| `s.arena()` text grid | `plot.fill`, or a saved structure with `plot.structure(id, x, y, z)` | the same |
| `s.level()`, `s.server()` | `game.onServer(server -> …)` | `t.onServer(fn)` |
| `s.blockAt`, `s.expectBlock`, `s.surfaceY` | read the level inside `game.onServer` | `t.call('mc.world.block', { pos })` |
| `s.command(cmd)` (ran at the scene's origin) | `game.api(ActionBlockingApi::of).runCommand(new CommandParams(cmd, false, Optional.empty()))` | `t.call('mc.action.runCommand', { cmd })` |
| `s.expect(x).as(…).isEqualTo(y)` | JUnit's `assertEquals(y, x, "…")` | `t.expect(x).equals(y)` |
| `s.check(x)…` | `game.check(holds, "what")` or `game.check(() -> assertEquals(…))` | `t.check(holds, 'what')` |
| `s.fail(reason)` | `fail(reason)` | `throw new Error(reason)` |
| `s.record(key, value)` | `TestReporter.publishEntry(key, value)` | `t.record(name, value)` |
| `s.await(cond).within(n).then(…)` | `game.await().within(n).as("what").until(cond)`, then carry on | `t.waitUntil(fn, { within: n, as: 'what' })` |
| `s.cleanup(r)` | `game.afterTest(server -> …)` | `t.afterTest(fn)` |
| `s.skip(why)` | `Assumptions.assumeTrue(…)` (the test is aborted), or `@Requires` | — |
| `s.player()`, `s.playerHere()` | a `Player` parameter and `@Requires(CLIENT)`, moved with `player.moveTo(…)`; or a `Bot` | `t.player()`, or `t.bot()` |
| `s.perf().sampleFor(…)`, `afterLoading(…)` | `game.perf(Perf.phases().phase("baseline", () -> {}).phase("loaded", …))` | `t.perf({ phases: [{ name: 'baseline' }, { name: 'loaded', run }] })` |
| `window.cpuLoad()` | `window.cpu()` | `perf.loaded.cpu` |
| `s.mods().loaded(id)`, `version(id)` | `Mods.loaded(id)`, `Mods.version(id)` (`net.magicterra.stagewright.capability`) | `t.call('mc.test.mods', { op: 'has', id })` |
| `s.probe(className)` | `Probe.of(className)` (absent: the test is aborted) | a descriptor's `t.capability(name).probe()`, or `Java.type(name)` |
| `s.hasClass(className)` | `Probe.hasClass(className)` | `t.hasClass(className)` |
| `s.capability(name)` | `game.capability(name)` or `game.capability(name, Type.class)` | `t.capability(name)` |
| `s.hasCapability(name)`, `s.capabilities()`, `s.capabilityProviders()` | `game.capabilities().has(name)`, `.available()`, `.registered()` | `t.capabilities().has(name)`, `.available()`, `.registered()` |
| `s.items()`, `s.menu()`, `s.equip()`, `s.quests()` | `game.capability("items", Items.class)`, `"menus"`, `"equip"` / `"curios"`, `"ftbquests"` | `t.capability('items')`, … |
| `s.recipes()`, `s.loot()`, `s.advancements()`, `s.structures()` | `game.api(TestQueriesBlockingApi::of)` | `t.call('mc.test.recipes', …)`, `mc.test.loot`, `mc.test.advancements`, `mc.test.structures` |
| `Java.loadClass(name)` in a KubeJS-style script | — | `Java.type(name)`; `Java.loadClass` is kept as another name for it |
| `block('minecraft:stone')` | `Blocks.STONE` | the id as a string |

## What behaves differently

- **A test runs on its own thread, not on the server tick.** It may block and wait, so it reads top to bottom; there
  is no tick budget for a whole test, only a `within` on each wait, and the scene's game as a whole is bounded by the
  topology's `timeoutMinutes`. Minecraft objects are touched inside `game.onServer(…)`.
- **Tests run at the same time** unless they say otherwise. A test changing something two tests share — a gamerule,
  the time, the weather — declares a lock and changes it through `game.override(…)`, which is undone when it ends
  ([Changing shared state](writing-tests.md#changing-shared-state)).
- **A plot is far from everything**, at z = −200000 and y = 200, and empty. A test lays its own floor, moves its bot
  there, and moves a player back when it ends, or the player falls when the plot is put back.
- **A command runs as the server at the overworld spawn**, not at the scene's origin. Use absolute positions from
  `plot.at(…)`, or `execute positioned`, and `as: 'player:<name>'` to run it as a bot.
- **`cpu` is not `cpuLoad`.** The old value was the process's share of the whole machine, between 0 and 1; the new
  one is CPU time over wall time counted in cores, so 2.0 is two cores busy, and the same work reads the same on a
  machine with more cores. A threshold carried over needs multiplying by the old machine's core count.
- **Missing things abort a test rather than skipping it**, and an aborted test is shown as skipped. A test that is
  disabled by `@Requires` in every scene it names fails the run, so a suite whose player tests ran nowhere does not
  pass.
- **Recorded values** (`t.record`, `TestReporter`) appear as report entries, as lines of the test's output in
  Gradle's report, and as `<property>` elements in the command-line runner's XML. Values a later program reads are
  better kept in the store ([Order, restarts and known failures](writing-tests.md#order-restarts-and-known-failures)).
