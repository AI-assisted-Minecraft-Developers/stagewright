# Getting started

StageWright has two audiences and two entry points. Pick the one that describes you.

- **You ship a modpack** and want to check it after an update. You have a server directory and no
  build tool. Use [the standalone runner](#testing-a-modpack-with-no-build-tool).
- **You build a mod** with Gradle and want scenes in your own repository. Use
  [the Gradle plugin](#adding-stagewright-to-a-gradle-project).

Either way, you need Minecraft 1.21.1, Java 21, and either Fabric Loader 0.16 or newer with Fabric
API, or NeoForge 21.

## Testing a modpack, with no build tool

One jar, one command. No Gradle, no test source set, no version matching by hand.

The runner is its own build, so it is not produced by building the rest of the repository. Build it
once, after publishing the engine and the loader jars it bundles:

```bash
cd engine && ../gradlew publishToMavenLocal
cd ..     && ./gradlew publishToMavenLocal
cd cli    && ../gradlew jar            # -> cli/build/libs/stagewright.jar
```

```
java -jar stagewright.jar --game-dir <the pack's server directory> --scenes <a folder of .js files>
```

That installs the right framework build into the pack's `mods/`, installs your scene files into
`config/stagewright/scenes/`, works out how the pack starts, runs it, and judges the results. The
process exit code is the verdict: `0` sound and passing, `1` a scene failed, `2` the framework itself
is broken and the results are void, `3` the game never armed.

Both loader builds of the framework ride inside `stagewright.jar`, so there is no loader-and-version
pairing for you to get right — which is the step that usually fails, and fails looking exactly like
the mod not working. Other mods your scenes need come in through `--mod <jar>`, repeatable.
Everything installed is recorded and swept again on the next run, so upgrading never leaves two
copies behind.

Scenes are plain JavaScript and register into the same registry, canaries and results file the
compiled ones do. See [Writing a scene](writing-a-scene.md#scenes-written-in-javascript) for the
model, and `--help` for the flags not covered here.

> **Note.** Provisioning clears `config/stagewright/scenes` and `config/stagewright/capabilities` in
> the game directory on **every** run, whether or not you passed `--scenes`. Keep the authored copies
> outside the game directory and point `--scenes` at them.

### The flags you will reach for first

| Flag | What it does |
|---|---|
| `--game-dir <dir>` | The pack directory holding `mods/` and `config/`. Required. |
| `--scenes <dir>` | A folder of scene files. `.js` goes to the scenes directory, `.json` to the capability descriptors directory. |
| `--mod <jar>` | Install this mod too. Repeatable. |
| `--expect <file>` | Reconcile the run against an expected-scenes manifest. A manifest that names no scene is refused before the game starts. |
| `--timeout <min>` | A ceiling, not a duration — the run ends when the results file carries its footer. Defaults to 45. |
| `--clean-world false` | Keep the existing world. The default is to delete it. |
| `--no-install` | Do not touch `mods/`; the pack already has what it needs. |
| `--launch "<command>"` | Start the server this way instead of detecting how. |

`--help` is only recognised as the **first** argument. Anywhere else it is an unknown option.

An option the CLI does not know is refused with the usage text and exit 3, and so is a value other
than `true` or `false` for `--clean-world`. A mistyped name is therefore an error before anything
runs, rather than a setting that is silently never read — `--expected` for `--expect` would
otherwise run with reconciliation off, and `--clean-wrold false` would delete the world.

Detection covers NeoForge and Forge argument files under `libraries/`, and a Fabric or Quilt server
jar at the top level. When it cannot tell, it says so and asks for `--launch`.

The server's output goes to `stagewright-run.log` in the game directory, and the results to
`stagewright-results.jsonl` beside it.

### Testing what only a client can reach

A dedicated server cannot reach anything that exists only on a client: a GUI a mod adds, a screen a
machine opens, the client half of a client-server split. Testing those needs a real client, and a
real client on a build machine needs assets, natives, a JVM and a login.

Point the runner at [HeadlessMC](https://github.com/headlesshq/headlessmc) and it needs none of that
from you:

```
java -jar stagewright.jar --game-dir <a client game directory> \
     --headlessmc headlessmc-launcher.jar --loader neoforge --mc-version 1.21.1 \
     --scenes <a folder of .js files>
```

The first run downloads Minecraft and the loader into that directory; later runs reuse them. The
client creates and enters a singleplayer world — `--world <name>`, defaulting to `stagewright` — so
the suite runs on its integrated server.

**Without an account, that client is genuinely headless**: every graphics call is replaced by a stub,
so there is no display and no virtual framebuffer to arrange. Anything that depends on rendering is
meaningless there by construction. Screen structure and input are testable; pixels are not.

**The stub scales to a mod and not to a modpack.** With every graphics entry point replaced, a mod
that reads image pixels while loading gets an all-zero image and throws, which takes mod loading down
with it. The stub also cannot be switched off from outside: launching offline forces it. So for a
pack, the choice is a real account or not going through that launcher's own launch at all.

**A real account** removes the stub and gives a real graphics context:

```
java -jar stagewright.jar --game-dir <a client game directory> \
     --headlessmc headlessmc-launcher.jar --loader neoforge --mc-version 1.21.1 \
     --account 0 --scenes <a folder of .js files> --expect <manifest>
```

Logging in is yours and stays yours. StageWright never prompts for credentials, never stores a token,
and never writes one to a results file or a log — an interactive login has no business going through
a test runner. Use the launcher's own login and account commands, run **from the game directory**, so
the credentials land there rather than in your real Minecraft installation. `--online` uses whichever
account the launcher already has selected; `--account <id>` makes that one primary first.

One trap that costs an hour: **Java ignores `HTTPS_PROXY`**. Behind a proxy, both the login and the
run need proxy system properties, passed with `--launcher-jvm "…"`. The runner warns when the
environment variable is set and no proxy property was given.

**Launching what is already installed** is the other route to a rendering client, and it needs no
account:

```
java -jar stagewright.jar --game-dir <the same client game directory> \
     --display-client <version-id> \
     --scenes <a folder of .js files> --expect <manifest>
```

The version id is the directory name under `<game dir>/versions`. This is a real graphics context
with no stubs, so it needs a display — a desktop, or a virtual framebuffer on a build machine, which
is the same trade the Gradle plugin's `virtualDisplay` option makes. In exchange the pack runs the
way a player runs it.

> **Note.** A crash on the client's first tick is the pack's, not the runner's. A client ticks
> throughout its own loading, so every mod's client-tick handler fires while the loading overlay is
> still up and configs are still loading. A mod that reads a config value there with no guard takes
> the launch down under any launcher, with nothing of StageWright's on the stack. The crash report
> under `<game dir>/crash-reports` names the mod.

Each shape leaves its logs in its own place, and the game's log is not the launcher's:

| Shape | The game's own log | The launcher's output |
|---|---|---|
| server | `stagewright-run.log` | — the runner starts the server itself |
| `--headlessmc` | `logs/latest.log` | `stagewright-headlessmc.log` |
| `--display-client` | `logs/latest.log` | `stagewright-client-launch.log` |

### The shape a player actually plays

The two above are one JVM each. A real game is two, talking over a socket, and that seam is the only
place a mod's halves can disagree — an unregistered packet, state behind a side check, plain desync.
`--with-client` runs both halves from one command:

```
java -jar stagewright.jar --game-dir <the pack's server directory> --scenes <a folder of .js files> \
     --with-client <a client game directory, not the server's> \
     --headlessmc headlessmc-launcher.jar --loader neoforge --mc-version 1.21.1
```

The scenes run on the server, which writes `stagewright-results.jsonl`. The client has two jobs: to be
logged in while they run, and to run the one probe only a joined client can — that a damage event
survives the wire — writing `stagewright-client-results.jsonl` in its own directory. Both files are
judged, each as its own suite, and the worse verdict is the run's; a client that wrote no file is
ENV. That is the rule the Gradle plugin applies to a topology's `companionResultsFile`, from the same
engine code, so the pair cannot be GREEN here and RED there. The client is installed with the same
framework build and the same
`--mod` jars as the server, because a loader that finds a different mod list on each end refuses the
connection, and it dials the local address at whatever port the pack's `server.properties` names.

The server does not start the suite until a player is actually on it. That is what makes a client
which never arrives a timeout you can read, rather than a passing run that quietly proved nothing.

Judging is always about the run this command can see. That is why "a client that joins a server you
started yourself" is not a mode: those scenes run on that server and write their results there, so the
only honest thing this process could report about them is that it cannot see them.

### Reconciling several runs

```
java -jar stagewright.jar --coverage run-a/stagewright-results.jsonl,run-b/stagewright-results.jsonl
```

This runs no game and takes no `--game-dir`. It answers the question no single run can be asked: did
every scene these runs register execute in at least one of them? See
[Topologies](topologies.md#a-skip-is-not-coverage) for why that matters.

### Scenes that run outside the game

`--attached <dir>` loads scene files into the runner's own JVM and drives the game over RPC rather
than in-process. It needs a driver mod in the pack to provide the socket, and it switches the run to
a hold, because an autorun suite halts the server when it drains — which would take the socket down
under the attached half mid-call. Attached scenes run first, then the in-process suite; both results
are judged and the worse wins.

## Adding StageWright to a Gradle project

### 1. Resolve the plugin

StageWright publishes to your local Maven repository. Build and publish it once, in the order at the
top of [the root build script](../../build.gradle), then:

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

### 2. Compile against the scene API

Your scenes compile against one artifact, which depends on nothing but Minecraft and the shared
vocabulary module:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    testmodImplementation 'net.magicterra:mc_stagewright-api:0.1.0+1.21.1:dev'
}
```

The `dev` classifier matters. The plain artifact is the remapped one, which cannot be compiled
against from a development environment; the `dev` one carries named mappings.

Put your scenes in a source set that is not packaged into your production jar. You have two ways to
get one, and the difference is a matter of timing:

- **Register it yourself** — `sourceSets { testmod }` in the script body — when you want to declare
  dependencies on it in the ordinary `dependencies { }` block, as above. The plugin's own wiring
  runs late, so a configuration it creates does not exist yet when that block is evaluated.
- **Let the plugin create it** — `stagewright { testmodSourceSet = true }` — when you only need the
  source set and its classpath wired to `main`, and you declare nothing extra on it.

See [the Gradle plugin](gradle-plugin.md#the-testmod-source-set-convention) for exactly what the flag
does and, just as importantly, what it deliberately leaves to you.

### 3. Put the harness in the run

The framework has to be a **mod** in the run directory, not a library on the classpath. Under
architectury-loom:

```groovy
dependencies {
    modLocalRuntime 'net.magicterra:mc_stagewright-fabric:0.1.0+1.21.1'      // or -neoforge
}
```

Under ModDevGradle, use the plugin's `installMods` instead. [Gates](gates.md#how-the-harness-reaches-the-game)
explains why the classpath route does not work.

### 4. Declare a topology

```groovy
stagewright {
    topologies {
        dedicatedServer {
            runTask    = 'runStagewrightDedicatedServer'
            expectFile = file('src/testmod/expected-scenes.txt')
        }
    }
}
```

`runTask` names **your own** dev-run task — the one that starts a server with your mod and the
harness loaded. That run configuration is where `-Dstagewright.autorun=true` belongs, along with
anything else about how your game starts. See [the Gradle plugin](gradle-plugin.md) for the rest of
the properties, and [Topologies](topologies.md) for what else you might declare.

### 5. Write the first scene

```java
package com.example.mymod.scenes;

import net.magicterra.stagewright.scene.*;
import net.minecraft.world.level.block.Blocks;

@SceneSet("mymod")
public final class FirstScenes implements SceneProvider {

    @SceneDef(budget = 100)
    static void theFloorIsWhereIPutIt(SceneContext s) {
        s.floor(5, Blocks.STONE);
        s.expectBlock(0, 0, 0).as("the pad under the origin").isEqualTo(Blocks.STONE);
        s.expectBlock(0, 1, 0).as("the space above it").isEqualTo(Blocks.AIR);
    }
}
```

Register the provider class so the runtime can find it. Create
`src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider` containing
one line:

```
com.example.mymod.scenes.FirstScenes
```

Add the scene's name to the manifest, in the same commit:

```
# src/testmod/expected-scenes.txt
mymod.theFloorIsWhereIPutIt
```

A scene that is registered but not declared fails the run, and so does a name in the manifest that
nothing registered. Both directions are checked on purpose; see
[Writing a scene](writing-a-scene.md#a-scene-and-its-manifest-entry-land-together).

### 6. Run it

```bash
./gradlew stagewrightDedicatedServer
```

While you are still writing the scene, run only that one:

```bash
./gradlew stagewrightDedicatedServer -Pstagewright.scenes=mymod.theFloorIsWhereIPutIt
```

A filtered run costs a game boot instead of a game boot plus the whole suite, and it labels itself so
nobody mistakes it for a full check. [Gates](gates.md#running-one-scene-while-you-write-it) covers
what a filter does and does not prove.

Do not pipe the output through `tail`. [Gates](gates.md#never-truncate-a-runs-output) explains why.

## Where to go next

- [Writing a scene](writing-a-scene.md) — the full authoring reference.
- [Capabilities](capabilities.md) — reaching what the base game has no concept of.
- [Topologies](topologies.md) — which process shape can establish which facts.
- [Gates](gates.md) — running the checks and reading what comes back.
- [The Gradle plugin](gradle-plugin.md) — tasks, properties and conventions.
- [JUnit attach](junit-attach.md) — asserting from outside the game.
