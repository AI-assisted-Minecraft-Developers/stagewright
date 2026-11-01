# Scene file

A scene is a game instance. Its declaration is `stagewright/scenes/<name>.yaml` on the test class path, and a test
class names it with `@Scene("<name>")`. Every class naming the same scene runs in one game. The test engine reads the
file when it starts the scene; why scenes are declared this way is in [the test engine's design](../design/test-engine.md).

```yaml
topology: dedicatedServerWithClient
world:
  preset: minecraft:flat
  seed: 5471
  structures: false
  gamemode: survival
  difficulty: easy
  gamerules: { keepInventory: true, randomTickSpeed: 0 }
  datapacks:
    enable: [minecraft:trade_rebalance]
    disable: []
  time: { at: noon, frozen: true }
  weather: { clear: false, thunder: false, frozen: true }
  tickRate: 20
  mobSpawning: false
```

A key this version does not apply is refused, and so is a value of the wrong kind: a scene does not run on a world it
did not ask for. There is no key for more than one client yet.

## `mods`

```yaml
mods:
  exclude: [farmersdelight, jei-*.jar]
```

Mods the scene's games start without, each a mod id or a pattern on its jar's file name, found in the game's `mods/`
or on its class path (where a build's dependency mods are). A name that matches no mod of a game is not an error, so
one scene can exclude from both its server and its client a mod only one of them has; the `mods` report entry and a
line of the engine's output name what matched nothing. Neither the pack's `mods/` nor the build's files are changed: Fabric is told the ids to disable
(`fabric.debug.disableModIds`); NeoForge gets copies of its argument files with the jars taken out of every path list,
and, for a jar in `mods/`, a game directory under `stagewright/scene-mods/` whose entries all link to the real one's but
`mods/`, which links the jars kept. The engine reports what it left out as an entry named `mods`. `only`, every mod but
those listed, is refused: it would take their dependencies too.

## `files`

Text written into the primary game's working directory before it starts, by path relative to it. That directory is the
scene's own copy of the topology's ([scene directories](launch-spec.md#scene-directories)), so nothing needs putting
back and no other scene sees the file. A path may not leave the directory.

```yaml
files:
  config/mymod/retreat.toml: |
    sharedQuota = 4
```

The world is under that directory too, so a file can go into it: `stagewright/world/datapacks/…` for a pack's server
under the command-line runner, `saves/<world>/…` for a client hosting its own.

This is also how a scene supplies [capability descriptors](../guide/capabilities.md#2-declare-one-in-a-json-file): the
game reads them only from `config/stagewright/capabilities/` in its working directory, so a descriptor the pack does
not already have there is written by the scene as `config/stagewright/capabilities/<file>.json`.

## `clientFiles`

The same as `files`, for the game client's working directory: the joining client's on `dedicatedServerWithClient`, the
game's own on `integratedServer`, where the game is the client. Put back the same way. A scene that declares them on a
topology with no client fails rather than running without them.

```yaml
clientFiles:
  options.txt: |
    onboardAccessibility:false
    pauseOnLostFocus:false
  config/fml.toml: |
    earlyWindowControl = false
```

## `world.from`

A save to start from instead of a new world: a zip or a directory, as a class path resource under `stagewright/`
(`from: worlds/v1.2.zip` is `src/test/resources/stagewright/worlds/v1.2.zip`, or a pack's
`stagewright/worlds/v1.2.zip`). It is put where the scene's world goes in its own directory, so every scene starts from
the save as it was given. A zip is unpacked from the folder holding its `level.dat`, so a zip of the world
folder and a zip of its contents both do. The creation settings below cannot be declared with it, since they apply only
to a new world; those applied once loaded still are. A client opening a save does not ask whether to back it up first,
which it otherwise does for a save made with worldgen mods.

## `topology`

Required. The name a [launch spec](launch-spec.md) was written for. A Gradle suite with a `loaderProject` has
`dedicatedServer`, `integratedServer` and `dedicatedServerWithClient`; `tests { topologies.put(…) }` maps other names to
topologies the build declares. The command-line runner has `dedicatedServer`, and the other two with `--client`.
How a topology's games are bounded and judged — `timeoutMinutes`, `stallMinutes`, `leaks` — is the topology's, not
the scene's: it is set in the Gradle `topologies { }` block, or on the suite for its generated topologies.

## `world` — at creation

Written into the primary game's `server.properties` before it starts, on top of what is there, in the scene's own
directory, so one scene's settings are never the next one's undeclared defaults. A dedicated server reads these
when it creates its world; on `integratedServer`, StageWright reads the same keys from the file the same way and the
client creates its singleplayer world from them.

| Key | `server.properties` | Value |
|---|---|---|
| `preset` | `level-type` | a world preset id, such as `minecraft:normal`, `minecraft:flat`, `minecraft:amplified` |
| `seed` | `level-seed` | any number or text |
| `structures` | `generate-structures` | true or false |
| `gamemode` | `gamemode` | `survival`, `creative`, `adventure`, `spectator` |
| `difficulty` | `difficulty` | `peaceful`, `easy`, `normal`, `hard` |
| `datapacks.enable` | `initial-enabled-packs` | pack ids, added to those the file already lists (`vanilla` when it lists none). Experimental features in 1.21.1 are packs too |
| `datapacks.disable` | `initial-disabled-packs` | pack ids, likewise |

They only take effect on a new world, so a topology that keeps its world between runs (`cleanWorld: false`) refuses a
scene declaring any of them. The game checks the datapacks once loaded: a pack it does not know, or one still enabled
that was meant to be off, fails every test of the scene and names the packs the game has.

Undeclared, each is whatever the topology's own `server.properties` says. On a topology whose launch spec marks it a
`server`, the engine sets `level-seed=5471` there before the scene's own keys, so an undeclared seed is that fixed one;
on `integratedServer` an undeclared key is a dedicated server's default — survival, easy, structures on, seed 5471.

## `world` — once loaded

Passed to the game as `-Dstagewright.scene.world` and applied after the world has loaded, before the first test.
Undeclared, these are the quiet world, never the game's own default:

| Key | Default | Meaning |
|---|---|---|
| `time.at` | `midnight` | `day` (1000), `noon` (6000), `night` (13000), `midnight` (18000), or a tick of the day |
| `time.frozen` | true | `doDaylightCycle` off |
| `weather.clear` | true | false: rain |
| `weather.thunder` | false | true: thunder, which is rain too |
| `weather.frozen` | true | `doWeatherCycle` off |
| `mobSpawning` | false | `doMobSpawning` |
| `tickRate` | the game's | ticks per second |
| `gamerules` | none | rule id to `true`, `false` or a whole number; applied last, so a rule named here wins over one the settings above imply |

The engine prints every setting a scene runs with, declared or not, as a report entry named `world` on the scene.
A test changing any of these for itself uses `game.override(…)` under the matching lock instead, and is put back when it
ends.
