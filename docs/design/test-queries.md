# Test queries

What a test asks the world that a player-facing tool has no reason to answer: where a recipe chain fails to
close, what a loot table can give, whether an advancement is held, where a structure generated, and what survives a
chunk leaving memory. They are WorldDriver methods that StageWright registers, so a test in the game, a script and an
outside client reach them the same way.

## Five methods

One method per subject, the question named by `op`, as WorldDriver's `mc.bot.roster` names its own:

| Method | `op` |
|---|---|
| `mc.test.chunks` | `unload`, `reload` |
| `mc.test.recipes` | `producing`, `ingredients`, `closure`, `uncraftable`, `audit` |
| `mc.test.loot` | `exists`, `roll` |
| `mc.test.advancements` | `grant`, `revoke`, `progress` |
| `mc.test.structures` | `registered`, `at`, `locate` |

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

The processor is `net.magicterra:worlddriver-api-processor`, at the WorldDriver version StageWright builds against.
While both are in development it is published to mavenLocal only.

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
