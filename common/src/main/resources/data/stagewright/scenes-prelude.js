// Loaded into the scope of every scene file under config/stagewright/scenes/ before that file
// runs. Defines the registration surface and the sugar that makes a scene readable; everything
// else a scene body touches is the Java SceneContext, reached directly through Rhino.
//
// Kept deliberately small. Each addition here is a thing a pack author has to learn that a mod
// author does not, and the point of the JS surface is that it is the SAME framework — same
// harness, same canaries, same results file — not a second one with its own vocabulary.

var __scenes = [];

// scene(name, budgetTicks, body[, options])
//
// budgetTicks is how long the scene may take in SERVER TICKS, not milliseconds — a scene is not a
// test thread, it runs inline on the tick loop. Anything that needs time to happen goes through
// s.await(...), never a loop.
//
// options.terrain picks the ground the arena is built on:
//   'run_world'  (default) empty sky at the grid altitude — you build every block you assert about
//   'superflat'  a bedrock/dirt/grass plain, arena at the surface
//   'generated'  a normally-generated overworld — hills, caves, water, trees — arena at the surface
// The last two are separate dimensions StageWright ships, so they are the same whatever world type
// the run itself was launched with, and 'generated' uses a fixed seed so the landscape under a
// given scene is the same every run.
function scene(name, budgetTicks, body, options) {
    if (typeof name !== 'string' || !name) throw new Error('scene() needs a name');
    if (typeof budgetTicks !== 'number') throw new Error("scene '" + name + "' needs a tick budget");
    if (typeof body !== 'function') throw new Error("scene '" + name + "' needs a body function");
    var opts = options || {};
    __scenes.push({
        name: name, budgetTicks: budgetTicks | 0, body: body, optional: false,
        terrain: opts.terrain ? String(opts.terrain) : 'run_world'
    });
}

// A scene that may fail without failing the run. For a defect you have accepted and want watched
// rather than fixed — it still runs, still reports, and still shows up in the results.
scene.optional = function (name, budgetTicks, body, options) {
    scene(name, budgetTicks, body, options);
    __scenes[__scenes.length - 1].optional = true;
};

// console.log inside a scene body goes to the server log, prefixed with the scene's name. There is
// no captured-log envelope here the way there is for an ad-hoc eval: a scene reports through its
// assertions and its recorded values, and a log line is for the human reading the run afterwards.
var console = {
    log:   function (m) { __bridge.log(String(m)); },
    error: function (m) { __bridge.log('[err] ' + String(m)); }
};

// Blocks are named, never constructed: block('minecraft:stone'). Unknown ids fail loudly at the
// point of use rather than resolving to air, which is what a silent lookup would do and is
// indistinguishable from "the scene placed nothing".
function block(id) {
    return __bridge.block(String(id));
}
