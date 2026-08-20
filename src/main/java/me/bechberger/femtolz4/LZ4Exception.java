package me.bechberger.femtolz4;

/** Thrown when LZ4 block data is malformed. */
public class LZ4Exception extends RuntimeException {
    public LZ4Exception(String message) { super(message); }
}
