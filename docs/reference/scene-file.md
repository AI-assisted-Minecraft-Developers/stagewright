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
did not ask for. `mods`, `files`, `clients` and `world.from` are not applied yet.

## `topology`

Required. The name a [launch spec](launch-spec.md) was written for; the Gradle plugin's `tests { topologies.put(…) }`
maps it to a topology the build declares.

## `world` — at creation

Written into the primary game's `server.properties` before it starts, on top of what is there, and put back when the
scene is over, so one scene's settings are never the next one's undeclared defaults. A dedicated server reads these
when it creates its world; a client hosting its own server does not read the file, so on that topology they do not
apply yet.

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

Undeclared, each is whatever the topology's own `server.properties` says.

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
