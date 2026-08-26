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

### E15 (REJECTED): replace wild-copy arms with arraycopy in decode
-35% decode on real data — arraycopy dispatch cost for 4-16B copies >> VarHandle
pair. The VarHandles really are the fast path; the 46-79% ArrayHandle.index
samples are their (irreducible-without-Unsafe) bounds plumbing, not a bug.

### E17 (REJECTED): single 8B move arms for len<=8 literals/matches
-5.2% decode on full jfr4 pair. Halving accesses didn't pay: probably branch
shape/inlining of the added tier hurt more than the saved move.

### E18 (KEPT f5e3eed): decompressJava wrapper skips Arrays.copyOf tail
n==decompressedSize is the norm -> return dst directly. +3.2-4.2% on the
array-returning decode API (ProfileDriver on all_gc_SerialGC). CorpusBench
bypasses the wrapper so the change is invisible there.

### E19 (REJECTED): chain-walk early exit when len>=48
Slower AND worse ratio on real JFR data: -6.1% compress mean, ratio
-0.36..-0.48% on two files. Deep search pays off on real repetitive streams.

### E20 (measuring): copyMatch pattern arms for offset==8 and offset==16
Real binary payloads (fj-kmeans: double lattices; JFR: pointer/event-size
lattices) have exact-8/16 periodic overlap matches; pure-store loops avoid the
geometric arraycopy's log(len/offset) intrinsic dispatches.
prof6: fj-kmeans decode@8 had 87.7% of samples inside copyMatch:928 (the
geometric loop end), so the whole decode spends its time materializing overlap
matches.

## Big-file benchmark corpora + noise protocol

corpora-big/: 3 x 160MB slices of renaissance-all_gc_details_{ZGC,G1,SerialGC}.jfr
(real JFR data, ratio ~2.7-3.6 at chain 1/8). ab_bench.sh now adds -Xmx4g,
trials=13. ~6 min per pass; ab pair = burnin+A1,B1,B2,A2 ~= 30 min.

Noise calibration: decompress columns serve as null-control when a change only
touches compress. Thermal drift measured up to +-12% within a single pair run
(big1: identical decode code showed +5.2% mean, +12.6% max delta).
Conclusions drawn only when |compress delta| > |same-cell decode delta| by a
clear margin, or when direction is consistent across >= 2 independent pairs.

### Findings
- E12-style tiered extendMatch: regressed on 10MB JFR files (jfr2/large
  mismatch-count distribution); needs big-file re-eval (E21, big2 pair).
- Commit 293accf stride-scaled insertion changes chain>=2 output slightly
  (-0.1%% ratio) by design; chain-1 output unchanged. Verified deterministically
  by byte-diffing compressed streams of both jars (jfr2/big1).

## Cumulative: orig(51f3a6d) -> HEAD(49941d3) on real JFR corpora (DualBench median)

| corpus | c@1 | c@8 | d@1 | d@8 |
|---|---|---|---|---|
| all_gc_SerialGC | 0.991 | 0.934 | 1.331 | 1.378 |
| all_gc_G1 | 1.035 | 0.866 | 1.474 | 1.430 |
| fj-kmeans | 1.295 | 0.876 | 1.175 | 1.256 |
| movie-lens | 1.041 | 0.854 | 1.241 | 1.325 |
| all_profile_G1 | 0.981 | 0.917 | 1.299 | 1.337 |

Decode: +18..+47% across the board (mean ~+33%). Compress@1: up to +30%
(kmeans), neutral elsewhere. Compress@8: -7..-15% with byte-identical output —
tracked to execution-shape drift (inlining/layout) on this host; E31
(byte-store offsets) and stride/fill variants all measured WORSE, so kept.

Day-3 lesson: full-pair A/B on this box is unusable (Defender on-access scans,
coline noise +-40% per pass). DualBench (same JVM, URLClassLoader-separated
jars, alternating 1.2-2s windows, median ratio) has a +-2% same-jar floor and
resolved multiple long-standing misattributions overnight.

## E33 (KEPT): skip controller end-step 17 -> 33
DualBench: random-20m@1 +27..+61% (ratio identical), mixed-20m@1 +27%
(ratio marginally better), serial-gc@1 neutral byte-identical. Commit b65440f.

## Native reference points (lz4_src.c, -O3, this host)
compress: native serial@1 1015 MB/s (java ~700), serial@8 204 (java ~60),
gc_G1@1 761 (java ~360), gc_G1@8 161, profile@8 261 (java ~120).
DECODE: native 958-1488 MB/s vs java 2100-3300 — java decode is 1.5-2.5x
FASTER than the bundled native build everywhere measured.
compress headroom remains (up to ~3x at chain 8); gap is per-candidate
VarHandle/index overhead in extendMatch, not structure.

## Level 10 (optimal parser) on real data — verified + documented
serial-gc: 2.192 -> 2.226 (+1.5%), 45 -> 13 MB/s
profile:   3.113 -> 3.194 (+2.6%), 104 -> 16 MB/s
movie-lens:2.731 -> 2.790 (+2.1%), 110 -> 12 MB/s
Round-trip verified (RT OK). README documents level 10.

## E37 (KEPT): literal-guard skip when litLen == 0 in decode
Match-dense data (JFR, JSON) has mostly empty literal runs; the long-form
output/input guards cost 2 speculated branches per sequence. Moved guards into
the non-zero branches only. DualBench decode: serial-gc@8 +9.8%, gc_G1@1
+8.6%, serial-160m@1 +6.2% (all windows consistent). Both tests+RT green.

## E36 (REJECTED): adaptive fast hash table for big inputs
2^14 slots: ratio +1.2% but speed -6%; 2^17: ratio +1.7%, speed -19% on
jfr-gcdet-zgc-160m — probe cache misses outweigh fewer collisions. Fixed 2^12
fill-free table stands for all sizes.

## New big corpora staged (bench-data/corpora-big)
160MB slices x3 JFR gc_details; wat-jfrtofp-160m (structured text,
ratio 6.5/9.8); gitpack-79m (zlib blobs, ratio ~1.0); cjfr-gcdet-g1-41m
(pre-compressed JFR, ratio 1.04); onnx-t5-40m (binary floats, ratio 1.04/1.17).

## E38 (KEPT): match output-overflow guard elided in wild-copy arm
The generic op+matchLen>dstEnd guard ran for every sequence; the wild-copy arm
(offset>=8 && len<=16 && op+16<=dstEnd) already implies it. Guard moved into
the arraycopy/copyMatch arms only. DualBench decode: serial-gc@8 +6.3%
(on top of E37's +9.8%), serial-160m@1 +11% — windows consistent.

## FINAL confirmed improvements (HEAD vs orig 51f3a6d, DualBench medians)
| cell | B/A |
|---|---|
| decode serial-gc @8 | +54.3% |
| decode gc_G1 @1 | +54.2% |
| decode serial-160m @1 | +24.4% |
| decode movie-lens @1 | +30.2% |
| decode onnx-t5 @1 | +38.8% |
| decode gitpack @1 | +8.0% |
| compress zgc-160m @1 | +2.6% (ratio identical) |
| compress wat-160m @1 | +19.3% (ratio identical) |
| compress mixed/random @1 (earlier E33) | +27% / +61% |

## E42 (KEPT): memset table + sentinel instead of fill-free stale checks
Pre-clear fastHead with EMPTY_SLOT per call; probe guard collapses from
(v4-match && sv>=srcOff && delta>=1 && delta<WINDOW && src re-read) to
(v4-match && delta<WINDOW). Ratios identical everywhere; json-10m@1 +10.8%,
wat-160m@1 +3.8%, serial-gc@1 neutral. Tests+RT green.

## E43 (KEPT): lazy probe-2 (defer h1/slot1 until probe-1 misses)
Speculating the pos+1 probe costs 2 loads + a multiply per iteration even on
the dominant probe-1-hit path; deferring it wins on long-literal corpora
(serial-gc +19%, json-10m +19%) at a -5% cost on ultra-fast text (wat,
already 1.4 GB/s). Outputs byte-identical; tests+RT green.

## E44/E45 (REJECTED): chain candidate-loop restructure
Hoisted pos-invariant maxMatch + folded the duplicated miss-path exit into one.
Byte-identical output but -24% compress@8 on wat-160m (4/4 windows). C2 clearly
schedules the two-exit shape better; reverted.

## E46 (REJECTED): wider lazy matching at chain>=8
lazyLimit 8->16, lazyDepth 2->4. wat@8: ratio +1.1% for -1.5% speed (ok-ish),
but serial-gc@8: +0.25% ratio for -18% speed. Reverted; level 10 remains the
max-ratio answer.

## E47 (REJECTED): drop src[sv+bestLen] prefilter in chain walk
extendMatch's 16-byte tier subsumes the mismatch check in principle, but the
extra extendMatch invocations cost -17% @8 on serial-gc (byte-identical).
The prefilter is a real filter; reverted.

## E43 (REVERTED after corpus widening)
Lazy probe-2 (+19% serial-gc/json, -5% wat) turned out to be -32% on
mixed-20m@1: on miss-heavy data the speculative pos+1 load was hiding the
table-load latency; deferring it serializes two dependent loads per miss.
Full check of E42-only (838a57f) vs original 51f3a6d, compress@1:
  mixed 1.247 | serial-gc 1.105 | json 1.155 | rle 1.466 | wat-160m 1.147
  geomean +21.8%, every corpus positive  -> E42 stays, E43 out.

## E48 (REJECTED): skip mid-match insertions for matchLen>=32
-14.5% compress@8 on serial-gc AND slightly worse ratio (2.192->2.189):
interior positions do get traversed on this corpus. Reverted.

## FINAL cumulative vs original 51f3a6d (state = 838a57f + reverts)
c@1: jfr-serial +10.5%, json +15.5%, rle +46.6%, wat-160m +14.7%, mixed +24.7%
c@8: jfr-serial -6.8%, wat -12.3%, json -14.8%, mixed +11.8%, rle +14.0%
d@1: jfr-serial +48.3%, json +47.4%, mixed +41.0%, rle +1.2%
d@8: jfr-serial +51.1%, json +11.8%, mixed +46.9%, rle +1.5%
(all DualBench medians, byte-identical output; c@8 residual = layout drift,
 every algorithmic revert measured worse)

## E50 (KEPT): HASH_BITS_FAST 12 -> 13
DualBench c@1 vs e87375f: serial-gc +4.9% (ratio 1.9825->2.0066), json +0.4%,
mixed +1.2%, rle +0.3%. Fewer collisions, fill cost still trivial.

## E52 (REJECTED): insert-loop 8-byte dual-hash unroll
Byte-identical, neutral on wat@8 (0.993) and serial-gc@8 (0.998): C2 already
schedules the scalar loop optimally. Reverted for simplicity.

## E55 (KEPT, T=64): chain-walk early exit after len>=64 (one more candidate)
JFR on c@8 wat: chain walk + extendMatch ~45% of samples; most extra candidates
never beat a >=64B match. c@8: wat +12.9% (ratio -0.86%), serial-gc +5.9%
(ratio -0.007%), json -0.4% (identical), mixed -4.2% (identical).
T=24 measured +44%/-4.8% on wat: too much ratio loss -> 64.

## FINAL cumulative vs original 51f3a6d (state a26d89d, DualBench medians)
c@1: jfr-serial 1.200 | wat-160m 1.129 | json 1.124 | mixed 1.255 | rle 1.443
     geomean +22.5%
c@8: jfr-serial 0.893 | wat-160m 1.006 | json 0.858 | mixed 1.111 | rle 1.040
     geomean -2.3% (json/serial-gc residual = byte-identical C2 layout drift;
     null-calibration shows ~2% slot bias on this host)
d@1: jfr-serial 1.447 | json 1.359 | mixed 1.465 | rle 1.038   geomean +31%
d@8: jfr-serial 1.531 | json 1.121 | mixed 1.460 | rle 1.031   geomean +28%
Ratios: identical everywhere except c@8 wat -0.86% (E55) and c@1 ratio
slightly BETTER (E50 13-bit). Level 10: +1.5..2.6% ratio over level 9.
