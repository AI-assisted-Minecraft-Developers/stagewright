# Publishing and artifacts

What StageWright publishes, which coordinate a consumer depends on for which job, and why picking the wrong one
produces a link error rather than a version conflict.

## The build layout

StageWright is not one Gradle build. It is three, and the split is structural rather than organisational:

| Build | Root | Contains | Why separate |
|---|---|---|---|
| root | `settings.gradle` | `:stagewright-api`, `:stagewright-common`, `:stagewright-fabric`, `:stagewright-neoforge`, `:stagewright-cli`; includes `engine` | Applies loom and Architectury. |
| engine | `engine/settings.gradle` | `stagewright-engine`, `stagewright-agent` (`agent/`), `stagewright-testengine` (`testengine/`) | Must never see a Minecraft classpath, and cannot be a subproject of the plugin, which is itself an included build of the root. |
| Gradle plugin | `gradle-plugin/settings.gradle` | the plugin | Included by the root's `pluginManagement`; includes `engine`. |

`stagewright-cli` is the one module of the root build kept out of the loom pipeline: it is plain JVM, runs on a
machine with no Minecraft classpath, and carries the two loader jars as resources, taken from the sibling modules, so
building it builds them.

### The Architectury transforms run one at a time

The root build runs its projects in parallel, but its `transformProduction*` tasks share one build service that admits
a single task at a time. The Architectury plugin hands each transform its configuration, the classpath among it,
through JVM system properties, and the Gradle daemon has one set of those for every task it runs. Two transforms that
overlap can each read the other's: on CI, `:stagewright-common:transformProductionFabric` read the classpath of
`:stagewright-api:transformProductionNeoForge`, which lists what api depends on but not api itself, and failed with a
`Type … not present` error for one of api's own classes. Whether two transforms overlap depends on how long the tasks
before them take, so the failure comes and goes with unrelated changes.

## Where builds are published

CI (`.github/workflows/ci.yml`) builds and tests every pull request and every push to `master`, and publishes a push to
`master` to the Nexus at `https://nexus.gardel.top/repository/maven-releases`, which anyone can read, once its build
passes. A `master` run still queued when a newer push arrives is cancelled, so that push is never published under a
number of its own. A consumer adds the Nexus to both its `repositories` and its `pluginManagement { repositories }`,
limited to this project's groups so that Gradle does not look there for anything else:

```groovy
maven {
    url 'https://nexus.gardel.top/repository/maven-releases'
    content { includeGroupByRegex 'net\\.magicterra(\\..*)?' }
}
```

The pattern covers `net.magicterra` and `net.magicterra.stagewright`, the group Gradle looks up the plugin under. Every
build also publishes to `mavenLocal()` for local development; until a build of the version you need is on the Nexus,
that is where a consumer finds it, with `mavenLocal()` listed first in both blocks.

## Versions

One version, for every artifact of all three builds: `<base>-alpha.<n>+<minecraft>`, such as
`0.1.0-alpha.42+1.21.1`.

- `<base>` is in `gradle/version.gradle`.
- `<n>` is the CI run number, taken from `BUILD_NUMBER`. Run numbers start at 1, which leaves `0` to the builds
  published by hand (see below). A value that is not a whole number without leading zeros stops the build.
- `<minecraft>` is `minecraft_version` from `gradle.properties`.

A build without `BUILD_NUMBER` is `0.1.0-snapshot+1.21.1`, which the Nexus publishing tasks refuse. The engine, the
Gradle plugin and the command-line runner carry the Minecraft version too, although nothing in them depends on it:
builds of the three are only ever used together.

A version is compared three ways: by Gradle when it resolves dependencies, by Fabric Loader as SemVer, and by NeoForge
as Maven does. `alpha.<n>` and lowercase `snapshot` are the pre-release forms all three order alike: numbered builds by
their numbers, then the snapshot, then `<base>` itself. Others are not: Maven puts an unknown qualifier such as `build`
after the release, Fabric puts an uppercase `SNAPSHOT` before every numbered build, and Gradle and Maven order
`<base>-0` at or above the release. A version range over StageWright or WorldDriver builds therefore ends its bounds in
`-alpha.0`; the Minecraft and loader ranges, over releases, do not.

The WorldDriver range both loaders declare starts at the build `worlddriver_version` names, not at `<base>-alpha.0`:
StageWright is compiled against what that build added (the typed method registry its `mc.test.*` methods publish into,
for one), so an older WorldDriver of the same line is refused by the loader rather than failing to link at server
start. Moving `worlddriver_version` to a newer build raises the floor with it. Only a `snapshot` keeps the
`<base>-alpha.0` floor, since it carries no build number.

## The artifacts

Group is `net.magicterra` throughout.

| Coordinate | What it is | Classifiers | POM dependencies |
|---|---|---|---|
| `mc_stagewright-api` | The test API (`net.magicterra.stagewright.test`: `Game`, `Plot`, `Bot`, `Scene`, …) and the capability layer (`net.magicterra.stagewright.capability`). What a test author compiles against. | plain, `sources`, `dev` | none |
| `mc_stagewright-common` | The runtime: the in-game JUnit run, the script and YAML tests, the `mc.test.*` methods. Not consumed directly. | plain, `sources`, `dev` | none |
| `mc_stagewright-fabric` | The loader mod jar, bundling api and common, and nesting the JUnit Platform the game runs. | plain, `sources`, `dev` | none |
| `mc_stagewright-neoforge` | The same for NeoForge. | plain, `sources`, `dev` | none |
| `stagewright-engine` | Run-directory rules, display checks and the supervisor, shared by the plugin, the test engine and the CLI. No Minecraft, no Gradle API, no third-party dependencies. | plain, `sources` | none |
| `stagewright-agent` | The `-javaagent` a supervised game is started with. JDK only. See [the protocol](supervision-protocol.md). | plain, `sources` | none |
| `stagewright-testengine` | The JUnit Platform engine `stagewright` a `stagewrightTest<Name>` task runs ([design](../design/test-engine.md)). Carries the agent and a relocated Rhino. | plain, `sources` | `stagewright-engine`, `junit-platform-engine`, ASM, SnakeYAML |
| `stagewright-gradle-plugin` plus its plugin marker | The `net.magicterra.stagewright` plugin. | plain | `stagewright-engine`, declared `api` |
| `stagewright-cli` | The command-line runner: one executable jar with its dependencies inside it, because its audience downloads one file and runs it. | plain | none |

Every jar above, `sources` and `dev` included, and the CLI's `stagewright.jar` carry `META-INF/COPYING` and
`META-INF/COPYING.LESSER`, and every POM, the plugin marker's included, declares `LGPL-3.0-only` with its URL, plus the
project and SCM URL. All three builds take both from `gradle/license.gradle`; `scripts/check_packaging.py` checks the
built jars and POMs.

### Why the Minecraft-facing POMs declare nothing

A mod jar nests its runtime dependencies through Jar-in-Jar and is remapped and self-contained. A POM that re-declared
them would hand a consumer a second, unremapped copy of classes the jar already carries, so every module of the root
build publishes a POM stripped of every `<dependency>`, and Gradle module metadata is disabled for them as well — a
Gradle consumer prefers `.module` over the POM, and its `runtimeElements` variant would leak exactly what the POM
strips.

That includes `mc_stagewright-api`, whose public types name WorldDriver's API (`Game.registry()` answers WorldDriver's
`ApiRegistry`). Whoever compiles against it declares `net.magicterra:worlddriver-api:<version>:dev` beside it; the
Gradle plugin's `stagewrightTestCompile` configuration does both by default.

The engine, the agent, the test engine and the plugin are plain-JVM libraries, so their POMs declare what a consumer's
build tool has to resolve.

## The plain artifact and the development-mappings artifact

Every Minecraft-facing module publishes two jars under one coordinate: the plain artifact and the same module with the
`dev` classifier. **Which one you need depends on whether you are compiling or running, and choosing wrong fails as a
link error on Minecraft signatures rather than as anything a build tool recognises as a version problem.**

The plain artifact is loom's remapped output. Its Minecraft references are *intermediary* names —
`net/minecraft/class_1291`. That is correct for shipping and useless for compiling, because a consumer building in a
development environment resolves against named or Mojang mappings — `net/minecraft/core/BlockPos` — so linking the
published jar directly fails on every Minecraft-touching signature.

Inside one Gradle build, consumers avoid this with `project(path: ':x', configuration: 'namedElements')`. That
configuration has no published equivalent, so what is published instead is the artifact it points at: loom already
builds `build/devlibs/<name>-dev.jar`, and the `dev` classifier stops it being discarded.

So:

- **Compiling tests against the API** — `mc_stagewright-api` with the `dev` classifier, on a plain configuration, not a
  `mod*` one: loom must *not* remap it, because the development jar is already in the mappings that compile classpath
  uses.
- **Putting the framework into a development run** — `mc_stagewright-<loader>` with no classifier, through a `mod*`
  configuration under loom so that loom *does* remap it. The Gradle plugin does this itself, in its own
  `modStagewrightTestRuntime` configuration, so the framework reaches the test runs and no other run.

Under ModDevGradle the `-neoforge` jar is already Mojang-mapped, so one coordinate does both jobs and the `dev` caveat
does not apply. It is a Fabric and loom concern.

## Depending on WorldDriver, and WorldDriver depending on StageWright

StageWright and WorldDriver depend on each other, in opposite directions and at different points. Each depends on one
exact version of the other: `worlddriver_version` here, `stagewright_version` in WorldDriver's `gradle.properties`.
Moving to a newer version is a commit that changes that line. To build against your own local build of the other side
instead, publish it to `mavenLocal` and pass its version on the command line, as in
`-Pworlddriver_version=0.1.0-snapshot+1.21.1`. `mavenLocal` is searched before the Nexus.

## Publishing when neither side has published yet

Once both sides have versions in a repository, neither build needs the other's source. This sequence is only needed
when that is not yet true — the very first versions, a new `<base>` that neither side has published, or a fresh
machine building both from source. `scripts/build_driver_pair.sh <worlddriver checkout>` runs it into `mavenLocal`, as
`…-snapshot+1.21.1` with `BUILD_NUMBER` unset, and logs each step under `build/driver-pair-logs`:

1. **StageWright** — the engine build (`./gradlew -p engine publishToMavenLocal`: engine, agent and test engine), then
   the Gradle plugin (`-p gradle-plugin`). WorldDriver's root build *applies* the plugin, so nothing there configures
   until the plugin is in a repository; and the plugin declares the engine as an `api` dependency, so the engine comes
   first.
2. **WorldDriver** — `:api` and `:api-processor`, with `-PworlddriverBootstrap`, which declares no StageWright test
   suite, so nothing asks for StageWright's loader jars, which do not exist yet.
3. **StageWright** — `:stagewright-api`, which compiles against WorldDriver's API and runs its annotation processor.
4. **WorldDriver** — `:api` and `:common`, still with `-PworlddriverBootstrap`. StageWright's common module compiles
   against the development-mappings artifact of `:common`.
5. **StageWright** — the rest of the root build.
6. **WorldDriver** — its whole build, whose tests compile against `mc_stagewright-api:dev`.

Each side names the other's version on every step: `-Pworlddriver_version=<v>` on StageWright's builds,
`-Pstagewright_version=<v>` on WorldDriver's, which also selects the Gradle plugin version.

To publish the sequence to the Nexus instead, each module is published once, with `BUILD_NUMBER=0` on both sides and
`publishAllPublicationsToGardelNexusRepository` in place of `publishToMavenLocal`, `MAVEN_USERNAME` and `MAVEN_PASSWORD`
set. CI numbers its runs from 1, so no CI build takes 0. Do not rehearse with `BUILD_NUMBER=0` locally: `mavenLocal` is
searched before the Nexus on both sides, so a rehearsed `alpha.0` would stand in for the published one on that machine
from then on.

Publishing one version to `mavenLocal` more than once, which every build without `BUILD_NUMBER` does, does not
invalidate loom's remapping cache, which is keyed on coordinates rather than on bytes: a build can succeed against a
previously remapped jar with no error, running code that no longer exists in the source tree. The Gradle plugin deletes
remapped StageWright and WorldDriver jars older than the jar last published, as it is applied
([details](../guide/gradle-plugin.md#test-suites-tests--)).

## Support matrix

| | |
|---|---|
| Minecraft | 1.21.1 (range `[1.21.1,1.22)`) |
| Loaders | Fabric and NeoForge, through Architectury |
| Fabric Loader | the version in `gradle.properties`, range `[0.16,)` |
| NeoForge | the version in `gradle.properties`, range `[21,)` |
| Java | 21 |
| WorldDriver | required; from the build `worlddriver_version` names up to the next breaking release |

Both loaders' jar metadata takes the mod id, name, authors, licence, description and every range above from
`gradle.properties` when the jar is built; `fabric.mod.json` gets the Maven ranges rewritten into Fabric's predicate
form (`[1.21.1,1.22)` becomes `>=1.21.1 <1.22`).

WorldDriver's range is that build up to its next breaking one, which is the next minor while it is `0.x`. Both loaders
refuse to start without WorldDriver or with a version outside the range: Fabric declares it in `depends`, NeoForge
declares a `required` dependency. The Gradle plugin refuses a `worldDriverVersion` outside it when resolving
dependencies.

The Minecraft platform versions must stay in step with WorldDriver's `gradle.properties`. StageWright compiles against
WorldDriver's modules built against those exact versions, and a difference surfaces as link errors on Minecraft
signatures rather than as a helpful version conflict.

The mod id is `mc_testkit`, which predates the rename. A consumer's mod list and every log line still carry it; the
artifact coordinates do not.

## Compatibility

Every build is a `0.1.0` pre-release, and no surface carries a compatibility promise yet. The agreements two separately
built programs rely on are written down under `docs/reference/`: the [scene file](scene-file.md), the
[launch spec](launch-spec.md) and the [supervision protocol](supervision-protocol.md).

Every artifact of all three builds carries the one version described under [Versions](#versions); there is no
independent scheme for any of them.

## Checking a pair of candidates

`scripts/build_driver_pair.sh /path/to/worlddriver` runs the sequence above into `mavenLocal`, builds and tests both
sides, builds the CLI, and checks architecture and packaging. Both repositories use the same `BUILD_NUMBER`, or
`snapshot` when it is unset.

That checks the builds, not the two together in a game. For that, run WorldDriver's test suites against the candidate,
from the WorldDriver checkout: `./gradlew stagewrightTestFabric stagewrightTestNeoforge
-Pstagewright_version=<candidate>`, each with its whole output redirected to a file. The client topologies need a
display.

### Paired pull requests

Give related changes in both repositories the same branch name. On a pull request, CI checks whether the companion
repository has that branch and uses its current commit. When it exists, CI runs `build_driver_pair.sh` against both
candidate sources, and WorldDriver's CI runs `stagewrightTestNeoforge` against the candidate StageWright. When no
companion branch exists, CI uses the published dependency. These builds publish only to the runner's local Maven
repository.
