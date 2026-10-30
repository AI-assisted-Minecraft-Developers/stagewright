// Its top level reaches for the game, which a file read outside the game cannot do.
const player = mc.observe.player();

test("never registered", { scene: "script" }, (t) => {});
