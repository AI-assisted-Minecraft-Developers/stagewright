# Test queries

What a test asks the world that a player-facing tool has no reason to answer: where a recipe chain fails to
close, what a loot table can give, whether an advancement is held, where a structure generated, and what survives a
chunk leaving memory. They are WorldDriver methods that StageWright registers, so a test in the game, a script and an
outside client reach them the same way.

## Eight methods

One method per subject, the question named by `op`, as WorldDriver's `mc.bot.roster` names its own:

| Method | `op` |
|---|---|
| `mc.test.chunks` | `unload`, `reload` |
| `mc.test.recipes` | `producing`, `ingredients`, `closure`, `uncraftable`, `audit`, `count` |
| `mc.test.loot` | `exists`, `roll`, `list` |
| `mc.test.advancements` | `grant`, `revoke`, `progress`, `list` |
| `mc.test.structures` | `registered`, `at`, `locate`, `list` |
| `mc.test.mods` | `list`, `has` |
| `mc.test.logs` | `mark`, `since` |
| `mc.test.toasts` | `list` (a client only) |

`mc.test.logs` reads the warnings and errors of the JVM that answers it: the server's through `game`, the client's
through `client`, one log on an integrated server. An appender on log4j's root logger keeps the latest 4096 from the
JVM's start, each with its logger, level, thread, formatted message and exception. `mark` answers where the log stands,
and `since` lists what came after a mark at a level and by a logger prefix, saying how many it left out and how many
are no longer kept. `Logs` wraps the two for a Java test, and refuses an answer it cannot vouch is complete:

```java
Logs logs = Logs.of(client.registry());
long mark = logs.mark();
// ... what must not warn
assertEquals(List.of(), logs.since(mark, LogsContracts.Level.WARN, "com.example.mymod"));
```

What a pack has, a test can count and list: `list` names every loot table, advancement or structure, sorted, or only
those of a `namespace`, with a `count` that holds even when the list is cut at 100000; `mc.test.mods` lists the loaded
mods with their versions (`count`, `mods: [{id, version}]`), and `has` asks for one id (`has`, `version`). `count` on
recipes gives the number loaded, in all and per recipe type, and `audit` reports its totals beside the failures:
`total`, `attempted`, `succeeded`, `skipped`, `failureCount` and `failuresBySerializer`. A script reaches all of them
through `t.call`:

```javascript
const mods = t.call('mc.test.mods', { op: 'list' });
t.record('mods.count', mods.count);
t.expect(t.call('mc.test.mods', { op: 'has', id: 'worlddriver' }).has).equals(true);
```

All are hidden: callable on every transport and left out of tool lists, since every tool listed is sent with every
prompt to every model client, and these serve tests, not agents. A new question goes under the subject it belongs to
as another `op` rather than a new method. The contracts are in `stagewright-api`
(`net.magicterra.stagewright.test.methods`); the implementations in `stagewright-common`.

## Called like WorldDriver's own

`TestQueriesApi` is a `@Spec` interface, and WorldDriver's annotation processor generates its callers as it does
WorldDriver's: from an in-game test,

```java
var chunks = game.api(TestQueriesBlockingApi::of).chunks(new ChunksContracts.Unload(pos, Optional.empty(), 400));
```

The generated `TestQueriesBlockingApi` is compiled into `mc_stagewright-api`, beside the contracts, so a project
whose tests compile against StageWright's API (the Gradle plugin's `stagewrightTestCompile`) has it without running
any annotation processor itself. Each method takes one of its subject's question records (`LootContracts.Roll`,
`AdvancementsContracts.Grant`, …) and answers that subject's reply type, which several records implement — one per
kind of answer — so a test asserts which it got: `assertInstanceOf(LootContracts.Rolled.class, queries.loot(…))`. What
a script gets from `t.call` is the same answer as JSON.

The processor is `net.magicterra:worlddriver-api-processor`, at the WorldDriver version StageWright builds against.
Only StageWright's own build runs it. While both are in development it is published to mavenLocal only.

## Answers, not failures

A wait that runs out is an answer with `done: false`, and for an unload what still held the chunk; a structure not
found within the radius is `found: false`. Both are findings a test may assert either way. What the caller got wrong
— an unknown item, table, advancement, structure, dimension or player — is `invalid_params` at the field.

Each query starts on the server thread, hopping there from a test thread; one that spans ticks waits on the tick
loop rather than holding the server thread. `locate` is the exception: it searches, and generates, on the server
thread, which is why it takes an explicit chunk radius and reports how many milliseconds it took.

## What a test undoes itself

Nothing here is undone when the test ends. A grant answers the criteria it awarded, so the test can revoke exactly
those; a bot leaves with its advancements anyway. A chunk unloaded stays out of memory until something loads it
again, the test's own `reload` or a plot being put back.
