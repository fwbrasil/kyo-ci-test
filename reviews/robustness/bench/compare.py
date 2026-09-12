#!/usr/bin/env python3
"""Compares two JMH JSON result files row by row.

    compare.py <base.json> <tip.json> [drift-percent]

Prints every row with base and tip scores (the unit JMH reports), the delta as a percentage of base,
and a marker when the delta lies outside the drift band AND outside the two runs' combined 99.9%
error, which is what makes a row a suspect worth a -f 3 confirmation rather than noise. Rows are
sorted by delta so the extremes are at the ends.
"""

import json
import sys


def rows(path: str) -> dict[str, tuple[float, float, str]]:
    with open(path) as f:
        data = json.load(f)
    out = {}
    for r in data:
        name = r["benchmark"].split(".")[-1]
        m = r["primaryMetric"]
        out[name] = (m["score"], m.get("scoreError", 0.0) or 0.0, m["scoreUnit"])
    return out


def main() -> int:
    base, tip = rows(sys.argv[1]), rows(sys.argv[2])
    drift = float(sys.argv[3]) if len(sys.argv) > 3 else 5.0
    names = sorted(set(base) | set(tip))
    table = []
    for n in names:
        if n not in base or n not in tip:
            table.append((0.0, n, base.get(n), tip.get(n), "MISSING"))
            continue
        (bs, be, unit), (ts, te, _) = base[n], tip[n]
        delta = (ts - bs) / bs * 100 if bs else 0.0
        outside_error = abs(ts - bs) > (be + te)
        mark = "SUSPECT" if abs(delta) > drift and outside_error else ("noise" if abs(delta) > drift else "")
        table.append((delta, n, (bs, be), (ts, te), mark))
    table.sort(key=lambda t: t[0])
    print(f"| row | base | tip | delta | |")
    print(f"|---|---|---|---|---|")
    suspects = 0
    for delta, n, b, t, mark in table:
        if mark == "MISSING":
            print(f"| {n} | {b} | {t} | | MISSING |")
            continue
        (bs, be), (ts, te) = b, t
        print(f"| {n} | {bs:.3f} ± {be:.3f} | {ts:.3f} ± {te:.3f} | {delta:+.1f}% | {mark} |")
        suspects += mark == "SUSPECT"
    print()
    print(f"rows: {len(names)}  drift band: ±{drift}%  suspects: {suspects}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
