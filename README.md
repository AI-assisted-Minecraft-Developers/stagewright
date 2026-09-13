# StageWright

Cross-loader (Fabric + NeoForge) Minecraft mod test framework. Spec:
`../worlddriver/docs/superpowers/specs/2026-07-16-stagewright-design.md`. Orchestration
contract: `docs/orchestration-contract-v0.md`.

## Testing a modpack (no build tool)

For someone who ships a pack rather than a mod: one jar, one command, no Gradle and no testmod.

```
java -jar stagewright.jar --game-dir <the pack's server dir> --scenes <a folder of .js files>
```

That installs the right StageWright build into the pack's `mods/`, installs the scene files into
`config/stagewright/scenes/`, works out how the pack starts (NeoForge/Forge argument files, or a
Fabric/Quilt server jar), runs it, and judges the results. Exit code is the verdict: `0` GREEN,
`1` RED, `2` DEAD, `3` ENV.

The framework's own jar for both loaders rides inside `stagewright.jar`, so there is no version to
match by hand — the pairing of loader and Minecraft version is the step that fails, and it fails
looking exactly like the mod not working. Other mods the scenes need (a driver whose verbs they
call, the pack's own mod under test) come in through `--mod <jar>`, repeatable. Everything installed
is recorded and removed again on the next run, so upgrading never leaves two copies behind.

Scenes are plain JavaScript and register into the same registry, canaries, and results file the Java
ones do — see [Writing a scene](#writing-a-scene) for the model and `--help` for the rest of the
flags.

### Client topologies without a display or an account

A dedicated server cannot reach anything that only exists on a client — a GUI a mod adds, a screen a
machine opens, the client half of a client/server split. Testing those needs a real client, and a
real client on a build box needs assets, natives, a JVM, and a login. Point the CLI at
[HeadlessMC](https://github.com/headlesshq/headlessmc) and it needs none of that from you:

```
java -jar stagewright.jar --game-dir <a client game dir> \
     --headlessmc headlessmc-launcher-2.10.0.jar --loader neoforge --mc-version 1.21.1 \
     --scenes <a folder of .js files>
```

The first run downloads Minecraft and the loader into that game dir — minutes, once. Later runs
reuse them. The client is genuinely headless (HeadlessMC stubs out every LWJGL call, so there is no
display and no Xvfb) and runs on an offline account, which needs no Minecraft login.

The client creates and enters a singleplayer world (`--world <name>`), so the suite runs on its
integrated server. For the two-JVM shape, see the next section.

A run that ends GREEN still logs three `ERROR`s, and none of them is a problem. Two are an offline
account being an offline account: authlib returns `401` fetching user properties, and Realms rejects
the session. The third is `OpenAL 1.1 not supported`, after which vanilla turns sound off by itself.
There is also a `Couldn't save auto screenshot` warning for the world's `icon.png` — a PNG write, on
a client whose graphics calls are stubs. Do not chase any of the four.

Anything that depends on rendering is meaningless here by construction. Screenshots and pixel
assertions are not a client topology feature under `-lwjgl`; screen structure and input are.

#### …and when the stub is not enough: `--display-client`

The stub scales to a mod. It does not scale to a modpack. Under `-lwjgl` every LWJGL entry point is
replaced, so a mod that reads image pixels while loading gets an all-zero image and throws —
Supplementaries reading a palette strip through Moonlight, measured on All the Mods 10. NeoForge then
dispatches setup a second time trying to recover and the run dies in twenty "already registered"
errors that name nothing relevant; removing that mod surfaces the next one. And the stub cannot be
turned off from the outside: `hmc.offline=true` **forces** it, and says so on the way past — *"You
are offline, game will start in headless mode!"*. So the choice is a real account (next section) or
not going through HeadlessMC's launch at all, which is this one.

Split the two jobs. HeadlessMC installs — that part is genuinely hard and needs no login. Then
launch what it installed yourself, on a real display:

```
java -jar stagewright.jar --game-dir <the same client game dir> \
     --display-client neoforge-21.1.248 \
     --scenes <a folder of .js files> --expect <manifest>
```

The version id is the directory name under `<game dir>/versions`. This is a real GL context with no
stubs, so it needs a display — a desktop, or Xvfb on a build box, which is the same trade the Gradle
plugin's `virtualDisplay` makes. In exchange the pack runs the way a player runs it, and the scenes
that need a connected player finally execute: All the Mods 10's 进度解锁 and 任务领取 had skipped in
every run that ever existed before this.

**A crash on the first client tick is the pack's, not the runner's.** A client ticks throughout its
own loading — `Minecraft.run` calls `runTick` from the first frame — so every mod's client-tick
handler fires while the loading overlay is still up and configs are still being loaded. A mod that
reads a config value there with no guard throws `Cannot get config value before config is loaded` and
takes the launch down, under any launcher, with nothing of StageWright's on the stack. The director
does not force or count those ticks, and the run is reported as the crash it is — the report under
`<game dir>/crash-reports` names the mod. Drop it from the client half or take it up with its author.

**Which file answers which question.** Each topology leaves its logs under its own game dir, and the
two client-side files are not the same log:

| topology | the game's own log | the launcher's output |
|---|---|---|
| server (`--game-dir <server dir>`) | `stagewright-run.log` | — (the CLI runs the server itself) |
| HeadlessMC client (`--headlessmc`) | `logs/latest.log` | `stagewright-headlessmc.log` |
| `--display-client` | `logs/latest.log` | `stagewright-client-launch.log` |

A crash line points at the crash report first and the launcher log second; what the game itself said
on the way down is in `logs/latest.log`.

#### …or give HeadlessMC an account: `--account` / `--online`

Offline is what forces the stub, so an account is what removes it — and then HeadlessMC's launch is
fine for a pack, with a real GL context and a real profile. `--account <id>` makes that account
primary before launching; `--online` uses whichever one HeadlessMC already has selected:

```
java -jar stagewright.jar --game-dir <a client game dir> \
     --headlessmc headlessmc-launcher-2.10.0.jar --loader neoforge --mc-version 1.21.1 \
     --account 0 --scenes <a folder of .js files> --expect <manifest>
```

**Logging in is yours and stays yours.** StageWright never prompts for credentials, never stores a
token, and never writes one to a results file or a log — an interactive login has no business going
through a test runner. Use HeadlessMC's own `login` (a Microsoft device-code flow: it prints a URL
and a code, you approve it in a browser) and `account` to list what it kept. Run those **from the
game dir**, so the credentials land in that dir's `HeadlessMC/auth/.accounts.json` and not in your
real `.minecraft`.

One trap that costs an hour: **Java ignores `HTTPS_PROXY`**. Behind a proxy, both the login and the
run need `-Dhttps.proxyHost` / `-Dhttps.proxyPort` — pass them with `--launcher-jvm "…"`, which puts
them on the launcher JVM (the CLI warns if the environment variable is set and no proxy property is).

Measured on All the Mods 10, launcher-installed: 480 mod jars, 520 in the loader's list, GREEN.

### The shape a player actually plays

The two topologies above are one JVM each. A real game is two, talking over a wire, and that seam is
the only place a mod's halves can disagree — a packet nobody registered, state behind an
`isClientSide`, plain desync. `--with-client` runs both halves from one command:

```
java -jar stagewright.jar --game-dir <the pack's server dir> --scenes <a folder of .js files> \
     --with-client <a client game dir, not the server's> \
     --headlessmc headlessmc-launcher-2.10.0.jar --loader neoforge --mc-version 1.21.1
```

The scenes run on the server — it writes the results, and its exit code is the verdict. The client's
whole job is to be logged in while they do. It is installed with the same framework build and the
same `--mod` jars as the server, because a loader that finds a different mod list on each end refuses
the connection, and it dials `127.0.0.1` at whatever `server-port` the pack's `server.properties`
names.

The server does not start the suite until a player is actually on it. That is what makes a client
which never arrives a timeout you can read, rather than a green run that quietly proved nothing.
Both halves stop themselves — the server halts when the scenes drain, the client closes when the
server drops it — and both are killed anyway on the way out, along with the game process HeadlessMC
leaves behind it, so a failed run cannot strand a Minecraft on the port.

Judging is always about the run this command can see, which is why "a client that joins a server you
started yourself" is not a mode: those scenes run on that server and write their results there, so
the only honest thing this process could report about them is that it cannot see them.

## Writing a scene

A scene is a static method taking a `SceneContext`. `@SceneSet` names the prefix; `@SceneDef` sets
the tick budget and the force-loaded chunk radius. The body runs once, on the scene's first tick, at
an origin the harness picked — scene code never sees absolute coordinates.

```java
@SceneSet("ws")
public final class WaystoneScenes implements SceneProvider {

    @SceneDef(budget = 200)
    static void placingAWaystoneRegistersItInTheDatabase(SceneContext s) {
        s.arena().key('#', Blocks.STONE).at(-6, -6).layer(0, floor).layer(1, air).build();

        var waystone = WaystonesAPI.placeWaystone(s.level(), s.rel(0, 1, 0), DEFAULT).orElseThrow();
        s.record("uid", waystone.getWaystoneUid());

        s.expect(waystone.getPos()).as("recorded position").isEqualTo(s.rel(0, 1, 0));
        s.check(uids(s)).as("the database").contains(waystone.getWaystoneUid());

        s.await(future::isDone).within(200).then(() -> s.expect(...));
    }
}
```

- `expect` fails at the first violation; `check` records it and carries on, so a body probing twenty
  things reports all twenty rather than the first.
- `record(k, v)` attaches a value to the scene's results line **and** to every failure message it
  produces. The failure `reason` is the only diagnostic channel that reliably survives a long run —
  the async logger drops bursts exactly when a suite is finishing.
- `await(cond).within(n).then(...)` is the only way to span ticks. Bodies never block or sleep.
- `cleanup(r)` runs on PASS, FAIL and TIMEOUT alike. Anything server-wide that outlives the scene's
  chunks — a database row, a player's inventory — belongs here, or scene N+1 inherits it.
- `perf().sampleFor(n, w -> …)` and `perf().afterLoading(load, n, w -> …)` measure TPS, mean and p99
  tick interval, peak heap, process CPU, and client FPS, all against a baseline taken moments earlier
  in the same world so the assertion is about the mod rather than the machine.
- `command("setblock ~ ~ ~ …")` runs a command at the scene's origin — so `~ ~ ~` is this arena, not
  the world origin. It returns the command's numeric result and the lines it printed, which makes
  `data get` a way to read state no block lookup reaches, and it throws on any command the game
  rejects, so a typo fails the scene instead of passing silently. It is the widest surface a scene
  file has: everything is strings, so nothing goes through a method name that Fabric spells
  differently.

### Capabilities Minecraft does not have

The facets above cover the vanilla server API. Everything a modpack is actually about lives outside
it, so there is a seam. Four ways in, in ascending order of what they cost you.

#### 0. What is installed: `s.mods()`

The ground floor of everything conditional, and the one thing a registry census cannot answer (a mod
that adds no content has no namespace):

```js
s.mods().loaded('mekanism')       s.mods().version('create')      s.mods().count()
s.mods().require('ae2')           // or record a skip that names the mod
s.mods().any('a', 'b')            // a fork, a rename, a compat shim — one assertion holds them
```

A run where the list never arrived **fails** rather than reporting an empty one: an empty list makes
every mod look uninstalled, so a pack's scenes would all skip citing a mod that is plainly there.

#### 1. Already there: shipped descriptors and generic block capabilities

A pack configures nothing and gets the big ecosystems detected, plus — on NeoForge — the three
platform capabilities every tech mod is built on:

```js
s.capability('itemhandler').insert(0, 0, 0, 0, 'minecraft:diamond', 3);
s.capability('energy').fill(0, 0, 0);
s.capability('fluids').fill(0, 0, 0, 'minecraft:water', 1000);
s.capability('mekanism').probe().callStatic('…');
```

`itemhandler` / `energy` / `fluids` are written against the **capability**, not against a mod.
Mekanism, Thermal, Powah and Industrial Foregoing share no API — they share the interface a hopper
talks to them through, so one facet covers all of them, including mods written after it. Vanilla
containers implement `itemhandler` too, which is what lets the mechanism be proved with no mod
installed at all.

#### 2. Declare one: a `.json` beside your scenes

For a mod nobody wrote an adapter for, when you have a `config/` folder and no build tool:

```json
{ "name": "mymod:rituals", "mods": ["mymod"], "probe": "com.mymod.RitualApi" }
```

```js
s.capability('mymod:rituals').probe().callStatic('lookup');
```

Conditions may be any of `mods` / `anyMods` / `classes` / `items` / `blocks` — the registry ones
being the only way to catch a mod that loaded with its content switched off in config. **At least one
is required**: a descriptor stating none would report itself available everywhere, so scenes gated on
it would run against packs without the thing and fail for a reason that has nothing to do with the
pack. Declaring a name StageWright ships **replaces** ours, which is how a pack whose fork moved a
class fixes it without waiting for a release.

#### 3. Ship an adapter: `CapabilityProvider`

A mod that wants its own machinery testable ships one in its own jar:

```java
public final class RitualsCapability implements CapabilityProvider {
    public String name()                        { return "mymod:rituals"; }
    public boolean availableIn(SceneContext ctx) { return true; }
    public Object facet(SceneContext ctx)        { return new Rituals(ctx); }
}
```

Registered like scenes are, through
`META-INF/services/net.magicterra.stagewright.scene.CapabilityProvider`. Any scene can then reach it,
including a `.js` file in a pack's config folder — no prelude change, no new verb, nothing to import:

```js
if (!s.hasCapability('mymod:rituals')) return;      // or just call it, and let it skip
s.capability('mymod:rituals').consecrate(0, 1, 0);
s.record('capabilities', String(s.capabilities()));  // what this runtime offered
```

**Absent is a skip, not a failure** — the same rule as a scene needing a player on a bare dedicated
server. A mod that is not installed is not the pack's defect, and must not read as a pass either.
The skip message names what the runtime *did* offer, so the results explain themselves a week later.

Write adapters typed, against your mod's real classes. Discovery instantiates each provider inside
its own try/catch, so one that cannot load — because its mod is not in this runtime — is recorded as
absent with the reason and its neighbours are unaffected. That is also why the two adapters
StageWright itself ships (`curios`, `ftbquests`) are reflective and yours should not be: they ride in
the harness jar, which is in every run whether or not those mods are.

#### 4. When you want none of the above: `probe`

A pack author with `.js` files and no build still needs a way in. `s.probe(className)` is guarded
reflection into a **mod's** API:

```js
var api = s.probe('com.some.mod.Api');                  // absent class => skip
s.expect(api.callStatic('lookup', s.player()).asInt()).isAtLeast(1);
```

`net.minecraft.*` is refused — and this is correctness, not caution. A production Fabric jar carries
intermediary names, so `getX` is `method_10263` there and a by-name call would pass on NeoForge and
throw on Fabric. **A mod's own class names are never remapped**, which is exactly what makes the same
technique sound for them. Passing a Minecraft object as an *argument* is fine; that is holding, not
calling. Everything non-primitive comes back wrapped in another `Probe`, so a scene never ends up
holding a bare Minecraft object it might then call a method on, and leaving the wrapper is explicit
(`asString`, `asInt`, `asDouble`, `asBoolean`, `asList`, `isNull`).

A miss names the alternatives on the class, because reflection that only says "no such method" sends
you to a decompiler. The same reflection appearing three times in a pack is a `CapabilityProvider`
waiting to be written — this is a hatch, not an API.

### The pack's own content: recipes, menus, quests, advancements, structures, loot

Capabilities reach a mod's *code*. Most of what a modpack actually is, though, is **data** — 93,834
recipes, 2,490 advancements, 4,712 quests, 888 structures, 41,810 loot tables, in the pack these
numbers were measured on. Six facets read and drive it, and every one is on `SceneContext`:

| facet | reads | drives |
|---|---|---|
| `s.recipes()` | `producing`, `ingredientsOf`, `ingredientSlotsOf`, `uncraftable`, `closureOf` | **`crafts(id)`**, `craftAudit()` |
| `s.menu()` | `hasMenuAt`, `title`, `slotCount`, `item`, `contents` | `openAt`, `put`, `click`, `shiftClick`, `close` |
| `s.advancements()` | `registered`, `has`, `parentOf`, `remaining`, `all`, `allIn` | `grant`, `revoke`, `awaitEarned` |
| `s.quests()` | `loaded`, `chapters`, `allQuests`, `dependenciesOf`, `isComplete`, `canStart` | `complete` |
| `s.structures()` | `registered`, `all`, `allIn`, `at`, `generatedAt`, `locate`, `distanceTo` | — (worldgen places them) |
| `s.loot()` | `exists`, `all` | `roll`, `rollCounts`, `rollTotals`, `distinct` |

**The split down the middle of that table is the point.** Reading data proves the road is
*connected*; running it proves the road can be *walked*, and the two fail apart. A recipe whose
`matches()` rejects its own declared ingredients is registered, has resolvable ingredients, and
`closureOf` walks straight through it — every static check passes and the item is craftable by
nobody. So `crafts(recipeId)` fills a recipe's grid from its own declaration and runs it, and
`craftAudit()` does that to the whole pack.

What `craftAudit()` returns needs its own line, because "failures" is not a bug list. Three
categories of "does not craft" are correct behaviour: a recipe that is **not a crafting grid** (only
`CraftingInput` is constructible from a declaration, so smelting and every tech-mod type is
*skipped*); one that **declares nothing** and computes itself from whatever is in the grid (vanilla
map cloning, armour dyeing, fireworks — auditing those reported five vanilla recipes as broken on a
clean install); and one that **declares everything and never matches on purpose** (ComputerCraft
ships 40 `impostor_*` recipes so JEI can display something the real crafting does elsewhere).

The assertable subset is therefore keyed on the **serializer**, not the type:
`failuresInVanillaTypes()` — a `minecraft:crafting_shaped` recipe is run by vanilla's own matcher and
has no room for "I meant it not to work". Keying it on `RecipeType` instead is vacuous, and was:
every crafting-grid recipe in the game shares the single type `minecraft:crafting`, so the filter
matched nothing and read exactly like a clean pack while 60 recipes failed. `failuresByType()` exists
so that contradiction is visible. Measured: vanilla 887/887; All the Mods 10 45,115 attempted, 45,055
succeeded, 60 failures, **0 in vanilla serializers**.

`s.menu()` has the same honesty problem and the same answer. `openAt` throws, so it cannot be used to
ask *whether* a block is reachable — which is a pack author's first question — hence `hasMenuAt`. Be
ready for the answer: of 14 surveyed blocks in All the Mods 10, **5 expose a menu and 9 do not**, and
3 of the 5 are vanilla. `BlockBehaviour#getMenuProvider` returns null by default and vanilla blocks
only get one from `BaseEntityBlock`; a mod with its own hierarchy usually never overrides it. The
facet asks the block, then falls through to the block entity, and that is as far as a generic opener
can go — Mekanism's `TileEntityMekanism` implements seventeen interfaces and `MenuProvider` is not
one of them, while its capabilities stay perfectly readable. The two seams are independent.

Opening builds the menu **server-side** — the mod's own `createMenu`, its real slots, its real click
handler — and sends no open packet. Sending one asks the client to rebuild the menu from a data
buffer only the opener knows the shape of; a generic caller cannot know, and Actually Additions' coal
generator read a `BlockPos` out of a null buffer and took the client's packet listener down
mid-suite. Nine scenes of thirty-three ran and the verdict blamed coverage.

### The world a scene runs in

Every scene in a run shares one world, and left alone that world moves — so what a scene sees would
depend on how long the scenes before it took, which is a property of the machine rather than of the
test. A run therefore holds four things still, announces the list in the log at suite start, and
writes it into the results header and the verdict (`WORLD: clock=frozen@midnight …`), because a
GREEN that does not say what world it was green in is claiming more than it proved:

| Pinned | Why it is on the list |
|---|---|
| `dayTime` = the scene's clock, default **midnight** | The whole worlddriver suite finishes inside `dayTime`≈130 — sunrise, exactly where sky brightness crosses the threshold vanilla dice-rolls against to decide whether a sun-sensitive mob ignites. Midnight is the only value that is *decided* rather than merely fixed: `isDay()` is false, so the roll never happens. Noon would not buy that. |
| `doDaylightCycle=false` | Setting the time is not enough on its own — a long scene drifts back into the sunrise band it was moved out of. |
| `doMobSpawning=false` | Required *by* the choice of night, not independent of it. Arenas tick entities and the arena audit reports an entity increase inside the box as a leak, so natural hostile spawning would become a fresh source of false REDs. |
| `doWeatherCycle=false` + clear | The audit already treats a rain or thunder flip as a leak, so weather straddling a scene would accuse it. |

`randomTickSpeed`, `doFireTick` and `mobGriefing` are deliberately **not** pinned: a crop that grows,
a fire that spreads, a creeper that craters are things a pack's own scenes legitimately test, and
nothing changes them unless a scene does.

A scene whose subject *is* the time of day says so, and the harness applies it per scene — so one
scene's clock is never a function of the scene before it:

```java
@SceneDef(budget = 200, clock = Clock.NOON)          // daylight is the point of this one
static void phantomsBurnAtDawn(SceneContext s) { … }
```

```javascript
scene('cropGrowsOvernight', 400, function (s) { … }, { clock: 'running' });
```

`Clock.RUNNING` starts at midnight with the daylight cycle actually advancing, for a scene whose
subject is the passage of time; the harness restores the frozen default before the arena audit's
closing snapshot, so the scene is not reported for a gamerule the harness changed on its behalf.

## Three topologies, and why they are not three copies of one

All three run scenes on a **server**. What differs is which server, and whether a real client is
attached to it — so the names say that. ("The client topology" never ran a scene on a client.)

| topology | scenes run on | player | client FPS |
|---|---|---|---|
| `dedicatedServer` | a headless dedicated server | none | — |
| `integratedServer` | the integrated server inside a real game client | yes | measurable |
| `dedicatedServerWithClient` | a dedicated server, with a client joined over multiplayer | yes | — (no client in that JVM) |

Scenes needing a real player call `s.player()` or `s.playerHere()`; where there is none the scene
resolves PASS carrying `skipped:` and the reason, so it is still counted, still reconciled against
the expected-scenes manifest, and visibly did not run. `Perf.Window.fps()` works the same way — a
number under `integratedServer`, NaN elsewhere, guarded with `hasFps()`.

The built-in `remotePlayerIsPresent` scene is what makes the third topology's claim checkable: it
asserts a connected player with a live network connection, and skips where none is expected.

**The world must be the same world.** `integratedServer` creates its singleplayer world with the
settings a dedicated server boots with — survival, easy, cheats on — not the creative/peaceful pair a
sandbox would reach for. It was creative/peaceful once, and the effect was not a warning: hostile
mobs never spawned and a creative player was immune to the damage those scenes assert on, so five of
worlddriver's combat scenes reported timeouts and "mock player refused mobAttack damage" and read
exactly like bot bugs. A topology that differs in gamemode or difficulty is not a second topology, it
is a second product, and every disagreement it reports is about itself.

## Gates

Applying `net.magicterra.stagewright` to a mod project turns each declared topology into one task
that provisions a clean run directory, runs that topology's own dev-run task, and judges the results:

```groovy
stagewright {
    topologies {
        dedicatedServer {
            runTask = 'runStagewrightDedicatedServer'
            expectFile = file('src/testmod/expected-scenes.txt')
            installMods.from configurations.stagewrightRuntime   // see below — not optional on MDG
        }
        integratedServer {
            runTask = 'runStagewrightIntegratedServer'
            virtualDisplay = true          // start an Xvfb on headless Linux; no-op elsewhere
        }
        dedicatedServerWithClient {
            runTask          = 'runStagewrightDedicatedServerWithClient'
            companionRunTask = 'runStagewrightJoiningClient'   // stood up beside it, killed after
            // Judge the companion's own verdict. Not optional in practice — see below.
            companionResultsFile = file('run-stagewright-joining-client/stagewright-client-results.jsonl')
        }
    }
}
```

    ./gradlew stagewrightDedicatedServer     # 0 GREEN / 1 RED / 2 DEAD / 3 ENV

One command each, including the two-process one. No orchestrator, no Gradle-inside-Gradle: the run is
an ordinary task dependency, the companion is a process built from that run task's own resolved
`JavaExec` spec, and a build service owns both so Gradle tears them down on every exit path.

`runTask` takes a task path (`':neoforge:runDogfoodServer'`) when the run lives on a loader
subproject and the gate belongs on the root — which is every multi-loader build.

**`companionResultsFile` reads like an option and behaves like a requirement.** Omitting it is
tempting whenever the companion has nothing of its own to assert — in a third-party mod's client
there is no worlddriver for the built-in probe to read, so the file can only ever say `skipped:`.
That reasoning is correct about the file's *contents* and wrong about its *absence*. Unread, a
missing file is also unread: a companion that dies before joining leaves this topology running
exactly the scenes `dedicatedServer` already runs, and the gate reports GREEN over a run that lost
its whole reason to exist. `stagewrightCoverage` does not catch it either, whenever some other
topology also has a player — which is the normal case, since `integratedServer` has one.

Declared, a missing file is ENV and names itself, and a skip is printed into the gate output where
someone will see it. Both failure modes have now been paid for once: a NeoForge companion that
started and never constructed the driver, and a Twilight Forest client that died on the tick after
joining and turned twelve server-side scenes into skips three log files away from the cause.

A companion also gets `installMods` applied to **its own** run directory, not just the server's.
Before that it launched with an empty `mods/`, which is not an error anywhere: the client boots,
joins, arms nothing, and the run hangs until the server's budget ends it.

### `stagewrightCoverage`: the question one run cannot be asked

    ./gradlew stagewrightCoverage       # after the topologies have run

A scene that needs a player records a **skip** on a dedicated server, and a skip resolves as PASS —
correctly, because a topology without a player is not a defect in the scene. But that also makes it
invisible, and a suite whose player scenes skip on *every* topology it runs reports GREEN over
subjects it has never once executed. All the Mods 10 shipped exactly that: two of the six subjects it
exists to test, registered, reconciled, counted in the footer, and skipped in every run that has ever
existed.

So each verdict now prints `skip:` rather than `pass:` and closes with a `COVERAGE:` census of what
this topology tested nothing about, and this task reconciles the topologies against each other:
**every scene any run registers must have executed in at least one of them.** It is not a list of
what must run where — that list would be maintained by whoever just forgot to update it. Add a scene
and the check covers it the moment it exists.

It deliberately does not `dependsOn` the run tasks: a topology whose verdict is RED aborts the build,
and this report has the most to say precisely then. Run the topologies, then run this. A declared
results file that is not there is reported, not skipped — dropping it would shrink the union of
executed scenes and blame the runs that did happen.

The one exception is declared at the scene, and it is an assertion rather than an excuse:

```java
@SceneDef(budget = 100, mustSkip = true)          // its subject IS the skip
static void absentCapabilityIsARecordedSkip(SceneContext s) { … }
```

The verdict then *requires* the skip and calls the run DEAD if the scene executes — because what
broke in that case is the absence detection every other suite's skips are trusted through.

The CLI has the same check for packs that have no build tool:

    java -jar stagewright.jar --coverage run-a/stagewright-results.jsonl,run-b/stagewright-results.jsonl

### `installMods`: how the harness reaches the game

`installMods` copies jars into the run directory's `mods/` before the game starts — normally just
this framework's loader jar, resolved from a configuration so its version comes from the dependency
block like everything else.

Under **architectury-loom** you do not need it: `modLocalRuntime` already puts a mod jar in front of
FML. Under **ModDevGradle** you do, and leaving it out does not look like a mistake. MDG's dev run
assumes the only mod is yours and offers no equivalent; putting the harness on the runtime classpath
instead — the obvious alternative — gets it *discovered* and then claimed as a plain game library.
FML logs the jar by name, the mod never enters the mod list, and the run boots, ticks, writes no
results and reports **ENV, "the game never armed"**, over a log containing no error and no mention of
StageWright at all.

`mods/` is where `ModsFolderLocator` looks in every run, dev or production, so this is also the only
delivery that puts the exact artifact a player would install into the run. Jars a previous install
left are swept first: the filenames carry versions, so an upgrade otherwise lands *beside* its
predecessor and FML arms one of the two — reporting the old code's behaviour as the new code's.

### Running one scene while you write it

    ./gradlew stagewrightDedicatedServerFabric -Pstagewright.scenes=wd.gearScope
    ./gradlew stagewrightDedicatedServerFabric "-Pstagewright.scenes=wd.client*,pack.*"

`*` is the only metacharacter and matches any run of characters; everything else is literal, so a
name with a `.` in it needs no escaping. Entries are comma-separated and a scene runs if it matches
any of them. The cost of a run is a game boot you pay either way plus roughly a second per scene, so
on worlddriver's 222-scene suite a filter is the difference between a boot and a boot plus five
minutes (231 scenes executed in 4m55s, measured on this box) — which is the difference between
iterating on a scene and batching guesses at it.

**A filtered run is not a gate result, and everything says so.** The pattern is written into the
results header, the game logs it, the plugin logs it, and the verdict label reads
`GREEN (FILTERED — not a gate result)`. Expected-scenes reconciliation is skipped, because under a
filter every unmatched scene is legitimately absent and reporting the whole manifest as missing would
bury the outcome you asked for. Canaries are filtered like everything else, so a narrow run usually
has no framework self-check left in it — the other half of why it must never stand in for a gate.

A pattern that matches **nothing** is RED, not an empty green suite. That typo is the failure that
would otherwise look most like success.

### Holding a topology open

Every topology also gets a `Hold` task: same run, `-Dstagewright.hold=true`, suite armed but not
started, client never closing itself, and a `TESTKIT_ENDPOINT` descriptor published into the run
directory once the game is genuinely in a world.

    ./gradlew stagewrightIntegratedServerFabricHold      # Ctrl-C ends it

It exists for everything that has to assert from OUTSIDE the game — `:stagewright-junit`'s UI tests,
an interactive session, a bare-RPC suite that must not run through the harness it is checking. Those
cannot be scenes, because a scene body runs inside the runtime under test. A hold has no results
file and no verdict: what it produces is an endpoint, and the verdict belongs to whatever attaches.
See **JUnit 5 attach** below.

See `../conformance-mods/README.md` for the six-step recipe for adding this to a mod that has never
heard of StageWright, and for the three third-party mods it is exercised against.

## Which repo the gates run in

StageWright does not test itself. A gate belongs to the **consumer**: it is a task in that
project's build, declared by that project's `stagewright {}` block, driving that project's run task
and judging that project's results against that project's `expected-scenes-*.txt`. You run it from
there.

    cd ../worlddriver && ./gradlew stagewrightDedicatedServerFabric

There is nothing to run from this repo. That used to be a real question — the orchestrators were
Python living here and driving someone else's `gradlew`, with a three-deep fallback chain
(`--project-root` > `$STAGEWRIGHT_PROJECT_ROOT` > `$TESTKIT_PROJECT_ROOT` > cwd) to work out which
build they had been pointed at. Making the gate a task in the consumer's own build deleted the
question along with the chain.

## The scene suite

    ./gradlew stagewright<Topology><Loader>          # 0 GREEN / 1 RED / 2 DEAD / 3 ENV

DEAD means a canary was mis-judged: the framework is broken and the results are void, which is a
different thing from a failing scene and must never be read as one. The verdict task is the sole
verdict authority.

Scenes live in `common/src/main/java/net/magicterra/stagewright/scene/Scenes.java`
(explicit registry = single source for execution AND reconciliation). A scene
body runs once on its first tick, builds an origin-relative arena, asserts, and
may register `ctx.await(cond).within(ticks).then(action)` continuations. Bodies
never block, never sleep, never touch absolute coordinates. **Migration rule
(P1.5a pre-flight)**: a scene body's own synchronous loop (e.g. driving a
Walker in-body for N ticks, as every dogfood `wd.*` scene does) must be
bounded by a fixed tick cap — a scene body is not a test thread, it runs
inline on the server tick, so an unbounded loop hangs the dedicated server
itself, not just the one scene. 每个 `withRequired(false)` 场景必须在 javadoc
引用一个已立案的 task 编号，且在每个阶段验收时重审 optional 名单（防 carve-out
蠕变）。

Status: P1a walking skeleton done. P1b instrument-contract subset landed.
P1c dogfood wave 1 landed (below): downstream mods contribute scenes over
SPI, proven by porting worlddriver's historically-swallowed trio
(`wd.ascendDeadZoneWatchdog`/`wd.ascendMovementNoop`/`wd.diagonalAscentSpeed`)
to `wd.*` scenes running side-by-side with their legacy `@GameTest` twins
(dual-gate A/B; the legacy twins are deleted once both gates go green 3
runs in a row).

## Dogfood run: worlddriver scenes over SceneProvider SPI

The dogfood suite runs on **both loaders** — the canonical acceptance commands
are identical apart from loader name, run task, results path, and manifest
(P1.6 made fabric a first-class dogfood target alongside neoforge):

    cd ../worlddriver
    ./gradlew stagewrightDedicatedServerNeoforge
    ./gradlew stagewrightDedicatedServerFabric

Run task, results path and manifest are no longer arguments — they are the topology's
declaration in the consumer's `stagewright {}` block, which is also what stops a gate from being
pointed at the wrong results file. That failure did not fail fast: every scene ran while the
orchestrator polled a path nothing was writing.

Each boots a full dedicated server with **both** worlddriver and stagewright
loaded (the loader's `build.gradle` run config `dogfoodServer`, `stagewright.autorun`
armed) — this is what proves the gate generalizes beyond its own
bare-bones testkit-`<loader>` module to a real, feature-loaded mod. Same exit
codes as plain T0 above; the suite header's `registered[]` carries the
built-in scenes plus every downstream `wd.*` scene.

The `wd.*` scenes live in `common` behind a loader-injected body-factory seam
(neoforge injects `FakePlayerFactory`; fabric injects a vanilla-only
`AvatarFakePlayer`), so both loaders register the **same** scenes via the **same**
common `SceneProvider` service file. P1.6's dual-loader ×3 determinism matrix
found every `wd.*` scene metric **byte-identical across both loaders** (fabric ==
neoforge; the sole timing variance is `wd.entityLeash`'s await tick count — an
entity-indexing wait sensitive to server startup tick-debt, both within the
`within(180)` liveness bound. The startup tick-debt catch-up burst that made a
tight wall-clock bound flaky is fixed at the source by the harness **settle
barrier** (D1: drains startup tick-debt before arming scenes); `within(180)` is
retained as a pure liveness guard after `within(120)` was falsified by a wild
`TIMEOUT@121` under external box load — task#88 **closed**).

**Wall-clock (130-scene dogfood suite, P4c acceptance, 2026-07-18).** T0 dedicated-server
runs land at **~80 s neoforge / ~75 s fabric** per full-suite run (measured 83.6/81.6 s
neoforge ×2, 73.6/79.6 s fabric ×2), and are byte-identical `(name, outcome)` within each
loader and cross-loader. The T1 integrated-client run pays a one-time client cold boot
(~28-30 s) on top of the suite. The bare-RPC instrument contract is seconds once a hold is up:
it attaches to a server someone already started rather than booting one of its own.

`--expect-file scripts/stagewright/expected-scenes-neoforge.txt` is the **canonical
external-expectation gate** (the fabric manifest `expected-scenes-fabric.txt`
carries the identical governance): a checked-in manifest (one scene name per line,
`#` comments and comma-separated names allowed) naming every `wd.*` scene the
gate expects to see in `registered[]`. Each migrated `wd.*` scene MUST
be added to this file **in the same commit** that adds the scene — the manifest
lives beside the code and reviews with it, so a scene missing from *both* the
file and `registered[]` is exactly the silent-composition hole the gate exists
to close. If the resolved expectation set is empty (file missing, or present but
containing no names after stripping comments/blanks) the gate **fails
loudly** — `--expect-file not found` / `expectation source given but contains no
scene names`, argparse exit 2 — rather than silently degrading to "expect
nothing". See `docs/orchestration-contract-v0.md`'s appendix for why
this is load-bearing (it is the precondition for deleting the legacy
`@GameTest` twins: without it, a broken `ServiceLoader` discovery chain would
silently drop `wd.*` from `registered[]` and the suite would self-consistently
go GREEN on fewer scenes than intended).

`--expect-scene name1,name2,...` remains supported as an **ad-hoc** override for
one-off runs (e.g. asserting a subset while iterating on a single new scene);
when both are given they are **unioned and de-duplicated**. The checked-in
`--expect-file` is the canonical form for acceptance — prefer it so the
expectation set is version-controlled and can never drift from the migrated
scene list.

Downstream mods contribute scenes via the `SceneProvider` SPI in three
lines — see `docs/orchestration-contract-v0.md` for the full
appendix (discovery order, name-uniqueness enforcement, canary ownership):

    public final class WorldDriverScenes implements SceneProvider {
        public List<Scene> scenes() { return List.of(Scene.of("ad.myScene", ..., ctx -> { ... })); }
    }

...discovered via a `META-INF/services` file whose single line names the
implementation. Since P1.6 the provider lives in the loader-shared module so
ONE registration serves every loader; since P4a both the provider class and its
service file live in the `testmod` source set (out of the production jar), e.g.
`common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`:

    net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverScenes

Keep exactly one service file per provider across all source sets — a copy in
a loader module alongside the common one double-registers the provider on that
loader's dev classpath and trips the duplicate-scene-name gate (RED by design).

### Scene library structure (dogfood suite: 171 `wd.*` scenes, by family)

As of P4c the **entire** legacy `@GameTest` suite has been migrated to `wd.*`
dogfood scenes and deleted (`migrate-then-delete`; the drift log
[`../worlddriver/docs/stagewright/migration-log.md`](../worlddriver/docs/stagewright/migration-log.md) records
every retirement). `grep -rn "@GameTest(" common/src neoforge/src fabric/src`
now returns **zero** test-method call sites. The dogfood suite is
**171 `wd.*` scenes** across **15 `SceneProvider` classes** — the original seed
provider, one per migrated family, and two families that never had a legacy twin — all in
`common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/scene/`, all listed
(one line each) in the single common service file
`common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`:

| provider class | family | scenes | migrated from (legacy class) |
|---|---|---:|---|
| `WorldDriverCoreScenes` | Core (main `AgentGameTest`) | 20 | `AgentGameTest` (deleted) |
| `WorldDriverBridgeScenes` | Bridging / pillaring | 19 | — (net-new, no legacy twin) |
| `WorldDriverProcessScenes` | Process core (driver / process / combat) | 16 | `AgentGameTestServer` (deleted) |
| `WorldDriverStationScenes` | Station (craft / smelt / recipe / observe) | 15 | `AgentGameTestServer` (deleted) |
| `WorldDriverSurvivalScenes` | Survival (reflex / autos) | 14 | `AgentGameTestServer` (deleted) |
| `WorldDriverBiasScenes` | Bias (planner cost/constraint) | 13 | `AgentGameTestBias` (deleted) |
| `WorldDriverTerrainScenes` | Terrain | 12 | `AgentGameTestTerrain` (deleted) |
| `WorldDriverWaterBankScenes` | WaterBank | 11 | `AgentGameTestWaterBank` (deleted) |
| `WorldDriverSchedulerScenes` | Scheduler semantics (matrices) | 11 | `AgentGameTestServer` (deleted) |
| `WorldDriverCoverageScenes` | Coverage / instrumentation of the harness itself | 11 | — (net-new, no legacy twin) |
| `WorldDriverWaterCrossScenes` | WaterCross | 10 | `AgentGameTestWaterCross` (deleted) |
| `WorldDriverScenes` | core seed (dogfood wave-1/2a/2b + `wd.entityLeashLowY` task#87 D2) | 10 | (seeded, P1c–P2a; +1 D2) |
| `WorldDriverAvatarScenes` | Avatar (server-body capability) | 5 | `AgentGameTestServer` (deleted) |
| `WorldDriverCombatScenes` | CombatSense | 2 | `AgentGameTestCombatSense` (deleted) |
| `WorldDriverBuildScenes` | BuildBlock | 2 | `AgentGameTestBuildBlock` (deleted) |

**Total 171 `wd.*`.** The migration itself accounted for 131 (10 seed + 121 mapped 1:1); the other 40
were written after it, most of them in the two families with no legacy twin. The whole gate manifest
is larger again — **222 scenes**: these 171 plus **38 `cap.*`** (StageWright's own capability-seam
suite, in `StageWrightCapabilityScenes`) and **13 `pack.*`**. A run registers 232, the difference
being the framework's ten built-ins and canaries.

One legacy arena, `descentDriftArena`,
was retired-without-scene (controller-adjudicated, P4b wave 2 — see migration-log)
and two scenes are net-new (0 legacy twin): `wd.settingRegistryClosed` (P2a) and
`wd.entityLeashLowY` (the task#87 D2 low-Y leash probe, promoted to `required` after
void-moat isolation cleared the engine — see migration-log); the remaining 121 map
1:1. No legacy `@GameTest` class survives —
`AgentGameTestServer`, `AgentGameTestRegistrar` and `AgentGameTestSupport` were all
deleted at the P4c finale.

**Two deliberate optional-FAIL sensors.** Two scenes are registered
`.withRequired(false)` on purpose — they are *visible* live-bug / false-green
signatures, kept red-on-purpose and **never tuned to green** (per the module rule
that every `withRequired(false)` scene must cite a filed task in its javadoc and be
re-audited each acceptance to prevent carve-out creep):

- **`wd.vineClingFidelityProbe`** (WaterBank) — legacy `required=false`; runs
  optional-**PASS** (wall-backed vine cling fidelity).
- **`wd.vineOverWaterClimb`** (WaterBank) — the live **−711** bug against the
  clean `walkerVineFreeHangClimb`-OFF baseline; deterministic optional-**FAIL**
  (`pocketTicks=29`). Its RED *is* the proof the live bug reproduces (task ref:
  the −711 live record cited in the scene javadoc).

**Two D2 sensors closed and promoted to `required`** (both were optional-FAIL
signatures until D2 fixed/cleared their engine debt — they now assert green as
first-class gates):

- **`wd.riverSheerBank`** (WaterBank) — **task#91 CLOSED, promoted to `required`.**
  The gap #48 shared-body FALSE-GREEN it surfaced was a real EXECUTOR gap: A* always
  routed the correct far-lateral exit (low bank +5 EAST across open water), but the
  climb-out executor misread that laterally-distant, only-+1-higher waypoint as a
  climb-HERE intent and trenched the +5 sheer wall (`wallPressTicks≈51`). Fixed
  structurally by `walkerWaterClimbLateralGate` (default ON, baseline-EXEMPT — a
  correctness invariant): the climb-out engages only when the waypoint is horizontally
  BESIDE the bot, so the swim-drive carries it to the real walk-out. K≥6 A/B both
  loaders: gate OFF 6/6 wedge, gate ON 6/6 ashore (byte-identical ARRIVED); 10 sibling
  water families byte-unchanged. Acceptance: `ashore=true wallPressTicks=54` both loaders.
- **`wd.entityLeashLowY`** (core seed) — **task#87 CLOSED, promoted to `required`.**
  A net-new low-Y (y=-58/-59) twin of `wd.entityLeash` built to reproduce the deleted
  `entityLeashRepathArena`'s y≈-60 phase-2 stall. Round-1 RED was a terrain confound;
  void-moat isolation (fill the whole rig footprint to air at low Y) produced GREEN ×6
  **byte-identical with the y=200 twin**, proving the engine has no low-Y defect — the
  legacy stall was **rig-disease**, not an engine bug. Closed, probe kept as a `required`
  low-Y liveness gate. Acceptance: phase2 `reached=true sceneTicks=2` both loaders.

A dogfood run is GREEN with these two optional sensors present (one optional-PASS,
one optional-FAIL) — the acceptance gate
requires all *required* scenes PASS and the `(name, outcome)` set be identical
across runs and loaders, so an optional sensor flipping to green (a silent fix or a
tuned rig) would itself be caught by the cross-run/cross-loader identity check.

**One REQUIRED scene runs a topology-portable validation suite: `wd.agentRpcSmoke`** (task#92,
**closed** in D1). Its JS RPC/YAML validation suite (`WorldDriverCommon.runValidation()`) runs **in
full on both topologies** — the earlier blanket early-PASS guard on any non-dedicated topology was
**removed**. The ~35 client-face checks each self-skip a single "no client" placeholder on the
dedicated path but run their full real branch on integrated, so the suite is **147 checks on dedicated
(T0)** and **259 on integrated (T1/T2)** (integrated ⊃ dedicated — a measured, topology-aware total,
not an assumption). After the worker completes the scene asserts, on whichever topology it is on:
`FAIL == 0` ∧ `TOTAL ==` that topology's expected count (`RPC_SMOKE_EXPECTED_TOTAL_DEDICATED=147` /
`RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED=259`) ∧ every `SKIP(task#92)` result ∈ the named allow-list.
Exactly **one named topology-skip** is sanctioned: `42_combat: melee engage clears a zombie pack` —
full area-clear needs a flat, entity-clean arena, so it records a **counted** `SKIP(task#92)` on the
integrated path (the whole `42_combat` file self-skips on dedicated for want of a client); the offence
itself is covered deterministically by dogfood `wd.serverCombat*`. Pinned by the scene's
`RPC_SMOKE_NAMED_SKIPS` allow-list — not deleted, not swallowed. The `passNote` reports the topology,
the total, and the named-skip list into the results-JSONL `reason`, so a run proves it actually ran
the suite (e.g. T1 `wd.agentRpcSmoke` runs ~800 ticks / ~35 s wall, not an early-PASS).

### How to add a scene (single-place how-to)

Adding one dogfood scene touches at most four spots — do all of them **in the same
commit**, and two independent gates catch a slip:

1. **Write the scene body** in the family's provider class under
   `common/src/testmod/.../scene/` (e.g. `WorldDriverTerrainScenes`), and register
   it in that provider's `scenes()` list:
   `Scene.of("ad.myScene", … , ctx -> { … })` (append `.withRequired(false)` +
   a task-citing javadoc **only** if it is a deliberate optional sensor). A scene
   body runs once on its first server tick, builds an origin-relative arena, and
   asserts; its own synchronous loops **must** be tick-bounded (a scene body is not
   a test thread — it runs inline on the server tick), and it never touches absolute
   coordinates. If it needs a new provider **class**, add one line for it to the
   common service file
   `common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`
   (keep exactly one service file across all source sets — a duplicate trips the
   duplicate-scene-name gate).
2. **Add the scene name to BOTH manifests** —
   `scripts/stagewright/expected-scenes-neoforge.txt` **and**
   `scripts/stagewright/expected-scenes-fabric.txt` (they are identical by construction:
   the scenes live in `common` and register for both loaders through the same
   service file). This is the same-commit rule the manifest exists to enforce.

**The two gates that catch a mistake:** (a) the **`--expect-file` reconcile gate** —
a name in a manifest but missing from `registered[]` (bad service wiring / typo), or
a scene in `registered[]` but absent from the manifest, fails the run loudly instead
of silently degrading (the #85 silent-composition hole); (b) the **duplicate-name
gate** — two providers (or a stray second service file) claiming the same scene name
is RED by design. So a half-done addition — scene added but manifest not updated,
or manifest updated but service file not wired — cannot slip through as a
self-consistent false-green.

## Instrument contract (trust chain)

```bash
# terminal 1 — in the consumer repo
./gradlew stagewrightDedicatedServerFabricHold

# terminal 2 — here
TESTKIT_ENDPOINT=<abs>/stagewright-endpoint.json ./gradlew :stagewright-junit:test --rerun-tasks
```

26 bare-RPC checks against a live dedicated server: what `DriverApi.route` answers, what it
refuses, whether a world write is visible to the driver's own read, whether a tool's advertised
schema is the one the validator enforces. This is the instrument face the scene harness sits on —
green here is the precondition for trusting any scene's setup or assertions. Contract:
`docs/instrument-contract-v0.md`.

**They are out-of-process because that is the whole point.** A scene body runs inside the harness,
on the server thread, through the assertion stack the harness uses to judge itself — so a scene
asserting these would be asking the thing under test to vouch for itself. Here the process is
different, the transport is bare RPC, and the assertion stack is JUnit's, which is three
independent things that all have to break at once to produce a false green. That property is why
this suite could not simply become scenes when the Python that used to run it was deleted.

**Face-gated, not env-gated.** These carry `@RequiresFace(Face.SERVER)` and the UI tests carry
`@RequiresFace(Face.CLIENT)`; the extension probes the live endpoint once (does `mc.client.*`
answer?) and skips the other set with a reason naming both faces. So one command runs whichever
half the hold you started can actually support, and half the instrument contract — that a
client-only verb is refused, that an empty player list reads as `{present:false}` — keeps being
observable, which it is not on any topology that has a client.

**One test ends the endpoint's usefulness.** `ArmedSuiteContractTest` proves the on-demand trigger
refuses a second `mc.test.run`, which requires accepting a first one — and that starts the real
suite. It carries the highest `@Order` and `junit-platform.properties` turns class ordering on, so
it lands after everything else; re-running against the same hold fails with a message telling you
to restart it, rather than looking like a driver regression.

## The client topologies, and what the client asserts for itself

Two of the three topologies put a REAL game client in the run, and both are Gradle tasks — the
`t1.py` / `t2.py` orchestrators that used to stand them up (and `guidrive.py`, which drove the title
screen by label-matched widget clicks over RPC) are gone. `ClientDirector` does that from inside the
client now: "open this world" and "join this server" are one vanilla call each, so the several
hundred lines whose failure modes all lived in the seam between two processes went with them.

    ./gradlew stagewrightIntegratedServerFabric            # a client hosting its own integrated server
    ./gradlew stagewrightDedicatedServerWithClientFabric   # a headless server with a real client joined

**Where a client-only assertion belongs.** Scene bodies run on the SERVER thread. On
`integratedServer` that still reaches client-only state, because both ends share a JVM — a scene can
route `mc.bot.setting` or `mc.test.reset` and read the answer. On `dedicatedServerWithClient` it
cannot: the client is a different process. Anything whose subject is the BOUNDARY between the two
therefore runs in the client's own JVM, as a **client probe**, and writes its own results file:

    <client run dir>/stagewright-client-results.jsonl

The plugin's `companionResultsFile` points a topology's verdict at it, and the worse of the two
verdicts wins — a green server with a red client is a red run, which is the whole point: the server
cannot see what the client sees. A declared-but-missing file is ENV, never a pass.

The probe that exists today is `client.damageSourceAcrossTheWire`. The driver emits `player.hurt`
client-side, mirroring the server's DamageSource off `ClientboundDamageEventPacket`. An integrated
server exchanges that packet through an in-memory connection which never serialises it, so the
scene-side check proves the attribution logic and not the wire. In the client's JVM the packet is
encoded, sent over a socket and decoded before anything reads it — the difference between "works in
singleplayer" and "works on a server". It listens through the driver's own event fan-out, the same
one live push subscribers get, so what it asserts is what an attached agent would have received.

A probe causes its own stimulus rather than waiting on a server-side scene: two harnesses in two
processes agreeing on when something should have happened is a synchronisation problem with no
handshake to solve it. To make that possible the harness ops the player it was waiting for — on the
await-player path the only player present is the companion client the harness itself launched, and a
dev dedicated server ships an empty `ops.json` with `online-mode=false`.

**That probe's subject is worlddriver, and most clients running StageWright do not have one.** A
third-party mod's client has no driver and never should, so the probe reports a **skip** there — a
PASS carrying the `skipped:` prefix and the flag, judged by the same contract as any other, which
makes it count as untested rather than as tested-and-fine. Where the driver IS present it executes
and asserts for real.

Getting that guard right is subtler than it reads. `if (WorldDriverCommon.api() == null)` cannot
answer the question, because naming the class is what makes the JVM load it — the check throws
`NoClassDefFoundError` instead of returning false. So does a field typed `Consumer<DriverEvent>`.
Every worlddriver reference now lives in one class, `DriverFeed`, which tests presence with
`Class.forName(<string>)` and is never loaded at all by a runtime that answers no. The measured cost
of not doing this: a conformance fork's client died on the tick after joining, so the server's suite
ran with no player and skipped twelve scenes, and no log said the two facts were the same fact.

## JUnit 5 attach (out-of-process) — P2c

`stagewright/junit` (`:stagewright-junit`) is a **pure-JVM** JUnit 5 module: no game
classes, no Minecraft on its classpath. Its live UI tests **attach** to an
already-online held topology over RPC, so a UI scene body runs on the JUnit test
thread — free to `await` an asynchronous client screen — instead of inline on a
server tick (the cross-thread blocking rule that kept in-game UI scenes out of
P2b). It is the correct home for the first-batch UI scenes P2b deferred.

**Attach contract.** The module discovers the live topology through the
`TESTKIT_ENDPOINT` environment variable, which names an absolute path to a
descriptor file. The descriptor is a frozen schema-v1
JSON record — `{version, topology, loader, rpcHost, rpcPort, worldName, holdPid,
writtenAtEpochMs}`. `holdPid` is the game's own pid, not the launcher's; `rpcPort` is the face of
the JVM that wrote the file, and `topology` says which face that is. The authoritative key-by-key
semantics live in the **attach appendix** of
`docs/orchestration-contract-v0.md`
（`## TESTKIT_ENDPOINT attach 契约（v0 附录，P2c T1）`）. `Endpoint.parse` rejects
a missing key **loudly** (`IllegalArgumentException`) rather than defaulting it —
a truncated descriptor never attaches to a wrong port.

**Fail-fast.** When no live endpoint is configured — `TESTKIT_ENDPOINT` unset (or
empty) and no `stagewright.endpoint` property, or the named file is absent — `attach`
throws `StageWrightAttachException` rather than hanging or reporting a mystery connection
refusal.

**The producer is the game.** `./gradlew stagewright<Topology>Hold` stands a topology up with
`-Dstagewright.hold=true` and a path to write to; the game publishes the descriptor itself, once it
is in a world, from the port it actually bound. Only that JVM knows both of those things, which is
why the deleted Python holds — which polled a log from outside for the same moment — needed a retry
loop and this does not.

`stagewright.hold` is a property of its own rather than `-Dstagewright.autorun=false`, because
autorun belongs to the host build's run config: overriding it means two `-D`s for one key on one
command line and a silent dependence on which the JVM reads last. When that was the design, the held
game ran all 190 scenes underneath the tests that had attached to it — every UI test then failed
intermittently, on the game moving under them.

**Running them.** Two terminals: one holds the game, one runs the tests against it. The hold ends
when you stop it.

```bash
# terminal 1 — in the consumer repo (worlddriver), any topology
./gradlew stagewrightIntegratedServerFabricHold
# ... [mc_testkit] endpoint descriptor written to <abs>/stagewright-endpoint.json

# terminal 2 — in stagewright
TESTKIT_ENDPOINT=<abs>/stagewright-endpoint.json ./gradlew :stagewright-junit:test --rerun-tasks
```

`--rerun-tasks` because the test task's inputs do not change between runs against a live game, so
Gradle would otherwise report the previous verdict for a run it never made.

**Attach latency budget.** With an endpoint present, `attach()` is bounded at
worst-case **~10s** — a 5s websocket connect window plus a 5s `mc.system.version`
liveness probe — before it fails loudly. The far larger cost sits BEFORE attach: a cold client
boot is ~28-30s; budget for that in any wrapper that starts the topology itself.

**Serial lease.** One held topology serves **one** attach client. The
extension attaches a single shared `StageWright` **singleton** once per JVM (guarded
by a lock; a failed attach is re-thrown as a LOUD container-level error on every
later use, never downgraded to a skip) — no second topology instance, no concurrent attach.

**Two-layer test structure.** The module's tests split into two layers that can
never silently shrink each other:

- **Pure self-tests** (`SelfTest`) — endpoint parse/round-trip, envelope codec,
  `pollUntil` timeout-vs-return, `StageWrightTimeoutException` ≠ `AssertionError` —
  always run; they need no game and no socket.
- **Live UI tests** (`ui.*`) — gated `@EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT)`;
  they run only when a live endpoint is present.

The two attach **fail-fast** self-tests are the symmetric counterpart: they are
`@DisabledIfEnvironmentVariable(TESTKIT_ENDPOINT)`, because the "no endpoint
configured" branch they assert is only reachable when the env is **absent** (with
it present, `attach` succeeds and defeats the `assertThrows`). So the gating is a
mirror, not a hole: **env-off** ⇒ the 2 fail-fast self-tests run + the 6 live UI
tests skip; **env-on** ⇒ the 2 fail-fast self-tests skip + all 6 live UI
tests run. Every test runs in exactly one of the two modes and JUnit reports the
skips honestly — a test can never fall through both gates and vanish.

**When it runs again**, `--rerun-tasks` is MANDATORY: `TESTKIT_ENDPOINT` is an environment
variable, not a gradle task input, so a plain re-run is UP-TO-DATE and silently skips every live
test. The descriptor is loader-agnostic — the identical module attaches to a fabric or a neoforge
client with no code change.

**✅ containerFurnace — task#90 收案（D1）**：`ui.containerFurnace` 要**右键世界里的方块**
打开方块实体容器屏（`FurnaceScreen`），而仪表面曾缺这一维——`mc.client.input.click` 只在
已开屏内点 widget、`mc.client.input.key` 只走键盘绑定（原版「使用/放置」绑右键，`glfwKeyCode`
不映射），唯一能右键世界方块的 `mc.bot.useItem` 是模块纪律禁依赖的行为面 verb。task#90 落了
instrument 级 **`mc.test.input.useOnBlock`**（世界右键，合成 `BlockHitResult` 直调 `gameMode`）
+ 配套 **`mc.test.input.heldKeys`**（持键回读），补上了缺的世界右键维度。`ContainerFurnaceTest`
因此从 `@Disabled` **转为启用**（`@EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT)`），经 attach
在 live 世界真开炉屏。完整证据见 `../.superpowers/sdd/task-2-report.md`（task#90 = D1-T2）。

## The production topology (dedicated + client)

The **production-isomorphic** shape: a **dedicated server** JVM and a **real client** JVM wired over
a genuine multiplayer connection, rather than the integrated single-JVM server the other client
topology hosts. Two worlddriver RPC sockets are live at once, one per face. This is what makes the
server-side checks run against a **dedicated** `PlayerList` holding a real `ServerPlayer` that
arrived over the network, not a FakePlayer stand-in.

    ./gradlew stagewrightDedicatedServerWithClientFabric
    ./gradlew stagewrightDedicatedServerWithClientNeoforge

One command, both processes: the companion client is built from its own run task's resolved
`JavaExec` spec and a build service owns it, so Gradle tears it down on every exit path. The server
holds the suite back until a player has actually joined (`-Dstagewright.awaitPlayer`), which is what
stops the run proving only what the single-JVM topology already proved.

`remotePlayerIsPresent` is the built-in scene that asserts the topology really was two-ended, and
the client's own probe file carries what the server cannot see — see "The client topologies" above.

Both loaders are green here as of 2026-08-08; NeoForge was the last, and what it was RED on is worth
keeping, because nothing in the run said it. The two loaders' dev launchers stage part of the child
environment in a Gradle property rather than on the spec, merged in the first line of their own task
action — which a companion never reaches, being launched *from* the spec. loom's carries
`MOD_CLASSES`, which is how FML in dev learns whose classes are whose. Without it FML still finds the
mod file (its `neoforge.mods.toml` is on the classpath), reads the manifest, prints the mod in the
mod list, attaches no classes, finds no `@Mod` to construct, and carries on: **a mod with no code is
a legal mod, so nothing warns.** The driver was absent from a JVM that listed it. Fabric was green
throughout on identical code, because fabric-loom passes the same information as a `-D` on the
command line, where copying the spec preserves it.

### `mc.test.run` — on-demand scene trigger

T2 does not autorun the scene suite at world-load the way the dogfood/T1 servers
do. Instead the harness, once the dual-end probe passes, calls **`mc.test.run`**
on the **server** face: an on-demand trigger that runs the registered scene suite
and appends its footer to `stagewright-results.jsonl`. It is **idempotent** — a
second call while a run is in flight is rejected by an in-flight latch rather than
starting an overlapping run — and it is the **first testkit consumer of the P2a
`registerVerb` SPI** (the product's own paired-registration entry, dogfooded).
The **autorun path is untouched**: T0/T1 still arm and run at world-entry exactly
as before (the fabric/neoforge dogfood gates regression-prove the footer still
emits on the autorun path), so `mc.test.run` is an additive second door, not a
rewrite of the trigger.

Because production `runServer` now arms the `mc.test.*` verbs (the loader
forwarding is unconditional — see the adjudication note in `TODO.md`), the trigger
is reachable on any dedicated server, not only the testkit run configuration; this
is trust-model-consistent (the RPC surface is already a first-party capability
face) and recorded as a testkit-wiring reclassification rather than a behavior change.

### JUnit attach on T2

The **same** `:stagewright-junit` UI tests attach to this topology with no code change, through a
`TESTKIT_ENDPOINT` descriptor tagged `topology: "dedicated_plus_client"` and carrying one extra
optional key, **`serverRpcPort`** (the dedicated server's RPC port beside the client `rpcPort`). The
frozen schema-v1 required-8 set is unchanged and `Endpoint.parse` tolerates its absence.

`stagewrightDedicatedServerWithClient<Loader>Hold` holds **both** halves and writes one descriptor
per run directory: the server's beside its results, the client's beside the companion's. A UI test
wants the client one — `mc.client.*` exists nowhere else — and a bare-server contract suite wants the
server one, so which file you point `TESTKIT_ENDPOINT` at is the whole choice.

The UI tests remain guarded by `@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT")`: with no
endpoint they skip rather than fail, so a run with the env unset reports coverage it did not have.
Read a green `:stagewright-junit:test` as covering the UI only when the command that produced it set
that variable.

### 世界模板两端一致性声明

Per spec §11, both ends run the **same jar** on the **same machine**: the client
and the dedicated server are the identical worlddriver build (one `./gradlew`
tree, one loader per run), and the T2 world derives from a single per-loader
template archive copied into the server's run directory. There is no cross-machine
version skew and no template divergence between the two ends — the client joins a
server whose world, mod set, and protocol are byte-identical to what the same
checkout would produce standalone.

## Verbs & namespace policy (P2a)

The driver exposes a public paired-registration entry so a mod (or the testkit
runtime) can add its own RPC verb with a schema that is validated identically to
every built-in verb:

    ToolCatalog.registerVerb(schema, handler);   // schema + route, atomically

`registerVerb` installs the MCP `ToolSchema` and the `route()` handler in one
step, so a verb can never exist without a schema — a route reached at dispatch
time with no schema is a loud `IllegalStateException`, not a silent skip (that
was the #280-shaped hole). It also enforces the namespace policy at registration
time (throws on violation):

- `mc.*` — reserved for the driver layer.
- `mc.test.*` — granted to the StageWright runtime, and now granted *wholly*: the
  driver's own `mc.test.yaml` (previously grandfathered into this namespace) was
  retired along with the YAML harness, so the grant has a single claimant.
- `<modid>.*` — everything third-party.
- **Hijack guard**: driver-owned names (the built-in curated + hidden catalog) are
  rejected by `registerVerb` even when the name falls
  inside a granted namespace — a third party cannot shadow a driver verb through
  the paired entry. Within the third-party/extra space, last-wins applies
  (same-classpath trust boundary; two mods colliding on one `<modid>.<verb>` is
  not arbitrated).

`mc.test.reset` is the first consumer of this SPI: a hidden (RPC-only, absent
from MCP `tools/list`) client-entry reset for testkit client-pool reuse — it
releases held movement keys, closes any open screen, clears the chat readback
log, and cancels a residual smooth-look process. Client-only: a dedicated server
rejects it loudly.

#280 is closed on the same wave: `mc.bot.setting`'s schema is now CLOSED
(`additionalProperties(false)`) and built from the single-source
`SettingsRegistry`, so an unknown key is rejected loudly, all-or-nothing (nothing
is applied) instead of being silently dropped. The full contract — namespace
policy, paired-registration semantics, #280 closure, and the four headless
checks (18-21) that pin them — is in the P2a appendix of
`docs/instrument-contract-v0.md`.

## Gradle plugin: task entry points

Superseded — see **Gates** above. The plugin no longer shells a Python orchestrator per topology and
propagates its exit code; it provisions the run directory, runs the topology's own dev-run task,
supervises the companion process, and judges the results itself through `engine/Verdict`. Task names
are `stagewright<Topology><Loader>`, one per declared topology, and the exit legend is unchanged:
`0 GREEN / 1 RED / 2 DEAD / 3 ENV`.

## Gradle plugin: testmod source-set convention (P3b T3)

This is a second, opt-in facet of the same `net.magicterra.stagewright` plugin
whose three task entry points are documented above; the flag lives in the same
`stagewright { }` extension block.

**Why**: tests belong out of the production jar. A mod that ships gametest /
testkit scenes bundled into `main` ships test-only code (and test-only deps)
to players. The convention is a `testmod` source set — compiled separately,
never packaged into the mod jar — that the gradle plugin `net.magicterra.stagewright`
can register on request.

**How**: opt in via the extension flag (default off, zero impact):

    plugins {
        id 'java'
        id 'net.magicterra.stagewright'
    }
    stagewright {
        testmodSourceSet = true
    }

When `java` is applied (checked via a `Plugins.withType(JavaPlugin)` reaction —
see "boundaries" below) and the flag resolves `true`, the plugin registers a
`testmod` `SourceSet` whose compile **and** runtime classpaths extend
`main`'s output directory plus `main`'s own compile/runtime classpaths, so
`testmod` code can see `main` code and all of `main`'s dependencies. The
registered `SourceSet` is exposed read-only as `testkit.testmodSourceSetRef`
(null when the flag is off, or when `java` was never applied) — consumers wire
it into THEIR OWN loom run config, e.g.:

    loom {
        runs {
            client {
                // sketch — exact loom API varies by loom/fabric-loom version;
                // this repo's own testmod migration (LANDED in P4a) is where a
                // concrete wiring is now proven out — see "Realized wiring" below.
                source(testkit.testmodSourceSetRef)
            }
        }
    }

**Boundaries (binding for v1)**:
- The plugin registers the source set and wires its classpath ONLY. It never
  touches loom run configs, never adds dependencies beyond `main`'s own output
  + classpaths, and never changes jar packaging (`testmod` output is not added
  to any jar task). Auto-wiring the source set into a loom run config is
  **v2** scope — loom's run-config API differs enough across versions that
  baking it into the plugin now would be premature coupling.
- No-java-plugin behavior: registration reacts to `Plugins.withType(JavaPlugin.class, ...)`,
  which fires immediately if `java` is already applied, later if it is applied
  afterwards, and never if it is never applied — so a plain non-java consumer
  with the flag left on does not crash; the source set is simply never
  created. The create-or-not decision itself is deferred to `afterEvaluate` so
  it reads the flag's FINAL value regardless of whether `stagewright { }` is
  configured before or after the `plugins { }` block finishes applying this
  plugin.
- worlddriver's own legacy `@GameTest` tests used to live in `main` and
  violated this convention themselves. Migrating them onto `testmod` source sets
  was a parked, separate task at P3b T3 — **it has since LANDED (P4a)**; the
  concrete, per-loader wiring it produced is recorded in **Realized wiring**
  below. P3b T3 landed only the plugin-side building block; P4a proved it out on
  a real dual-loop (fabric + neoforge) mod.

## Realized wiring: worlddriver's own testmod migration (P4a)

P3b T3 (above) is the plugin-side convention in the abstract; **P4a moved
worlddriver's own tests out of the production jars and into `testmod` source
sets**, which turned every "v2 / consumer figures it out" hand-wave above into a
concrete, byte-gated wiring. This section is the standing record of what that
took — it is loader-mechanism reality, not the plugin flag.

> **⚑ Retired at P4-final.** The migration campaign closed at P4c (legacy
> `@GameTest` 130 → 0; every arena is now an `wd.*` scene) and **P4-final retired
> the old dedicated-server run machinery itself** (the manifest, its run config,
> and the two run/reconcile scripts) — deleted in commit `9f506d1` plus the
> P4-final docs close. The full deleted-machinery inventory with commit pointers
> lives in the drift log's P4-final note:
> [`../worlddriver/docs/stagewright/migration-log.md`](../worlddriver/docs/stagewright/migration-log.md). The
> per-loader wiring below is kept as the standing loader-mechanism record, but
> two present-tense details are now **historical**: the `neoforge` and `fabric`
> `testmod` sets no longer hold any `.java` sources — both are **empty-source
> bridges** that carry only `:common`'s scenes into their loom runs (deleting
> either bridge would silently drop all scene delivery). The dogfood suite is
> armed by `-Dstagewright.autorun` on the `runDogfoodServer` run; stagewright is the
> sole test gate.

**Three testmod source sets, hand-wired (not via the plugin flag).** The plugin's
`testmodSourceSet = true` registers *one* source set and wires its classpath
only — it deliberately never touches loom run configs (v2 scope). worlddriver
needs the source set attached to loom runs across **three** modules, so P4a wires
them directly in each `build.gradle`:

- **`common`** — the dogfood scenes (`WorldDriverScenes`) + probes (`SimProbes`)
  + the `net.magicterra.stagewright.scene.SceneProvider` service file. `testmod`
  compile/runtime classpaths extend `main`'s output + `main`'s own classpaths.
- **`neoforge`** — at P4a this held the 8 legacy `@GameTest` arena classes (+
  `Support`); **since P4c all are migrated to `wd.*` scenes and deleted**, so the
  set is now an **empty-source bridge** (retained: deleting it drops scene
  delivery). Same classpath extension, plus `:common`'s `testmod` **output** on
  the compile classpath (so `SimProbes` delegates resolve).
- **`fabric`** — an (always source-empty) `testmod` set that exists purely as
  the run `source` carrier for `:common`'s testmod output+resources — the same
  empty-source-bridge role neoforge's set now also plays.

**The `.scene` sub-package JPMS lesson.** The scenes could **not** stay in
`net.magicterra.worlddriver.bot.stagewright` when moved to `testmod`: `main` still owns
that package (the production verbs `TestResetVerb` / `TestRunVerb` live there and
must ship). A package owned by two source sets that both feed the same mod module
is a **split package** — the loader's module layer rejects it. The fix was to
move the scenes into a dedicated sub-package
`net.magicterra.worlddriver.bot.stagewright.scene` (the service file becomes
`META-INF/services/net.magicterra.stagewright.scene.SceneProvider`). Lesson: when
relocating classes from `main` into a `testmod` set that is folded into the same
mod, they must occupy a package `main` does not also populate.

**loom `named('main')` vs `maybeCreate('main')` — a real mechanism difference.**
Both loaders fold `:common`'s testmod output into the worlddriver mod via loom's
`mods { }` block, but the API call differs by loom platform:

- **neoforge** can write `mods { named('main') { sourceSet …, project(':common') } }`
  — architectury's neoforge path has already created the default `main`
  ModSettings entry by the time the script body runs.
- **fabric** cannot: fabric-loom creates its default `main` entry in an
  `afterEvaluate` that runs *after* this script body, so `named('main')` throws
  *"ModSettings with name 'main' not found"*. fabric therefore replicates loom's
  own default explicitly — `def mainMod = maybeCreate('main'); mainMod.sourceSet
  sourceSets.main` — and only then adds `:common`'s testmod.

**Per-run scoping: fabric runtimeClasspath vs neoforge global modFolders.** How
scene discovery is *scoped to only the runs that want it* also differs:

- **neoforge**: `:common`'s testmod is deliberately kept **off**
  `runtimeClasspath` and delivered only through the `mods { }` `modFolders`
  group. loom emits modFolders only for source sets on a given run's classpath,
  so a run that does not say `source sourceSets.testmod` (e.g. `contractServer`,
  `server`) never receives the scenes. Putting testmod on the global
  runtimeClasspath instead would leak the scenes into *every* run.
- **fabric**: the opposite is safe — `:common`'s testmod output goes directly on
  **this fabric testmod source set's** `runtimeClasspath`, and only runs whose
  `source` is that testmod set carry it. `contractServer` (whose `main` set never
  carries `:common`'s testmod) stays scene-free.

In both loaders the instrument-contract run (`contractServer`) is intentionally
**not** given the scenes — the instrumentation contract is independent of the
dogfood scenes by design.

**Production-jar byte gate — a standing acceptance convention.** Because "tests
belong out of the production jar" is now enforced by structure rather than by
discipline, P4a promoted it to a *gate that every classpath-touching change must
re-run*. After `./gradlew :fabric:build :neoforge:build`, `unzip -l` each
remapped production jar and assert:

- **ZERO** entries for `AgentGameTest*`, `WorldDriverScenes`, `SimProbes`, and
  `META-INF/services/net.magicterra.stagewright.scene.SceneProvider`;
- **still present**: the production verbs `TestResetVerb` / `TestRunVerb` and
  `META-INF/services/net.magicterra.stagewright.StageWrightVerbHook` (the byte gate is
  bidirectional — it also guards against *accidentally deleting* the production
  verbs that P3a deliberately keeps in `main`).

The same assertion is re-run against the **published** mod jars in `~/.m2`
(`publishToMavenLocal`) so the maven face and the build face agree. (The mod
artifacts publish under `worlddriver-*` (the driver mod) and `mc_testkit-*`
(the testkit family) coordinates — the historical `worlddriver-testkit-*`
naming residual was fixed 2026-07-19, see the maven section above.)

**Migrate-then-delete.** Scenes and their legacy `@GameTest` twins are kept side
by side until a scene is proven a byte-faithful replacement, then the twin is
retired in bounded waves. Wave 1 (P4a) retired 8 twins (legacy registered
130 → 122); **the campaign ran to completion — P4b/P4c retired the rest, legacy
`@GameTest` reached 0 at the P4c finale, and P4-final retired the GameTestServer
run machinery itself. stagewright is now the sole test gate.** The full policy,
per-twin provenance, the reframed legacy acceptance formula, and the P4-final
machinery-retirement note live in the drift log:
[`../worlddriver/docs/stagewright/migration-log.md`](../worlddriver/docs/stagewright/migration-log.md).

## Maven publishing (P3b T4)

The **Gates** and **gradle plugin** sections above cover running topologies; this
section covers shipping the framework itself. The
`mc_stagewright-junit` artifact below is what an out-of-process **JUnit 5 attach**
consumer depends on.

`./gradlew publishToMavenLocal` from the repo root publishes seven artifacts
to `~/.m2/repository/net/magicterra/`:

| artifactId | module | POM dependencies |
|---|---|---|
| `worlddriver-common` | root `common` | none (cleansed) |
| `worlddriver-fabric` | root `fabric` | none (cleansed) |
| `worlddriver-neoforge` | root `neoforge` | none (cleansed) |
| `mc_stagewright-common` | `stagewright/common` | none (cleansed) |
| `mc_stagewright-fabric` | `stagewright/fabric` | none (cleansed) |
| `mc_stagewright-neoforge` | `stagewright/neoforge` | none (cleansed) |
| `mc_stagewright-junit` | `stagewright/junit` | gson, junit-jupiter-api |

**Two opposite POM rules, and why.** The six mod-jar publications (root
`subprojects{}` block in the top-level `build.gradle`) nest their runtime
dependencies (Rhino, netty-codec-http, and for the testkit
mod-jars each other) via Jar-in-Jar and are remapped, self-contained
artifacts — a POM that re-declared those deps would hand a naive consumer a
second, unremapped copy of the same classes on their classpath
(`docs/feedback/2026-06-04`, bug #2). So those POMs are stripped of every
`<dependency>` entry and `GenerateModuleMetadata` is disabled outright.
`stagewright/junit` is the opposite case: a thin plain-JVM library with no
shading, so its POM **must** declare its real compile-time deps (gson,
junit-jupiter-api) or a consumer's build tool has no way to resolve them
transitively. Its `.module` Gradle metadata is left enabled (unlike the mod
jars) because, with no Jar-in-Jar split to reconcile, the variant graph and
the POM already agree.

**Naming quirk — FIXED (2026-07-19):** the three
`stagewright/{common,fabric,neoforge}` build.gradle files set
`base.archivesName = 'mc_testkit-*'`, but the root `subprojects{}` publishing
block used to read `artifactId = base.archivesName.get()` with an eager
`.get()` that resolved *before* those child scripts ran, so the published
Maven artifactId came out as `worlddriver-testkit-{common,fabric,neoforge}`.
The root build now defers the read to `afterEvaluate`, so the child override
wins and the published coordinates match the jar file names:
`mc_testkit-{common,fabric,neoforge}` (verified via `publishToMavenLocal`;
POMs remain dependency-cleansed; the stale `worlddriver-testkit-*` mavenLocal
directories were removed — only mavenLocal ever carried them, no remote
consumers existed).

**Consuming `mc_stagewright-junit` from an external Gradle project:**

    repositories {
        mavenLocal()
    }
    dependencies {
        implementation 'net.magicterra:mc_stagewright-junit:0.1.0+1.21.1'
    }

### Support matrix

| | |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.16.14 |
| NeoForge | 21.1.230 line (`[21,)`) |
| Java | 21 |

### Compatibility promise

- The **instrumentation contract v0** frozen surface (see
  `docs/instrument-contract-v0.md` and
  `docs/orchestration-contract-v0.md`) is backward-compatible:
  code written against it keeps working across patch/minor releases of this
  module.
- The **behavioral surface and internal APIs** (scene execution timing,
  internal classes not part of the frozen contract, `StageWrightRpc` wire
  details) carry **no compatibility promise** and may change without notice.
- Published artifact versions track `mod_version` in the root
  `gradle.properties` — there is no independent versioning scheme for
  `stagewright` or `mc_stagewright-junit`.
