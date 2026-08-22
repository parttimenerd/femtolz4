package me.bechberger.femtolz4;

import java.util.Arrays;

/**
 * JNI crossover measurement: finds the block size where native JNI beats pure Java.
 * Run: java --enable-native-access=ALL-UNNAMED -cp target/femtolz4-0.1.0.jar me.bechberger.femtolz4.CrossoverTest
 */
public class CrossoverTest {
    static final int ITERS = 5000;

    public static void main(String[] args) {
        byte[] bigBuf = new byte[1024 * 1024];
        for (int i = 0; i < bigBuf.length; i++)
            bigBuf[i] = (byte) ((i % 512 < 256) ? (i & 0x7F) : (i * 7 + 13));

        int[] sizes = {64, 256, 512, 1024, 4096, 8192, 16384, 32768, 65536, 262144};

        System.out.println("native available: " + LZ4.isNativeAvailable());
        System.out.printf("%-12s  %12s  %12s  %8s%n",
            "block_bytes", "native_MB/s", "java_MB/s", "winner");
        System.out.println("-".repeat(55));

        for (int sz : sizes) {
            byte[] src = Arrays.copyOf(bigBuf, sz);
            int maxComp = LZ4.maxCompressedLength(sz);
            byte[] dstN = new byte[maxComp], dstJ = new byte[maxComp];

            // warmup native
            for (int i = 0; i < 500; i++) LZ4.compress(src, 0, sz, dstN, 0, 1);
            // measure native (JNI path)
            long t0 = System.nanoTime();
            for (int i = 0; i < ITERS; i++) LZ4.compress(src, 0, sz, dstN, 0, 1);
            double nativeMBs = (double) sz * ITERS / ((System.nanoTime() - t0) / 1e9) / 1e6;

            // warmup java
            for (int i = 0; i < 500; i++) LZ4.compressJava(src, 0, sz, dstJ, 0, 1);
            // measure java (pure Java path)
            t0 = System.nanoTime();
            for (int i = 0; i < ITERS; i++) LZ4.compressJava(src, 0, sz, dstJ, 0, 1);
            double javaMBs = (double) sz * ITERS / ((System.nanoTime() - t0) / 1e9) / 1e6;

            System.out.printf("%-12d  %12.0f  %12.0f  %8s%n",
                sz, nativeMBs, javaMBs,
                nativeMBs > javaMBs ? "native" : "java");
        }
    }
}
