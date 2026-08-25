#!/usr/bin/env python3
"""Aggregate `jfr print --events jdk.ExecutionSample[,jdk.NativeMethodSample]` output.
Reports (a) top self-frames, (b) top frame histogram over all frames,
(c) self-frame line-level detail for femtolz4 classes.
Usage: topframes.py <jfr-print-output-file or -> [package-prefix]"""
import re, sys
from collections import Counter

path_or_dash = sys.argv[1] if len(sys.argv) > 1 else "-"
prefix = sys.argv[2] if len(sys.argv) > 2 else "me.bechberger.femtolz4"

fh = sys.stdin if path_or_dash == "-" else open(path_or_dash)
sample_tops = Counter()      # top frame of each sample (self time)
all_frames = Counter()       # any occurrence in stack (inclusive)
self_lines = Counter()       # top frames restricted to our package
n_samples = 0
in_trace = False
first_frame = None
seen_in_sample = set()

frame_re = re.compile(r"^\s+([\w.$]+)\(([^)]*)\)(?:\s+line:\s+(-?\d+))?")

for line in fh:
    if line.startswith(("jdk.ExecutionSample", "jdk.NativeMethodSample")):
        if first_frame is not None:
            sample_tops[first_frame] += 1
            for f in seen_in_sample:
                all_frames[f] += 1
            n_samples += 1
        first_frame = None
        seen_in_sample = set()
        in_trace = True
        continue
    if in_trace:
        m = frame_re.match(line)
        if m:
            meth, sig, ln = m.group(1), m.group(2), m.group(3)
            seen_in_sample.add(meth)
            if first_frame is None:
                first_frame = meth
                if meth.startswith(prefix):
                    self_lines[f"{meth} line:{ln}"] += 1
        elif line.strip().startswith("]") or (line.strip() == "" or not line.startswith(" ")):
            in_trace = False

if first_frame is not None:
    sample_tops[first_frame] += 1
    for f in seen_in_sample:
        all_frames[f] += 1
    n_samples += 1

print(f"== {n_samples} samples ==")
print("\n-- SELF (top frame) --")
for m, c in sample_tops.most_common(20):
    print(f"{c:7d} {100.0*c/max(1,n_samples):5.1f}%  {m}")
print("\n-- SELF lines in " + prefix + " --")
for m, c in self_lines.most_common(25):
    print(f"{c:7d} {100.0*c/max(1,n_samples):5.1f}%  {m}")
print("\n-- INCLUSIVE --")
for m, c in all_frames.most_common(20):
    print(f"{c:7d} {100.0*c/max(1,n_samples):5.1f}%  {m}")
