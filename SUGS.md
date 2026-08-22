# femtolz4 optimization suggestions

Last updated 2026-08-22. P0–P5 are implemented; P6–P8 are pending.

## Status of previous suggestions

| Suggestion | Status |
|------------|--------|
| P0: Fix lazy-lookahead reinsertion self-link | ✅ Implemented |
| P1: Widen chain hash to 16 bits | ✅ Implemented |
| P2: Compact fingerprint/delta tail entry | ✅ Implemented |
| P3: Adaptive lazy matching (skip when match ≥ 64 bytes) | ✅ Implemented |
| P4: Clear only head[] on chain init | ✅ Implemented |
| P5: Avoid allocating unused JNI state | ✅ Implemented |
| P6: JNI crossover for small blocks | ❌ Not done |
| P7: JMH no-allocation benchmark | ❌ Not done |
| P8: Fast-path micro-optimizations | ❌ Not done (low priority) |

## Updated baseline (ThinkStation, 2026-08-22, post P0–P5)

Input: `/tmp/large_test.bin` (281 MB, 3.77x compressibility)

| Implementation | Compress MB/s | Decomp MB/s | Ratio |
|----------------|:-------------:|:-----------:|:-----:|
| femto-native-fast | 665 | 1382 | 3.77x |
| femto-native (chain=8) | 197 | 1325 | 4.12x |
| femto-java-fast | 543 | 920 | 3.77x |
| femto-java (chain=8) | 181 | 1122 | 4.48x |
| yawkat-native | 839 | 1012 | 3.71x |
| yawkat-java | 342 | 963 | 3.72x |

For comparison, the original SUGS.md baseline (pre-optimization, ThinkStation, smaller JFR input):
- native chain=1: 685 MB/s, 3.77x
- native chain=8: 195 MB/s, 4.39x
- Java chain=1: 559 MB/s
- Java chain=8: 142 MB/s
- yawkat-native: 589 MB/s

Notable changes from baseline:
- Java chain=8: 181 MB/s vs 142 MB/s (+27%) — P0, P1, P2 improvements
- Java chain=1: 543 MB/s vs 559 MB/s (−3%) — likely measurement variance or different input
- C chain=8: 197 MB/s vs 195 MB/s (+1%) — largely unchanged; P1/P2 offset by P3 ratio trade-off
- C chain=1: 665 MB/s vs 685 MB/s (−3%) — measurement variance (P0–P5 don't affect fast path)
- Decompress: dramatically improved (1382 MB/s native — decompressor fast-path VarHandle work)

## Observed anomaly: C chain=8 ratio vs Java chain=8

On highly-compressible data (3.77x input), C chain=8 achieves 4.12x while Java chain=8 achieves 4.48x.
The P3 threshold (skip lazy when match ≥ 64 bytes) affects both equally. The difference likely
comes from the tail storage representation:
- Java tail: full 4-byte value — exact rejection, no false positives on the quick check
- C tail: 16-bit fingerprint — occasional false positives waste chain budget on non-matching candidates

A false-positive rate of ~1/65536 sounds small but may matter over millions of chain walks on
highly-compressible data where chains are long. Consider testing with exact 4-byte tail storage
(64-bit slots, 512 KiB table) to measure the ratio impact, accepting the L2 cache pressure.

## Executive summary of remaining work

1. **C chain ratio recovery**: The compact fp16 tail may be losing ratio on highly compressible data
   vs Java's exact-value tail. Test 64-bit tail slots (512 KiB) to quantify.
2. **C chain=1 vs yawkat gap**: femto-native-fast is 21% slower (665 vs 839 MB/s). Both scan
   every byte so the gap is likely in match extension and/or probe efficiency. Profile to identify.
3. **P6 (JNI crossover)**: benchmark at 64 B–1 MiB to find break-even point for routing small
   blocks to Java.
4. **P7 (JMH no-alloc benchmark)**: separate codec throughput from array allocation overhead.

## Remaining optimization experiments

### C chain: test exact 4-byte tail storage

Replace the compact 32-bit `(fp16<<16)|delta16` tail with 64-bit `(value<<32)|prevPos` layout
matching Java. Table grows from 256 KiB to 512 KiB (total state: 768 KiB, exceeds L2 on
Threadripper 3995WX). Expected trade-off: better ratio (fewer wasted chain steps) at some
cache-pressure cost for chain-8 speed. Measure on both compressible and incompressible inputs.

Accept only if: ratio improves ≥ 0.1x AND chain-8 speed does not drop more than 5%.

### P6: Find the Java/native crossover for small blocks

Every native call crosses JNI and pins two arrays with GetPrimitiveArrayCritical.
Benchmark the caller-provided-buffer APIs at 64 B, 256 B, 1 KiB, 4 KiB, 16 KiB, 64 KiB,
256 KiB, and 1 MiB. If Java wins below a stable threshold, route small blocks to Java even
when native code is present.

Also measure GC pause behavior — holding critical arrays during large native calls can delay GC.

### P7: Separate codec throughput from convenience-API allocation

The benchmark currently calls methods that allocate/trim output arrays.
Add a no-allocation benchmark using the existing source/destination offset API and preallocated
buffers. Report both:
- end-to-end API throughput, including returned-array allocation/copy
- codec throughput, using reused buffers

Use JMH with forks, warmup iterations, multiple measurement iterations, randomized order,
and error bars. On ThinkStation, pin to one core/NUMA node.

### P8: Fast path micro-optimizations (low priority)

- Generation-stamped hash slots to avoid the 64 KiB reset (helps small blocks most)
- Platform-specific literal-copy thresholds
- Prefetch-distance tuning for the hit path in lz4_compress_fast

Profile evidence required before implementing any P8 item.

## Validation gate for every optimization

1. Run the native-enabled and pure-Java unit/property suites.
2. Run ASAN and Valgrind via bench_thinkstation.sh.
3. Compare compressed output against yawkat/lz4-java oracle.
4. Record throughput and ratio on: tiny blocks (64 B–4 KiB), frame-sized blocks (64 KiB–4 MiB),
   JFR/binary data, text/JSON, incompressible random data, highly repetitive data.
5. Keep an optimization only if repeated measurements show a stable gain.
