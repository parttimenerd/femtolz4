# femtolz4

A small Java library for LZ4 block and frame compression.

femtolz4 is an independent implementation of LZ4 based on [LaurentChardon/lz4](https://github.com/LaurentChardon/lz4),
no copied source, no vendored C library. The pure-Java path and the bundled C
extension share the same algorithmic structure. The library picks the faster path
automatically at runtime and falls back to pure Java everywhere else.

The goal is a small codebase, with small (50KB) releases with competitive performance and an optimized
native implementation for linux/amd64 and Mac/darwin/aarch64 included in every JAR.

_This is a prototype of the SapMachine team._

## Install

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>femtolz4</artifactId>
  <version>0.1.0</version>
</dependency>
```

Requirements: JDK 17+, no other runtime dependencies.

## Quick start

Compress and decompress files using the frame stream API:

```java
import me.bechberger.femtolz4.LZ4FrameOutputStream;
import me.bechberger.femtolz4.LZ4FrameInputStream;

// Compress
try (var out = new LZ4FrameOutputStream(Files.newOutputStream(path))) {
    out.write(data);          // or: inputStream.transferTo(out)
}

// Decompress
try (var in = new LZ4FrameInputStream(Files.newInputStream(path))) {
    byte[] restored = in.readAllBytes();
}
```

The output is compatible with the `lz4` CLI tool and any spec-compliant LZ4 decoder.

## API

### Frame streams

`LZ4FrameOutputStream` and `LZ4FrameInputStream` are drop-in replacements for
`GZIPOutputStream` / `GZIPInputStream`. They are the right choice for almost
all use cases — use them unless you have a specific reason to manage raw blocks.

**Constructors:**

```java
new LZ4FrameOutputStream(out)                      // default: 1 MiB blocks, level 1
new LZ4FrameOutputStream(out, level)               // level 1–9, default block size
new LZ4FrameOutputStream(out, blockSize, level)    // explicit block size and level
new LZ4FrameOutputStream(out, LZ4.compress())      // pass a Compressor directly
new LZ4FrameOutputStream(out, LZ4.compressHigh())
```

**Compression levels** (1–9):

| Level | Speed | Ratio |
|-------|-------|-------|
| 1 (default) | fastest | good |
| 5 | balanced | better |
| 9 | slower | best |

**Block sizes:** 64 KiB, 256 KiB, 1 MiB (default), 4 MiB.
Smaller blocks reduce peak memory use; larger blocks improve ratio on
compressible data. The default 1 MiB works well for most cases.

### Block API

Use this only when you manage raw LZ4 blocks directly — for example, embedding
compressed data in a custom binary format where you store the original size yourself.

```java
// Compress a byte array
byte[] compressed = LZ4.compress(data);          // fastest
byte[] compressed = LZ4.compressHigh(data);      // best ratio

// Decompress — you must know the original (uncompressed) size
byte[] original = LZ4.decompress(compressed, originalSize);

// Low-level: compress into an existing buffer
int compressedLen = LZ4.compress(src, srcOff, srcLen, dst, dstOff, maxChain);

// Worst-case output size (for pre-allocating dst)
int maxLen = LZ4.maxCompressedLength(srcLen);
```

`LZ4.compressHigh(int level)` accepts a level from 1 to 256 (search chain depth).
The zero-argument form uses the maximum (256). Higher values improve ratio at the
cost of compression speed; decompression speed is unaffected.

### Compressor and Decompressor handles

`LZ4.compress()` and `LZ4.compressHigh()` return a `LZ4.Compressor` handle that
can be passed to `LZ4FrameOutputStream` or called directly:

```java
LZ4.Compressor c = LZ4.compressHigh(5);

// Use with frame stream
try (var out = new LZ4FrameOutputStream(sink, c)) { ... }

// Use directly (dst must be at least LZ4.maxCompressedLength(srcLen) bytes)
int n = c.compress(src, srcOff, srcLen, dst, dstOff, dst.length - dstOff);
```

Similarly, `LZ4.decompress()` returns a `LZ4.Decompressor`:

```java
LZ4.Decompressor d = LZ4.decompress();
int written = d.decompress(src, srcOff, dst, dstOff, originalLen);
```

### xxHash-32

```java
int hash = XXHash32.hash(data, 0, data.length);   // seed = 0
```

Used internally for frame checksums; also available as a standalone utility.

## Compatibility and limits

- Output is standard LZ4 frame format, readable by the `lz4` CLI and all
  spec-compliant decoders.
- `LZ4FrameInputStream` handles concatenated frames, skippable frames,
  block-independent and block-dependent frames.
- `LZ4FrameOutputStream` writes block-independent frames only.
- Header checksum, block checksum, and content checksum are verified when present.
- Malformed or truncated input throws `LZ4Exception` — it never crashes.
- Native acceleration is bundled for `linux/amd64`;
  all other platforms fall back to pure Java automatically.

Frame feature support:

| Feature | Decode | Encode |
| --- | :---: | :---: |
| Independent blocks | yes | yes |
| Dependent blocks | yes | no |
| Block checksum | yes | no |
| Content checksum | yes | no |
| Skippable frames | yes | no |

## Relationship to lz4-java

[lz4-java](https://github.com/lz4/lz4-java) (and its fork
[yawkat/lz4-java](https://github.com/yawkat/lz4-java)) inspired femtolz4.
lz4-java covers more platforms and has a longer track record. femtolz4 trades
breadth for a much smaller, simpler codebase that doesn't depend on the lz4 library,
with a 50KB JAR (vs ~870KB for lz4-java).

The `LZ4.Compressor` and `LZ4.Decompressor` interfaces are intentionally
compatible with lz4-java's `LZ4Compressor` and `LZ4FastDecompressor`, so
migration is mostly a find-and-replace of factory calls.

## CLI

```
java -jar femtolz4.jar compress   <input>     <output.lz4> [level]
java -jar femtolz4.jar decompress <input.lz4> <output>
```

`level` is from `1` (fastest) to `9` (best ratio), default `1`.

## Building

Requirements: JDK 17+, Maven 3.6+.

```bash
mvn package
```

The JAR lands at `target/femtolz4-0.1.0.jar`.

### Native libraries

Pre-built native library for linux/amd64 is bundled in the
JAR under `native/<platform>/`. To rebuild from source:

```bash
python3 build_native.py                  # linux-amd64
python3 build_native.py linux-amd64
python3 build_native.py --list           # show available targets
```

To skip native builds and force the pure-Java path:

```bash
mvn package -Dnative.skip=true
```

## Testing

```bash
mvn test                    # fast suite: unit, round-trip and property tests
mvn test -Pfuzz             # deep-fuzz suite: 50× more jqwik tries, adds deep-fuzz tests
mvn test -Dtest.full=true   # full suite: also runs slow/exhaustive tests
mvn test -Dnative.skip=true # skip native build, exercise the pure-Java path only
```

The test suite has 559 tests across nine test classes and uses
[jqwik](https://jqwik.net) for property-based fuzzing.

**`RobustnessTest`** — the decompressor must never crash, only throw `LZ4Exception`:
- every possible truncation of a valid compressed block or frame stream,
- random-garbage fuzzing fed directly as "compressed" input,
- crafted adversarial blocks: overflowing literal/match length fields,
  huge or zero match offsets, oversized frame block-size fields.

**`DecompressorFuzzTest`** — decompressor correctness and safety:
- differential oracle: femto-java and femto-native must agree on every input,
- bit-flip and multi-byte mutation of valid compressed blocks (never crashes),
- crafted raw LZ4 blocks for all token combinations, all match offsets 1–65535,
  all match lengths at overflow-encoding thresholds (4, 18–20, 273–275, …),
- overlap copy exhaustive: all offsets 1–7 × all match lengths 4–256,
- `srcOff` / `dstOff` independence; `dstLen` boundary semantics,
- 3000 tries: femto always succeeds on yawkat/yawkat-HC compressed output.

**`YawkatFuzzTest`** — all 11 compressor modes vs yawkat oracle (34 properties):
- 3000 random inputs: all 11 modes → femto-native, femto-java, yawkat all agree,
- MFLIMIT boundary stress, match-followed-by-short-tail, exact boundary lengths,
- sorted, descending, sawtooth, cycling byte range, alternating 16-byte blocks,
- single-repeated-byte, short-pattern-repeated, all-zeros, all-0xFF,
- mixed compressible/incompressible, adaptive-skip reset, Fibonacci-spaced matches,
- 2000 cross-compatibility tries: every compressor mode → yawkat oracle.

**`SpecFuzzTest`** — spec-derived invariants (S1–S10) for every LZ4 block format rule:
- overflow chain encoding for all litLen and matchLen (15 + n×255 + r),
- offset=0 rejected; offset=1 produces byte-run; max offset=65535 accepted,
- offset past written output rejected; overlapping copy semantics (reads own output),
- MFLIMIT compliance: all compressor modes verified against yawkat decoder,
- source and dest bounds: no read past `srcOff+srcLen`, canary bytes beyond `dstLen`,
- compressor invariants: determinism, `maxCompressedLength` bound, short inputs,
- deep-fuzz (activated by `-Pfuzz`): random crash-safety, bit-flip, cross-compatibility.

**`ProbeEdgeTest`** — structural edge cases: srcLen 0–11, back-to-back zero-literal
tokens, large srcOff sentinel boundary, dstOff with matchLowerBound, copyLiterals
at VarHandle boundaries (litLen 1–129), copyMatch exhaustive (offset 1–16 × len 4–64).

Slow tests (large-payload fuzzing, exhaustive truncation at higher chain depths) are
tagged `"slow"` and excluded by default; use `-Dtest.full=true` to include them.
Deep-fuzz tests (high-volume property tests) are tagged `"deep-fuzz"` and activated
by `-Pfuzz` (50× default jqwik tries) or `-Dtest.full=true`.

## Benchmark

Measured on Apple M4 Pro (macOS), JDK 25.
Comparison against [yawkat/lz4-java](https://github.com/yawkat/lz4-java) 1.11.0.
`femto-fast` / `femto-hc` = dispatch path (Java on macOS, native on linux/amd64);
`femto-java-*` forces the pure-Java path explicitly.
`yawkat-native` is the yawkat library's native path; `yawkat-java` its pure-Java path.

Run `./benchmark.sh` to regenerate with numbers from your own machine.

<!-- BENCHMARK:START -->
<!-- generated by benchmark.sh on 2026-08-24 — Apple M4 Pro, macOS — java version 25.0.3 -->

### HA_gc_details.jfr  (3 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |           682 |             890 | 1,71x |
| femto-hc               |           174 |            1073 | 2,02x |
| femto-java-fast        |           345 |             884 | 1,71x |
| femto-java             |           172 |            1096 | 2,02x |
| femto-java-hc          |            97 |            1086 | 2,05x |
| yawkat-native          |           875 |            1167 | 1,68x |
| yawkat-java            |       **942** |        **1269** | 1,68x |

### jvm17-gc-jfc.jfr  (7 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |           996 |            1308 | 2,24x |
| femto-hc               |           236 |            1482 | 2,48x |
| femto-java-fast        |           851 |            1389 | 2,24x |
| femto-java             |           252 |            1493 | 2,48x |
| femto-java-hc          |           122 |            1413 | 2,51x |
| yawkat-native          |          1066 |            1612 | 2,23x |
| yawkat-java            |      **1103** |        **1618** | 2,23x |

### flight.jfr  (13 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          1135 |            1614 | 2,76x |
| femto-hc               |           279 |            1707 | 3,06x |
| femto-java-fast        |           953 |            1597 | 2,76x |
| femto-java             |           296 |            1699 | 3,06x |
| femto-java-hc          |           155 |            1707 | 3,10x |
| yawkat-native          |          1237 |        **1966** | 2,75x |
| yawkat-java            |      **1246** |            1961 | 2,75x |

### failure.jfr  (19 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |      **1416** |            2118 | 2,59x |
| femto-hc               |           471 |            2105 | 2,63x |
| femto-java-fast        |          1109 |            2178 | 2,59x |
| femto-java             |           494 |            2142 | 2,63x |
| femto-java-hc          |           101 |            1900 | 2,75x |
| yawkat-native          |          1333 |        **2251** | 2,60x |
| yawkat-java            |          1346 |            2174 | 2,60x |

### large_test.bin  (267 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |      **1438** |            2196 | 2,59x |
| femto-hc               |           473 |            2133 | 2,63x |
| femto-java-fast        |          1116 |            2166 | 2,59x |
| femto-java             |           494 |            2111 | 2,63x |
| femto-java-hc          |           100 |            1892 | 2,75x |
| yawkat-native          |          1326 |            2209 | 2,60x |
| yawkat-java            |          1329 |        **2247** | 2,60x |

### large.jfr  (262 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          1132 |            1505 | 2,72x |
| femto-hc               |           306 |            1724 | 3,19x |
| femto-java-fast        |           956 |            1515 | 2,72x |
| femto-java             |           319 |            1733 | 3,19x |
| femto-java-hc          |            91 |            1699 | 3,29x |
| yawkat-native          |          1179 |            1780 | 2,80x |
| yawkat-java            |      **1180** |        **1787** | 2,80x |

### json-10m.bin (10 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |      **2768** |            3136 | 6,05x |
| femto-hc               |           411 |            6316 | 9,67x |
| femto-java-fast        |          2517 |            3261 | 6,05x |
| femto-java             |           369 |        **8320** | 9,67x |
| yawkat-fast            |          1519 |            2187 | 6,03x |
| yawkat-hc              |            81 |            3180 | 9,79x |

### random-20m.bin (21 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |         10120 |       **69527** | 1,00x |
| femto-hc               |          7002 |           66636 | 1,00x |
| femto-java-fast        |          3814 |           55882 | 1,00x |
| femto-java             |          6786 |           56698 | 1,00x |
| yawkat-fast            |               |           57158 | 1,00x |
| yawkat-hc              |            51 |            3700 | 1,00x |

### mixed-20m.bin (21 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |     **12937** |           49817 | 1,98x |
| femto-hc               |          3917 |       **50117** | 1,97x |
| femto-java-fast        |         10000 |           45599 | 1,97x |
| femto-java             |          3645 |           45992 | 1,97x |
| yawkat-fast            |          3027 |            4538 | 1,94x |
| yawkat-hc              |           117 |            4558 | 1,98x |

<!-- BENCHMARK:END -->

## License

MIT, Copyright 2026 SAP SE and contributors