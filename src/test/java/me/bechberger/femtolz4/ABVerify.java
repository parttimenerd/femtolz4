package me.bechberger.femtolz4;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Byte-identity A/B verifier for compressor changes: compresses each file with
 * {@code LZ4.compressorJava(level)} loaded from two isolated sibling jars and
 * requires byte-identical outputs, then round-trips each side through its own
 * {@code LZ4.decompressJava()}. Prints one line per (file, level); exits
 * non-zero on any mismatch. Complements DualBench (lengths only).
 *
 * Usage: java -cp build/test-classes me.bechberger.femtolz4.ABVerify &lt;levels-csv&gt; &lt;jarA&gt; &lt;jarB&gt; &lt;file...&gt;
 */
public final class ABVerify {

    static final class Side {
        final Method compFactory, decompFactory;
        final Class<?> decIface;
        Side(String jar) throws Exception {
            URLClassLoader cl = new URLClassLoader(new URL[]{ new File(jar).toURI().toURL() }, null);
            Class<?> lz4 = Class.forName("me.bechberger.femtolz4.LZ4", true, cl);
            compFactory   = lz4.getMethod("compressorJava", int.class);
            decompFactory = lz4.getMethod("decompressJava");
            // the factory returns a package-private lambda: invoke via the public interface
            decIface = Class.forName("me.bechberger.femtolz4.LZ4$Decompressor", false, cl);
        }
    }

    static byte[] compressWith(Method factory, int level, byte[] src) throws Exception {
        Object comp = factory.invoke(null, level);
        Method cm = comp.getClass().getMethod("compress",
                byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        byte[] dst = new byte[src.length + 17 + src.length / 255];
        int n = (int) cm.invoke(comp, src, 0, src.length, dst, 0, dst.length);
        return Arrays.copyOf(dst, n);
    }

    static byte[] decompressWith(Method factory, Class<?> decIface, byte[] comp, int origLen) throws Exception {
        Object dec = factory.invoke(null);
        Method dm = decIface.getMethod("decompress",
                byte[].class, int.class, byte[].class, int.class, int.class);
        byte[] out = new byte[origLen];
        dm.invoke(dec, comp, 0, out, 0, origLen);
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: ABVerify <levels-csv> <jarA> <jarB> <file...>");
            System.exit(2);
        }
        int[] levels = Arrays.stream(args[0].split(",")).mapToInt(Integer::parseInt).toArray();
        Side A = new Side(args[1]), B = new Side(args[2]);
        boolean fail = false;
        for (int i = 3; i < args.length; i++) {
            byte[] src = Files.readAllBytes(Path.of(args[i]));
            for (int level : levels) {
                byte[] a = compressWith(A.compFactory, level, src);
                byte[] b = compressWith(B.compFactory, level, src);
                boolean same = Arrays.equals(a, b);
                boolean rta = Arrays.equals(src, decompressWith(A.decompFactory, A.decIface, a, src.length));
                boolean rtb = Arrays.equals(src, decompressWith(B.decompFactory, B.decIface, b, src.length));
                System.out.printf("%-42s L%-3d A=%9d B=%9d compIdent=%s rtA=%s rtB=%s%n",
                        Path.of(args[i]).getFileName(), level, a.length, b.length, same, rta, rtb);
                if (!same || !rta || !rtb) fail = true;
            }
        }
        System.out.println(fail ? "FAIL" : "ALL OK");
        System.exit(fail ? 1 : 0);
    }
}
