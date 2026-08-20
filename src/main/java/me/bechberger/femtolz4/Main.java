package me.bechberger.femtolz4;

import java.io.*;
import java.nio.file.*;

/**
 * Minimal command-line interface:
 * <pre>
 *   femtolz4.jar compress   &lt;input&gt; &lt;output.lz4&gt; [level]
 *   femtolz4.jar decompress &lt;input.lz4&gt; &lt;output&gt;
 * </pre>
 * Level is the maxChain depth (default 1 = fastest, 8 = balanced).
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: femtolz4 compress|decompress <input> <output> [level]");
            System.exit(1);
        }
        String cmd    = args[0];
        Path   input  = Path.of(args[1]);
        Path   output = Path.of(args[2]);

        if (cmd.equals("compress")) {
            int level = args.length >= 4 ? Integer.parseInt(args[3]) : LZ4FrameOutputStream.LEVEL_FAST;
            try (InputStream  in  = Files.newInputStream(input);
                 OutputStream out = new LZ4FrameOutputStream(Files.newOutputStream(output),
                                                              LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE,
                                                              level)) {
                in.transferTo(out);
            }
        } else if (cmd.equals("decompress")) {
            try (InputStream  in  = new LZ4FrameInputStream(Files.newInputStream(input));
                 OutputStream out = Files.newOutputStream(output)) {
                in.transferTo(out);
            }
        } else {
            System.err.println("unknown command: " + cmd);
            System.exit(1);
        }
        System.err.printf("%s %s → %s%n", cmd, input.getFileName(), output.getFileName());
    }
}
