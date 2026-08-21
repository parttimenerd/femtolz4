package me.bechberger.femtolz4;

import net.jpountz.xxhash.XXHashFactory;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link XXHash32}.
 * Oracle: net.jpountz.xxhash (bundled in the at.yawk.lz4 test dependency), seed=0.
 */
class XXHash32Test {

    private static final net.jpountz.xxhash.XXHash32 ORACLE =
            XXHashFactory.safeInstance().hash32();

    private static int oracleHash(byte[] data, int off, int len) {
        return ORACLE.hash(data, off, len, 0 /* seed */);
    }

    // ── Property: matches the reference implementation on arbitrary input ───────

    @Property(tries = 1000)
    void matchesOracleWholeArray(@ForAll @Size(max = 8192) byte[] data) {
        assertEquals(oracleHash(data, 0, data.length), XXHash32.hash(data, 0, data.length));
    }

    @Property(tries = 500)
    void matchesOracleWithOffsetAndLength(@ForAll @Size(min = 1, max = 8192) byte[] data,
                                          @ForAll int rawOff,
                                          @ForAll int rawLen) {
        int off = Math.floorMod(rawOff, data.length + 1);
        int len = Math.floorMod(rawLen, data.length - off + 1);
        assertEquals(oracleHash(data, off, len), XXHash32.hash(data, off, len));
    }

    // ── Property: same input, same output (determinism) ─────────────────────────

    @Property(tries = 200)
    void deterministic(@ForAll @Size(max = 4096) byte[] data) {
        assertEquals(XXHash32.hash(data, 0, data.length), XXHash32.hash(data, 0, data.length));
    }

    // ── Property: byte-for-byte-equal inputs of varying lengths all match oracle ─

    @Property(tries = 300)
    void matchesOracleAcrossLengths(@ForAll @IntRange(min = 0, max = 4096) int len,
                                    @ForAll long seed) {
        byte[] data = randomBytes(len, seed);
        assertEquals(oracleHash(data, 0, data.length), XXHash32.hash(data, 0, data.length));
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    static Stream<Arguments> edgeCases() {
        byte[] zeros = new byte[1024];
        byte[] ones  = new byte[1024];
        Arrays.fill(ones, (byte) 0xFF);
        byte[] offsetBuf = randomBytes(200, 7);
        byte[] tailBuf   = randomBytes(64, 3);

        return Stream.of(
                Arguments.of("emptyInput",              new byte[0],            0,  0),
                Arguments.of("singleByte",               new byte[]{(byte) 0xAB}, 0,  1),
                Arguments.of("exactlyFourBytes",         new byte[]{1, 2, 3, 4}, 0,  4),
                Arguments.of("fiveBytesTriggersTailLoop", new byte[]{1, 2, 3, 4, 5}, 0, 5),
                Arguments.of("allZeros",                 zeros,                  0,  zeros.length),
                Arguments.of("allOnes",                  ones,                   0,  ones.length),
                Arguments.of("nonZeroOffsetIntoLargerBuffer", offsetBuf,         50, 100),
                Arguments.of("offsetAtEnd",               tailBuf,               64, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeCases")
    void matchesOracleOnEdgeCase(String name, byte[] data, int off, int len) {
        assertEquals(oracleHash(data, off, len), XXHash32.hash(data, off, len));
    }

    // ── Sizes around the 4-byte lane boundary and typical block sizes ───────────

    @Property(tries = 100)
    void matchesOracleNearLaneBoundaries(@ForAll @IntRange(min = 0, max = 20) int delta,
                                         @ForAll long seed) {
        int len = Math.max(0, 4 * 50 + delta - 10); // spans a few lengths around multiples of 4
        byte[] data = randomBytes(len, seed);
        assertEquals(oracleHash(data, 0, data.length), XXHash32.hash(data, 0, data.length));
    }

    private static byte[] randomBytes(int len, long seed) {
        byte[] data = new byte[len];
        new Random(seed).nextBytes(data);
        return data;
    }
}
