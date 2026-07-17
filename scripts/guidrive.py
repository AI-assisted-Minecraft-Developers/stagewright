#!/usr/bin/env python3
"""guidrive — reusable widget-tree GUI drive library for the mc-testkit T1 shell.

Extracted from scripts/react_smoke.py and scripts/into_world.py so orchestrators
(t1.py, instrument_client.py) can drive a real Fabric client from the title screen
into a fixed singleplayer world WITHOUT importing those script files. The reason we
copy the pure helpers rather than import react_smoke:

  * react_smoke has module-level side effects (it mkdir's fabric/run/smoke on import)
    and hardcodes RUN_DIR = fabric/run — but T1 runs under fabric/run-t1, so its
    PORT_FILE / discover_port constants point at the wrong directory.
  * The plan mandates "两脚本原文不动,库为增量" — leave the two scripts untouched,
    the library is a pure additive increment. A self-contained module with a
    RUN-DIR-PARAMETERIZED port discovery satisfies both constraints cleanly.

All navigation goes through the AgentDriver WebSocket RPC (mc.client.input.click on
a widget's centre in MC internal coords). NO xdotool / OS-level input, so NO window
manager is required under Xvfb (this is the whole advantage over the pixel-coordinate
approach in smoke-test-client.sh — that script's matchbox/pkill lines are NOT a
pattern to copy).
"""
import asyncio
import json
import time

import websockets


# --------------------------------------------------------------------- RPC --
class Rpc:
    """Minimal JSON-RPC-over-WebSocket client (id-matched request/response)."""

    def __init__(self, ws):
        self.ws = ws
        self.next_id = 1

    async def call(self, method, params=None, timeout=30):
        rid = self.next_id
        self.next_id += 1
        await self.ws.send(json.dumps({"id": rid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(f"rpc {method} timed out after {timeout}s")
            raw = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            msg = json.loads(raw)
            if msg.get("id") != rid:
                continue
            if "error" in msg:
                raise RuntimeError(f"rpc {method} error: {msg['error']}")
            return msg.get("result")


# ----------------------------------------------------------- widget helpers --
def find_widget(node, predicate):
    """Depth-first search for the first widget node where predicate(node) is True."""
    if predicate(node):
        return node
    for child in node.get("children", []) if isinstance(node, dict) else []:
        hit = find_widget(child, predicate)
        if hit is not None:
            return hit
    return None


def by_label(substring):
    """Predicate matching a widget whose ``message`` contains ``substring`` (ci)."""
    s = substring.lower()

    def pred(n):
        msg = n.get("message") if isinstance(n, dict) else None
        return isinstance(msg, str) and s in msg.lower()

    return pred


def by_type(substring):
    """Predicate matching a widget whose ``type`` contains ``substring``."""
    s = substring.lower()

    def pred(n):
        t = n.get("type") if isinstance(n, dict) else None
        return isinstance(t, str) and s in t.lower()

    return pred


async def click_widget(rpc, widget, why=""):
    cx = widget["x"] + widget["width"] / 2
    cy = widget["y"] + widget["height"] / 2
    label = widget.get("message", "<no-label>")
    print(f"  [act ] click '{label}' @({cx:.1f},{cy:.1f})  // {why}")
    return await rpc.call("mc.client.input.click", {"x": cx, "y": cy, "button": 0})


async def wait_until(rpc, predicate, *, label, timeout=120, poll=1.0):
    """Spin observing screen.info until predicate(info) is True."""
    start = time.time()
    last = None
    while time.time() - start < timeout:
        info = await rpc.call("mc.client.screen.info")
        last = info
        if predicate(info):
            print(f"  [wait] '{label}' reached after {time.time()-start:.1f}s "
                  f"(type={info.get('type')})")
            return info
        await asyncio.sleep(poll)
    raise TimeoutError(f"'{label}' not reached in {timeout}s; last info={last}")


# ------------------------------------------------------------- connect flow --
def read_port(port_file):
    """Return the int port in ``port_file`` if present+valid, else None (pure)."""
    try:
        p = int(port_file.read_text().strip())
        return p if p > 0 else None
    except (FileNotFoundError, ValueError):
        return None


async def discover_port(port_file, timeout=300):
    """Wait until ``port_file`` exists and holds a valid port number."""
    start = time.time()
    while time.time() - start < timeout:
        p = read_port(port_file)
        if p is not None:
            return p
        await asyncio.sleep(1.0)
    raise TimeoutError(f"{port_file} never appeared within {timeout}s")


async def connect(port, retries=30):
    """Connect to ws://127.0.0.1:<port>/rpc with retries (RPC binds before the WS
    handler is fully wired). Returns the open websocket."""
    uri = f"ws://127.0.0.1:{port}/rpc"
    last = None
    for _ in range(retries):
        try:
            return await websockets.connect(uri, max_size=8 * 1024 * 1024,
                                            ping_interval=None)
        except Exception as e:  # noqa: BLE001 — retry on any connect error
            last = e
            await asyncio.sleep(1.0)
    raise RuntimeError(f"could not connect to {uri}: {last}")


async def wait_api_ready(rpc, tries=120):
    """Poll mc.client.screen.info until the client API answers (client up at title)."""
    for _ in range(tries):
        try:
            await rpc.call("mc.client.screen.info", timeout=5)
            return
        except Exception:  # noqa: BLE001
            await asyncio.sleep(1.0)
    raise RuntimeError("client api never became reachable")


# ------------------------------------------------------------- world drive ---
async def goto_main_menu(rpc):
    """Dismiss any startup-only screens (welcome/onboarding) → land on TitleScreen."""
    info = await rpc.call("mc.client.screen.info")
    if info.get("type") == "TitleScreen":
        return
    if info.get("type") and "Onboarding" in info["type"]:
        tree = await rpc.call("mc.client.screen.tree")
        btn = find_widget(tree, by_label("Continue"))
        if btn is None:
            raise RuntimeError("Onboarding screen has no Continue widget")
        await click_widget(rpc, btn, why="dismiss welcome dialog")
    await wait_until(rpc, lambda i: i.get("type") == "TitleScreen", label="TitleScreen")


async def goto_singleplayer(rpc):
    """TitleScreen → Singleplayer → SelectWorld (or CreateWorld if no saves)."""
    tree = await rpc.call("mc.client.screen.tree")
    sp = find_widget(tree, by_label("Singleplayer"))
    if sp is None:
        raise RuntimeError("Singleplayer button not found on TitleScreen")
    await click_widget(rpc, sp, why="enter singleplayer flow")
    return await wait_until(
        rpc,
        lambda i: i.get("type") in ("SelectWorldScreen", "CreateWorldScreen"),
        label="SelectWorld or CreateWorld")


async def await_world_list(rpc, timeout=8):
    """The SelectWorld list is read off disk asynchronously; poll until at least one
    WorldListEntry row appears (or the list is confirmed empty). Returns latest tree."""
    start = time.time()
    tree = await rpc.call("mc.client.screen.tree")
    while time.time() - start < timeout:
        if find_widget(tree, by_type("WorldListEntry")) is not None:
            return tree
        await asyncio.sleep(0.3)
        tree = await rpc.call("mc.client.screen.tree")
    return tree


async def open_existing_world(rpc, entry, world_name):
    """Select+open an existing SelectWorld row: click the row (highlights+enables
    Play), then click 'Play Selected World'."""
    await click_widget(rpc, entry, why=f"select existing world '{world_name}'")
    await asyncio.sleep(0.3)
    tree = await rpc.call("mc.client.screen.tree")
    play = (find_widget(tree, by_label("Play Selected World"))
            or find_widget(tree, by_label("Play Selected"))
            or find_widget(tree, by_label("Play")))
    if play is None:
        raise RuntimeError("Play Selected World button not found on SelectWorldScreen")
    await click_widget(rpc, play, why=f"open existing world '{world_name}'")


async def open_create_form(rpc):
    """From SelectWorldScreen click 'Create New World' → CreateWorldScreen."""
    tree = await rpc.call("mc.client.screen.tree")
    cnw = find_widget(tree, by_label("Create New World"))
    if cnw is None:
        raise RuntimeError("Create New World button not found on SelectWorldScreen")
    await click_widget(rpc, cnw, why="open world-creation form")
    await wait_until(rpc, lambda i: i.get("type") == "CreateWorldScreen",
                     label="CreateWorldScreen")


async def set_world_name(rpc, name):
    """Type the fixed name into the CreateWorld name EditBox. END + many BACKSPACE/
    DELETE clears from any cursor position (Ctrl+A over RPC proved unreliable)."""
    tree = await rpc.call("mc.client.screen.tree")
    box = find_widget(tree, by_type("EditBox"))
    if box is not None:
        cx = box["x"] + box["width"] / 2
        cy = box["y"] + box["height"] / 2
        print(f"  [act ] click name field @({cx:.1f},{cy:.1f})  // focus world-name box")
        await rpc.call("mc.client.input.click", {"x": cx, "y": cy, "button": 0})
        await asyncio.sleep(0.2)
    print(f"  [act ] clear + type world name '{name}'")
    try:
        await rpc.call("mc.client.input.key", {"key": "END"})
        for _ in range(32):
            await rpc.call("mc.client.input.key", {"key": "BACKSPACE"})
        for _ in range(32):
            await rpc.call("mc.client.input.key", {"key": "DELETE"})
    except Exception as e:  # noqa: BLE001
        print(f"  [warn] could not clear name field via keys ({e}); typing anyway")
    await rpc.call("mc.client.input.typeText", {"text": name})


async def set_superflat_creative_cheats(rpc):
    """Best-effort: Superflat world-type + Creative game mode + Allow Cheats. Toggling
    cycle buttons via RPC is best-effort; bail gracefully if the layout differs so
    world creation still succeeds (reusable by name is the only hard requirement)."""

    async def cycle_to(prefixes, target, why, max_clicks=6):
        """Click a 'Prefix: Value' cycle button until Value contains target. ``prefixes``
        may be a single label or a list of candidate labels (MC renames toggles across
        versions, e.g. 'Allow Cheats' vs 'Allow Commands')."""
        if isinstance(prefixes, str):
            prefixes = [prefixes]
        for _ in range(max_clicks):
            tree = await rpc.call("mc.client.screen.tree")
            btn = None
            for pfx in prefixes:
                btn = find_widget(tree, by_label(pfx))
                if btn is not None:
                    break
            if btn is None:
                print(f"  [warn] cycle button {prefixes} not found; skipping {why}")
                return False
            msg = btn.get("message") or ""
            if target.lower() in msg.lower():
                print(f"  [obs ] '{msg}' already at target  // {why}")
                return True
            await click_widget(rpc, btn, why=f"cycle {why}")
            await asyncio.sleep(0.2)
        print(f"  [warn] could not reach '{target}' for {why}")
        return False

    _CHEATS = ["Allow Cheats", "Allow Commands"]
    await cycle_to("Game Mode", "Creative", why="set Creative game mode")
    await cycle_to(_CHEATS, "ON", why="enable cheats/commands")
    tree = await rpc.call("mc.client.screen.tree")
    world_tab = (find_widget(tree, by_label("World"))
                 or find_widget(tree, by_label("More")))
    if world_tab is not None and (world_tab.get("message") or ""):
        await click_widget(rpc, world_tab, why="open World/More tab")
        await asyncio.sleep(0.3)
    await cycle_to(_CHEATS, "ON", why="enable cheats (World tab)")
    await cycle_to("World Type", "Superflat", why="set Superflat world type")


async def commit_world_creation(rpc):
    """On CreateWorldScreen, click the action 'Create New World' button (fires gen)."""
    tree = await rpc.call("mc.client.screen.tree")
    cnw = find_widget(tree, by_label("Create New World"))
    if cnw is None:
        raise RuntimeError("Create New World action button not found")
    await click_widget(rpc, cnw, why="commit world creation")


async def respawn_if_dead(rpc):
    """If a DeathScreen is showing, click Respawn. Returns True if it acted."""
    info = await rpc.call("mc.client.screen.info")
    if info.get("type") != "DeathScreen":
        return False
    tree = await rpc.call("mc.client.screen.tree")
    btn = find_widget(tree, by_label("Respawn"))
    if btn is None:
        raise RuntimeError("DeathScreen has no Respawn button")
    await click_widget(rpc, btn, why="respawn the dead player into the world")
    return True


async def wait_in_world(rpc, timeout=240):
    """Wait until fully in-world (no screen, player spawned). A world saved with a
    dead player loads onto a DeathScreen — click Respawn instead of hanging."""
    start = time.time()
    info = None
    while time.time() - start < timeout:
        info = await rpc.call("mc.client.screen.info")
        if info.get("worldOpen") and info.get("hasPlayer") and not info.get("hasScreen"):
            return info
        if info.get("type") == "DeathScreen":
            print("  [act ] dead player on load → Respawn")
            await respawn_if_dead(rpc)
        await asyncio.sleep(0.5)
    raise TimeoutError(f"not in-world within {timeout}s (last={info})")


async def quit_to_title(rpc):
    """Open pause → Save and Quit (or Quit to Title) → wait TitleScreen. Best effort.

    There is no dedicated open-pause RPC route (AgentApi comment: "Inventory / pause
    are reachable via mc.client.input.key"); the ESCAPE key with no screen open
    toggles the pause menu, so we synthesize it."""
    await rpc.call("mc.client.input.key", {"key": "ESCAPE"})
    await wait_until(rpc, lambda i: i.get("type") == "PauseScreen",
                     label="PauseScreen", timeout=30, poll=0.5)
    tree = await rpc.call("mc.client.screen.tree")
    saveq = (find_widget(tree, by_label("Save and Quit"))
             or find_widget(tree, by_label("Quit to Title")))
    if saveq is None:
        raise RuntimeError("Save and Quit / Quit to Title button not found")
    await click_widget(rpc, saveq, why="leave world to TitleScreen")
    await wait_until(rpc, lambda i: i.get("type") == "TitleScreen",
                     label="back to TitleScreen", timeout=120, poll=2.0)
