"""Shared pure-verdict logic for testkit runners (t0 scenes, instrument checks). Semantics frozen by docs/testkit/orchestration-contract-v0.md — do not change judge() behavior without a contract review."""
import json
import sys


CANARY_EXPECT = {"MUST_FAIL": "FAIL", "MUST_TIMEOUT": "TIMEOUT"}


def judge(lines, record_type="scene", expected=None):
    """Pure verdict from parsed JSONL lines. Returns (exit_code, report_lines).

    expected: optional iterable of names that MUST appear in the suite header's
    registered[] (by raw name — this module has no norm() helper today, so
    matching is plain string equality against registered[]'s "name" field).
    Any name absent from registered[] raises code to at least 1 and appends a
    MISSING-EXPECTED report line. expected=None (the default) leaves existing
    behavior byte-for-byte unchanged.

    The check runs in BOTH directions. MISSING-EXPECTED alone (expected minus
    registered) left the manifest's stated purpose half-closed: a scene added to
    the Java provider but never added to the manifest was silently accepted as
    GREEN, which is exactly the "silent-composition hole this gate exists to
    close" the manifest header claims to close. UNDECLARED covers the reverse.

    Scope: only names sharing a NAMESPACE with the manifest are eligible for
    UNDECLARED, where a namespace is the "prefix." of any dotted expected name
    (here: "ad."). That is derived from `expected` rather than hardcoded, and it
    is what keeps the testkit's own built-ins out of it — canaryMustFail /
    canaryMustTimeout / canaryMustSwallow / awaitTicks / floorAssert are
    registered by mc-testkit's Scenes.java for every suite and are deliberately
    not in any loader manifest. Canary records are skipped outright as well,
    since the canary block below is their real gate.
    """
    report = []
    suite, done, scenes, dup_counts = None, None, {}, {}
    for rec in lines:
        if rec["type"] == "suite":
            suite = rec
        elif rec["type"] == record_type:
            # last-wins: a duplicate name lets a later record silently overwrite an
            # earlier one (e.g. FAIL then PASS) and mask a real failure as GREEN —
            # count occurrences per name so that hole gets flagged below.
            dup_counts[rec["name"]] = dup_counts.get(rec["name"], 0) + 1
            scenes[rec["name"]] = rec
        elif rec["type"] == "done":
            done = rec
    if suite is None:
        return 3, ["no suite header — server never armed"]
    if done is None:
        return 1, ["no done footer — harness died mid-run"]

    code = 0
    for name in sorted(n for n, count in dup_counts.items() if count > 1):
        code = max(code, 1)
        report.append(f"DUPLICATE: '{name}' has {dup_counts[name]} {record_type} records "
                       f"— last-wins can mask an earlier FAIL as GREEN")
    if expected:
        expected = list(expected)
        reg_names = {r["name"] for r in suite["registered"]}
        for want in expected:
            if want not in reg_names:
                code = max(code, 1)
                report.append(f"MISSING-EXPECTED: {want} not in registered")
        want_set = set(expected)
        namespaces = {n.split(".", 1)[0] + "." for n in expected if "." in n}
        if namespaces:
            undeclared = sorted(
                r["name"] for r in suite["registered"]
                if r["name"] not in want_set
                and r.get("canary") not in CANARY_EXPECT
                and r.get("canary") != "MUST_SWALLOW"
                and any(r["name"].startswith(ns) for ns in namespaces))
            for name in undeclared:
                code = max(code, 1)
                report.append(f"UNDECLARED: {name} registered but not in the expected manifest "
                              f"— add it to the manifest in the same commit that registers it")
    for reg in suite["registered"]:
        name, canary = reg["name"], reg["canary"]
        rec = scenes.get(name)
        if canary == "MUST_SWALLOW":
            if rec is not None:
                return 2, [f"DEAD: swallow-canary '{name}' was executed — skip gate broken"]
            report.append(f"canary '{name}': correctly omitted (swallow gate alive)")
        elif canary in CANARY_EXPECT:
            if rec is None:
                return 2, [f"DEAD: canary '{name}' has no record — catch gate broken"]
            if rec["outcome"] != CANARY_EXPECT[canary]:
                return 2, [f"DEAD: canary '{name}' -> {rec['outcome']}, expected {CANARY_EXPECT[canary]}"]
            report.append(f"canary '{name}': caught as {rec['outcome']} (expected)")
        else:
            if rec is None:
                code = max(code, 1)
                report.append(f"SWALLOWED: '{name}' registered but never recorded")
            elif rec["outcome"] != "PASS":
                if reg["required"]:
                    code = max(code, 1)
                report.append(f"{'FAIL' if reg['required'] else 'fail(optional)'}: "
                              f"'{name}' -> {rec['outcome']} — {rec.get('reason', '')}")
            else:
                report.append(f"pass: '{name}' ({rec['ticks']} ticks, {rec['wallMs']} ms)")
    drifted = set(scenes) - {r["name"] for r in suite["registered"]}
    if drifted:
        code = max(code, 1)
        report.append(f"DRIFTED: records for unregistered names {sorted(drifted)}")
    declared = done.get("scenes")
    records_by_name_all = sum(dup_counts.values())
    if isinstance(declared, int) and declared != records_by_name_all:
        code = max(code, 1)
        report.append(f"TRUNCATED: done.scenes={declared} but {records_by_name_all} {record_type} records")
    return code, report


def parse(path):
    """Parse a JSONL results file into a list of dicts.

    Undecodable lines are dropped and warned to stderr rather than raising —
    this can only push a verdict further toward RED, never toward a false
    GREEN: a dropped footer line fails the "no done footer" check, a dropped
    scene/check record surfaces as SWALLOWED, and a dropped header line fails
    the "no suite header" ENV check. There is no escape path to GREEN through
    a truncated/corrupted results file. Qualifier: for a third-party harness
    producing a legitimate v0 footer without a `scenes` field, the compound
    edge case of a dropped-bad-line PLUS a duplicate record could in principle
    lose both the DUPLICATE and TRUNCATED signals at once — this repo's
    harness has written `scenes` unconditionally since 936f4f2, so that edge
    case does not apply here.
    """
    records = []
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"[verdict] WARN: dropped undecodable line {i}: {line[:80]!r}",
                      file=sys.stderr)
    return records
