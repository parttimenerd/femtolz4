package me.bechberger.femtolz4;

import java.nio.file.*;

/**
 * Long-running driver for async-profiler.
 * Usage: ProfileDriver [compress|decompress] [chain] [file]
 * Defaults: compress, chain=1, ~/Downloads/container.jfr
 * To force pure-Java path: compress-java or decompress-java
 */
public class ProfileDriver {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "compress";
        int chain   = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        String filePath = args.length > 2 ? args[2]
                        : System.getProperty("user.home") + "/Downloads/container.jfr";

        byte[] data = Files.readAllBytes(Path.of(filePath));
        System.err.println("file: " + filePath + "  size: " + data.length + " bytes");
        System.err.println("mode: " + mode + "  chain: " + chain
                           + "  native: " + LZ4.isNativeAvailable());

        byte[] comp = mode.contains("java")
                    ? LZ4.compressJava(data, chain)
                    : LZ4.compress(data, chain);

        // warm up JIT
        for (int i = 0; i < 50; i++) {
            switch (mode) {
                case "decompress"      -> LZ4.decompress(comp, data.length);
                case "decompress-java" -> LZ4.decompressJava(comp, data.length);
                case "compress-java"   -> LZ4.compressJava(data, chain);
                default                -> LZ4.compress(data, chain);
            }
        }

        System.err.println("warmed – profiler may attach now");

        long deadline = System.currentTimeMillis() + 30_000;
        long ops = 0;
        while (System.currentTimeMillis() < deadline) {
            switch (mode) {
                case "decompress"      -> LZ4.decompress(comp, data.length);
                case "decompress-java" -> LZ4.decompressJava(comp, data.length);
                case "compress-java"   -> LZ4.compressJava(data, chain);
                default                -> LZ4.compress(data, chain);
            }
            ops++;
        }
        System.err.printf("done: %d ops  %.1f MB/s%n",
            ops, (double) data.length * ops / 1e6 / 30.0);
    }
}
