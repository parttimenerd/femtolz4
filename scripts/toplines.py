#!/usr/bin/env python3
"""Top-frame line histogram from `jfr print --events jdk.ExecutionSample` output."""
import re, sys

hist = {}
tot = 0
in_stack = False
first_frame_done = False
for ln in open(sys.argv[1]):
    if 'stackTrace = [' in ln:
        in_stack = True
        first_frame_done = False
        continue
    if in_stack and not first_frame_done:
        m = re.search(r'([\w.$]+)\([\w, [\]]*\) line: (\d+)', ln)
        if m:
            key = f'{m.group(1)}:{m.group(2)}'
            hist[key] = hist.get(key, 0) + 1
            tot += 1
            first_frame_done = True
            in_stack = False

for k, v in sorted(hist.items(), key=lambda kv: -kv[1])[:int(sys.argv[2] if len(sys.argv) > 2 else 12)]:
    print(f'{v:7d} {100*v/tot:5.1f}%  {k}')
print(f'{tot:7d} total')
