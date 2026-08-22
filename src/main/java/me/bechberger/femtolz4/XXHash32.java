package me.bechberger.femtolz4;

/**
 * xxHash-32 (seed=0).
 *
 * <p>Used internally to compute the LZ4 frame header checksum byte, but also
 * exposed as a standalone, dependency-free hashing utility.
 *
 * @see <a href="https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md">xxHash spec</a>
 */
public final class XXHash32 {

    private static final int PRIME1 = 0x9E3779B1;
    private static final int PRIME2 = 0x85EBCA77;
    private static final int PRIME3 = 0xC2B2AE3D;
    private static final int PRIME4 = 0x27D4EB2F;
    private static final int PRIME5 = 0x165667B1;

    /**
     * Computes the xxHash-32 (seed=0) of {@code data[off, off+len)}.
     *
     * @param data source array
     * @param off  start offset
     * @param len  number of bytes to hash
     * @return the 32-bit hash
     */
    public static int hash(byte[] data, int off, int len) {
        int p   = off;
        int end = off + len;
        int h;

        if (len >= 16) {
            int limit = end - 16;
            int v1 = PRIME1 + PRIME2;
            int v2 = PRIME2;
            int v3 = 0;
            int v4 = -PRIME1;

            do {
                v1 = round(v1, readInt(data, p)); p += 4;
                v2 = round(v2, readInt(data, p)); p += 4;
                v3 = round(v3, readInt(data, p)); p += 4;
                v4 = round(v4, readInt(data, p)); p += 4;
            } while (p <= limit);

            h = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
              + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
        } else {
            h = PRIME5; // seed = 0
        }

        h += len;

        for (; p + 4 <= end; p += 4) {
            int lane = readInt(data, p);
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

    /** Reads 4 bytes at {@code p} as a little-endian int. */
    private static int readInt(byte[] data, int p) {
        return (data[p] & 0xFF)
             | ((data[p+1] & 0xFF) <<  8)
             | ((data[p+2] & 0xFF) << 16)
             | ((data[p+3] & 0xFF) << 24);
    }

    /** One round of the 16-byte-block accumulator update. */
    private static int round(int acc, int lane) {
        acc += lane * PRIME2;
        acc  = Integer.rotateLeft(acc, 13);
        return acc * PRIME1;
    }

    /**
     * Incremental (streaming) xxHash-32 (seed=0).
     * Accepts data in arbitrary-sized chunks via {@link #update} and produces
     * the final hash via {@link #digest}.
     */
    static final class Streaming {
        private int v1, v2, v3, v4;
        private final byte[] pending = new byte[16];
        private int pendingLen;
        private long totalLen;

        Streaming() { reset(); }

        void reset() {
            v1 = PRIME1 + PRIME2;
            v2 = PRIME2;
            v3 = 0;
            v4 = -PRIME1;
            pendingLen = 0;
            totalLen = 0;
        }

        void update(byte[] data, int off, int len) {
            totalLen += len;

            // Not enough to fill a 16-byte stripe — just buffer
            if (pendingLen + len < 16) {
                System.arraycopy(data, off, pending, pendingLen, len);
                pendingLen += len;
                return;
            }

            // Complete the pending stripe if any
            if (pendingLen > 0) {
                int fill = 16 - pendingLen;
                System.arraycopy(data, off, pending, pendingLen, fill);
                v1 = round(v1, readInt(pending, 0));
                v2 = round(v2, readInt(pending, 4));
                v3 = round(v3, readInt(pending, 8));
                v4 = round(v4, readInt(pending, 12));
                off += fill;
                len -= fill;
                pendingLen = 0;
            }

            // Process full 16-byte stripes
            while (len >= 16) {
                v1 = round(v1, readInt(data, off));
                v2 = round(v2, readInt(data, off + 4));
                v3 = round(v3, readInt(data, off + 8));
                v4 = round(v4, readInt(data, off + 12));
                off += 16;
                len -= 16;
            }

            // Buffer remainder
            if (len > 0) {
                System.arraycopy(data, off, pending, 0, len);
                pendingLen = len;
            }
        }

        int digest() {
            int h;
            if (totalLen >= 16) {
                h = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
                  + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
            } else {
                h = PRIME5;
            }
            h += (int) totalLen;

            int p = 0;
            while (p + 4 <= pendingLen) {
                h += readInt(pending, p) * PRIME3;
                h  = Integer.rotateLeft(h, 17) * PRIME4;
                p += 4;
            }
            while (p < pendingLen) {
                h += (pending[p] & 0xFF) * PRIME5;
                h  = Integer.rotateLeft(h, 11) * PRIME1;
                p++;
            }

            h ^= h >>> 15; h *= PRIME2;
            h ^= h >>> 13; h *= PRIME3;
            h ^= h >>> 16;
            return h;
        }
    }

    private XXHash32() {}
}
