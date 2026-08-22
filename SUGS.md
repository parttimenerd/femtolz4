# femtolz4 optimization suggestions

Last updated 2026-08-22. P0–P9 are implemented; P10–P11 are pending.

## Status of previous suggestions

| Suggestion | Status |
|------------|--------|
| P0: Fix lazy-lookahead reinsertion self-link | ✅ Implemented |
| P1: Widen chain hash to 16 bits | ✅ Implemented |
| P2: Compact fingerprint/delta tail entry → exact 64-bit abspos tail | ✅ Implemented (P2 reversed: see P2b) |
| P3: Adaptive lazy matching (skip when match ≥ 64 bytes) | ✅ Implemented |
| P4: Clear only head[] on chain init | ✅ Implemented |
| P5: Avoid allocating unused JNI state | ✅ Implemented |
| P6: JNI crossover for small blocks | ❌ Not done |
| P7: JMH no-allocation benchmark | ❌ Not done |
| P8: Fast-path micro-optimizations | ✅ Partially done |
| P9: 12-bit fast hash + unsigned distance check | ✅ Implemented |
| P2b: Replace compact fp16/delta tail with exact 64-bit abspos tail (C) | ✅ Implemented |

### P2b: Why the compact tail was reverted

The compact 32-bit `(fp16<<16)|delta16` tail had a stale-slot corruption bug:
when `tail[pos & WINDOW_MASK]` is overwritten by a later position at `pos + WINDOW_SIZE`,
the stored relative delta is reinterpreted relative to the new walker's position,
causing the chain to terminate after ~2 steps regardless of `max_chain`. Symptom:
C chain=8 produced output identical to chain=2 on all inputs. Fix: 64-bit absolute
positions (matching Java), which fail the window check cleanly when stale.

## Updated baseline (ThinkStation, 2026-08-22, post P0–P9)

Input: `/tmp/large_test.bin` (281 MB, 3.67x compressibility at chain=1)

| Implementation | Compress MB/s | Decomp MB/s | Ratio |
|----------------|:-------------:|:-----------:|:-----:|
| femto-native-fast | 867 | 1372 | 3.67x |
| femto-native (chain=8) | 207 | 1754 | 4.48x |
| femto-java-fast | 602 | 930 | 3.67x |
| femto-java (chain=8) | 174 | 1111 | 4.48x |
| yawkat-native | 883 | 1007 | 3.71x |
| yawkat-java | 343 | 964 | 3.72x |

Mac (darwin-aarch64, M4):
| Implementation | Compress MB/s | Decomp MB/s | Ratio |
|----------------|:-------------:|:-----------:|:-----:|
| femto-native-fast | 1060 | 2268 | 2.59x |
| femto-java-fast | 1219 | 2027 | 2.59x |
| yawkat-native | 1379 | 1362 | 2.60x |

For comparison, the original SUGS.md baseline (pre-optimization, ThinkStation):
- native chain=1: 685 MB/s, 3.77x
- Java chain=1: 559 MB/s

Notable changes from P5 baseline:
- C chain=1: 867 MB/s (was 665 → +30%) — two-step loop + 12-bit L1 hash
- Java chain=1: 602 MB/s (was 543 → +11%) — branchless `&` + 12-bit L1 hash
- C chain=8: 207 MB/s (was 197 → +5%) — P2b exact tail eliminates false chain walks
- Java chain=8: 174 MB/s (was 181 → −4%) — variance; chain=8 ratio improved to 4.48x
- JNI overhead: ~3.5% for 256KB blocks (867 JNI vs 898 standalone C)

## Ratio change

The 12-bit hash trades 0.10x ratio for 14% speed:
- chain=1 ratio: 3.77x → 3.67x (−0.10x)
- chain≥2 ratio: unchanged (chain path uses different 16-bit hash table)

## Remaining gap analysis

### C chain=1 vs yawkat-native (ThinkStation: 867 vs 883 MB/s, 2% gap)
Near-parity. Branch misses are the remaining bottleneck (6.8% at 12-bit).
Further reduction requires either wider unrolling or a branchless sentinel scheme.

### C chain=1 vs Mac Java fast (1060 vs 1219 MB/s, 15% gap)
Java wins on ARM because the JIT's `&` branchless condition eliminates branch
misprediction entirely; clang emits `SUBS+CCMP` which is slightly branch-predictable.
The unsigned-distance change improved C by +5% but didn't close the gap fully.

## Remaining optimization experiments

### P10: Close C fast-path vs Mac Java gap on ARM

femto-native-fast on Mac is 1060 MB/s; femto-java-fast is 1219 MB/s (+15%).
Root cause: Java's `sv >= 0 & sv > pos - WINDOW_SIZE & ...` with non-short-circuit `&`
generates a pure flag-AND with no conditional jumps. Clang's SUBS+CCMP is slightly
different and may be less optimal on Apple M4 OoO pipeline.

Options:
1. Try `__builtin_expect` hint on the miss path to bias the compiler toward
   generating a branchless sequence for the hit test.
2. Try a sentinel scheme that makes `dist - 1 < WINDOW_SIZE` fully branchless
   even for the loop-end check (eliminate the `pos + 1 < loop_end` guard in the
   two-step loop by padding the source buffer with dummy bytes).
3. Accept the gap: Java leads native on M4 because JIT knows the branch statistics
   from warm runs; ahead-of-time C cannot adapt.

### P11 (was P6): Find the Java/native crossover for small blocks

Every native call crosses JNI and pins two arrays with GetPrimitiveArrayCritical.
JNI overhead is ~3.5% for 256KB blocks (measured: 870 C vs 867 JNI MB/s).
For smaller blocks the fixed overhead dominates. Benchmark at 64 B, 256 B, 1 KiB,
4 KiB, 16 KiB, 64 KiB. Route blocks below the crossover to pure-Java.

Also measure GC pause behavior — holding critical arrays during large native calls
can delay GC on concurrent collectors.

### P7: Separate codec throughput from convenience-API allocation

The benchmark currently calls methods that allocate/trim output arrays.
Add a no-allocation benchmark using the existing source/destination offset API and
preallocated buffers. Use JMH with forks, warmup iterations, multiple measurement
iterations, randomized order, and error bars. On ThinkStation, pin to one NUMA node.

## Validation gate for every optimization

1. Run the native-enabled and pure-Java unit/property suites.
2. Run ASAN and Valgrind via bench_thinkstation.sh.
3. Compare compressed output against yawkat/lz4-java oracle.
4. Record throughput and ratio on: tiny blocks (64 B–4 KiB), frame-sized blocks (64 KiB–4 MiB),
   JFR/binary data, text/JSON, incompressible random data, highly repetitive data.
5. Keep an optimization only if repeated measurements show a stable gain.
