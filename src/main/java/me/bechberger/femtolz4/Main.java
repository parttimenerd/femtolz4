package me.bechberger.femtolz4;

import java.io.*;
import java.nio.file.*;

/**
 * Minimal command-line interface:
 * <pre>
 *   femtolz4.jar compress   &lt;input&gt; &lt;output.lz4&gt; [level]
 *   femtolz4.jar decompress &lt;input.lz4&gt; &lt;output&gt;
 * </pre>
 * Level is from 1 (fastest) to 9 (best ratio), default 1.
 * Higher levels spend more CPU time searching for longer matches before writing
 * the compressed frame.
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        int exitCode = run(args, System.err);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args, PrintStream err) throws IOException {
        if (args.length < 3) {
            err.println("usage: femtolz4 compress|decompress <input> <output> [level]");
            return 1;
        }
        String cmd    = args[0];
        Path   input  = Path.of(args[1]);
        Path   output = Path.of(args[2]);

        try {
            if (cmd.equals("compress")) {
                int level = parseLevel(args);
                try (InputStream  in  = Files.newInputStream(input);
                     OutputStream out = new LZ4FrameOutputStream(Files.newOutputStream(output), level)) {
                    in.transferTo(out);
                }
            } else if (cmd.equals("decompress")) {
                try (InputStream  in  = new LZ4FrameInputStream(Files.newInputStream(input));
                     OutputStream out = Files.newOutputStream(output)) {
                    in.transferTo(out);
                }
            } else {
                err.println("unknown command: " + cmd);
                return 1;
            }
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        err.printf("%s %s → %s%n", cmd, input.getFileName(), output.getFileName());
        return 0;
    }

    private static int parseLevel(String[] args) {
        if (args.length < 4) return LZ4FrameOutputStream.LEVEL_FAST;
        try {
            return Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid level: " + args[3]);
        }
    }
}
