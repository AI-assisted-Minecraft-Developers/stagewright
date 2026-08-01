"""Cross-platform process/launcher primitives for the testkit orchestrators.

The orchestrators were written on the Linux dev host and hard-coded its process
tooling. On a Windows checkout every one of them died before launching anything:
``./gradlew`` is a shell script, and ``CreateProcess`` refuses it with
``WinError 193: not a valid Win32 application``.

This module is the ONE place that knows about the difference. **Linux behaviour is
unchanged** — same argv, same signals, same semantics as before this module existed;
the Windows branch is the documented equivalent:

===============  ==============================  ==================================
concern          POSIX (unchanged)               Windows
===============  ==============================  ==================================
gradle wrapper   ``./gradlew``                   ``cmd /c gradlew.bat``
process list     ``ps -eo pid,args``             CIM ``Win32_Process`` via PowerShell
hard kill        ``kill -9 <pid>``               ``taskkill /PID <pid> /F``
tree kill        ``killpg(getpgid(pid), sig)``   ``taskkill /PID <pid> /T /F``
detached child   ``start_new_session=True``      ``CREATE_NEW_PROCESS_GROUP``
graceful stop    ``SIGINT``                      ``CTRL_BREAK_EVENT``
liveness probe   ``os.kill(pid, 0)``             ``OpenProcess`` + ``GetExitCodeProcess``
virtual display  ``Xvfb :N`` + ``DISPLAY=:N``    none — renders on the real desktop
===============  ==============================  ==================================

.. warning::
   The liveness row is not cosmetic. On Windows ``os.kill(pid, 0)`` does **not**
   probe — CPython maps any signal that is not ``CTRL_C_EVENT``/``CTRL_BREAK_EVENT``
   onto ``TerminateProcess(handle, sig)``, so the POSIX "signal 0 asks, it never
   kills" idiom silently KILLS the process it was only supposed to ask about. Always
   go through :func:`pid_alive` here.

Nothing in this module requires a third-party package; the Windows paths use
``ctypes`` (liveness) and stock ``powershell``/``taskkill`` (listing, killing).
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys

IS_WINDOWS = sys.platform == "win32"

# Popen kwargs that keep a child's console noise out of ours and, on Windows, avoid a
# console window flashing for every helper we shell out to.
_NO_WINDOW = {"creationflags": 0x08000000} if IS_WINDOWS else {}   # CREATE_NO_WINDOW


# --------------------------------------------------------------------------- launcher

def gradlew_cmd(*args):
    """argv for the gradle wrapper with ``args`` appended.

    POSIX gets the same ``["./gradlew", ...]`` it always had. Windows needs the batch
    wrapper run through ``cmd``: ``CreateProcess`` cannot execute a ``.bat`` directly,
    and a bare ``["gradlew.bat"]`` therefore fails the same way ``./gradlew`` does.

    The ``.\\`` prefix is load-bearing — with a bare name ``cmd`` resolves against PATH
    (and against the current directory only when ``NoDefaultCurrentDirectoryInExePath``
    is unset), which on this box fails with "'gradlew.bat' is not recognized" even
    though the caller set ``cwd`` to the repo root. Spelling the current directory is
    the POSIX ``./gradlew`` intent exactly.
    """
    if IS_WINDOWS:
        return ["cmd", "/c", r".\gradlew.bat", *args]
    return ["./gradlew", *args]


def python_cmd(script, *args):
    """argv for re-invoking a sibling script with THIS interpreter.

    Hard-coding ``python3`` breaks on Windows (no such name) and silently uses the
    wrong interpreter inside a venv. ``sys.executable`` is right on both.
    """
    return [sys.executable, script, *args]


def detach_kwargs():
    """Popen kwargs for a child that must survive Ctrl-C in the parent.

    POSIX: its own session. Windows: its own process group (there are no sessions, and
    a new group is what stops console Ctrl-C from propagating).
    """
    if IS_WINDOWS:
        return {"creationflags": 0x00000200}   # CREATE_NEW_PROCESS_GROUP
    return {"start_new_session": True}


# ----------------------------------------------------------------------- process list

def iter_processes():
    """Yield ``(pid:int, cmdline:str)`` for every process we can see.

    Used by the orchestrators' ``sweep()`` to find leftover testkit JVMs by matching a
    marker (e.g. ``stagewright.autorun``) in the command line, then killing them by explicit
    PID — the repo bans ``pkill``, whose pattern matches the sweeper's own shell.
    """
    if IS_WINDOWS:
        # tasklist cannot show command lines, so a JVM's -D flags are invisible to it;
        # CIM can. One PowerShell call per sweep is cheap next to a server run.
        ps = ("Get-CimInstance Win32_Process | "
              "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
        try:
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=60, **_NO_WINDOW).stdout
        except (OSError, subprocess.SubprocessError):
            return
        # `out` has come back None on a loaded box (observed once, mid-run, right after a
        # game JVM exit). Whatever the cause, a process LISTING must degrade to "found
        # nothing" — its only caller is sweep(), and an exception there aborts the entire
        # orchestrator run, which is far worse than failing to spot a leftover JVM.
        #
        # The explicit encoding above is the other half of that contract. `text=True`
        # alone decoded with whatever default applied and blew up on the first process
        # whose command line was not valid UTF-8 — on a zh-CN box that is routine
        # (observed 2026-07-26: `UnicodeDecodeError: 'utf-8' codec can't decode byte
        # 0xb7 at position 102344`, raised inside subprocess's reader THREAD, so
        # `except (OSError, SubprocessError)` here never saw it: UnicodeDecodeError is
        # a ValueError). Same reasoning applies to the POSIX `ps` branch.
        if not out:
            return
        for line in out.splitlines():
            pid, _, cmd = line.partition("\t")
            pid = pid.strip()
            if pid.isdigit():
                yield int(pid), cmd
        return

    try:
        out = subprocess.run(["ps", "-eo", "pid,args"],
                             capture_output=True, text=True,
                             encoding="utf-8", errors="replace").stdout
    except (OSError, subprocess.SubprocessError):
        return
    if not out:            # same degrade-to-empty contract as the Windows branch above
        return
    for line in out.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2 and parts[0].isdigit():
            yield int(parts[0]), parts[1]


def find_processes(*markers):
    """PIDs whose command line contains EVERY marker. Empty markers match nothing."""
    if not markers:
        return []
    return [pid for pid, cmd in iter_processes() if all(m in cmd for m in markers)]


def process_cwd(pid):
    """A process's working directory, or None when the platform can't tell us.

    Linux exposes it as ``/proc/<pid>/cwd``; t2 uses that as an unambiguous owner
    tie-break (loom launches the forked server with cwd == the run dir). Windows keeps
    it in the process PEB with no supported user-mode read, so this returns None there
    and callers must fall back to a command-line match — a weaker tie-break, which is
    why the fallback is spelled out at the call site rather than hidden here.
    """
    if IS_WINDOWS:
        return None
    try:
        return os.path.realpath(os.readlink(f"/proc/{pid}/cwd"))
    except OSError:
        return None


# ----------------------------------------------------------------------------- killing

def pid_alive(pid):
    """True if ``pid`` is a live process. Never kills anything (see module warning)."""
    if pid is None:
        return False
    if IS_WINDOWS:
        import ctypes
        from ctypes import wintypes
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        STILL_ACTIVE = 259
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            if not k32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == STILL_ACTIVE
        finally:
            k32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True   # exists, owned by someone else


def kill_pid(pid, hard=True):
    """Kill one process by explicit PID. ``hard=False`` asks politely first.

    POSIX: SIGKILL / SIGTERM. Windows: ``taskkill /F`` / ``taskkill`` (no ``/T`` — this
    is the single-PID form; use :func:`kill_tree` when children must go too).
    """
    if pid is None:
        return False
    if IS_WINDOWS:
        cmd = ["taskkill", "/PID", str(pid)] + (["/F"] if hard else [])
        return subprocess.run(cmd, capture_output=True, **_NO_WINDOW).returncode == 0
    try:
        os.kill(int(pid), signal.SIGKILL if hard else signal.SIGTERM)
        return True
    except (ProcessLookupError, ValueError):
        return False
    except PermissionError:
        return False


def interrupt_pid(pid):
    """The documented ``--hold`` release: POSIX SIGINT, Windows CTRL_BREAK_EVENT.

    Windows has no SIGINT delivery to another process; CTRL_BREAK reaches a child that
    was started with :func:`detach_kwargs` (its own process group) and Python turns it
    into ``KeyboardInterrupt`` just like SIGINT — which is what the hold's ``finally``
    teardown is written against.
    """
    if pid is None:
        return False
    if IS_WINDOWS:
        try:
            os.kill(int(pid), signal.CTRL_BREAK_EVENT)
            return True
        except (OSError, ValueError):
            # Not in a group we can signal (e.g. adopted after a pool restart) — the
            # caller's grace loop escalates to kill_pid, same as a POSIX SIGINT that
            # the child ignored.
            return False
    try:
        os.kill(int(pid), signal.SIGINT)
        return True
    except (ProcessLookupError, ValueError):
        return False


class DisplaySession:
    """A display for a GUI client run: ``.env`` to hand the child, ``.close()`` to tear down.

    ``.display`` is the X display number on POSIX and ``None`` on Windows; ``.proc`` is the
    Xvfb ``Popen`` or ``None``. ``close()`` is idempotent so a ``finally`` can call it after
    an early failure already did.
    """

    def __init__(self, env, display=None, proc=None):
        self.env = env
        self.display = display
        self.proc = proc
        self._closed = False

    def close(self):
        if self._closed:
            return
        self._closed = True
        if self.proc is not None and self.proc.poll() is None:
            kill_pid(self.proc.pid)

    # Legacy shim: the call sites used to hold an Xvfb Popen and reach for `.pid`.
    @property
    def pid(self):
        return self.proc.pid if self.proc is not None else None


def display_session(probe=None, display=None, screen="1280x720x24"):
    """Provide a display for a client run — Xvfb on POSIX, the real desktop on Windows.

    This is the one platform difference the module originally missed, and it was the
    FIRST thing every GUI orchestrator did, so on Windows they all died before launching
    anything. Two separate failures, in order:

    1. ``probe`` (t1's ``probe_free_display``) globs ``/tmp/.X11-unix/X*``, which on Windows
       matches nothing, so it returned display 101 — a silently wrong answer, not an error.
    2. ``Popen(["Xvfb", ...])`` then raised ``FileNotFoundError: [WinError 2]``, uncaught.

    On Windows the gradle ``runClient`` task renders on the actual desktop, so the correct
    behaviour is to run with the inherited environment and no display server at all. The
    POSIX branch is byte-identical to the ``start_xvfb`` it replaces (same argv, same
    socket poll, same errors), and ``probe`` stays a caller-supplied callable so the
    display-picking logic keeps living next to its own self-tests in t1.
    """
    if IS_WINDOWS:
        print("[display] Windows: no virtual display — the client renders on the real "
              "desktop (do not minimize the game window; screen capture uses gdigrab)")
        return DisplaySession(dict(os.environ))

    d = display if display is not None else (probe() if probe is not None else 99)
    sock = f"/tmp/.X11-unix/X{d}"
    proc = subprocess.Popen(
        ["Xvfb", f":{d}", "-screen", "0", screen,
         "-ac", "+extension", "GLX", "+render", "-noreset"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    import time as _time
    for _ in range(50):
        if os.path.exists(sock):
            print(f"[display] Xvfb up on :{d} (pid={proc.pid})")
            return DisplaySession(dict(os.environ, DISPLAY=f":{d}"), d, proc)
        if proc.poll() is not None:
            raise RuntimeError(f"Xvfb :{d} died on startup (rc={proc.returncode})")
        _time.sleep(0.2)
    proc.terminate()
    raise RuntimeError(f"Xvfb :{d} socket never appeared")


def kill_tree(proc, sig=None):
    """Kill a Popen AND its children (the gradle wrapper spawns the game JVM).

    POSIX keeps the original ``killpg(getpgid(pid), sig)``; Windows uses
    ``taskkill /T``, which walks the child tree by parent PID.
    """
    if proc is None or proc.poll() is not None:
        return
    if IS_WINDOWS:
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       capture_output=True, **_NO_WINDOW)
        return
    try:
        os.killpg(os.getpgid(proc.pid), sig or signal.SIGTERM)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.terminate()
        except OSError:
            pass
