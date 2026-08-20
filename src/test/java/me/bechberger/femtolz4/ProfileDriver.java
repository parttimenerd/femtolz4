package me.bechberger.femtolz4;

import java.nio.file.*;

/** Long-running driver for async-profiler. Pass "compress" or "decompress". */
public class ProfileDriver {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "compress";
        int chain   = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        byte[] data = Files.readAllBytes(Path.of(System.getProperty("user.home") + "/Downloads/failure.jfr"));
        byte[] comp = LZ4.compress(data, chain);

        // warm up JIT
        for (int i = 0; i < 20; i++) {
            if (mode.equals("decompress")) LZ4.decompress(comp, data.length);
            else LZ4.compress(data, chain);
        }

        System.err.println("ready – profiler may attach now");
        Thread.sleep(500);

        long deadline = System.currentTimeMillis() + 30_000;
        long ops = 0;
        while (System.currentTimeMillis() < deadline) {
            if (mode.equals("decompress")) LZ4.decompress(comp, data.length);
            else LZ4.compress(data, chain);
            ops++;
        }
        System.err.println("done: " + ops + " ops");
    }
}
