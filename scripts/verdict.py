"""Shared pure-verdict logic for testkit runners (t0 scenes, instrument checks). Semantics frozen by docs/testkit/orchestration-contract-v0.md — do not change judge() behavior without a contract review."""
import json


CANARY_EXPECT = {"MUST_FAIL": "FAIL", "MUST_TIMEOUT": "TIMEOUT"}


def judge(lines, record_type="scene"):
    """Pure verdict from parsed JSONL lines. Returns (exit_code, report_lines)."""
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
    return code, report


def parse(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]
