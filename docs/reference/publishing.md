# Publishing and artifacts

What StageWright publishes, which coordinate a consumer depends on for which job, and why
picking the wrong one produces a link error rather than a version conflict.

## The build layout

StageWright is not one Gradle build. It is four, and the split is structural rather than
organisational:

| Build | Root | Contains | Why separate |
|---|---|---|---|
| root | `settings.gradle` | `:stagewright-api`, `:stagewright-common`, `:stagewright-fabric`, `:stagewright-neoforge`, `:stagewright-attached`, `:stagewright-junit` | Applies loom and Architectury. |
| engine | `engine/settings.gradle` | `stagewright-engine` | Must never see a Minecraft classpath, and cannot be a subproject of the plugin, which is itself an included build of the root. |
| Gradle plugin | `gradle-plugin/settings.gradle` | the plugin | Included by the root's `pluginManagement`; includes `engine`. |
| CLI | `cli/settings.gradle` | the fat jar | Must never see a Minecraft or a Gradle-API classpath; includes `engine`. |

Two of the root build's modules are scoped out of the loom pipeline by name —
`stagewright-junit` and `stagewright-attached` — because both are plain-JVM libraries and
putting a Minecraft classpath under a module whose whole contract is not having one would make
it unconsumable by the CLI.

### The Architectury transforms run one at a time

The root build runs its projects in parallel, but its `transformProduction*` tasks share one build
service that admits a single task at a time. The Architectury plugin hands each transform its
configuration, the classpath among it, through JVM system properties, and the Gradle daemon has
one set of those for every task it runs. Two transforms that overlap can each read the other's: on
CI, `:stagewright-common:transformProductionFabric` read the classpath of
`:stagewright-api:transformProductionNeoForge`, which lists what api depends on but not api
itself, and failed with `Type net/magicterra/stagewright/scene/SceneContext$Progress not present`.
Whether two transforms overlap depends on how long the tasks before them take, so the failure
comes and goes with unrelated changes.

## Where builds are published

CI (`.github/workflows/ci.yml`) builds and tests every pull request and every push to `master`, and
publishes a push to `master` to the Nexus at `https://nexus.gardel.top/repository/maven-releases`,
which anyone can read, once its build passes. A `master` run still queued when a newer push arrives
is cancelled, so that push is never published under a number of its own. A consumer adds the Nexus
to both its `repositories` and its `pluginManagement { repositories }`, limited to this project's
groups so that Gradle does not look there for anything else:

```groovy
maven {
    url 'https://nexus.gardel.top/repository/maven-releases'
    content { includeGroupByRegex 'net\\.magicterra(\\..*)?' }
}
```

The pattern covers `net.magicterra` and `net.magicterra.stagewright`, the group Gradle looks up the
plugin under. Every build also publishes to `mavenLocal()` for local development.

## Versions

One version, for every artifact of all four builds: `<base>-build.<n>+<minecraft>`, such as
`0.1.0-build.42+1.21.1`.

- `<base>` is in `gradle/version.gradle`.
- `<n>` is the CI run number, taken from `BUILD_NUMBER`. Run numbers start at 1, which leaves `0`
  to the builds published by hand (see below). A value that is not a whole number without leading
  zeros stops the build.
- `<minecraft>` is `minecraft_version` from `gradle.properties`.

A build without `BUILD_NUMBER` is `0.1.0-build.local+1.21.1`, which the Nexus publishing tasks
refuse. The engine, the Gradle plugin and the command-line runner carry the Minecraft version too,
although nothing in them depends on it: builds of the four are only ever used together.

Every build is a pre-release of `<base>`, so SemVer, and with it Fabric Loader, orders all of them
below `0.1.0` itself. That is why a version range over StageWright or WorldDriver builds ends its
bounds in `-0`; the Minecraft and loader ranges, over releases, do not.

## The artifacts

Group is `net.magicterra` throughout.

| Coordinate | What it is | Classifiers | POM dependencies |
|---|---|---|---|
| `mc_stagewright-api` | The published contract: `Scene`, `SceneContext`, `SceneProvider`, `SceneDef`, the facets. The only thing a scene author compiles against. | plain, `sources`, `dev` | `mc_stagewright-attached` only |
| `mc_stagewright-attached` | The vocabulary both homes share: `Expect`, `SceneFailure`, `SceneSkipped`, `SceneOutcome`, `Clock`, `Terrain`, `Canary`, and the Rhino plumbing. No Minecraft. | plain, `sources` | none |
| `mc_stagewright-common` | The harness, the results writer, the `mc.test.*` verbs. Not consumed directly. | plain, `sources`, `dev` | none |
| `mc_stagewright-fabric` | The loader mod jar, shadow-bundling api, common and attached. | plain, `sources`, `dev` | none |
| `mc_stagewright-neoforge` | The same for NeoForge. | plain, `sources`, `dev` | none |
| `mc_stagewright-junit` | The JUnit 5 attach module, for tests that run outside the game. | plain | gson, the JUnit BOM, `junit-jupiter-api`, `mc_stagewright-attached` |
| `stagewright-engine` | Verdict, manifest, coverage and run-directory rules, shared by the plugin and the CLI. No Minecraft, no Gradle API, no third-party dependencies. | plain, `sources` | none |
| `stagewright-gradle-plugin` plus its plugin marker | The `net.magicterra.stagewright` plugin. | plain | `stagewright-engine`, declared `api` |
| `stagewright-cli` | The command-line runner: one executable jar with its dependencies inside it, because its audience downloads one file and runs it. | plain | none |

Every jar above, `sources` and `dev` included, and the CLI's `stagewright.jar` carry
`META-INF/COPYING` and `META-INF/COPYING.LESSER`, and every POM, the plugin marker's included,
declares `LGPL-3.0-only` with its URL, plus the project and SCM URL. All four builds take both
from `gradle/license.gradle`; `scripts/check_packaging.py` checks the built jars and POMs.

### Why the POM rules differ per artifact

There are three rules, not two, and each follows from how the artifact carries its
dependencies.

A **mod jar nests its runtime dependencies** through Jar-in-Jar and is remapped and
self-contained. A POM that re-declared them would hand a consumer a second, unremapped copy of
classes the jar already carries, so `mc_stagewright-common`, `-fabric` and `-neoforge` publish
POMs stripped of every `<dependency>`, and Gradle module metadata is disabled for them as well —
a Gradle consumer prefers `.module` over the POM, and its `runtimeElements` variant would leak
exactly what the POM strips.

`mc_stagewright-api` is stripped the same way **with one exception**:
`mc_stagewright-attached` survives. That is the rule applied correctly rather than a loophole.
The attached module is nested in nothing; the loader jars bundle it for runtime, but its types
are on the api's own public surface — `SceneContext.expect` returns `Expect`, `Scene` carries
`Canary` and `Terrain` — so a consumer needs it at *compile* time to see those types at all.
Stripping it would not prevent a duplicate; it would make `mc_stagewright-api` fail to compile
against for everyone who had not separately guessed the coordinate.

`mc_stagewright-attached`, `mc_stagewright-junit` and `stagewright-engine` are thin plain-JVM
libraries that shade and nest nothing, so their POMs declare what a consumer's build tool has to
resolve, and their module metadata is left enabled because the variant graph and the POM agree.

## The plain artifact and the development-mappings artifact

Every Minecraft-facing module publishes two jars under one coordinate: the plain artifact and
the same module with the `dev` classifier. **Which one you need depends on whether you are
compiling or running, and choosing wrong fails as a link error on Minecraft signatures rather
than as anything a build tool recognises as a version problem.**

The plain artifact is loom's remapped output. Its Minecraft references are *intermediary* names
— `net/minecraft/class_1291`. That is correct for shipping and useless for compiling, because a
consumer building in a development environment resolves against named or Mojang mappings —
`net/minecraft/core/BlockPos` — so linking the published jar directly fails on every
Minecraft-touching signature.

Inside one Gradle build, consumers avoid this with
`project(path: ':x', configuration: 'namedElements')`. That configuration has no published
equivalent, so what is published instead is the artifact it points at: loom already builds
`build/devlibs/<name>-dev.jar`, and the `dev` classifier stops it being discarded. It is a
classifier rather than a second publication so that the coordinates stay one artifact with two
faces, and no POM claims a dependency on it.

So:

- **Compiling a scene against the SPI** — take `mc_stagewright-api` with the `dev` classifier,
  on a plain configuration. Not a `mod*` configuration: loom must *not* remap it, because the
  development jar is already in the mappings that compile classpath uses.
- **Putting the framework into a development run** — take `mc_stagewright-<loader>` with no
  classifier, on `modLocalRuntime` (loom) so that loom *does* remap it, because the published
  jar is intermediary and the dev runtime needs named. This is the mirror image of the rule
  above, and both are right for their side.
- **Putting the framework into a ModDevGradle run** — take `mc_stagewright-<loader>` with no
  classifier, on a `localRuntime` that `runtimeClasspath` extends — not `runtimeOnly`, which would
  publish it as a runtime dependency of your mod. A modpack gets the same jar handed to its loader
  by the CLI.

There is one exception worth knowing, because it saves a NeoForge-only consumer from looking for
a classifier they do not need. Under ModDevGradle the shaded `-neoforge` jar is already
Mojang-mapped, so one coordinate does both jobs and the `dev` caveat does not apply. It is a
Fabric and loom concern.

The artifact *name* is load-bearing beyond resolution: `ModInstall` sweeps and installs by the
`mc_stagewright-` prefix, so a previous install's versioned filename is removed rather than
left beside its replacement — where the loader would see two copies of the framework and arm
the stale one.

## Depending on WorldDriver, and WorldDriver depending on StageWright

StageWright and WorldDriver depend on each other, in opposite directions and at different
points. Each depends on one exact version of the other that is on the Nexus: `worlddriver_version`
here, `stagewright_version` in WorldDriver's `gradle.properties`. Moving to a newer version is a
commit that changes that line. To build against your own local build of the other side instead,
publish it to `mavenLocal` and pass its version on the command line, as in
`-Pworlddriver_version=0.1.0-build.local+1.21.1`. `mavenLocal` is searched before the Nexus.

## Publishing when neither side has published yet

Once both sides have versions on the Nexus, neither build needs the other's source. This sequence is
only needed when that is not yet true — the very first versions, or a new `<base>` that neither side
has published — and is run once, with `BUILD_NUMBER=0` on both sides and every step publishing
straight to the Nexus with `publishAllPublicationsToGardelNexusRepository`, `MAVEN_USERNAME` and
`MAVEN_PASSWORD` set. Each module is published in exactly one step, since a version on the Nexus is
published once. CI numbers its runs from 1, so no CI build takes 0. The two sides do not really
depend on each other in a loop: it is broken by source set, since WorldDriver's main code never
depends on StageWright, and by module, since `mc_stagewright-api` depends on nothing with a
Minecraft classpath beyond Minecraft itself.

1. **StageWright**, four publications from three different builds: `engine` first, then the
   Gradle plugin, then `:stagewright-api` and `:stagewright-attached` from the root build. None
   of the four touches WorldDriver. The plugin has to exist before step 2 because WorldDriver's
   root build *applies* it, so nothing there configures at all until the plugin marker is in that
   repository; and the engine has to exist before the plugin, because the plugin declares
   it as an `api` dependency, so a consumer resolving the plugin from a repository resolves the
   engine from the same one. Locally the plugin's own build includes the engine directly, which
   is exactly why this ordering only bites someone starting from a clean checkout.
2. **WorldDriver** — publish its common module with `-PworlddriverBootstrap`. That property
   drops the loaders' `modLocalRuntime` dependency on the StageWright loader jars, which loom
   resolves at *configuration* time; without it a clean machine cannot get this far.
   StageWright's common module compiles against the development-mappings artifact this produces.
3. **StageWright** — the rest of the root build: `:stagewright-common`, `:stagewright-fabric`,
   `:stagewright-neoforge` and `:stagewright-junit`, each by its own task path. Not a root-wide
   publish, which would publish `:stagewright-api` and `:stagewright-attached` a second time; nor
   the engine or the Gradle plugin, which are separate builds.
4. **StageWright** — the command-line runner, `-p cli`. Its build takes the engine from source and
   the loader jars and `mc_stagewright-attached` from `mavenLocal` alone — the Nexus is not among
   its repositories — so first run a root `publishToMavenLocal` from the same commit and with the
   same `BUILD_NUMBER`. That publishes to `mavenLocal` only, and nothing to the Nexus.
5. **WorldDriver** — its loader modules. Its test-mod source set compiles against the api's
   development-mappings artifact, and its development runs put the loader jars on the classpath.

To try the sequence first, run it with `publishToMavenLocal` in place of the Nexus task and
`BUILD_NUMBER` unset, so every version is `…-build.local+1.21.1`, and step 4's root publish is
already done. Each side pins the version of the other it builds against, so point each at the
other's local build on every build of the rehearsal: `-Pworlddriver_version=0.1.0-build.local+1.21.1`
on StageWright's root build, and `-Pstagewright_version=0.1.0-build.local+1.21.1` on WorldDriver's,
which also selects the Gradle plugin version. Not with `BUILD_NUMBER=0`: `mavenLocal` is searched
before the Nexus on both sides, so a rehearsed `build.0` would stand in for the published one on
that machine from then on.

Two problems come from publishing one version to `mavenLocal` more than once, which every build
without `BUILD_NUMBER` does, as `…-build.local`. Republishing does not invalidate loom's remapping
cache, which is keyed on coordinates rather than on bytes — so a build can succeed against a
previously remapped jar with no error and no warning, running code that no longer exists in the
source tree. WorldDriver carries an explicit eviction pass and fails the build rather than letting
that pass silently. And the command-line runner takes the framework jars it bundles, and
`mc_stagewright-attached` it compiles against, from `mavenLocal` rather than from the root build, so
a runner built before the root `publishToMavenLocal` carries the framework last published there; the
engine it builds from source through `includeBuild`. Neither reaches CI: nothing in StageWright's
own builds consumes a StageWright jar through loom, and CI builds the runner right after publishing
the framework.

## Support matrix

| | |
|---|---|
| Minecraft | 1.21.1 (range `[1.21.1,1.22)`) |
| Loaders | Fabric and NeoForge, through Architectury |
| Fabric Loader | the version pinned in `gradle.properties`, range `[0.16,)` |
| NeoForge | the version pinned in `gradle.properties`, range `[21,)` |
| Java | 21 |
| WorldDriver | optional; when present, `[0.1.0-0,0.2.0-0)` — every `0.1.0` build |

Both loaders' jar metadata takes the mod id, name, authors, licence, description and every range
above from `gradle.properties` when the jar is built; `fabric.mod.json` gets the Maven ranges
rewritten into Fabric's predicate form (`[1.21.1,1.22)` becomes `>=1.21.1 <1.22`).

WorldDriver's range is derived from `worlddriver_version`, the build StageWright compiles
against: that release up to its next breaking one, which is the next minor while it is `0.x`.
Without WorldDriver StageWright still loads and runs its scenes, with no `mc.test.*` verbs.
With a WorldDriver outside the range the loader refuses to start: NeoForge through an
`optional` dependency, Fabric through `breaks` (Fabric Loader does not check a `suggests`
version, so that entry only documents the relationship).

The Minecraft platform versions must stay in lockstep with WorldDriver's `gradle.properties`.
StageWright compiles against WorldDriver's common module built against those exact versions, and
drift surfaces as link errors on Minecraft signatures rather than as a helpful version conflict.

The mod id is `mc_testkit`, which predates the rename. A consumer's mod list and every log line
still carry it; the artifact coordinates do not.

## Compatibility promise

The frozen surfaces are the [orchestration contract](orchestration-contract.md) and the
[instrument contract](instrument-contract.md): the results-file format, the exit codes, the
endpoint descriptor schema, and the scene SPI. Code written against those keeps working across
patch and minor releases.

Everything else carries no promise and may change without notice — scene execution timing,
internal classes, and the `StageWrightRpc` wire details.

Every artifact of all four builds carries the one version described under [Versions](#versions);
there is no independent scheme for any of them.
