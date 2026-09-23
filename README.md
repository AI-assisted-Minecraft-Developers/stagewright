# StageWright

*[English](README.md) · [简体中文](README-zh_CN.md)*

An in-game integration-test framework for Minecraft mods and modpacks. A **scene** is a piece of code
that builds a situation in a live world, drives it, and asserts an outcome. StageWright launches the
game in a chosen process topology, runs the scenes against a running server, and writes a
machine-readable result that a Gradle task or a command-line runner judges.

It runs on Minecraft 1.21.1 under both Fabric and NeoForge, from one set of scenes.

## Why it exists

Minecraft has its own in-game test facility and both loaders expose it. StageWright exists for what
that leaves out:

- **A scene builds its world in code**, relative to an origin the framework assigns, so there is no
  separate world file to keep in step with the assertions and no absolute coordinate in a scene.
- **One registration serves both loaders.** Scenes live in shared code and arrive through a service
  loader, so the same suite runs on Fabric and NeoForge without a per-loader copy.
- **A run proves it could still have failed.** Every run carries canaries the framework must catch —
  one that must be reported as a failure, one as a timeout, one that must never execute. A run whose
  canaries land wrong is reported as a broken measurement, which is not the same thing as a broken
  mod and must never be read as one.
- **The same scenes run in several process shapes**, because each one can establish facts the others
  cannot.
- **A skip is not coverage.** A scene that cannot run where it is records a skip, and a separate
  check fails the build when a scene skipped in every topology the project runs.
- **A modpack can use it without a build tool.** Scenes can be plain JavaScript files in a pack's
  config folder, and a capability seam lets a mod or a pack teach the framework things the base game
  has no concept of.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Loader 0.16 or newer, with Fabric API |
| NeoForge | 21 |

Artifacts publish to your local Maven repository under the group `net.magicterra`. The Gradle plugin
is `net.magicterra.stagewright`.

## The shortest path to a passing run

### Testing a modpack, with no build tool

```
java -jar stagewright.jar --game-dir <the pack's server directory> --scenes <a folder of .js files>
```

That installs the right framework build into the pack's `mods/`, installs your scene files, works out
how the pack starts, runs it, and judges the results. The exit code is the verdict: `0` sound and
passing, `1` a scene failed, `2` the framework itself is broken and the results are void, `3` the
game never armed.

Both loader builds ride inside the jar, so there is no loader-and-version pairing for you to get
right. `--help` lists the rest, including how to run a real client with no display and no account.

### Adding it to a Gradle project

```groovy
// settings.gradle
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }

// build.gradle
plugins {
    id 'java'
    id 'net.magicterra.stagewright' version '0.1.0'
}

dependencies {
    // on whichever source set holds your scenes — see the guide
    testmodImplementation 'net.magicterra:mc_stagewright-api:0.1.0+1.21.1:dev'
    modLocalRuntime       'net.magicterra:mc_stagewright-fabric:0.1.0+1.21.1'
}

stagewright {
    topologies {
        dedicatedServer {
            runTask    = 'runStagewrightDedicatedServer'          // your own dev-run task
            expectFile = file('src/testmod/expected-scenes.txt')
        }
    }
}
```

That registers `./gradlew stagewrightDedicatedServer`, which provisions a clean run directory, runs
your dev-run task, and judges the results. [Getting started](docs/guide/getting-started.md) walks both
routes in full, including the first scene to write and how to run only that one while writing it.

## What a scene looks like

This is one of StageWright's own built-in scenes, verbatim from
`common/src/main/java/net/magicterra/stagewright/harness/Scenes.java`:

```java
Scene.of("awaitTicks", 200, ctx -> {
    ctx.setBlock(0, 0, 0, Blocks.STONE);
    ctx.await(() -> ctx.ticks() >= 40).within(100).then(() -> {
        if (ctx.ticks() < 40) ctx.fail("await fired before its condition held");
        ctx.assertBlock(0, 0, 0, Blocks.STONE);
    });
}),
```

The body runs once, synchronously, on the scene's first tick, inline on the server tick loop. It
never blocks and never sleeps: anything that takes time goes through `await`, which is also the only
way a scene spans ticks. Every coordinate is relative to an origin the framework assigns, and
exceeding the declared tick budget is a timeout, which a run reports separately from a failure.

Day to day you annotate a method instead, so the scene's name comes from the method name and is
never written twice:

```java
@SceneSet("mymod")
public final class MagnetScenes implements SceneProvider {

    @SceneDef(budget = 200)
    static void pullsItemsWithinRadius(SceneContext s) { … }
}
```

[Writing a scene](docs/guide/writing-a-scene.md) is the full reference.

## Process topologies

All three run scenes on a **server**; what differs is which server, and whether a real client is
attached to it.

| Topology | What it starts | Good for |
|---|---|---|
| dedicated server | one headless dedicated server | The bulk of a suite, and the only place where "a client-only call is refused" and "the player list is empty" are observable. |
| integrated server | one game client hosting its own server | Anything needing client-side state in the same process, and anything measured in frames. |
| dedicated server with client | a dedicated server plus a real client joined to it | Anything whose subject is the wire between the two halves — the difference between "works in singleplayer" and "works on a server". |

A consuming project names its own topologies; those three names are the convention because they say
what starts. [Topologies](docs/guide/topologies.md) explains which facts need which shape, and why a
scene that skips proves nothing.

## Documentation

- [Getting started](docs/guide/getting-started.md) — both entry points, end to end.
- [Writing a scene](docs/guide/writing-a-scene.md) — lifecycle, assertions, registration, naming.
- [Capabilities](docs/guide/capabilities.md) — reaching a mod's own machinery, and extending the seam.
- [Topologies](docs/guide/topologies.md) — the process shapes and what each one can establish.
- [Gates](docs/guide/gates.md) — running the checks and reading what comes back.
- [The Gradle plugin](docs/guide/gradle-plugin.md) — tasks, properties, source-set convention.
- [JUnit attach](docs/guide/junit-attach.md) — asserting from outside a running game.

The wire formats and the publishing layout are under [`docs/reference/`](docs/reference/), the
reasoning behind the larger decisions under [`docs/design/`](docs/design/), and
[`docs/README.md`](docs/README.md) indexes all of it.

## StageWright and WorldDriver

[WorldDriver](https://github.com/AI-assisted-Minecraft-Developers/worlddriver) is a Minecraft mod
that exposes the running game as one programmable API surface. StageWright began inside it and became
its own repository.

The two depend on each other, in opposite directions and at different points: StageWright's runtime
modules compile against WorldDriver's common module, while WorldDriver consumes StageWright as
published Maven artifacts and applies its Gradle plugin. That is not a cycle — it is broken by module
and by source set, since StageWright's scene API depends on nothing at all and WorldDriver's
production code never depends on StageWright — but it does mean a cold bootstrap has exactly one
working order, in four steps:

```bash
# 1. StageWright — the four things WorldDriver needs before it can configure at all
./gradlew -p engine publishToMavenLocal
./gradlew -p gradle-plugin publishToMavenLocal
./gradlew :stagewright-api:publishToMavenLocal :stagewright-attached:publishToMavenLocal

# 2. WorldDriver — its common module, which StageWright's runtime compiles against
./gradlew -PworlddriverBootstrap :common:publishToMavenLocal

# 3. StageWright — everything else
./gradlew publishToMavenLocal

# 4. WorldDriver — build
./gradlew build
```

Step 1 comes first because WorldDriver's root build *applies* the Gradle plugin, so nothing there
configures until the plugin marker exists locally; none of those four artifacts touches WorldDriver.
`-PworlddriverBootstrap` in step 2 is not optional on a clean machine: it drops the loader modules'
development-runtime dependency on StageWright's loader jars, which Gradle resolves at *configuration*
time, and which do not exist until step 3.

The header of [`build.gradle`](build.gradle) carries the same sequence with the reasoning for each
step. Only a change to the scene API forces the whole sequence again, and that module is interfaces
and value types, so it changes rarely.

## Licence

[LGPL-3.0-only](COPYING.LESSER) — the additional permissions, on top of the GPL-3.0 text in
[`COPYING`](COPYING) that they modify.

A mod that merely *uses* this framework — declaring the artifacts as dependencies, writing scenes
against the service interfaces, running the checks — is not a derived work of it and carries whatever
licence it likes. The copyleft attaches to StageWright's own sources and to modified copies of them.

One directory is a documented exception: `engine/src/main/java/.../engine/json/` is minimal-json
vendored verbatim under the MIT licence. Its headers are upstream's terms and stay as they are; see
`VENDORED.md` beside them.

The CLI's `stagewright.jar` also carries Rhino (MPL-2.0), Gson and its Error Prone annotations
(Apache-2.0). `META-INF/THIRD-PARTY-NOTICES` in that jar names each one with its source, and
`META-INF/licenses/` holds their licence texts.
