# Launch spec

How the test engine starts the games of one topology. Whatever configures a test run — the Gradle plugin, the
command-line runner — writes one file per topology, `<topology>.json`, into a directory, and passes that directory to
the test JVM as `-Dstagewright.launches=<directory>`. A scene's declaration names its topology; the engine reads the
matching file when it starts that scene. Why the engine does not ask Gradle is in
[the test engine's design](../design/test-engine.md).

## The file

```json
{
  "topology": "dedicatedServerWithClient",
  "gameDirectory": "/abs/run-stagewright-with-client",
  "cleanWorld": true,
  "timeoutMinutes": 20,
  "stallMinutes": 5,
  "leaks": "fail",
  "primary": {
    "java": "/usr/lib/jvm/java-21-openjdk/bin/java",
    "jvmArgs": ["-Xmx2g", "-Dstagewright.awaitPlayer=true", "@/abs/argfile"],
    "mainClass": "net.fabricmc.devlaunchinjector.Main",
    "args": ["--nogui", "--port", "25601"],
    "workingDirectory": "/abs/run-stagewright-with-client",
    "environment": {}
  },
  "companion": { "...": "the same fields, for the client that joins" }
}
```

| Field | Meaning |
|---|---|
| `topology` | the name, as the scene declaration spells it |
| `gameDirectory` | where the primary game writes; the engine keeps its logs and the agent jar under `stagewright/` in it |
| `worldDirectory` | the world the primary plays in, relative to `gameDirectory`; default `world`. The command-line runner points a pack's server at `stagewright/world`, so the pack's own world is never the one cleaned |
| `cleanWorld` | delete `worldDirectory` (and a client's `saves`) before each scene starts; default true |
| `timeoutMinutes` | the longest a scene's game may run; default 20 |
| `stallMinutes` | how long the game may go without changing phase, ticks or loaded classes before it is ended; 0 turns this off; default 5 |
| `leaks` | what a game the supervision agent had to force down after its run does to its scene: `fail` (the default) fails it, `report` leaves the verdict to its tests. Either way the held-up threads go into a `forcedDown` report entry and the game's log ([supervision](../design/supervision.md#the-supervisor)); any other value refuses the spec |
| `server` | the primary is a dedicated server: before each scene the engine accepts the EULA in its working directory and puts `online-mode=false`, `level-seed=5471` and `sync-chunk-writes=false` into its `server.properties` (the scene's own world keys are written after, so a declared seed wins), so a directory nothing else prepared starts; default false |
| `primary` | the game the tests run in |
| `companion` | optional: a second game started with it and ended after it, such as the client that joins a dedicated server |

A process is `java jvmArgs… mainClass args…` run in `workingDirectory` with `environment` added to the engine's own.
`mainClass` may be left out when an argument file in `jvmArgs` names it, as a NeoForge server's `unix_args.txt` does.
`jvmArgs` holds everything before the main class, class path included, in the order the run would have used.

## What the engine adds

To the primary only, placed after the run's own system properties and before the first `-cp` or argument file, so
that a `-D` here overrides the run's and the JVM rather than a launcher reads it:

| Argument | Why |
|---|---|
| `-javaagent:<agent jar>=…` | the supervision agent, which also carries the test events back ([protocol](supervision-protocol.md)) |
| `-Dstagewright.junit.classes=<classes>` | the scene's test classes, comma-separated: the game runs these and only these |
| `-Dstagewright.junit.scripts=<resources>` | the scene's script test files, comma-separated class-path resources such as `stagewright/tests/x.js` |
| `-Dstagewright.junit.scene=<name>` | the scene itself: a script file registers its tests for every scene, and the game keeps this one's |
| `-Dstagewright.junit.round=<n>` | which round of the scene this game runs, from 0: the tests that run after the game has restarted on the same world are in later rounds ([test engine](../design/test-engine.md#tests-after-a-restart)) |
| `-Dstagewright.junit.exitWhenDone=true` | the game ends itself once its run is over |
| `-Dstagewright.scene.world=<json>` | the part of the scene's world set once it has loaded ([scene file](scene-file.md)) |
| `-Dstagewright.companion.dir=<dir>` | only with a companion: its working directory, where its WorldDriver writes `worlddriver-rpc.port`, so a test's `Client` can reach it |

Before starting the primary, the engine also writes the scene's creation settings into `server.properties` in its
working directory, and puts the file back afterwards.

The companion gets nothing added. Its output and the primary's go to `<gameDirectory>/stagewright/logs/`.
