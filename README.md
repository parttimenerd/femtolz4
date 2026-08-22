# femtolz4

A small Java library for LZ4 block and frame compression.

femtolz4 is built on top of [LaurentChardon/lz4](https://github.com/LaurentChardon/lz4),
a public-domain C implementation of LZ4, with project-specific optimizations.
The Java implementation closely mirrors the same algorithmic structure, and the
library can use bundled native acceleration where available.

The goal is a tiny, understandable codebase with reasonable performance and a
pure-Java fallback.

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
byte[] compressed = LZ4.compress(data, 1);
byte[] original = LZ4.decompress(compressed, originalSize);
```

## Relationship to lz4-java

[lz4-java](https://github.com/lz4/lz4-java) (and its excellent fork [yawkat/lz4-java](https://github.com/yawkat/lz4-java))
inspired femtolz4. If raw throughput is the priority, use lz4-java: it is faster,
battle-tested, and supports more platforms. femtolz4 is for cases where
**simplicity and small footprint matter more than peak speed**.

Choose femtolz4 when you want:

- a tiny dependency footprint
- a codebase that is easy to audit and debug
- standard LZ4 frame interoperability with a simple API

| | lz4-java | femtolz4 |
|---|:---:|:---:|
| JAR size | ~876 KB | ~23 KB |
| Lines of Java source | ~10 000 | ~750 |
| Platforms with native acceleration | 7 | 2 (darwin/aarch64, linux/amd64) |
| Dependencies at runtime | none | none |

The full source, including the C core, fits in about 1,200 lines.

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
new LZ4FrameOutputStream(out, 5);                    // default 1 MiB blocks
new LZ4FrameOutputStream(out, 256 * 1024, 5);       // explicit block size + level
```

### Block API

```java
// Compress a byte array
byte[] compressed = LZ4.compress(data, 1 /*maxChain, 1=fastest*/);

// Decompress a byte array (you must know the original size)
byte[] original = LZ4.decompress(compressed, originalSize);

// Standalone xxHash-32 (seed=0), no dependencies
int hash = XXHash32.hash(data, 0, data.length);
```

The `maxChain` argument is an internal search-effort knob for the pure-Java compressor:

- lower values are faster
- higher values usually compress better
- it affects compression only, not decompression

For most code, prefer the frame API and its simpler `level` parameter.

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
|---|:---:|:---:|
| Independent blocks | yes | yes |
| Dependent blocks | yes | no |
| Block checksum | yes | no |
| Content checksum | yes | no |
| Skippable frames | yes | no |

If you need broad platform-native coverage and peak throughput, prefer
`lz4-java`/`yawkat-lz4-java`.

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

Advanced manual toolchain commands are still possible, but `build_native.py` is
the recommended and supported path.

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
- Use block API `maxChain` directly only for benchmarking or advanced tuning.

## Benchmark

Measured on Apple M4 Pro (macOS), JDK 25.
Comparison against [yawkat/lz4-java](https://github.com/yawkat/lz4-java) 1.11.0.
`-fast` variants use chain=1 (fastest), plain variants use chain=8 (balanced).

Run `./benchmark.sh` to regenerate with numbers from your own machine.

<!-- BENCHMARK:START -->
<!-- generated by benchmark.sh on 2026-08-21 — Apple M4 Pro, macOS — java version 25.0.3 2026-04-21 LTS -->
### aprof.jfr (1 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |           449 |             735 | 1,72x |
| femto-native         |           107 |            1351 | 1,96x |
| femto-java-fast      |           328 |             710 | 1,72x |
| femto-java           |            61 |            1156 | 1,96x |
| yawkat-native        |          1217 |             317 | 1,76x |
| yawkat-java          |           217 |            1410 | 1,73x |

### cpu_profile.jfr (1 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |           920 |            1339 | 2,22x |
| femto-native         |           144 |            1590 | 2,43x |
| femto-java-fast      |           243 |             901 | 2,22x |
| femto-java           |           120 |            1430 | 2,43x |
| yawkat-native        |          1140 |             973 | 2,22x |
| yawkat-java          |           533 |            1345 | 2,20x |

### HA_gc_details.jfr (3 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |           773 |            1160 | 1,78x |
| femto-native         |           108 |            1629 | 1,95x |
| femto-java-fast      |           633 |            1095 | 1,78x |
| femto-java           |            91 |            1335 | 1,95x |
| yawkat-native        |           946 |            1450 | 1,68x |
| yawkat-java          |           474 |            1269 | 1,72x |

### jvm17-gc-jfc.jfr (7 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |           950 |            1432 | 2,27x |
| femto-native         |           149 |            1902 | 2,46x |
| femto-java-fast      |           751 |            1273 | 2,27x |
| femto-java           |           128 |            1513 | 2,46x |
| yawkat-native        |          1253 |            1308 | 2,23x |
| yawkat-java          |           611 |            1447 | 2,26x |

### flight.jfr (13 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |          1127 |            1768 | 2,79x |
| femto-native         |           183 |            2271 | 2,98x |
| femto-java-fast      |           828 |            1519 | 2,79x |
| femto-java           |           156 |            1958 | 2,98x |
| yawkat-native        |          1408 |            1791 | 2,75x |
| yawkat-java          |           630 |            1555 | 2,78x |

### failure.jfr (19 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |          1129 |            2460 | 2,60x |
| femto-native         |           239 |            2525 | 2,62x |
| femto-java-fast      |           957 |            1478 | 2,60x |
| femto-java           |           196 |            1512 | 2,62x |
| yawkat-native        |          1533 |            1479 | 2,60x |
| yawkat-java          |           866 |            1475 | 2,60x |

### large_test.bin (267 MB)

| implementation | compress MB/s | decompress MB/s | ratio |
|----------------|:-------------:|:---------------:|:-----:|
| femto-native-fast    |          1112 |            1899 | 2,60x |
| femto-native         |           236 |            1588 | 2,62x |
| femto-java-fast      |           876 |            1192 | 2,60x |
| femto-java           |           194 |            1452 | 2,62x |
| yawkat-native        |          1442 |            1416 | 2,60x |
| yawkat-java          |           858 |            1411 | 2,60x |
<!-- BENCHMARK:END -->

## License

[MIT](LICENSE)
