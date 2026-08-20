package me.bechberger.femtolz4;

/** xxHash-32 (seed=0), used to compute the LZ4 frame header checksum byte. */
final class XXHash32 {

    private static final int PRIME1 = 0x9E3779B1;
    private static final int PRIME2 = 0x85EBCA77;
    private static final int PRIME3 = 0xC2B2AE3D;
    private static final int PRIME4 = 0x27D4EB2F;
    private static final int PRIME5 = 0x165667B1;

    static int hash(byte[] data, int off, int len) {
        int p   = off;
        int end = off + len;
        int h   = len + PRIME5; // seed = 0

        for (; p + 4 <= end; p += 4) {
            int lane = (data[p] & 0xFF)
                     | ((data[p+1] & 0xFF) <<  8)
                     | ((data[p+2] & 0xFF) << 16)
                     | ((data[p+3] & 0xFF) << 24);
            h += lane * PRIME3;
            h  = Integer.rotateLeft(h, 17) * PRIME4;
        }
        for (; p < end; p++) {
            h += (data[p] & 0xFF) * PRIME5;
            h  = Integer.rotateLeft(h, 11) * PRIME1;
        }
        h ^= h >>> 15; h *= PRIME2;
        h ^= h >>> 13; h *= PRIME3;
        h ^= h >>> 16;
        return h;
    }

    private XXHash32() {}
}
