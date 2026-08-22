package me.bechberger.femtolz4;

import java.nio.file.*;

/**
 * Long-running driver for async-profiler.
 * Uses the low-level block compressor/decompressor, so the tuning argument is
 * still the internal chain depth rather than the framed stream API's 1..9 level.
 * Usage: ProfileDriver [compress|decompress] [chainDepth] [file]
 * Defaults: compress, chainDepth=1, ~/Downloads/container.jfr
 * To force pure-Java path: compress-java or decompress-java
 */
public class ProfileDriver {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "compress";
        int chainDepth = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        String filePath = args.length > 2 ? args[2]
                        : System.getProperty("user.home") + "/Downloads/container.jfr";

        byte[] data = Files.readAllBytes(Path.of(filePath));
        System.err.println("file: " + filePath + "  size: " + data.length + " bytes");
        System.err.println("mode: " + mode + "  chainDepth: " + chainDepth
                           + "  native: " + LZ4.isNativeAvailable());

        byte[] comp = mode.contains("java")
                    ? LZ4.compressJava(data, chainDepth)
                    : LZ4.compress(data, chainDepth);

        // warm up JIT
        for (int i = 0; i < 50; i++) {
            runOne(mode, chainDepth, data, comp);
        }

        System.err.println("warmed – profiler may attach now");

        long deadline = System.currentTimeMillis() + 30_000;
        long ops = 0;
        while (System.currentTimeMillis() < deadline) {
            runOne(mode, chainDepth, data, comp);
            ops++;
        }
        System.err.printf("done: %d ops  %.1f MB/s%n",
            ops, (double) data.length * ops / 1e6 / 30.0);
    }

    private static void runOne(String mode, int chainDepth, byte[] data, byte[] comp) {
        if ("decompress".equals(mode)) {
            LZ4.decompress(comp, data.length);
        } else if ("decompress-java".equals(mode)) {
            LZ4.decompressJava(comp, data.length);
        } else if ("compress-java".equals(mode)) {
            LZ4.compressJava(data, chainDepth);
        } else {
            LZ4.compress(data, chainDepth);
        }
    }
}
