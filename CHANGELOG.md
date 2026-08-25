# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.1] - 2026-08-25

### Added
- `LZ4FrameInputStream(InputStream, boolean readSingleFrame)` — when `true`, stops after the first non-skippable frame end mark without reading further bytes from the underlying stream; mirrors lz4-java's `readSingleFrame` parameter

## [0.2.0] - 2026-08-25

### Added
- `LZ4.compressor(int level)` — new preferred factory method with lz4 CLI-compatible level mapping (levels 1–9, where 1=fast and 9=best ratio)
- `LZ4.compressorJava(int level)` — pure-Java equivalent of `compressor(level)`
- `LZ4.LEVEL_FAST` (1), `LZ4.LEVEL_DEFAULT` (9), `LZ4.LEVEL_MAX` (9) constants
- `LZ4Java` now implements both `LZ4.Compressor` and `LZ4.Decompressor`; instances are reusable across calls, avoiding hash-table reallocation

### Changed
- `LEVEL_MAX` is 9; levels 10–12 (lz4opt) and 13–14 (whole-block DP) are not implemented — benchmarking showed only 1–2% ratio gain at 3–80× speed cost, not a worthwhile tradeoff
- `LZ4FrameOutputStream` deprecated constants (`MIN_LEVEL`, `MAX_LEVEL`, `LEVEL_FAST`, `LEVEL_NORMAL`) removed; use `LZ4.LEVEL_FAST` / `LZ4.LEVEL_MAX` instead

### Fixed
- `LZ4FrameInputStream`: clean EOF after a single frame no longer throws `LZ4Exception`; single-frame streams now decode correctly
- `LZ4FrameOutputStream.flush()`: now materializes any buffered partial block to the underlying stream, allowing callers to observe on-disk progress mid-stream

### Deprecated
- `LZ4.compressHigh(int level)` — use `LZ4.compressor(int level)`
- `LZ4.compressHighJava(int level)` — use `LZ4.compressorJava(int level)`
- `LZ4Java.highCompressor(int level)` — use `LZ4.compressor(int level)`

## [0.1.0] - 2026-08-24

### Added
- Initial release of the project
