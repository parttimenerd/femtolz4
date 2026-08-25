#!/usr/bin/env python3
"""Compare two CorpusBench CSVs cell by cell.
Usage: ab_cmp.py <base.csv> <new.csv> [op] [median|max]"""
import csv, sys

base_f, new_f = sys.argv[1], sys.argv[2]
opfilter = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
stat_col = 10 if len(sys.argv) > 4 and sys.argv[4] == "max" else 6

def load(p):
    rows = {}
    with open(p) as fh:
        for r in csv.reader(fh):
            if not r or r[0] != "CSV" or r[1] == "operation":
                continue
            if len(r) <= stat_col:
                continue
            op, corpus, chain, impl, mbps = r[1], r[2], int(r[4]), r[5], float(r[stat_col])
            key = (corpus, chain, impl, op)
            # multi-pass files: keep the best per cell (max stat is ambient-noise robust)
            if stat_col == 10 and key in rows:
                mbps = max(mbps, rows[key])
            rows[key] = mbps
    return rows

b, n = load(base_f), load(new_f)
keys = sorted(set(b) & set(n))
print(f"{'corpus':<18} {'chain':>5} {'impl':<6} {'op':<10} {'base':>8} {'new':>8} {'delta':>7}")
tot = 0.0
cnt = 0
for k in keys:
    if opfilter and k[3] != opfilter:
        continue
    d = (n[k] - b[k]) / b[k] * 100
    tot += d
    cnt += 1
    flag = " <<<" if abs(d) >= 3 else ""
    print(f"{k[0]:<18} {k[1]:>5} {k[2]:<6} {k[3]:<10} {b[k]:>8.0f} {n[k]:>8.0f} {d:>+6.1f}%{flag}")
if cnt:
    print(f"mean delta: {tot/cnt:+.1f}%  ({cnt} cells)")
