package me.bechberger.femtolz4;
import java.lang.invoke.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/* Benchmark two jars in ONE JVM, alternating measurement windows.
   usage: java DualBench <file> <level> <windowSec> <rounds> jarA jarB
   compressor via LZ4.compressorJava(level), decompressor via LZ4.decompressJava(). */
public class DualBench {
    static Object make(ClassLoader cl, String factory, int level) throws Exception {
        Class<?> lz4 = Class.forName("me.bechberger.femtolz4.LZ4", true, cl);
        if (factory.equals("comp"))
            return lz4.getMethod("compressorJava", int.class).invoke(null, level);
        return lz4.getMethod("decompressJava").invoke(null);
    }
    interface Dec { int dec(byte[] s, int so, byte[] d, int off, int len); }
    static Dec wrap(final Object d, ClassLoader cl) {
        try {
            Class<?> iface = Class.forName("me.bechberger.femtolz4.LZ4$Decompressor", true, cl);
            java.lang.reflect.Method m = iface.getMethod("decompress", byte[].class, int.class, byte[].class, int.class, int.class);
            return (s, so, out, off, len) -> {
                try { return (int) m.invoke(d, s, so, out, off, len); }
                catch (Exception e) { throw new RuntimeException(e); }
            };
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static void main(String[] a) throws Exception {
        byte[] src = Files.readAllBytes(Path.of(a[0]));
        int level = Integer.parseInt(a[1]);
        double wsec = Double.parseDouble(a[2]);
        int rounds = Integer.parseInt(a[3]);
        String mode = a.length > 6 ? a[6] : "d";
        URLClassLoader clA = new URLClassLoader(new URL[]{new URL(a[4])}, null);
        URLClassLoader clB = new URLClassLoader(new URL[]{new URL(a[5])}, null);
        if (mode.startsWith("o")) {   // "o<N>": time N full compress ops per measurement
            int n = mode.length() > 1 ? Integer.parseInt(mode.substring(1)) : 3;
            opBench(src, level, rounds, clA, clB, n);
            return;
        }
        if (mode.equals("c")) { compBench(src, level, wsec, rounds, clA, clB); return; }
        byte[] comp;
        {
            Object cA = make(clA, "comp", level);
            byte[] tmp = new byte[(int)(src.length * 1.03) + 64];
            MethodHandle mh = MethodHandles.publicLookup().findVirtual(cA.getClass(), "compress",
                MethodType.methodType(int.class, byte[].class, int.class, int.class, byte[].class, int.class, int.class));
            int cl; try { cl = (int) mh.invoke(cA, src, 0, src.length, tmp, 0, tmp.length); } catch (Throwable e) { throw new RuntimeException(e); }
            comp = Arrays.copyOf(tmp, cl);
        }
        Dec decA = wrap(make(clA, "dec", 0), clA);
        Dec decB = wrap(make(clB, "dec", 0), clB);
        byte[] outA = new byte[src.length], outB = new byte[src.length];
        decA.dec(comp, 0, outA, 0, outA.length);
        decB.dec(comp, 0, outB, 0, outB.length);
        if (!Arrays.equals(outA, outB)) { System.out.println("OUTPUT MISMATCH"); return; }
        // warm both paths fully
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 6e9) { decA.dec(comp, 0, outA, 0, outA.length); decB.dec(comp, 0, outB, 0, outB.length); }
        System.out.println("win  A_MBps  B_MBps   B/A");
        double[] ratios = new double[rounds];
        for (int r = 0; r < rounds; r++) {
            /* Alternate which side measures first each round: the first
               window of a pair systematically gets turbo/thermal headroom,
               which otherwise biases every ratio the same direction. */
            double ra, rb;
            if ((r & 1) == 0) {
                ra = window(decA, comp, outA, wsec);
                rb = window(decB, comp, outB, wsec);
            } else {
                rb = window(decB, comp, outB, wsec);
                ra = window(decA, comp, outA, wsec);
            }
            ratios[r] = rb / ra;
            System.out.printf("%d %8.1f %8.1f %7.3f%n", r, ra, rb, rb/ra);
        }
        Arrays.sort(ratios);
        double med = ratios[rounds/2];
        System.out.printf("median B/A ratio: %.3f\n", med);
    }
    static void compBench(byte[] src, int level, double wsec, int rounds, URLClassLoader clA, URLClassLoader clB) throws Exception {
        Object cA = make(clA, "comp", level), cB = make(clB, "comp", level);
        byte[] d1 = new byte[(int)(src.length*1.03)+64], d2 = new byte[d1.length];
        // resolve via compressor interface type
        java.lang.reflect.Method cmA = cA.getClass().getMethod("compress", byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        java.lang.reflect.Method cmB = cB.getClass().getMethod("compress", byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        int clenA = (int) cmA.invoke(cA, src, 0, src.length, d1, 0, d1.length);
        int clenB = (int) cmB.invoke(cB, src, 0, src.length, d2, 0, d2.length);
        System.out.println("compLen A=" + clenA + " B=" + clenB + " ratioA=" + (double)src.length/clenA + " ratioB=" + (double)src.length/clenB);
        boolean ident = clenA == clenB && java.util.Arrays.equals(d1, 0, clenA, d2, 0, clenB);
        System.out.println("encIdent=" + ident);
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 6e9) { cmA.invoke(cA, src, 0, src.length, d1, 0, d1.length); cmB.invoke(cB, src, 0, src.length, d2, 0, d2.length); }
        double[] ratios = new double[rounds];
        System.out.println("win  A_MBps  B_MBps   B/A");
        for (int r = 0; r < rounds; r++) {
            /* E64: alternate first-measured side per round (turbo bias fix). */
            boolean aFirst = (r & 1) == 0;
            double ra, rb;
            if (aFirst) {
                ra = compWindow(cmA, cA, src, d1, wsec);
                rb = compWindow(cmB, cB, src, d2, wsec);
            } else {
                rb = compWindow(cmB, cB, src, d2, wsec);
                ra = compWindow(cmA, cA, src, d1, wsec);
            }
            ratios[r] = rb/ra;
            System.out.printf("%d %8.1f %8.1f %7.3f%n", r, ra, rb, rb/ra);
        }
        Arrays.sort(ratios);
        System.out.printf("median B/A ratio: %.3f\n", ratios[rounds/2]);
    }
    /* E64: fixed-op timing for large corpora — window mode quantizes when one
       compress op costs seconds (fractional-op boundary error ±1/ops ≈ ±25%).
       Times N full ops each side, alternating first side per round. */
    static void opBench(byte[] src, int level, int rounds, URLClassLoader clA, URLClassLoader clB, int n) throws Exception {
        Object cA = make(clA, "comp", level), cB = make(clB, "comp", level);
        byte[] d1 = new byte[(int)(src.length*1.03)+64], d2 = new byte[d1.length];
        java.lang.reflect.Method cmA = cA.getClass().getMethod("compress", byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        java.lang.reflect.Method cmB = cB.getClass().getMethod("compress", byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        int clenA = (int) cmA.invoke(cA, src, 0, src.length, d1, 0, d1.length);
        int clenB = (int) cmB.invoke(cB, src, 0, src.length, d2, 0, d2.length);
        System.out.println("compLen A=" + clenA + " B=" + clenB);
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 8e9) { cmA.invoke(cA, src, 0, src.length, d1, 0, d1.length); cmB.invoke(cB, src, 0, src.length, d2, 0, d2.length); }
        double[] ratios = new double[rounds];
        System.out.println("win  A_MBps  B_MBps   B/A");
        for (int r = 0; r < rounds; r++) {
            double ra, rb;
            if ((r & 1) == 0) {
                ra = opTime(cmA, cA, src, d1, n);
                rb = opTime(cmB, cB, src, d2, n);
            } else {
                rb = opTime(cmB, cB, src, d2, n);
                ra = opTime(cmA, cA, src, d1, n);
            }
            ratios[r] = rb/ra;
            System.out.printf("%d %8.1f %8.1f %7.3f%n", r, ra, rb, rb/ra);
        }
        java.util.Arrays.sort(ratios);
        System.out.printf("median B/A ratio: %.3f%n", ratios[rounds/2]);
    }
    static double opTime(java.lang.reflect.Method cm, Object c, byte[] src, byte[] dst, int n) throws Exception {
        long t = System.nanoTime();
        for (int i = 0; i < n; i++) cm.invoke(c, src, 0, src.length, dst, 0, dst.length);
        return n * (double) src.length / ((System.nanoTime() - t) / 1e9) / 1e6;
    }
    static double compWindow(java.lang.reflect.Method cm, Object c, byte[] src, byte[] dst, double wsec) throws Exception {
        long ops = 0, t = System.nanoTime(), e = t + (long)(wsec * 1e9);
        while (System.nanoTime() < e) { cm.invoke(c, src, 0, src.length, dst, 0, dst.length); ops++; }
        return ops * (double) src.length / ((System.nanoTime() - t) / 1e9) / 1e6;
    }
    static double window(Dec d, byte[] comp, byte[] out, double secs) {
        long ops = 0; long t0 = System.nanoTime(); long end = t0 + (long)(secs*1e9);
        while (System.nanoTime() < end) { d.dec(comp, 0, out, 0, out.length); ops++; }
        return ops * (double) out.length / ((System.nanoTime()-t0)/1e9) / 1e6;
    }
}
