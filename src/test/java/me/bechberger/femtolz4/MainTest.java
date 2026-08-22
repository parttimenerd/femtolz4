package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @Test void compressAndDecompressRoundTrip() throws Exception {
        Path input = Files.createTempFile("femtolz4-main-input", ".txt");
        Path compressed = Files.createTempFile("femtolz4-main-output", ".lz4");
        Path restored = Files.createTempFile("femtolz4-main-restored", ".txt");
        byte[] data = "cli round-trip".repeat(1000).getBytes(StandardCharsets.UTF_8);
        Files.write(input, data);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"compress", input.toString(), compressed.toString(), "5"},
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertEquals(0, Main.run(new String[]{"decompress", compressed.toString(), restored.toString()},
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertArrayEquals(data, Files.readAllBytes(restored));
    }

    @Test void invalidLevelIsRejected() throws Exception {
        Path input = Files.createTempFile("femtolz4-main-input", ".txt");
        Path compressed = Files.createTempFile("femtolz4-main-output", ".lz4");
        Files.write(input, "x".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[]{"compress", input.toString(), compressed.toString(), "0"},
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("level must be between 1 and 9"));
    }

    @Test void nonNumericLevelIsRejected() throws Exception {
        Path input = Files.createTempFile("femtolz4-main-input", ".txt");
        Path compressed = Files.createTempFile("femtolz4-main-output", ".lz4");
        Files.write(input, "x".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[]{"compress", input.toString(), compressed.toString(), "abc"},
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("invalid level: abc"));
    }

    @Test void unknownCommandIsRejected() throws Exception {
        Path input = Files.createTempFile("femtolz4-main-input", ".txt");
        Path output = Files.createTempFile("femtolz4-main-output", ".dat");
        Files.write(input, "x".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[]{"wat", input.toString(), output.toString()},
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown command: wat"));
    }
}