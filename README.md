# StageWright

*[English](README.md) · [简体中文](README-zh_CN.md)*

An in-game test framework for Minecraft mods and modpacks. Tests are JUnit 5 classes, or YAML and JavaScript files,
that run inside a live game: a test receives the game, a box of the world to build in and players to drive, does what
it needs, and asserts the outcome. StageWright starts the game each test asks for, runs the tests in it, and reports
them as ordinary JUnit results to Gradle, an IDE or its command-line runner.

It runs on Minecraft 1.21.1 under both Fabric and NeoForge, from one set of tests.

## Why it exists

Minecraft has its own in-game test facility and both loaders expose it. StageWright exists for what that leaves out:

- **A test asks for the game it needs.** A scene file says which kind of game — a dedicated server, a client hosting
  its own server, or a dedicated server with a real client joined to it — and which world, files and mods it starts
  with. Each kind can establish facts the others cannot.
- **A test builds its world in code.** It leases a plot, an empty box of the world that is put back exactly as it was
  when the test ends, so there is no world file to keep in step with the assertions and tests do not disturb each
  other.
- **Tests read top to bottom.** They run on a test thread, not the server thread, so calling the game, waiting a
  number of ticks or waiting for a condition is a plain blocking call.
- **One set of tests serves both loaders.** Tests live in shared code; the same suite runs on Fabric and NeoForge.
- **A test that can never run is a failure.** A test whose `@Requires` no scene meets fails the run instead of being
  silently skipped everywhere, and a test the game failed to load is reported as failed, never as missing.
- **A modpack can use it without a build tool.** YAML and JavaScript tests sit in the pack's server directory, and a
  capability system lets a mod or a pack teach the framework things the base game has no concept of.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Loader 0.16 or newer, with Fabric API |
| NeoForge | 21 |
| WorldDriver | required in every game process, see [below](#stagewright-and-worlddriver) |

Pushes to `master` are built, tested and published to `https://nexus.gardel.top/repository/maven-releases` as
`0.1.0-alpha.<build number>+1.21.1`, under the group `net.magicterra`. Until a build of the version you want is there,
publish StageWright to your local Maven repository and use `0.1.0-snapshot+1.21.1`;
[Publishing](docs/reference/publishing.md) gives the order. The Gradle plugin is `net.magicterra.stagewright`; the
command-line runner is `net.magicterra:stagewright-cli`.

## The shortest path to a passing run

### In a Gradle project

```groovy
// build.gradle, beside fabric-loom, architectury-loom or ModDevGradle
plugins {
    id 'net.magicterra.stagewright' version '0.1.0-snapshot+1.21.1'
}

repositories {
    mavenLocal()
    maven { url 'https://nexus.gardel.top/repository/maven-releases'; content { includeGroup 'net.magicterra' } }
}

stagewright { tests { fabric { } } }
```

Put a scene file in `src/testmod/resources/stagewright/scenes/`, tests in `src/testmod/java/` (or YAML and JavaScript
tests in `src/testmod/resources/stagewright/tests/`), and run `./gradlew stagewrightTestFabric`.

### For a modpack

```
java -jar stagewright.jar test <the pack's server directory>
```

That runs the YAML and JavaScript tests in `<server-dir>/stagewright/tests`, with the scenes in
`<server-dir>/stagewright/scenes`, in the pack's own server, on a world of its own; the pack's world and `mods/` are not
touched. JUnit XML goes to `<server-dir>/stagewright/reports`. `--client neoforge:1.21.1:<version>` also installs a
client and runs the scenes that need one.

[Getting started](docs/guide/getting-started.md) walks both routes in full.

## What a test looks like

A scene file, `stagewright/scenes/mymod.yaml`:

```yaml
topology: dedicatedServer
```

and a test that names it:

```java
@Scene("mymod")
class FirstTest {

    @Test
    void aPlacedBlockReadsBack(Game game, @Plot.Size(8) Plot plot) {
        plot.place(1, 0, 1, Blocks.STONE);
        boolean stone = game.onServer(server ->
                server.overworld().getBlockState(plot.at(1, 0, 1)).is(Blocks.STONE));
        assertTrue(stone, "stone where the test placed it");
    }

    @Test
    void aBotKeepsWhatItIsGiven(Bot bot) {
        bot.give(Items.BREAD, 3);
        assertEquals(3, bot.count(Items.BREAD));
    }
}
```

The same as a JavaScript test:

```javascript
test('a placed block reads back', { scene: 'mymod' }, (t) => {
    const plot = t.plot({ size: 8 });
    plot.place(1, 0, 1, 'minecraft:stone');
    t.expect(t.call('mc.world.block', { pos: plot.at(1, 0, 1) }).type).equals('minecraft:stone');
});
```

[Writing tests](docs/guide/writing-tests.md) covers the rest: bots and mobs driven tick by tick, waits, soft checks,
dependencies between tests, restarts on the same world, performance measurements.

## Topologies

| Topology | What it starts | Good for |
|---|---|---|
| `dedicatedServer` | one headless dedicated server | Most tests, and the only place where "a client-only call is refused" and "the player list is empty" can be observed. |
| `integratedServer` | one game client hosting its own server | Anything needing client-side state in the same process, and anything measured in frames. |
| `dedicatedServerWithClient` | a dedicated server plus a real client joined to it | Anything whose subject is the connection between the two halves — the difference between "works in singleplayer" and "works on a server". |

[Topologies](docs/guide/topologies.md) explains which facts need which kind of game.

## Documentation

- [Getting started](docs/guide/getting-started.md) — both entry points, end to end.
- [Writing tests](docs/guide/writing-tests.md) — the test API, in Java, YAML and JavaScript.
- [Capabilities](docs/guide/capabilities.md) — reaching a mod's own machinery, and extending that.
- [Topologies](docs/guide/topologies.md) — the kinds of game and what each one can establish.
- [The Gradle plugin](docs/guide/gradle-plugin.md) — tasks, properties, conventions.
- [Moving from scenes to tests](docs/guide/migrating-from-scenes.md) — for a project with tests written for the old
  `@SceneDef` / `scene()` way.

The file formats and the published artifacts are under [`docs/reference/`](docs/reference/), the reasoning behind the
larger decisions under [`docs/design/`](docs/design/), and [`docs/README.md`](docs/README.md) indexes all of it.

## StageWright and WorldDriver

[WorldDriver](https://github.com/AI-assisted-Minecraft-Developers/worlddriver) is a Minecraft mod that exposes the
running game as one programmable API. StageWright began inside it and became its own repository. StageWright's tests
reach the game through WorldDriver's methods, so StageWright requires WorldDriver on both Fabric and NeoForge: the
Gradle plugin brings it into the test runs, and a modpack has it in `mods/` or passes it to the runner with `--mod`.

The two depend on each other, in opposite directions and at different points: StageWright's API and runtime compile
against WorldDriver's published API and common module, while WorldDriver compiles its own tests against StageWright's
API and runs them with StageWright's Gradle plugin. That is not a cycle — WorldDriver's production code never depends
on StageWright. Each side depends on one exact published version of the other, set in `gradle.properties` —
`worlddriver_version` here, `stagewright_version` there — so neither needs the other's source to build.
[Publishing](docs/reference/publishing.md) explains how to move to a newer version, how to build against your own
local build of the other side, and the order to publish in when neither side has published anything yet.

## Licence

[LGPL-3.0-only](COPYING.LESSER) — the additional permissions, on top of the GPL-3.0 text in [`COPYING`](COPYING) that
they modify.

A mod that merely *uses* this framework — declaring the artifacts as dependencies, writing tests against its API,
running them — is not a derived work of it and carries whatever licence it likes. The copyleft attaches to
StageWright's own sources and to modified copies of them.

One directory is a documented exception: `engine/src/main/java/.../engine/json/` is minimal-json vendored verbatim
under the MIT licence. Its headers are upstream's terms and stay as they are; see `VENDORED.md` beside them.

The CLI's `stagewright.jar` also carries third-party libraries. `META-INF/THIRD-PARTY-NOTICES` in that jar names each
one with its source, and `META-INF/licenses/` holds their licence texts.
