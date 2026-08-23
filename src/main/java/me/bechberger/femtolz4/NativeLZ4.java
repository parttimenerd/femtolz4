package me.bechberger.femtolz4;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Tries to load a platform-specific native LZ4 library bundled in the JAR,
 * exposing the two block-level operations used by {@link LZ4}.
 *
 * <p>If the native library cannot be loaded (wrong platform, extract failure,
 * etc.) this class stays in {@code NOT_AVAILABLE} state and {@link LZ4} falls
 * back to the pure-Java implementation transparently.
 */
final class NativeLZ4 {

    static final boolean AVAILABLE;

    static {
        boolean ok = false;
        try {
            String res = nativeResourcePath();
            if (res != null) {
                try (InputStream in = NativeLZ4.class.getResourceAsStream(res)) {
                    if (in != null) {
                        String suffix = res.endsWith(".dylib") ? ".dylib" : ".so";
                        Path tmp = Files.createTempFile("femtolz4", suffix);
                        tmp.toFile().deleteOnExit();
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        System.load(tmp.toString());
                        ok = true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        AVAILABLE = ok;
    }

    /** Returns the classpath resource for the current platform, or null if unsupported. */
    private static String nativeResourcePath() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac") && arch.contains("aarch64"))
            return "/native/darwin-aarch64/libfemtolz4.dylib";
        if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64")))
            return "/native/linux-amd64/libfemtolz4.so";
        return null;
    }

    /** Returns compressed size, or 0 on failure. */
    static native int compress(byte[] src, int srcOff, int srcLen,
                               byte[] dst, int dstOff, int dstLen,
                               int maxChain);

    /** Returns decompressed size, or negative on error. */
    static native int decompress(byte[] src, int srcOff, int srcLen,
                                 byte[] dst, int dstOff, int dstLen);

    private NativeLZ4() {}
}
