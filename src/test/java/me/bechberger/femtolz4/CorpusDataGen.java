package me.bechberger.femtolz4;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

/**
 * Deterministic benchmark corpus generator.
 *
 * <p>Writes reproducible corpora to a directory so local and remote benchmark
 * runs compare the same bytes.
 */
public final class CorpusDataGen {

    private static final int MB = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path outDir = args.length > 0 ? Path.of(args[0]) : Path.of("/tmp/femtolz4-corpora");
        Files.createDirectories(outDir);

        writeCorpus(outDir, "random-20m.bin", random(20 * MB, 0x5eed5eedL));
        writeCorpus(outDir, "text-20m.bin", text(20 * MB));
        writeCorpus(outDir, "json-10m.bin", json(10 * MB));
        writeCorpus(outDir, "mixed-20m.bin", mixed(20 * MB));
        writeCorpus(outDir, "rle-20m.bin", new byte[20 * MB]);
        writeCorpus(outDir, "offset4-20m.bin", repeat(new byte[] {1, 2, 3, 4}, 20 * MB));
        writeCorpus(outDir, "offset16-20m.bin", repeat(random(16, 0x16161616L), 20 * MB));
    }

    private static void writeCorpus(Path outDir, String name, byte[] data) throws Exception {
        Path file = outDir.resolve(name);
        Files.write(file, data);
        System.out.printf("GEN,%s,size=%d,sha256=%s%n", file, data.length, sha256(data));
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] text(int size) {
        String[] lines = {
                "2026-08-22T18:45:17Z INFO service=compress worker=17 status=ok bytes=65536 ratio=2.81",
                "2026-08-22T18:45:17Z DEBUG stage=chain2 candidate=hit len=11 off=24",
                "2026-08-22T18:45:17Z WARN backpressure queue=encoder depth=3",
                "2026-08-22T18:45:17Z INFO request=79e2f block=1048576 checksum=pass"
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        for (int i = 0; out.size() < size; i++) {
            byte[] row = (lines[i % lines.length] + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(row, 0, Math.min(row.length, size - out.size()));
        }
        return out.toByteArray();
    }

    private static byte[] json(int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        for (int i = 0; out.size() < size; i++) {
            byte[] row = ("{\"time\":\"2026-08-22T18:00:" + (i % 60)
                    + "Z\",\"worker\":" + (i % 128)
                    + ",\"event\":\"compress\",\"bytes\":" + (4096 + i % 65536)
                    + ",\"ok\":true}\n").getBytes(StandardCharsets.UTF_8);
            out.write(row, 0, Math.min(row.length, size - out.size()));
        }
        return out.toByteArray();
    }

    private static byte[] words(int size) {
        String[] dict = {
                "compress", "decompress", "window", "entropy", "corpus", "latency", "throughput",
                "profile", "token", "offset", "literal", "match", "sequence", "frame", "checksum"
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        for (int i = 0; out.size() < size; i++) {
            StringBuilder line = new StringBuilder();
            for (int w = 0; w < 12; w++) {
                if (w > 0) line.append(' ');
                line.append(dict[(i + w * 3) % dict.length]);
            }
            line.append('\n');
            byte[] row = line.toString().getBytes(StandardCharsets.UTF_8);
            out.write(row, 0, Math.min(row.length, size - out.size()));
        }
        return out.toByteArray();
    }

    private static byte[] mixed(int size) {
        byte[] data = new byte[size];
        byte[] rnd = random(64 * 1024, 0x12345678L);
        byte[] txt = text(64 * 1024);
        int pos = 0;
        int chunk = 0;
        while (pos < size) {
            byte[] src = (chunk++ & 1) == 0 ? rnd : txt;
            int len = Math.min(64 * 1024, size - pos);
            System.arraycopy(src, 0, data, pos, len);
            pos += len;
        }
        return data;
    }

    private static byte[] repeat(byte[] pattern, int size) {
        byte[] data = new byte[size];
        int pos = 0;
        while (pos < size) {
            int len = Math.min(pattern.length, size - pos);
            System.arraycopy(pattern, 0, data, pos, len);
            pos += len;
        }
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private CorpusDataGen() {}
}
