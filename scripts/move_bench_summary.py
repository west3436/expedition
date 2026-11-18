#!/usr/bin/env python3
"""Summarise the move benchmark's results (#8) as a Markdown table.

Reads the move-bench.csv that the expedition_bench GameTest writes, one row per
move, and prints one line per case: the median of each measure over the case's
repeats, except the worst-tick columns, which take the worst repeat. A trip is
two moves, origin to transit and transit to destination, so the trip columns
double the move's.

Usage: python scripts/move_bench_summary.py <move-bench.csv> [more.csv ...]
Needs only Python 3 and its standard library.
"""

import csv
import statistics
import sys
from collections import OrderedDict

MEDIAN = ("ticks", "wall_ms", "work_ms", "idle_server_tick_ms", "alloc_mib", "heap_delta_mib")
WORST = ("max_tick_work_ms", "max_server_tick_ms")


def main(paths):
    cases = OrderedDict()
    for path in paths:
        with open(path, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                cases.setdefault((int(row["side"]), row["fill"], row["dims"]), []).append(row)
    if not cases:
        print("No results.")
        return 1
    print(
        "| side | fill | dims | blocks | block entities | entities | ticks | trip ticks | trip at 20 TPS (s) "
        "| work (ms) | worst tick, mover (ms) | worst tick, server (ms) | idle tick (ms) | allocated (MiB) | runs |"
    )
    print("|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for (side, fill, dims), rows in sorted(cases.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2])):
        med = {k: statistics.median(float(r[k]) for r in rows) for k in MEDIAN}
        worst = {k: max(float(r[k]) for r in rows) for k in WORST}
        first = rows[0]
        ticks = med["ticks"]
        print(
            f"| {side} | {fill} | {dims} | {first['blocks']} | {first['block_entities']} | {first['entities']} "
            f"| {ticks:g} | {2 * ticks:g} | {2 * ticks / 20:.2f} | {med['work_ms']:.1f} "
            f"| {worst['max_tick_work_ms']:.2f} | {worst['max_server_tick_ms']:.2f} "
            f"| {med['idle_server_tick_ms']:.2f} | {med['alloc_mib']:.1f} | {len(rows)} |"
        )
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
