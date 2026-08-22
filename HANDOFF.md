# femtolz4 Handoff — 2026-08-22

## Goal
Maximize both compression **speed** and **ratio** for all code paths on Mac M4 (AArch64) and Linux x86-64. Test across diverse data types: JFR profiles, word lists, incompressible random text.

## CRITICAL CONSTRAINTS (permanent — never violate)
1. **NEVER look at the lz4 reference repo, lz4-java bytecode, or any lz4 reference source.** All C and Java code must be derived independently.
2. **NEVER use `sun.misc.Unsafe`**. Use VarHandle (`INT_LE`, `LONG_LE` via `MethodHandles.byteArrayViewVarHandle`).

---

## Recent commits (this session)

```
2ff0b0c  perf: chain compress — hoist skip to loop top, avoid probe on miss-streak
52ae188  perf: chain compress — lazy<8 threshold + adaptive skip for incompressible data
ad9fc22  perf: compressFast2Way — adaptive skip counter for incompressible data
```

### What was done
- **`compressFast2Way` (chain=0)**: added adaptive skip counter (same as `compressFast`)
- **`compressChain2` + `compressJavaImpl` (chain >= 2)**: two changes:
  1. Lazy threshold reduced from 64 → 8: only attempt lazy when current match < 8 bytes. JFR data (long matches) gets ~15% speedup; words data is neutral.
  2. Adaptive skip counter: after 128 consecutive miss-bytes, apply yawkat-style exponential skip (step grows 1→16). **Incompressible data: chain=2 went from 119 MB/s → ~5000 MB/s (42x)**. Restructured as a top-of-loop continue block so the probe is skipped entirely on miss-streak positions.

---

## Current benchmark numbers (Mac M4)

| data | chain=1 | chain=2 | chain=4 | chain=8 | yawkat fast |
|------|---------|---------|---------|---------|-------------|
| words (10MB) | 437 MB/s 1.72x | 230 MB/s 2.00x | 192 MB/s 2.02x | 147 MB/s 2.03x | 291 MB/s 1.80x |
| text/random (20MB) | 5289 MB/s | 4982 MB/s | 3333 MB/s | 3317 MB/s | 36631 MB/s |
| large_test.bin/JFR (267MB) | ~1270 MB/s | ~530 MB/s | — | — | — |

**Test files:** `/tmp/words_test.bin`, `/tmp/text_test.bin`, `/tmp/large_test.bin`

---

## Known gaps and next areas to attack

### 1. Incompressible speed gap (chain=2: 5000 vs yawkat 36000 MB/s)
Even with skip active, we still insert into `head[]`/`tail[]` at the skipped position. An even faster approach: when `missBytes >= 128`, completely skip the insert too (just advance pos). Tradeoff: slight ratio loss at boundaries where compressible regions follow incompressible ones. Worth trying with `lazy<8` threshold already in place.

Current skip block in `compressChain2` (and `compressJavaImpl`) still does:
```java
tail[pos & WINDOW_MASK] = ((long) pos4s << 32) | (prevs & 0xFFFFFFFFL);
head[hs] = pos;
```
Try removing these two writes in the skip block and measure ratio impact.

### 2. Words compress speed (230 MB/s vs yawkat 291 MB/s)
Root cause: two separate array accesses per position — `head[h]` (int[]) and `tail[sv]` (long[]) are different cache lines. Yawkat likely uses a single flat `long[]` with 2 slots per bucket (LRU-2), which is cache-friendlier.
- `compressFast2Way` (chain=0) already uses this approach and gets ~450 MB/s at 1.72x ratio
- The ratio gap (2.00x chain vs 1.72x fast) is fundamental to the chain structure
- Next idea: try "packed chain" — a single `long[]` with the prev pointer packed into the same 64-bit slot as the prefix. This is what `tail[]` already does. The issue is `head[]` is a separate array. Could pack head into the low 32 bits of a second long[] and scan 2 entries per bucket — effectively the current approach, but with better memory layout.

### 3. Chain=4 / chain=8 ratio improvements
Currently chain=8 gives 2.03x on words. The insert loop only inserts every-2 positions. Could try inserting every-1 position (more chain coverage) — but would hurt speed.

### 4. JFR compress: close remaining gap with previous best
Previous best: ~553 MB/s (before skip counter was added to chain paths). Current: ~530 MB/s. The small regression is from the `if (missBytes >= 128)` check at the top of the loop — it's a branch that fires rarely on JFR but still costs a branch prediction miss every 128 positions. Could restructure as `if (missBytes < 128)` (inverted) to be predicted-not-taken (faster on M4 branch predictor).

### 5. Decompressor improvements (not yet tackled)
`decompressJavaImpl` has fast paths for 1-3 byte literals (JFR optimization). Could add similar VarHandle fast paths for 4-8 byte literals. Also: the copy-match overlap path (`copyMatch`) is byte-by-byte; could use a table of small-offset memcpy patterns.

---

## How to run benchmarks

```bash
# Build
mvn package -q -DskipTests

# Quick comparison vs yawkat on all 3 test files
YAWKAT_JAR=~/.m2/repository/at/yawk/lz4/lz4-java/1.11.0/lz4-java-1.11.0.jar
javac -cp target/femtolz4-0.1.0.jar:$YAWKAT_JAR /tmp/YawkatBench.java -d /tmp/cls
java --enable-native-access=ALL-UNNAMED -cp /tmp/cls:target/femtolz4-0.1.0.jar:$YAWKAT_JAR YawkatBench

# Run all 119 unit tests
mvn test
```

## Architecture quick reference

`LZ4.java` dispatch (on AArch64, always Java):
- `maxChain=0` → `compressFast2Way` — long[8192] 2-slot LRU buckets
- `maxChain=1` → `compressFast` — long[4096] flat hash (2-step unrolled)
- `maxChain=2` → `compressChain2` — int[65536] head + long[65536] tail, unrolled 2-step, speculative dual-load
- `maxChain>=3` → `compressJavaImpl` — same head/tail, generic chain walk

Skip counter pattern (in all 4 paths):
```java
int missBytes = 0, skipCtr = 2 << 6;
// In miss path: missBytes++; pos++;
// At top of loop:
if (missBytes >= 128) {
    int step = (skipCtr >> 6) + 1;
    if (skipCtr < (17 << 6)) skipCtr++;
    // (optionally: insert pos here or skip entirely)
    missBytes += step; pos += step; continue;
}
// On match: missBytes = 0; skipCtr = 2 << 6;
```
