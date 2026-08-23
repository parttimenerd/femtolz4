# femtolz4

A small Java library for LZ4 block and frame compression.

femtolz4 is an independent implementation of LZ4 written from first principles —
no copied source, no vendored C library. The pure-Java path and the bundled C
extension share the same algorithmic structure. The library picks the faster path
automatically at runtime and falls back to pure Java everywhere else.

The goal is a small, auditable codebase with competitive performance.

## Install

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>femtolz4</artifactId>
  <version>0.1.0</version>
</dependency>
```

Requirements: JDK 17+.

## Quick Start

For most applications, use frame streams:

```java
try (var out = new LZ4FrameOutputStream(Files.newOutputStream(path))) {
    out.write(data);
}

try (var in = new LZ4FrameInputStream(Files.newInputStream(path))) {
    byte[] restored = in.readAllBytes();
}
```

Use the low-level block API only when you need precise control and already know
the uncompressed size:

```java
byte[] compressed = LZ4.compress(data);
byte[] original = LZ4.decompress(compressed, originalSize);
```

## Relationship to lz4-java

[lz4-java](https://github.com/lz4/lz4-java) (and its excellent fork
[yawkat/lz4-java](https://github.com/yawkat/lz4-java)) inspired femtolz4.
lz4-java covers more platforms and has a longer track record. femtolz4 trades
breadth for a much smaller, simpler codebase.

| | lz4-java | femtolz4 |
| --- | :---: | :---: |
| JAR size | ~876 KB | ~50 KB |
| Lines of Java source | ~10 000 | ~2 000 |
| Lines of C source | ~2 000 | ~850 |
| Platforms with native acceleration | 7 | 2 (darwin/aarch64, linux/amd64) |
| Dependencies at runtime | none | none |

## API

There are two layers:

- `LZ4FrameInputStream` / `LZ4FrameOutputStream` are the easy, file-oriented API.
  They read and write standard LZ4 frames and are what most callers should use.
- `LZ4` exposes raw block compression and decompression. It is lower-level and
  requires the caller to manage details such as the uncompressed size.

### Frame API

```java
// Standard LZ4 frame streams (compatible with the lz4 CLI)
try (var out = new LZ4FrameOutputStream(Files.newOutputStream(path))) {
    out.write(data);
}

try (var in = new LZ4FrameInputStream(Files.newInputStream(path))) {
    byte[] data = in.readAllBytes();
}
```

`LZ4FrameOutputStream` exposes a public compression `level` from `1` to `9`:

- `1` = fastest compression
- `9` = best compression ratio (typically slower compression)
- higher levels spend more CPU time searching for longer matches

You can use the default settings:

```java
new LZ4FrameOutputStream(out)
```

or choose a level and optionally a block size:

```java
new LZ4FrameOutputStream(out, 5);              // default 1 MiB blocks
new LZ4FrameOutputStream(out, 256 * 1024, 5); // explicit block size + level
```

Alternatively, pass a `LZ4.Compressor` directly:

```java
new LZ4FrameOutputStream(out, LZ4.compress());        // fastest
new LZ4FrameOutputStream(out, LZ4.compressHigh());    // best ratio
```

### Block API

```java
// Fastest compression
byte[] compressed = LZ4.compress(data);

// Better ratio (higher search effort)
byte[] compressed = LZ4.compressHigh(data);

// Decompress (you must know the original size)
byte[] original = LZ4.decompress(compressed, originalSize);

// Standalone xxHash-32 (seed=0), no dependencies
int hash = XXHash32.hash(data, 0, data.length);
```

`LZ4.compressHigh(int level)` accepts a level from 1 to 256 (search chain
depth). The zero-argument form uses the maximum. Higher values improve ratio
at the cost of compression speed; decompression speed is unaffected.

## Compatibility and Limits

- Frame input supports standard LZ4 frames, concatenated frames, and skippable
  frames.
- Header checksum, block checksum, and content checksum are verified when present.
- Malformed input is rejected with `LZ4Exception`.
- Block-dependent frames are supported for decoding by `LZ4FrameInputStream`.
- `LZ4FrameOutputStream` encodes block-independent frames only; block-dependent
  encoding is intentionally not implemented to keep the encoder simple.
- Native acceleration is bundled for `darwin/aarch64` and `linux/amd64`; other
  platforms use the pure-Java path automatically.

Frame feature support summary:

| Feature | Decode | Encode |
| --- | :---: | :---: |
| Independent blocks | yes | yes |
| Dependent blocks | yes | no |
| Block checksum | yes | no |
| Content checksum | yes | no |
| Skippable frames | yes | no |

## CLI

```
java -jar femtolz4.jar compress   <input>     <output.lz4> [level]
java -jar femtolz4.jar decompress <input.lz4> <output>
```

`level` is from `1` (fastest) to `9` (best ratio). The default is `1`.

On invalid arguments, the CLI prints an error message and exits with status `1`.

## Building

Requirements: JDK 17+, Maven 3.6+.

```bash
mvn package
```

The JAR lands at `target/femtolz4-0.1.0.jar`.

### Native libraries

Pre-built native libraries for darwin/aarch64 and linux/amd64 are bundled in the JAR under
`native/<platform>/`.

To rebuild native libraries from source, use the helper script:

```bash
python3 build_native.py
```

Useful variants:

```bash
python3 build_native.py --list
python3 build_native.py darwin-aarch64
python3 build_native.py linux-amd64
```

To skip native builds and force the pure-Java path during Maven builds/tests:

```bash
mvn package -Dnative.skip=true
```

## Testing

```bash
mvn test                    # fast suite: unit, round-trip and property tests
mvn test -Dtest.full=true   # full suite: also runs slow/exhaustive tests
mvn test -Dnative.skip=true # skip native build, exercise the pure-Java path only
```

The test suite includes `RobustnessTest`, which checks that decompression
(native and pure-Java) never crashes. Acceptable failures are `LZ4Exception`
and `IOException`. It covers:

- every possible truncation of a valid compressed block and frame stream,
- random-garbage fuzzing (via [jqwik](https://jqwik.net)) fed directly as "compressed" input,
- specifically crafted adversarial input: overflowing literal/match length fields,
  huge or zero match offsets, and oversized frame block-size fields.

Slow tests (large-payload fuzzing, exhaustive truncation at higher chain depths) are
tagged `"slow"` and excluded by default; use `-Dtest.full=true` to include them.

## Choosing Parameters

- Start with frame API defaults.
- Increase `level` only if smaller output matters more than compression speed.
- Keep default block size unless you have memory-pressure reasons to lower it.

## Benchmark

Measured on Apple M4 Pro (macOS), JDK 25.
Comparison against [yawkat/lz4-java](https://github.com/yawkat/lz4-java) 1.11.0.
`femto-fast` / `yawkat-fast` = fastest mode; `femto-hc` / `yawkat-hc` = best-ratio mode.
`femto-java-*` forces the pure-Java path regardless of platform.

Run `./benchmark.sh` to regenerate with numbers from your own machine.

<!-- BENCHMARK:START -->
<!-- generated by benchmark.sh on 2026-08-23 — Apple M4 Pro, macOS — java version 25.0.3 -->

### HA_gc_details.jfr  (3 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |           717 |            1075 | 1,71x |
| femto-hc               |           180 |            1325 | 2,02x |
| femto-java-fast        |           718 |            1129 | 1,71x |
| femto-java             |           181 |            1260 | 2,02x |
| yawkat-native          |       **965** |        **1421** | 1,68x |
| yawkat-java            |           453 |            1145 | 1,72x |

### jvm17-gc-jfc.jfr  (7 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |           867 |            1436 | 2,24x |
| femto-hc               |           248 |        **1540** | 2,48x |
| femto-java-fast        |           907 |            1419 | 2,24x |
| femto-java             |           255 |            1534 | 2,48x |
| yawkat-native          |      **1134** |            1196 | 2,23x |
| yawkat-java            |           586 |            1412 | 2,26x |

### flight.jfr  (13 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          1034 |            1724 | 2,76x |
| femto-hc               |           299 |        **1826** | 3,06x |
| femto-java-fast        |          1054 |            1686 | 2,76x |
| femto-java             |           304 |            1707 | 3,06x |
| yawkat-native          |      **1343** |            1734 | 2,75x |
| yawkat-java            |           634 |            1509 | 2,78x |

### failure.jfr  (19 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          1372 |        **1757** | 2,59x |
| femto-hc               |           509 |            1657 | 2,63x |
| femto-java-fast        |          1354 |            1673 | 2,59x |
| femto-java             |           509 |            1654 | 2,63x |
| yawkat-native          |      **1448** |            1389 | 2,60x |
| yawkat-java            |           841 |            1371 | 2,60x |

### large_test.bin  (267 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          1323 |            1451 | 2,59x |
| femto-hc               |           490 |            1258 | 2,63x |
| femto-java-fast        |          1241 |            1374 | 2,59x |
| femto-java             |           503 |        **1568** | 2,63x |
| yawkat-native          |      **1380** |            1356 | 2,60x |
| yawkat-java            |           835 |            1361 | 2,60x |

### large.jfr  (262 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          1004 |            1549 | 2,72x |
| femto-hc               |           327 |        **1834** | 3,20x |
| femto-java-fast        |          1001 |            1537 | 2,72x |
| femto-java             |           310 |            1675 | 3,20x |
| yawkat-native          |      **1192** |            1577 | 2,81x |
| yawkat-java            |           649 |            1435 | 2,75x |

### json-10m.bin (10 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          2389 |            3150 | 6,05x |
| femto-hc               |           361 |            7593 | 9,67x |
| femto-java-fast        |      **2389** |            3126 | 6,05x |
| femto-java             |           364 |        **7611** | 9,67x |
| yawkat-fast            |          1746 |            1584 | 6,03x |
| yawkat-hc              |            87 |            2498 | 9,79x |

### random-20m.bin (21 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |          8539 |           57397 | 1,00x |
| femto-hc               |          6770 |       **57417** | 1,00x |
| femto-java-fast        |          8542 |           57268 | 1,00x |
| femto-java             |          6730 |           57164 | 1,00x |
| yawkat-fast            | **49083**[^earlyexit] |           56834 | 1,00x |
| yawkat-hc              |            43 |            5990 | 1,00x |

### mixed-20m.bin (21 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-fast             |     **10544** |           46945 | 1,97x |
| femto-hc               |          3699 |           46883 | 1,97x |
| femto-java-fast        |         10503 |       **47359** | 1,97x |
| femto-java             |          3706 |           46726 | 1,97x |
| yawkat-fast            |          2957 |            6078 | 1,94x |
| yawkat-hc              |            95 |            6069 | 1,98x |

[^earlyexit]: yawkat-fast on incompressible data exits early after a short scan — not a real compression speed.
<!-- BENCHMARK:END -->

## License

MIT, Copyright 2026 SAP SE and contributors
