package me.bechberger.femtolz4;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests: compress/decompress called from many threads simultaneously
 * must always produce correct results. All mutable state is ThreadLocal so there
 * should be no cross-thread interference; these tests confirm that.
 *
 * Also tests that thread-local buffer reuse (TL_DST, TL_DECOMP) does not corrupt
 * results across sequential calls on the same thread.
 */
class ConcurrencyTest {

    private static final int THREADS  = Runtime.getRuntime().availableProcessors() * 2;
    private static final int DURATION_MS = 2000;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] deterministicData(int seed, int len) {
        byte[] b = new byte[len];
        Random rng = new Random(seed);
        rng.nextBytes(b);
        return b;
    }

    /**
     * Run {@code task} on {@code nThreads} threads for {@code durationMs} ms.
     * Any exception thrown by a worker is rethrown in the calling thread.
     */
    private static long runParallel(int nThreads, long durationMs, Callable<Void> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicInteger ops = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < nThreads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                long deadline = System.currentTimeMillis() + durationMs;
                while (System.currentTimeMillis() < deadline
                        && firstFailure.get() == null) {
                    try {
                        task.call();
                        ops.incrementAndGet();
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                        return null;
                    }
                }
                return null;
            }));
        }

        start.countDown();
        pool.shutdown();
        pool.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS);

        for (Future<?> f : futures) f.get(); // propagate ExecutionException
        if (firstFailure.get() != null) {
            Throwable t = firstFailure.get();
            if (t instanceof AssertionError ae) throw ae;
            throw new RuntimeException(t);
        }
        return ops.get();
    }

    // ── T1: parallel compress + decompress, all chains ────────────────────────

    @Test void parallelCompressDecompress_allChains() throws Exception {
        int[] chains = {0, 1, 2, 4, 8, 256};
        // Pre-generate test data indexed by thread
        byte[][] inputs = new byte[THREADS][];
        for (int i = 0; i < THREADS; i++) inputs[i] = deterministicData(i, 4096 + i * 13);

        for (int chain : chains) {
            long ops = runParallel(THREADS, DURATION_MS, () -> {
                int id = (int)(Thread.currentThread().getId() % THREADS);
                byte[] src = inputs[id];
                // Native path
                byte[] comp = LZ4.compress(src, chain);
                assertArrayEquals(src, LZ4.decompress(comp, src.length),
                        "native chain=" + chain + " thread=" + id);
                // Java path
                byte[] jcomp = LZ4.compressJava(src, chain);
                assertArrayEquals(src, LZ4.decompressJava(jcomp, src.length),
                        "java chain=" + chain + " thread=" + id);
                // Cross: java compress / native decompress
                assertArrayEquals(src, LZ4.decompress(jcomp, src.length),
                        "cross java/native chain=" + chain + " thread=" + id);
                return null;
            });
            assertTrue(ops > 0, "no ops completed for chain=" + chain);
        }
    }

    // ── T2: many threads, each with a different input size ───────────────────

    @Test void parallelMixedSizes() throws Exception {
        int[] sizes = {0, 1, 11, 12, 13, 63, 64, 255, 256, 1023, 1024, 65535, 65536};
        CyclicBarrier barrier = new CyclicBarrier(sizes.length);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int s : sizes) {
            byte[] src = deterministicData(s, s);
            tasks.add(() -> {
                barrier.await();  // all threads start simultaneously
                long deadline = System.currentTimeMillis() + DURATION_MS;
                while (System.currentTimeMillis() < deadline) {
                    for (int chain : new int[]{0, 1, 256}) {
                        assertArrayEquals(src, LZ4.decompress(LZ4.compress(src, chain), src.length),
                                "size=" + s + " chain=" + chain);
                        assertArrayEquals(src, LZ4.decompressJava(LZ4.compressJava(src, chain), src.length),
                                "java size=" + s + " chain=" + chain);
                    }
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(sizes.length);
        try {
            for (Future<?> f : pool.invokeAll(tasks)) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    // ── T3: TL buffer reuse — second call on same thread must not corrupt first ─

    @Test void threadLocalReuseDoesNotCorrupt() {
        // Two consecutive compress/decompress calls on the same thread using
        // different inputs — the TL byte[] buffers are reused for the second
        // call; the first result must remain valid.
        int N = 1000;
        Random rng = new Random(42);
        for (int i = 0; i < N; i++) {
            int len1 = 100 + rng.nextInt(4000);
            int len2 = 100 + rng.nextInt(4000);
            byte[] src1 = new byte[len1];
            byte[] src2 = new byte[len2];
            rng.nextBytes(src1);
            rng.nextBytes(src2);

            // Compress both; keep both compressed forms
            byte[] comp1 = LZ4.compressJava(src1, 1);
            byte[] comp2 = LZ4.compressJava(src2, 1);

            // Decompress comp1 first, then comp2; verify both
            byte[] out1 = LZ4.decompressJava(comp1, len1);
            byte[] out2 = LZ4.decompressJava(comp2, len2);
            assertArrayEquals(src1, out1, "TL reuse corrupted result 1 at i=" + i);
            assertArrayEquals(src2, out2, "TL reuse corrupted result 2 at i=" + i);
        }
    }

    // ── T4: parallel frame streams (each thread owns its own stream) ──────────

    @Test void parallelFrameStreams() throws Exception {
        runParallel(THREADS, DURATION_MS, () -> {
            int id = (int)(Thread.currentThread().getId() % THREADS);
            byte[] src = deterministicData(id * 31, 8192 + id * 97);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos)) {
                out.write(src);
            }
            byte[] frame = baos.toByteArray();

            try (LZ4FrameInputStream in = new LZ4FrameInputStream(
                    new ByteArrayInputStream(frame))) {
                assertArrayEquals(src, in.readAllBytes(), "frame round-trip thread=" + id);
            }
            return null;
        });
    }

    // ── T5: parallel XXHash32 ─────────────────────────────────────────────────

    @Test void parallelXXHash32() throws Exception {
        // Pre-compute expected hashes on a single thread
        byte[][] inputs = new byte[THREADS][];
        int[] expected  = new int[THREADS];
        for (int i = 0; i < THREADS; i++) {
            inputs[i]  = deterministicData(i * 7, 1024 + i * 3);
            expected[i] = XXHash32.hash(inputs[i], 0, inputs[i].length);
        }

        runParallel(THREADS, DURATION_MS, () -> {
            int id = (int)(Thread.currentThread().getId() % THREADS);
            int got = XXHash32.hash(inputs[id], 0, inputs[id].length);
            assertEquals(expected[id], got, "xxhash mismatch thread=" + id);
            return null;
        });
    }

    // ── T6: property — random inputs round-trip under N parallel threads ──────

    @Property
    @Tag("deep-fuzz")
    void propertyParallelRoundTrip(@ForAll @Size(max = 16384) byte[] data) throws Exception {
        runParallel(Math.min(THREADS, 8), 200, () -> {
            for (int chain : new int[]{0, 1, 256}) {
                assertArrayEquals(data, LZ4.decompress(LZ4.compress(data, chain), data.length),
                        "native chain=" + chain);
                assertArrayEquals(data, LZ4.decompressJava(LZ4.compressJava(data, chain), data.length),
                        "java chain=" + chain);
            }
            return null;
        });
    }

    // ── T7: property — garbage input never crashes under parallel load ────────

    @Property
    @Tag("deep-fuzz")
    void propertyParallelGarbageNeverCrashes(@ForAll @Size(max = 2048) byte[] garbage,
                                             @ForAll @IntRange(min = 0, max = 8192) int dstLen)
            throws Exception {
        runParallel(Math.min(THREADS, 8), 100, () -> {
            try { LZ4.decompress(garbage, 0, garbage.length, new byte[dstLen], 0, dstLen); }
            catch (LZ4Exception ok) {}
            try { LZ4.decompressJava(garbage, dstLen); }
            catch (LZ4Exception ok) {}
            return null;
        });
    }

    // ── T8: same compressed buffer decompressed by many threads simultaneously ─

    @Test void sharedCompressedBufferParallelDecompress() throws Exception {
        // Multiple threads decompress from the *same* read-only compressed byte[]
        // — decompressor must not write to src, only to its own dst.
        byte[] src  = deterministicData(99, 65536);
        byte[] comp = LZ4.compress(src, 1);

        runParallel(THREADS, DURATION_MS, () -> {
            assertArrayEquals(src, LZ4.decompress(comp, src.length), "native shared src");
            assertArrayEquals(src, LZ4.decompressJava(comp, src.length), "java shared src");
            return null;
        });
    }

    // ── T9: interleaved compress+decompress on same thread (TL stress) ────────

    @RepeatedTest(5)
    void interleavedCallsSameThread() {
        // Simulates a use pattern where compress and decompress alternate rapidly,
        // exercising that TL_DST and TL_DECOMP don't alias each other.
        Random rng = new Random(123);
        for (int i = 0; i < 500; i++) {
            byte[] src = new byte[rng.nextInt(8192)];
            rng.nextBytes(src);
            byte[] comp  = LZ4.compressJava(src, 1);   // uses TL_DST
            byte[] back  = LZ4.decompressJava(comp, src.length); // uses TL_DECOMP
            byte[] comp2 = LZ4.compressJava(back, 1);  // TL_DST reused
            assertArrayEquals(src, back, "decompress wrong at i=" + i);
            assertArrayEquals(comp, comp2, "re-compress differs at i=" + i);
        }
    }
}
