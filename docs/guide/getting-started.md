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

Every build of `master` publishes the runner to the Nexus. This fetches the newest:

```bash
curl -fLo stagewright.jar 'https://nexus.gardel.top/service/rest/v1/search/assets/download?repository=maven-releases&maven.groupId=net.magicterra&maven.artifactId=stagewright-cli&maven.extension=jar&maven.classifier=&sort=version'
```

To build it from a checkout instead: it is its own build, so it is not produced by building the rest
of the repository. Build it after publishing the engine and the loader jars it bundles:

```bash
cd engine && ../gradlew publishToMavenLocal
cd ..     && ./gradlew publishToMavenLocal
cd cli    && ../gradlew jar            # -> cli/build/libs/stagewright.jar
```

```
java -jar stagewright.jar --game-dir <the pack's server directory> --scenes <a folder of .js files>
```

That hands the right framework build to the pack's loader, points the game at your scene files,
works out how the pack starts, runs it, and judges the results. The process exit code is the verdict:
`0` sound and passing, `1` a scene failed, `2` the framework itself is broken and the results are
void, `3` the environment never let the suite run — the game never armed, stalled before writing results,
or had no display.

Both loader builds of the framework ride inside `stagewright.jar`, so there is no loader-and-version
pairing for you to get right — which is the step that usually fails, and fails looking exactly like
the mod not working. Other mods your scenes need come in through `--mod <jar>`, repeatable. None of
them is copied into `mods/`: Fabric gets them through `-Dfabric.addMods`, NeoForge through
`--fml.mavenRoots` and `--fml.mods`. A `--mod` jar whose file name the pack's `mods/` already has is
skipped, since the pack's own copy loads anyway.

The scene files are not copied either. The game reads `.js` scenes and `.json` capability
descriptors straight from `--scenes`, through `-Dstagewright.scenesDir`. Scenes are plain JavaScript
and register into the same registry, canaries and results file the compiled ones do. See
[Writing a scene](writing-a-scene.md#scenes-written-in-javascript) for the model, and `--help` for
the flags not covered here.

Everything StageWright writes for itself lands under `<game dir>/stagewright/`: the results, the
heartbeat, the launch logs, staged mods and natives. A client's game directory is otherwise left as
it was — `mods/`, `config/`, `options.txt` — so it may be a player's own `.minecraft`. A dedicated
server's directory also gets its EULA acceptance and the forced `server.properties` keys, and
`--world` decides what happens to its world.

### The flags you will reach for first

| Flag | What it does |
|---|---|
| `--game-dir <dir>` | The pack's server directory, or a client's game directory. Required. |
| `--scenes <dir>` | A folder of `.js` scenes and `.json` capability descriptors, read in place. A folder with no `.js` file is refused, and a `.js` file that registers no scene fails the run. |
| `--mod <jar>` | Load this mod too. Repeatable. A path that is not a file is refused before anything runs, and so is `--mod` with `--no-install`. |
| `--expect <file>` | Reconcile the run against an expected-scenes manifest. A manifest that names no scene is refused before the game starts. |
| `--timeout <min>` | A ceiling, not a duration — the run ends when the results file carries its footer. Defaults to 45; at least 1. |
| `--stall-timeout <min>` | End the run once nothing it writes has grown for this long. ENV if it had written no results yet; otherwise judged on what it wrote, and a suite with no done footer is RED. Defaults to 5; at least 1. |
| `--world reset\|keep` | What to do with a dedicated server's existing world. Required when one exists. |
| `--no-install` | Load nothing; the pack already has StageWright in its `mods/`. With `--scenes`, that copy must be new enough to read `-Dstagewright.scenesDir`, or the run is ENV. |
| `--launch "<command>"` | Start the server this way instead of detecting how. |
| `-D<key>=<value>`, `-X<option>` | Passed to the game's JVM as given — `-Xmx8G`, or a system GLFW with `-Dorg.lwjgl.glfw.libname=<so>`. |

`--help` and `--version` are only recognised as the **first** argument. Anywhere else they are
unknown options. Every run also begins by printing what `--version` does — the jar's version, and
the time and hash of each engine and framework jar inside it — so a CLI rebuilt without
republishing the framework shows it.

An option the CLI does not know is refused with the usage text and exit 3, and so is a value other
than `reset` or `keep` for `--world`. A mistyped name is therefore an error before anything runs,
rather than a setting that is silently never read — `--expected` for `--expect` would otherwise run
with reconciliation off.

A server whose world already exists is refused with ENV until you say which you meant: `--world reset`
deletes it first, `--world keep` runs the scenes in it as it is. Neither is a safe default — one
destroys a world someone may care about, the other lets a previous run's leftovers fail this one.

Detection covers NeoForge and Forge argument files under `libraries/`, and a Fabric or Quilt server
jar at the top level. When it cannot tell, it says so and asks for `--launch`. StageWright itself runs
on NeoForge and Fabric, so without `--no-install` a Forge pack is refused.

The server's output goes to `stagewright/stagewright-run.log` in the game directory, and the results
to `stagewright/stagewright-results.jsonl` beside it.

When a run ends without its results, the runner says how: the game crashed, exited, or stalled. A
stall is reported with the screen a client sits on if it never reached its title screen, such as a
mod's update prompt, and otherwise with whether the process was spinning or asleep. A mod that failed
to construct is quoted from `logs/debug.log`, because the crash that follows it is rarely the cause.

### Testing what only a client can reach

A dedicated server cannot reach anything that exists only on a client: a GUI a mod adds, a screen a
machine opens, the client half of a client-server split. Testing those needs a real client, which
the runner installs and launches itself:

```
java -jar stagewright.jar --game-dir <a client game directory> \
     --client neoforge:1.21.1:21.1.252 \
     --scenes <a folder of .js files> --expect <manifest>
```

`--client` is `neoforge:<mc>:<version>`, `fabric:<mc>:<loader version>` or `vanilla:<mc>`, always
exact. The first run installs that version, its libraries and its assets into `--install-dir`, which
defaults to the official launcher's `.minecraft`; later runs reuse them. The layout is the launcher's
own, so the same installation serves both, and downloads go through HMCL's `cache/SHA-1` directory in
both directions. `--mirror bmclapi` tries BMCLAPI before the official servers, and `HTTPS_PROXY` is
honoured.

The player is offline, named by `--username` (default `StageWright`) — a test run has no business
holding an account. The client creates a world of its own, `stagewright-<timestamp>`, so the suite
runs on its integrated server. A GREEN run deletes that world; any other keeps it and prints where.

The four settings a run cannot be correct without — no pause on lost focus, no accessibility
onboarding, no narrator, no vsync — are set in the game's memory and kept out of `options.txt`, so a
player's own settings survive the run.

**A client needs a display.** On Linux that means `DISPLAY` or `WAYLAND_DISPLAY`; without one the
run is ENV before anything starts. A local `DISPLAY` is also asked whether it lets the client in, with
the cookie from `XAUTHORITY` or `$HOME/.Xauthority` (none when neither is set), and one that refuses
is ENV with the server's reason.
The runner never starts a virtual framebuffer: a display is the environment's to provide — the
desktop, a CI image with Xvfb, or `xvfb-run` around the command. A client that passes these checks
and still cannot open its window — it logs `glfwInit failed` — is killed and reported as ENV as soon
as it says so, rather than left on the dialog NeoForge opens next.

> **Note.** A crash on the client's first tick is the pack's, not the runner's. A client ticks
> throughout its own loading, so every mod's client-tick handler fires while the loading overlay is
> still up and configs are still loading. A mod that reads a config value there with no guard takes
> the launch down under any launcher, with nothing of StageWright's on the stack. The crash report
> under `<game dir>/crash-reports` names the mod.

Each shape leaves its logs in its own place, and the game's log is not the launcher's:

| Shape | The game's own log | The launcher's output |
|---|---|---|
| server | `stagewright/stagewright-run.log` | — the runner starts the server itself |
| `--client` | `logs/latest.log` | `stagewright/stagewright-client-launch.log` |

### The shape a player actually plays

The two above are one JVM each. A real game is two, talking over a socket, and that seam is the only
place a mod's halves can disagree — an unregistered packet, state behind a side check, plain desync.
`--with-client` runs both halves from one command:

```
java -jar stagewright.jar --game-dir <the pack's server directory> --scenes <a folder of .js files> \
     --client neoforge:1.21.1:21.1.252 \
     --with-client <a client game directory, not the server's>
```

The scenes run on the server, which writes `stagewright/stagewright-results.jsonl`. The client has two
jobs: to be logged in while they run, and to run the one probe only a joined client can — that a
damage event survives the wire — writing `stagewright/stagewright-client-results.jsonl` in its own
directory. Both files are judged, each as its own suite, and the worse verdict is the run's; a client
that wrote no file is ENV. That is the rule the Gradle plugin applies to a topology's
`companionResultsFile`, from the same engine code, so the pair cannot be GREEN here and RED there.
The client loads the same framework build and the same `--mod` jars as the server, because a loader
that finds a different mod list on each end refuses the connection, and it dials the local address at
whatever port the pack's `server.properties` names.

The server does not start the suite until a player is actually on it. That is what makes a client
which never arrives a timeout you can read, rather than a passing run that quietly proved nothing.

Judging is always about the run this command can see. That is why "a client that joins a server you
started yourself" is not a mode: those scenes run on that server and write their results there, so the
only honest thing this process could report about them is that it cannot see them.

### Reconciling several runs

```
java -jar stagewright.jar --coverage run-a/stagewright/stagewright-results.jsonl,run-b/stagewright/stagewright-results.jsonl
```

This runs no game and takes no `--game-dir`. It answers the question no single run can be asked: did
every scene these runs register execute in at least one of them? See
[Topologies](topologies.md#a-skip-is-not-coverage) for why that matters. This runner's results
record no build and a Gradle gate's in a git work tree do, so listing both together is ENV
(`MIXED BUILDS`).

### Scenes that run outside the game

`--attached <dir>` loads scene files into the runner's own JVM and drives the game over RPC rather
than in-process. It needs a driver mod in the pack to provide the socket, and it switches the run to
a hold, because an autorun suite halts the server when it drains — which would take the socket down
under the attached half mid-call. Attached scenes run first, then the in-process suite; both results
are judged and the worse wins. If the socket closes partway, the scene it closed during fails, the
scenes after it are recorded as not run, and the run is RED, even when the scenes involved are
optional.

## Adding StageWright to a Gradle project

### 1. Resolve the plugin

StageWright's builds are on a public Nexus; see [Publishing](../reference/publishing.md) for the
version scheme. Use the newest `0.1.0-build.<build number>+1.21.1` there:

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

### 2. Compile against the scene API

Your scenes compile against one artifact, which depends on nothing but Minecraft and the shared
vocabulary module:

```groovy
repositories {
    maven {
        url 'https://nexus.gardel.top/repository/maven-releases'
        content { includeGroup 'net.magicterra' }
    }
}

dependencies {
    testmodImplementation 'net.magicterra:mc_stagewright-api:0.1.0-build.0+1.21.1:dev'
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
    modLocalRuntime 'net.magicterra:mc_stagewright-fabric:0.1.0-build.0+1.21.1'      // or -neoforge
}
```

Under ModDevGradle, `localRuntime 'net.magicterra:mc_stagewright-neoforge:0.1.0-build.0+1.21.1'`, with
`runtimeClasspath.extendsFrom localRuntime` as the NeoForge MDK declares it — not `runtimeOnly`,
which is published with your mod. Either way
the jar reaches the run the way any runtime-only mod does, and nothing is copied into `mods/`; see
[Gates](gates.md#how-the-harness-reaches-the-game).

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
