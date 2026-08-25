package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based fuzz tests using jqwik.
 * Oracle: at.yawk.lz4 (lz4-java).
 * Covers: round-trip (native + pure-Java), cross-compat, all chain depths, invalid input.
 */
class FuzzTest {

    private static final LZ4Factory          YAWKAT     = LZ4Factory.safeInstance();
    private static final LZ4FastDecompressor YAWKAT_DEC = YAWKAT.fastDecompressor();
    private static final LZ4Compressor       YAWKAT_ENC = YAWKAT.fastCompressor();

    @Provide
    Arbitrary<Integer> allChains() {
        return Arbitraries.of(0, 1, 2, 4, 8, 16, 64, 256);
    }

    // ── Round-trip: femtolz4 compress → femtolz4 decompress ─────────────────

    @Property
    @Tag("deep-fuzz")
    void roundTripFast(@ForAll @Size(max = 65536) byte[] data) {
        byte[] compressed   = LZ4.compress(data, 1);
        byte[] decompressed = LZ4.decompress(compressed, data.length);
        assertArrayEquals(data, decompressed);
    }

    @Property

    void roundTripChain(@ForAll @Size(max = 32768) byte[] data,
                        @ForAll("allChains") int chain) {
        byte[] compressed   = LZ4.compress(data, chain);
        byte[] decompressed = LZ4.decompress(compressed, data.length);
        assertArrayEquals(data, decompressed);
    }

    // ── Round-trip: pure-Java path (bypasses native even if available) ────────

    @Property

    void roundTripJavaFast(@ForAll @Size(max = 65536) byte[] data) {
        byte[] compressed   = LZ4.compressJava(data, 1);
        byte[] decompressed = LZ4.decompressJava(compressed, data.length);
        assertArrayEquals(data, decompressed);
    }

    @Property

    void roundTripJavaChain(@ForAll @Size(max = 32768) byte[] data,
                            @ForAll("allChains") int chain) {
        byte[] compressed   = LZ4.compressJava(data, chain);
        byte[] decompressed = LZ4.decompressJava(compressed, data.length);
        assertArrayEquals(data, decompressed);
    }

    @Property

    void javaFemtoToYawkat(@ForAll @Size(min = 16, max = 65536) byte[] data,
                           @ForAll("allChains") int chain) {
        byte[] compressed = LZ4.compressJava(data, chain);
        byte[] dst        = new byte[data.length];
        YAWKAT_DEC.decompress(compressed, 0, dst, 0, data.length);
        assertArrayEquals(data, dst);
    }

    @Property
    @Tag("deep-fuzz")
    void crossDecompressors(@ForAll @Size(max = 32768) byte[] data,
                            @ForAll("allChains") int chain) {
        // Java compress → native decompress
        byte[] jcomp = LZ4.compressJava(data, chain);
        assertArrayEquals(data, LZ4.decompress(jcomp, data.length),
                "java-compress/native-decompress chain=" + chain);
        // Native compress → Java decompress
        byte[] ncomp = LZ4.compress(data, chain);
        assertArrayEquals(data, LZ4.decompressJava(ncomp, data.length),
                "native-compress/java-decompress chain=" + chain);
    }

    // ── Cross-compat: femtolz4 → yawkat ──────────────────────────────────────

    @Property

    void femtoToYawkat(@ForAll @Size(min = 16, max = 65536) byte[] data,
                       @ForAll("allChains") int chain) {
        byte[] compressed = LZ4.compress(data, chain);
        byte[] dst        = new byte[data.length];
        // jpountz fastDecompressor requires any non-final literal run to end
        // at least 8 bytes before destEnd.  Inputs shorter than 16 bytes can
        // produce streams that violate this (jpountz limitation, not an LZ4
        // spec violation).  We exclude those; roundTripFast covers them.
        YAWKAT_DEC.decompress(compressed, 0, dst, 0, data.length);
        assertArrayEquals(data, dst);
    }

    @Property(tries = 200)
    @Tag("slow-fuzz")
    void femtoToYawkatLarge(@ForAll @Size(min = 16, max = 10 * 1024 * 1024) byte[] data) {
        for (int chain : new int[]{0, 1, 256}) {
            byte[] compressed = LZ4.compress(data, chain);
            byte[] dst        = new byte[data.length];
            YAWKAT_DEC.decompress(compressed, 0, dst, 0, data.length);
            assertArrayEquals(data, dst, "chain=" + chain);
        }
    }

    // ── Cross-compat: yawkat → femtolz4 ──────────────────────────────────────

    @Property
    void yawkatToFemto(@ForAll @Size(max = 65536) byte[] data) {
        byte[] tmp  = new byte[YAWKAT_ENC.maxCompressedLength(data.length)];
        int    n    = YAWKAT_ENC.compress(data, 0, data.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);
        assertArrayEquals(data, LZ4.decompress(comp, data.length), "native decompressor");
        assertArrayEquals(data, LZ4.decompressJava(comp, data.length), "java decompressor");
    }

    // ── All chain depths produce valid round-trip output ─────────────────────

    @Property
    @Tag("deep-fuzz")
    void allChainDepths(@ForAll @Size(max = 16384) byte[] data) {
        for (int chain : new int[]{0, 1, 2, 4, 8, 16, 64, 256}) {
            byte[] ncomp = LZ4.compress(data, chain);
            assertArrayEquals(data, LZ4.decompress(ncomp, data.length),
                    "native round-trip chain=" + chain);
            assertArrayEquals(data, LZ4.decompressJava(ncomp, data.length),
                    "native-compress/java-decompress chain=" + chain);
            byte[] jcomp = LZ4.compressJava(data, chain);
            assertArrayEquals(data, LZ4.decompressJava(jcomp, data.length),
                    "java round-trip chain=" + chain);
            assertArrayEquals(data, LZ4.decompress(jcomp, data.length),
                    "java-compress/native-decompress chain=" + chain);
        }
    }

    @Property
    void allLevelsRoundTrip(@ForAll @Size(max = 16384) byte[] data) {
        for (int level : new int[]{1, 3, 5, 7, 9}) {
            byte[] comp = new byte[LZ4.maxCompressedLength(data.length)];
            int n = LZ4.compressor(level).compress(data, 0, data.length, comp, 0, comp.length);
            byte[] compressed = Arrays.copyOf(comp, n);
            assertArrayEquals(data, LZ4.decompress(compressed, data.length),
                    "level=" + level + " native-decompress");
            assertArrayEquals(data, LZ4.decompressJava(compressed, data.length),
                    "level=" + level + " java-decompress");
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Example
    void emptyInput() {
        byte[] empty = new byte[0];
        assertArrayEquals(empty, LZ4.decompress(LZ4.compress(empty, 1), 0));
        assertArrayEquals(empty, LZ4.decompress(LZ4.compress(empty, 8), 0));
    }

    @Example
    void singleByte() {
        byte[] one = {(byte) 42};
        assertArrayEquals(one, LZ4.decompress(LZ4.compress(one, 1), 1));
    }

    // ── Truncated / invalid input must not crash ──────────────────────────────

    @Property

    void truncatedInputSafe(@ForAll @Size(min = 1, max = 512) byte[] randomData) {
        // Random bytes fed as compressed input. Must throw LZ4Exception or succeed
        // gracefully — never NPE, AIOOBE, or other unexpected exception.
        try {
            LZ4.decompress(randomData, 1024);
        } catch (LZ4Exception e) {
            // expected: malformed input
        } catch (Exception e) {
            fail("Unexpected exception on invalid input: "
                    + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    @Property

    void truncatedInputJavaSafe(@ForAll @Size(min = 1, max = 512) byte[] randomData) {
        try {
            LZ4.decompressJava(randomData, 1024);
        } catch (LZ4Exception e) {
            // expected
        } catch (Exception e) {
            fail("Java decompress threw unexpected exception: " + e.getClass().getName());
        }
    }

    // ── Compressed output fits within maxCompressedLength ────────────────────

    @Property

    void outputFitsInMaxBound(@ForAll @Size(max = 65536) byte[] data) {
        int max = LZ4.maxCompressedLength(data.length);
        assertTrue(LZ4.compress(data, 1).length <= max);
        assertTrue(LZ4.compress(data, 8).length <= max);
    }
}
