# Attaching a JUnit suite to a running game

Some things cannot be asserted from inside a scene, because a scene body runs in the runtime under
test, on the server thread, through the assertion stack the harness uses to judge itself. Asking a
scene to vouch for the harness is asking the thing under test to vouch for itself.

`:stagewright-junit` is the way round that. It is a **pure-JVM JUnit 5 module** — no Minecraft on its
classpath, no loader, no game classes — that attaches to an already-running game over the driver's
WebSocket RPC and asserts from outside it. The process is different, the transport is bare RPC, and
the assertion stack is JUnit's, which is three independent things that would all have to break at
once to produce a false pass.

It is also the correct home for anything that needs to wait on an asynchronous client screen, because
a test method runs on the JUnit thread and is free to block, where a scene body inline on the server
tick is not.

## The two halves

The module's tests split into layers that cannot silently shrink each other.

**Pure self-tests** need no game and no socket: descriptor parsing and round-tripping, the RPC
envelope codec, polling that distinguishes a timeout from a return, and the fact that a timeout
exception is not an assertion error. They always run.

**Live tests** attach to a running game and split again by which side of the game they need:

- The **instrument-contract** tests assert what the driver's router answers, what it refuses,
  whether a world write is visible to the driver's own read, and whether a tool's advertised schema
  is the one the validator enforces. Half of what they check is only observable where there is
  genuinely no client — that a client-only verb is refused, that an empty player list reads as empty
  — so they require a **server** face.
- The **UI** tests open screens, send input, and read back what the client shows. Client-only calls
  exist nowhere else, so they require a **client** face.

Green here is the precondition for trusting any scene's setup or assertions, because it is the
instrument face the scene harness sits on.

## Running them

Two terminals. One holds a game, one runs the tests against it.

```bash
# terminal 1 — in the consuming project, any topology
./gradlew stagewrightIntegratedServerHold
# … endpoint descriptor written to <abs>/stagewright-endpoint.json
```

```bash
# terminal 2 — in stagewright
TESTKIT_ENDPOINT=<abs>/stagewright-endpoint.json \
  ./gradlew :stagewright-junit:test --rerun-tasks
```

The hold ends when you stop it.

`--rerun-tasks` is **mandatory on a re-run**, not a habit. `TESTKIT_ENDPOINT` is an environment
variable and not a Gradle task input, so a plain second invocation is up to date and silently reports
the previous verdict for a run it never made.

The descriptor is loader-agnostic. The identical module attaches to either loader's game with no code
change.

## The trap: with the variable unset, the live half skips

Every live test class carries `@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches =
".+")`. With no endpoint configured they are **disabled**, not failed — which is the right behaviour
for a developer running the whole build on a laptop with no game up, and a dangerous one for anybody
reading the result.

**A green `:stagewright-junit:test` without that variable set is not coverage at all.** It is the
self-tests passing and the entire live suite skipping. Read a green run as covering the instrument
contract or the UI only when the command that produced it set the variable.

The gating is a mirror rather than a hole. The two attach fail-fast self-tests carry the opposite
annotation, `@DisabledIfEnvironmentVariable`, because the branch they assert — what `attach()` does
when nothing is configured — is only reachable when the variable is absent; with it present, `attach`
succeeds and defeats the assertion. So with the variable off the fail-fast self-tests run and the
live tests skip; with it on the reverse. Every test runs in exactly one of the two modes, and JUnit
reports the skips honestly. Nothing can fall through both gates and vanish.

The module's test task passes the ambient `TESTKIT_ENDPOINT` straight through, empty when unset, so
the fail-fast path is exercised rather than accidentally inheriting a stale value.

## The endpoint descriptor

A held game writes a small JSON file once it is genuinely in a world, from the port it actually
bound. Only that JVM knows both of those things, which is why the descriptor is published by the
game rather than scraped from a log by whatever started it.

The schema is frozen at version 1. **Eight keys are required**, and a missing one throws rather than
defaulting — a truncated descriptor must never attach to a plausible wrong port:

| Key | What it is |
|---|---|
| `version` | The schema version, `1`. |
| `topology` | The label the consuming project gave this topology. A free string. |
| `loader` | Which loader this game is running. |
| `rpcHost` | The host the RPC socket is on. |
| `rpcPort` | The RPC port **of the face of the JVM that wrote this descriptor**. |
| `worldName` | The world the game is in. |
| `holdPid` | The **game's** own process id, not the launcher's. |
| `writtenAtEpochMs` | When it was written. |

Two optional keys are tolerated. `mcpPort` is the same JVM's Model Context Protocol HTTP port, which
anything asserting about a tool's declared schema needs — bare RPC does not carry the schema catalog.
`serverRpcPort` lets a client-face descriptor point at the dedicated server it is joined to.

Unknown keys are ignored, so a future addition never breaks an older reader.

`rpcPort` being the *writer's* face is the whole reason a topology that holds two processes writes two
descriptors, one per run directory. A UI test wants the client's; a bare-server contract suite wants
the server's. Which file you name is the entire choice.

## What attaching does

```java
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.CLIENT)
class SomeUiTest {
    @Test void opensTheInventory(StageWright tk) { … }
}
```

The extension reads `TESTKIT_ENDPOINT`, falling back to the `stagewright.endpoint` system property,
reads the descriptor at that path, connects, and probes the live game once before handing the
connection over. With an endpoint present the whole attach is bounded at roughly ten seconds — a
five-second connect window plus a five-second liveness probe — before it fails loudly. The far larger
cost sits before the attach: a cold client boot takes tens of seconds, and anything that starts the
topology itself should budget for that.

Where nothing is configured, or the named file is absent, `attach` throws rather than hanging or
reporting a mysterious connection refusal.

**A failed attach is a loud container-level error, never a skip.** The extension attaches eagerly in
`beforeAll` and lets the failure propagate, because the intended reading is "you forgot to start a
hold" and that must not be swallowed as a disabled test. A `StageWright` instance is injected into any
test constructor or method parameter of that type.

**Serial lease.** One held game serves one attaching process. The extension attaches a single shared
instance once per JVM behind a lock, and a failure is re-thrown as the same loud error on every later
use rather than downgraded.

### Face gating

`@RequiresFace(Face.CLIENT)` or `@RequiresFace(Face.SERVER)` on a test class skips it when the
attached game is the other face, with a message naming both — so it reads as "wrong hold", not as
missing coverage.

The face is detected **by asking, not by reading the label**. The descriptor's `topology` is a free
string a consumer chose; whether a client-only call answers is the property every face-sensitive test
actually depends on. One probe per JVM, cached, since a game cannot grow or lose a client while a
test run is attached to it.

A probe failure for any reason *other* than there being no client is re-thrown rather than reported
as a server face. A broken transport quietly reported as a legitimate face would skip exactly the
tests that would have caught it.

That gating is why one command runs whichever half the hold you started can support, and why the
server-only half of the instrument contract stays observable at all.

## One test ends the endpoint's usefulness

The contract test that proves the on-demand suite trigger refuses a second call has to accept a first
one — and accepting it starts the real scene suite, which summons mobs, sets the time, and can bring
a player into a world every other test assumed was empty.

It therefore carries the highest ordering annotation and the module turns class ordering on, so it
lands after everything else. Re-running against the same hold fails with a message telling you to
restart it, rather than looking like a regression in the code under test.

## Consuming the module from another project

```groovy
repositories {
    mavenLocal()
}
dependencies {
    testImplementation 'net.magicterra:mc_stagewright-junit:0.1.0+1.21.1'
}
```

It is a thin plain-JVM library that shades and nests nothing, so its published POM declares its real
compile-time dependencies — a JSON library and the JUnit Jupiter API — and a consumer's build tool
resolves them transitively. This is deliberately the opposite of the mod-jar publications, which nest
their dependencies inside the jar and therefore strip them from the POM.
