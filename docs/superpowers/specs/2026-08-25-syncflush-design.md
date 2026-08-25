# syncFlush flag for LZ4FrameOutputStream

## Goal

Match lz4-java's `syncFlush` behaviour on `LZ4BlockOutputStream`: make `flush()` a no-op at the LZ4 layer by default, and opt-in to partial-block emission via `syncFlush=true`.

## Motivation

The current `LZ4FrameOutputStream.flush()` always emits a partial block, which is lz4-java's non-default (`syncFlush=true`) mode. Users migrating from lz4-java or relying on throughput-oriented defaults would expect `flush()` to be cheap (only flushing the underlying stream) unless they explicitly opt in.

## Design

### Field

Add `private final boolean syncFlush` to `LZ4FrameOutputStream`.

### `flush()` behaviour

| `syncFlush` | behaviour |
|---|---|
| `false` (default) | call `out.flush()` only — no partial block emitted |
| `true` | emit partial block via `flushBlock()`, then call `out.flush()` |

### `close()` behaviour

Unchanged. Always emits any buffered data as a partial block and writes the LZ4 frame end mark (4 zero bytes), regardless of `syncFlush`.

### Constructor changes

Add `syncFlush` to the full constructor and one convenience overload. All existing constructors keep their signatures and implicitly set `syncFlush=false`.

**New / changed signatures:**

```java
// Full constructor (existing, extended)
public LZ4FrameOutputStream(OutputStream out, int blockSize, LZ4.Compressor compressor, boolean syncFlush)

// Convenience overload (new)
public LZ4FrameOutputStream(OutputStream out, boolean syncFlush)
```

All other existing constructors delegate to the full constructor with `syncFlush=false`.

### No format change

The output format is identical regardless of `syncFlush`. The flag only controls whether `flush()` emits a partial block. A stream written with `syncFlush=false` is byte-for-byte identical to one written with `syncFlush=true` if `flush()` is never called.

## Testing

Add to an existing test file (e.g. `LZ4Test.java` or `EdgeCaseTest.java`):

1. **`syncFlush=false`, `flush()` called** — write some bytes, call `flush()`, verify nothing beyond the frame header has been written to the underlying stream yet (no partial block bytes).
2. **`syncFlush=true`, `flush()` called** — write some bytes, call `flush()`, verify the partial bytes are decompressible immediately via `LZ4FrameInputStream`.
3. **`close()` always finalizes** — with `syncFlush=false`, verify that `close()` still emits buffered data and the end mark, and the full stream round-trips correctly.

## Out of scope

- A separate `syncFlush` flag on `LZ4FrameInputStream` (reads are already on-demand).
- A `syncFlush=false` no-op optimisation in `write()` (not needed; buffering is unchanged).
- Any change to block-size defaults or compression levels.
