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
First noisy A/B: rle +24%, mixed +8%, json ±9% inconclusive — awaiting interleaved rerun.

## Queue

- E3: chain-walk candidate pruning with 4-byte read at sv+bestLen-3 (chain ≥ 3, ~50% time)
- E4: re-profile decode after E2; consider short literal wildcard copy improvements
- E5: XXHash32 VarHandle readInt (block/content checksum frames)
- E6: LZ4FrameOutputStream writeLE32 batching

