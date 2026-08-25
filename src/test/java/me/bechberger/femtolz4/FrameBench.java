package me.bechberger.femtolz4;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Quick frame-stream benchmark for iteration: measures LZ4FrameOutputStream /
 * LZ4FrameInputStream throughput on a single file.
 *
 * Usage: FrameBench [-Dbench.levels=1,9] [-Dbench.reps=5] file...
 * Prints CSV rows: CSV,FILE,LEVEL,compMBps,decMBps,ratio
 */
public class FrameBench {

    public static void main(String[] args) throws Exception {
        int[] levels = Arrays.stream(System.getProperty("bench.levels", "1,9").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int reps = Integer.getInteger("bench.reps", 5);

        for (String path : args) {
            byte[] data = Files.readAllBytes(Path.of(path));
            double mb = data.length / 1_000_000.0;
            for (int level : levels) {
                // warmup + correctness
                byte[] comp = compress(data, level);
                byte[] back = decompress(comp, data.length);
                if (!Arrays.equals(data, back))
                    throw new AssertionError("frame round-trip failed: " + path + " level " + level);

                double best = 0;
                for (int r = 0; r < reps; r++) {
                    long t0 = System.nanoTime();
                    comp = compress(data, level);
                    best = Math.max(best, mb / ((System.nanoTime() - t0) / 1e9));
                }
                double cMBps = best;
                final byte[] c = comp;
                best = 0;
                for (int r = 0; r < reps; r++) {
                    long t0 = System.nanoTime();
                    decompress(c, data.length);
                    best = Math.max(best, mb / ((System.nanoTime() - t0) / 1e9));
                }
                double dMBps = best;
                System.out.printf(Locale.ROOT, "CSV,%s,%d,%.0f,%.0f,%.3f%n",
                        Path.of(path).getFileName(), level, cMBps, dMBps,
                        (double) data.length / comp.length);
            }
        }
    }

    private static byte[] compress(byte[] data, int level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2 + 256);
        try (LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos, level)) {
            out.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] decompress(byte[] comp, int originalLen) throws IOException {
        byte[] dst = new byte[originalLen];
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            int off = 0, rem = originalLen;
            while (rem > 0) {
                int n = in.read(dst, off, rem);
                if (n < 0) break;
                off += n; rem -= n;
            }
        }
        return dst;
    }
}
