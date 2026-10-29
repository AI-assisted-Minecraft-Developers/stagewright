# Getting started

StageWright runs tests inside a live Minecraft game. There are two ways in:

- **You build a mod with Gradle.** Apply the Gradle plugin and run `./gradlew stagewrightTest<Loader>`. Tests can be
  JUnit 5 classes, YAML files or JavaScript files. See [Adding StageWright to a Gradle project](#adding-stagewright-to-a-gradle-project).
- **You ship a modpack** and have a server directory, not a build. Run the command-line runner,
  `java -jar stagewright.jar test <server-dir>`. Tests are YAML or JavaScript files. See
  [Testing a modpack with no build tool](#testing-a-modpack-with-no-build-tool).

Either way you need Minecraft 1.21.1, Java 21, and Fabric Loader 0.16 or newer with Fabric API or NeoForge 21. Every
game process also needs [WorldDriver](https://github.com/AI-assisted-Minecraft-Developers/worlddriver) and the
Architectury API it depends on: StageWright's tests reach the game through WorldDriver's methods, and its loader jars
refuse to start without it.

## What a test is made of

Two files, always:

- **A scene file**, `stagewright/scenes/<name>.yaml`, says which game the tests need: which
  [topology](topologies.md) (a dedicated server, a client hosting its own server, or a dedicated server with a client
  joined to it), and what world, files and mods it starts with. The [scene file reference](../reference/scene-file.md)
  lists every key.
- **Tests that name the scene.** A Java class says `@Scene("<name>")`; a YAML or JavaScript test file under
  `stagewright/tests/` says `scene: <name>`.

Every test naming one scene runs in the same game. The test engine starts that game, runs the tests in it, and
reports them to whoever asked — Gradle, an IDE, or the command-line runner — as ordinary JUnit results.

```yaml
# stagewright/scenes/mymod.yaml
topology: dedicatedServer
world:
  preset: minecraft:flat
  difficulty: peaceful
```

A Java test receives what it needs as parameters: the `Game`, a `Plot` (an empty box of the world leased to this test
alone and put back afterwards), a `Bot` (a server-side player joined for this test), and more. Tests run on a test
thread, not the server thread, so every call may block and the test reads top to bottom:

```java
package com.example.mymod.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

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

The same two checks as a JavaScript test. `t` offers the test's verbs and handles; WorldDriver's methods are called
by name with `t.call`:

```javascript
// stagewright/tests/first.js
test('a placed block reads back', { scene: 'mymod' }, (t) => {
    const plot = t.plot({ size: 8 });
    plot.place(1, 0, 1, 'minecraft:stone');
    t.expect(t.call('mc.world.block', { pos: plot.at(1, 0, 1) }).type).equals('minecraft:stone');
});

test('a bot keeps what it is given', { scene: 'mymod' }, (t) => {
    const bot = t.bot();
    bot.give('minecraft:bread', 3);
    t.expect(bot.count('minecraft:bread')).equals(3);
});
```

And in YAML, where a test is a list of steps:

```yaml
# stagewright/tests/first.yaml
scene: mymod
tests:
  - name: a placed block reads back
    plot: { size: 8 }
    given:
      - place: { at: [1, 0, 1], block: "minecraft:stone", as: stone }
    then:
      - call: mc.world.block
        params: { pos: "${stone}" }
        expect: { type: { equals: "minecraft:stone" } }
```

[Writing tests](writing-tests.md) covers what a test can ask for, how to wait, how tests share the game, and the
script and YAML forms in full.

## Adding StageWright to a Gradle project

### 1. Resolve the plugin

Builds of StageWright's `master` are published to the Nexus at `https://nexus.gardel.top/repository/maven-releases` as
`0.1.0-alpha.<n>+1.21.1`. Until a build of the version you want is there, publish StageWright to your local Maven
repository and use `0.1.0-snapshot+1.21.1`; [Publishing](../reference/publishing.md) gives the order.

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url 'https://nexus.gardel.top/repository/maven-releases'
            content { includeGroupByRegex 'net\\.magicterra(\\..*)?' }
        }
        gradlePluginPortal()
    }
}
```

### 2. Declare a test suite

On a single-loader project under Fabric Loom, Architectury Loom or ModDevGradle:

```groovy
// build.gradle
plugins {
    id 'net.magicterra.stagewright' version '0.1.0-snapshot+1.21.1'
}

repositories {
    mavenLocal()
    maven { url 'https://nexus.gardel.top/repository/maven-releases'; content { includeGroup 'net.magicterra' } }
    maven { url 'https://maven.architectury.dev/'; content { includeGroup 'dev.architectury' } }
}

stagewright { tests { fabric { } } }   // the suite's name is yours; `neoforge { }` reads better there
```

That is all the wiring. The plugin creates a `testmod` source set that sees `main`, puts StageWright's test API,
WorldDriver's API and JUnit Jupiter on its compile classpath, declares the runs each topology needs in your loader
plugin, and registers `stagewrightTestFabric`. Nothing it adds reaches your production jar or `runClient`.
[The Gradle plugin](gradle-plugin.md) explains each convention and how to change it, and how a multi-loader build
declares one suite per loader.

### 3. Write the scene and the first test

Put the scene file in `src/testmod/resources/stagewright/scenes/mymod.yaml`, the Java test in `src/testmod/java/`, and
any YAML or JavaScript tests in `src/testmod/resources/stagewright/tests/`, as in the examples above.

### 4. Run it

```bash
./gradlew stagewrightTestFabric
```

The task is a Gradle `Test` task: results appear in Gradle's test report under `build/reports/tests/`, a failure
inside the game is a failed test with the game's stack trace, and each game's log is under `stagewright/logs/` in
that topology's game directory ([which one](gradle-plugin.md#test-suites-tests--)). The task is not part of `check`,
because it starts games.

While writing one test class, run only that class:

```bash
./gradlew stagewrightTestFabric --tests com.example.mymod.test.FirstTest
```

A `--tests` filter selects script tests too, by their file's path under the test root and their name, as the test
report shows them:

```bash
./gradlew stagewrightTestFabric --tests 'stagewright/tests/first.js'         # every test in the file
./gradlew stagewrightTestFabric --tests 'stagewright/tests/first.js.places'  # one of them
./gradlew stagewrightTestFabric --tests '*first*'                            # any file or class with "first" in it
```

A pattern naming only Java classes runs no script test, and starts no game for a scene that has nothing else.

## Testing a modpack with no build tool

One jar, one command. Build it from a checkout of StageWright (it builds the two loader jars it carries along the
way):

```bash
./gradlew :stagewright-cli:jar          # -> cli/build/libs/stagewright.jar
```

Builds of `master` also publish it to the Nexus as `net.magicterra:stagewright-cli`.

Lay the tests out inside the pack's server directory:

```
<server-dir>/
  mods/                         the pack's mods, WorldDriver among them (or pass it with --mod)
  stagewright/
    scenes/<name>.yaml          scene files
    tests/*.yaml, tests/*.js    the tests
    worlds/                     saves a scene starts from (optional, see world.from)
```

Any of these may be a symbolic link, to tests kept in a repository of their own for instance, and so may the files in
them: the runner copies what the links point to.

Then run:

```bash
java -jar stagewright.jar test <server-dir>
```

The runner works out how the pack's server starts (NeoForge argument files under `libraries/`, or a Fabric server
launcher at the top level; `--launch "<command>"` when it cannot tell), hands StageWright and your tests to the loader
as extra mods without copying anything into `mods/`, and runs every `dedicatedServer` scene. The server plays in
`<server-dir>/stagewright/world`, which each scene starts afresh; the pack's own world is not touched. JUnit XML
reports go to `<server-dir>/stagewright/reports` (or `--reports <dir>`), with what each test recorded (`t.record`) as
`<property>` elements of its `<testcase>`, and each game's log to `<server-dir>/stagewright/logs/`.

A StageWright jar already in `mods/` loads instead of the runner's own; one that is not the build the runner carries
is named in a warning when the run starts, as is a WorldDriver jar in `mods/` when `--mod` names another one. Such a
jar is usually left over from an earlier way of running the tests, and is best taken out of `mods/`.

The exit code is `0` when every test passed, `1` when one did not, and `3` when the run could not start.

| Option | What it does |
|---|---|
| `--mod <jar>` | Also load this mod, such as WorldDriver. Repeatable. Skipped when `mods/` already has that file. |
| `--launch "<cmd>"` | Start the server this way instead of detecting how. |
| `--java <path>` | The Java to start the games with. |
| `--timeout <min>` | The longest one scene's games may run. |
| `--stall-timeout <min>` | End a scene's game once its phase, server ticks and loaded classes have all stood still this long. |
| `--leaks fail\|report` | A game that is still up after its run, because a mod's thread keeps it from ending, is forced down and fails its scene (`fail`, the default); with `report` the scene passes or fails on its tests alone. The threads that held it are named in the report and the game's log either way. |
| `-D<key>=<value>`, `-X<option>` | Passed to the games' JVMs as given, such as `-Xmx8G`. |
| `--reports <dir>` | Where the JUnit XML goes. |

### Scenes with a client

`integratedServer` and `dedicatedServerWithClient` scenes need a real client, which the runner installs and starts
itself when given `--client`. Without it, those scenes are reported skipped, saying so, and do not fail the run; the
same goes for a scene naming a topology the runner does not know. A client that starts and then crashes fails its
scene.

```bash
java -jar stagewright.jar test <server-dir> --client neoforge:1.21.1:21.1.248
```

`--client` is `neoforge:<mc>:<version>` or `fabric:<mc>:<loader version>`, and must name the loader the server runs.
The first run installs it into `--install-dir` (the official launcher's `.minecraft` by default, in the launcher's own
layout, with downloads shared with HMCL's cache); `--mirror bmclapi` tries BMCLAPI first, and `HTTPS_PROXY` is honoured.
The client runs in `<server-dir>/stagewright/client`, never a player's own directory, with the server's mods or those
in `--client-mods <dir>`, as the offline player `--username` (`StageWright` by default).

A client needs a display, and the runner does not start one: `DISPLAY`, or `WAYLAND_DISPLAY` when `DISPLAY` is unset.
On a machine without a desktop, run it under `xvfb-run`.

`java -jar stagewright.jar --help` lists every option.

## Where to go next

- [Writing tests](writing-tests.md) — what a test can ask for, in Java, YAML and JavaScript.
- [Topologies](topologies.md) — which kind of game can establish which facts.
- [Capabilities](capabilities.md) — reaching a mod's own machinery.
- [The Gradle plugin](gradle-plugin.md) — every property, task and convention.
