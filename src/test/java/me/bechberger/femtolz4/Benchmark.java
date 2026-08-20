package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Throughput benchmark: femtolz4-native vs femtolz4-java vs at.yawk lz4-java.
 *
 * Run:
 *   mvn test-compile -q
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "target/test-classes:target/femtolz4-0.1.0.jar:$(ls ~/.m2/repository/at/yawk/lz4/lz4-java/1.11.0/*.jar)" \
 *        me.bechberger.femtolz4.Benchmark
 */
public class Benchmark {

    static final String[] FILES = {
        System.getProperty("user.home") + "/Downloads/aprof.jfr",          //  503 KB
        System.getProperty("user.home") + "/Downloads/cpu_profile.jfr",    //  829 KB
        System.getProperty("user.home") + "/Downloads/HA_gc_details.jfr",  //  3.2 MB
        System.getProperty("user.home") + "/Downloads/jvm17-gc-jfc.jfr",   //  6.7 MB
        System.getProperty("user.home") + "/Downloads/flight.jfr",         //   12 MB
        System.getProperty("user.home") + "/Downloads/failure.jfr",        //   18 MB
        "/tmp/large_test.bin",                                               // ~266 MB
    };

    static final int WARMUP_REPS  = 4;
    static final int MEASURE_REPS = 8;

    // ── Impl interface ────────────────────────────────────────────────────────

    interface Impl {
        String name();
        /** Compress src[0..src.length) and return compressed bytes. */
        byte[] compress(byte[] src);
        /** Decompress comp back to originalLen bytes. */
        byte[] decompress(byte[] comp, int originalLen);
    }

    // ── femtolz4 (native path active when available) ──────────────────────────

    static final Impl FEMTO = new Impl() {
        public String name() { return LZ4.isNativeAvailable() ? "femto-native" : "femto-java"; }
        public byte[] compress(byte[] s) { return LZ4.compress(s, 1); }
        public byte[] decompress(byte[] c, int n) { return LZ4.decompress(c, n); }
    };

    // ── femtolz4 pure-Java (direct call, no native dispatch) ──────────────────

    static final Impl FEMTO_JAVA = new Impl() {
        public String name() { return "femto-java"; }
        public byte[] compress(byte[] s) { return LZ4.compressJava(s); }
        public byte[] decompress(byte[] c, int n) { return LZ4.decompressJava(c, n); }
    };

    // ── at.yawk lz4-java (net.jpountz package) – native ──────────────────────

    static LZ4Factory yawkNative;
    static LZ4Factory yawkJava;
    static {
        try { yawkNative = LZ4Factory.nativeInstance(); }
        catch (Throwable t) { yawkNative = null; System.err.println("yawkat native unavail: " + t.getMessage()); }
        yawkJava = LZ4Factory.safeInstance();
    }

    static Impl yawkImpl(LZ4Factory f, String label) {
        if (f == null) return null;
        LZ4Compressor        c = f.fastCompressor();
        LZ4FastDecompressor  d = f.fastDecompressor();
        return new Impl() {
            public String name() { return label; }
            public byte[] compress(byte[] src) {
                byte[] dst = new byte[c.maxCompressedLength(src.length)];
                int n = c.compress(src, 0, src.length, dst, 0, dst.length);
                return Arrays.copyOf(dst, n);
            }
            public byte[] decompress(byte[] comp, int len) {
                byte[] dst = new byte[len];
                d.decompress(comp, 0, dst, 0, len);
                return dst;
            }
        };
    }

    // ── Pure-Java compress/decompress (same algorithm as LZ4.java) ───────────

    static int javaCompress(byte[] src, byte[] dst) {
        final int WS = 1<<16, WM = WS-1, HB = 16, HS = 1<<HB, MM = 4, PAD = 5, NIL = Integer.MIN_VALUE;
        if (src.length == 0) return 0;
        int[] head = new int[HS]; int[] tail = new int[WS];
        Arrays.fill(head, NIL); Arrays.fill(tail, NIL);
        int op=0, ls=0, pos=0, se=src.length-PAD;
        while (pos < src.length) {
            int ml=0, md=0;
            if (pos <= se-2) {
                int mm=se-pos, lim=pos-WS, cl=1;
                int v=g4(src,pos)^((src[pos+4]&0xFF)<<24); int h=(v*0x9E3779B9)>>>(32-HB);
                for (int sv=head[h]; sv>lim; sv=tail[sv&WM]) {
                    if (g4(src,sv)!=g4(src,pos)||src[sv+ml]!=src[pos+ml]) { if(--cl==0) break; continue; }
                    int len=MM; while(len<mm&&src[sv+len]==src[pos+len]) len++;
                    if(len>ml){ml=len;md=pos-sv;if(len==mm)break;} if(--cl==0)break;
                }
            }
            if (ml>=MM) {
                int ll=pos-ls, mx=ml-MM;
                dst[op++]=(byte)((Math.min(ll,15)<<4)|Math.min(mx,15));
                if(ll>=15){int r=ll-15;for(;r>=255;r-=255)dst[op++]=(byte)255;dst[op++]=(byte)r;}
                if(ll>0){System.arraycopy(src,ls,dst,op,ll);op+=ll;}
                dst[op++]=(byte)md; dst[op++]=(byte)(md>>>8);
                if(mx>=15){int r=mx-15;for(;r>=255;r-=255)dst[op++]=(byte)255;dst[op++]=(byte)r;}
                ls=pos+ml; int lim=Math.min(ls,se+1);
                while(pos<lim){int v2=g4(src,pos)^((src[pos+4]&0xFF)<<24);int h2=(v2*0x9E3779B9)>>>(32-HB);tail[pos&WM]=head[h2];head[h2]=pos;pos+=2;}
                pos=ls;
            } else {
                if(pos<=se){int v2=g4(src,pos)^((src[pos+4]&0xFF)<<24);int h2=(v2*0x9E3779B9)>>>(32-HB);tail[pos&WM]=head[h2];head[h2]=pos;}
                pos++;
            }
        }
        int ll=src.length-ls;
        if(ll>0){dst[op++]=(byte)(Math.min(ll,15)<<4);if(ll>=15){int r=ll-15;for(;r>=255;r-=255)dst[op++]=(byte)255;dst[op++]=(byte)r;}System.arraycopy(src,ls,dst,op,ll);op+=ll;}
        return op;
    }

    static int g4(byte[] b, int p) { return (b[p]&0xFF)|((b[p+1]&0xFF)<<8)|((b[p+2]&0xFF)<<16)|((b[p+3]&0xFF)<<24); }

    static byte[] javaDecompress(byte[] src, int sz) {
        byte[] dst=new byte[sz]; int ip=0,op=0;
        while(ip<src.length){
            int tok=src[ip++]&0xFF,ll=tok>>>4,mx=tok&0xF,b;
            if(ll==15){do{b=src[ip++]&0xFF;ll+=b;}while(b==255);}
            System.arraycopy(src,ip,dst,op,ll);ip+=ll;op+=ll;
            if(ip>=src.length)break;
            int off=(src[ip]&0xFF)|((src[ip+1]&0xFF)<<8);ip+=2;
            int mlen=4+mx; if(mx==15){do{b=src[ip++]&0xFF;mlen+=b;}while(b==255);}
            int ms=op-off; for(int i=0;i<mlen;i++)dst[op+i]=dst[ms+i]; op+=mlen;
        }
        return dst;
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        List<Impl> impls = new ArrayList<>(Arrays.asList(FEMTO, FEMTO_JAVA));
        Impl yn = yawkImpl(yawkNative, "yawkat-native");
        Impl yj = yawkImpl(yawkJava,   "yawkat-java");
        if (yn != null) impls.add(yn);
        impls.add(yj);

        System.out.printf("native available: %s%n%n", LZ4.isNativeAvailable());
        System.out.printf("%-18s  %8s  %8s  %6s%n", "impl", "comp MB/s", "dec MB/s", "ratio");
        System.out.println("-".repeat(50));

        for (String path : FILES) {
            Path p = Path.of(path);
            if (!Files.exists(p)) continue;
            byte[] data = Files.readAllBytes(p);
            double mb = data.length / 1_000_000.0;
            System.out.printf("%n=== %s  (%.0f MB) ===%n", p.getFileName(), mb);

            for (Impl impl : impls) {
                // warmup
                byte[] comp = null;
                for (int i = 0; i < WARMUP_REPS; i++) {
                    comp = impl.compress(data);
                    impl.decompress(comp, data.length);
                }
                // measure compress
                long t0 = System.nanoTime();
                for (int i = 0; i < MEASURE_REPS; i++) comp = impl.compress(data);
                double cMBs = mb * MEASURE_REPS / ((System.nanoTime()-t0)/1e9);
                // measure decompress
                final byte[] cf = comp;
                t0 = System.nanoTime();
                for (int i = 0; i < MEASURE_REPS; i++) impl.decompress(cf, data.length);
                double dMBs = mb * MEASURE_REPS / ((System.nanoTime()-t0)/1e9);

                double ratio = (double) data.length / comp.length;
                System.out.printf("  %-18s  %8.0f  %8.0f  %5.2fx%n",
                    impl.name(), cMBs, dMBs, ratio);
            }
        }
    }
}
