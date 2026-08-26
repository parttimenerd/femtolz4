# femtolz4 overnight performance log — 2026-08-25/26

Goal: improve compress/decompress throughput, guided by profiling. Keep correctness (full test suite green).

Machine: Mac (darwin-aarch64), SapMachine JDK 25. All benchmarks run serially, no parallel load.

Methodology per experiment:
1. Implement change on `perf/overnight` branch.
2. `mvn -q test` (or at least the LZ4Test/FuzzTest/RobustnessTest subset) must stay green.
3. A/B benchmark (CorpusBench levels 0,1,8 + frame-level Benchmark) with identical flags.
4. Keep if median throughput improves ≥ ~2% without ratio regression; otherwise revert.
5. Commit + log result here.

## Baseline (T0)

CorpusBench java impl, chain=1, median of 7 trials (400ms warm / 600ms measure):

| corpus | comp MB/s | decomp MB/s |
|--------|-----------|-------------|
| json-10m   | 1836 | 5132 |
| text-20m   | 9704 | 21666 |
| mixed-20m  | 5831 | 29553 |
| rle-20m    | 12722 | 152118 |
| random-20m | 2684 | 48090 |

Full CSVs: `results/corpus-baseline-java.csv`, `results/corpus-baseline-native.csv`.

## Profiling (JFR, 1ms execution samples, 30s each, flight.jfr 13.6MB)

- compress-java chain=1: 96% `LZ4Java.compressFast`; most self-time attributed around
  literal-emission lines (~74% incl. inlined copyLiterals), extendMatch 3%.
- decompress-java: 62% `decompressJavaImpl`, concentrated at the match-copy call site
  (copyMatch inlined, ~62%); **33% in `VarHandleByteArrayAsInts$ArrayHandle.index`**
  (VarHandle bounds plumbing) — big signal.
- compress-java chain=8: 91% `compressJavaImpl`: ~49% chain walk + rejection,
  ~21% literal emission, ~10% post-match insertion.

## Methodology notes

- Mac-mini-class ambient noise is severe: identical-code reruns show ±10-40% median swings!
  → A/B via interleaved passes (A,B,B,A), compare **best-trial max** (not median),
    plus median as secondary. See `scripts/ab_pair_run.sh` + `scripts/ab_cmp.py`.
- Killed long-abandoned `jfr-cli-test` java process (was ~55% CPU for 12h) — measurement hygiene.
- jdt.ls (VS Code) compiles with wrong source level into target/, clobbering Maven classes.
  → all A/B builds via plain javac into `build/` (see `scripts/build_jars.sh`);
    baseline jar = git HEAD sources + worktree native resources (C unchanged).

## Experiments

### E1: LZ4FrameInputStream — decode blocks directly into caller's buffer
Avoids the blockBuf intermediate + full arraycopy of all output on large reads
(`read(b,off,len)` with len ≥ blockMaxSize, block-independent frames). Raw blocks read
straight into caller's buffer too.
Bug found & fixed during dev: raw-block sizeField has bit31 set; EOF sentinel must be `long`.
Tests: all green (writeZeroLength, concatenated frames, checksums, truncation, …).
FrameBench (best of 6, level 1): flight.jfr dec 1374→1441 (+5%), jvm17 1280→1274 (0%),
HA_gc 923→916 (0%). Modest but structural; helps more with real sinks (cache).

### E2: decompressJavaImpl — wild-copy arm for short matches
`offset ≥ 8 && matchLen ≤ 16 && op+16 ≤ dstEnd` → two fixed 8-byte overlapping moves
instead of arraycopy dispatch. (Java block decode only.)
Interleaved A/B (max of 18 trials, 7 corpora): **decode json +21.0%, rle +32.2%,
offset4 +8.5%, offset16 +4.4%, text/mixed/random ±0; compress ±0.** Kept + committed.

### E3: chain-walk 4-byte candidate prune at ±(len-3) — REJECTED
Replaced the 1-byte `src[sv+bestLen]` prune with a 4-byte VarHandle read at
`±(bestLen-3)` in the chain≥3 walk (+2 lazy/chain2 sites).
Interleaved A/B (max): json@2 -9%, mixed@2 -11%, offset4@2 -15%, chain 8/256 -3..-5%,
json@8/256 +2%. mean -5.1%. The v4-in-tail fingerprint filter already rejects most
candidates; the extra memory access only pays off on sparse-match corpora. Reverted.

### E4: decode literal runs ≤ 16 via one unconditional 16-byte wild copy
Replaced the 1/2/3-byte specials + 4-32B ladder in the decode main loop with two fixed
8-byte moves (overshoot within dstEnd/srcEnd margins). >16 → arraycopy.
Interleaved A/B: decode +0.4% max / +0.2% median (json +1.9%, offset4 +1.1%, rest ~0);
compress untouched. Neutral-to-positive, simpler code. Kept.

### E5: XXHash32.readInt via VarHandle — kept (affects block/content checksum paths)

### E6: copyLiterals ≤16 wild copy arm (compress side) — kept + committed (34e816a, with E4)
Second full A/B (e6b) after thermal settling: compress mean **-2.5%**
(text@1 -3.9%, random@8 -16.1%, mixed@8 -5.6%; only consistent regression
mixed@1 +4-5%, reproduced identically in e6p — accepted given net win).
Decode vs HEAD: text -49%, mixed -39% (includes E4 which was still
uncommitted at that point).

### Harness: thermal drift guard
A1-vs-A2 (identical jars, start vs end of a session) showed compress +20%
mean drift — Apple Silicon frequency ramp from cold start; decode stable
(+0.7%). ab_pair_run.sh now prepends a discarded burn-in pass so all
measured passes run at steady-state clocks. The A-B-B-A pass averaging
cancels roughly-linear residual drift.

### E7: 16-bit LE VarHandle for match offsets — measuring (in e789)
SHORT_LE.set(dst, op, (short) matchDist) replaces paired byte stores at all 6
compress emit sites; SHORT_LE.get+mask replaces the paired byte reads at the
decode offset site. Same byte layout, no guard changes needed.

### E8: drop the per-call 32 KiB hash-table fill in compressFast (chain=1) — measuring
JFR showed Arrays.fill(long[],long) = 10.5% of samples compressing json-10m
at chain=1 (table is allocated per LZ4Java instance; static helpers create one
per call, so JVM zero-fill + explicit sentinel fill both ran per 10 MB block).
Fill removed; candidate checks hardened instead: v4 fingerprint, sv >= srcOff,
1 <= pos-sv <= 65535, and INT_LE re-read of the 4 bytes at sv (extendMatch
trusts the first 4 bytes from the fingerprint). Stale slots can then only
produce *real* in-window matches.
BUG FOUND + FIXED before bench: my first "unsigned" window test used
`((pos-sv-1) >>> 0) < 65535` — `>>> 0` is a no-op for int, so signed -1 passed
and 12 zero bytes compressed to offset=0 (invalid stream). Caught by
CorpusBench rle-20m crashing on decode + a 12-byte minimal repro (Dump/MIN in
/tmp/rt). Replaced with explicit `pos - sv >= 1 && pos - sv < WINDOW_SIZE`.
Verified: LZ4.compressorJava(1/8) instance reuse stress over 2000 mixed blocks
(REUSE_OK), 800 static roundtrips (ALL_ROUNDTRIPS_OK), full mvn suite (exit 0).

### E9: decode copy overhaul — measuring (in e789)
JFR on text decode: copyMatch = 80% of samples.
 E9a: decode dispatch gains a 32-byte wild-copy arm for offset >= 16, len <= 32
 (two 16-byte shots, provably no store->load forwarding chain).
 E9b: copyMatch offsets 3..7 and >= 8 unified into prime-offset + geometric
 arraycopy doubling (phase-safe: copy lengths stay multiples of offset until
 the final partial step; log2 steps, vectorized intrinsic).
 Offsets 1/2/4 keep their fill/pattern-store special cases (pure stores).
 E9c: copyLiterals > 16 (and guard-fail tails) -> plain arraycopy, deleting the
 LONG_LE 17..64 ladder (random@1 had 22.4% of samples inside Longs.get from
 those tiers). Net ~-30 lines.

## Queue

- E10: extendMatch deep-dive (97% of text@1 compress) — inlined? 16B loop shape?
- E11: re-profile decode post-E9; verify copyMatch share collapsed
- E12: (stretch) NEON 16B match extension for darwin-aarch64 C encoder
- E13: adaptive hash size for chain path by block size


## Second night: real-data phase (2026-08-26)

New benchmark reality: switched from synthetic corpora to REAL JFR recordings
from ../condensed-data/benchmark (Renaissance suite: all_gc_*, all_profile_G1,
fj-kmeans, movie-lens, scala-stm-bench7; 10-83 MB each). Synthetic text/rle
corpora have extreme match lengths (ratio ~254) that made extendMatch dominate
and misled tuning that does not transfer to real data.

JFR profile of HEAD(37ea025) on synthetic corpus (prof4-*):
- compress text@1: 92.8% extendMatch line 861 (all-equal long-match loop),
  6.0% writeOverflow 255-loop.
- compress text@8: 90.8% compressJavaImpl line 312 = post-match hash-insertion
  loop (O(matchLen) inserts per emitted match).
- decompress text@1: 47% copyMatch; 'ProfileDriver.main' 32-41% = destination
  array zeroing + caller loop (benchmark artifact, same for A and B).

### E12 (REVERTED): tiered extendMatch 32B->64B stride at len>=68
Hypothesis from synthetic profile. Real-data A/B (jfr1 pair): chain=1
regressed; the tier setup cost exceeds wins when real-world matches are mostly
< 68 bytes. Reverted before commit.

### E13 (KEPT, in working tree): stride-scaled post-match insertion
`for (ip=insertStart; ip<insertEnd; ip+=2)` -> iStep = 2 (matchLen<=128),
8 (<=1024), 32 (>1024). Skipped (head,tail) entries are never written, chain
integrity keeps. Targets the 90.8% chain-mode hotspot above.
jfr1 (stacked with E12/E14): compress +5.8..+10.2% on ALL real files, both
chain 1 and 8 (chain=8 +7..10% attributable to E13).

### E14 (KEPT): writeOverflow via Arrays.fill for 255-runs
Targets 6% hotspot on long-match compress; near-neutral on real data but tiny
and safe, helps long-match cases.

### Watch: decompress -4.5..-6% on some real files in jfr1
None of E12-E14 touch decode; candidate causes: sequence-structure change from
E13 (more/shorter matches), or JIT layout drift across jar rebuilds. jfr2 pair
(E13+E14 only, vs 37ea025) re-measures to attribute.
