package com.botmaker.session.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The launched app's output has to reach the log, and has to stop before it becomes the log. Both halves are
 * defects that have actually cost time here: discarding it entirely is what made a failed Steam launch
 * undiagnosable, and echoing a Proton cold start unbounded would bury every {@code [Session]} line under
 * winetricks chatter.
 */
class AppOutputLogTest {

    private static final long TIMEOUT_MS = 3_000;

    /** A whole line arrives even when it is written in pieces — the writer is another process, appending. */
    @Test
    void completeLinesReachTheSink(@TempDir Path dir) throws Exception {
        File file = dir.resolve("app.log").toFile();
        assertTrue(file.createNewFile());
        List<String> lines = new CopyOnWriteArrayList<>();

        try (AppOutputLog log = AppOutputLog.forTesting(file, lines::add, 10, 100)) {
            // Deliberately split mid-line: a reader that emitted what had arrived so far would tear this in two.
            append(file, "wine: could not load ");
            awaitQuiet();
            assertTrue(lines.isEmpty(), "a partial line must not be emitted: " + lines);

            append(file, "d3d11.dll\n");
            await(lines, 1);
            assertEquals(List.of("[App] test: wine: could not load d3d11.dll"), lines);
        }
    }

    /** Blank padding is dropped, and an over-long line is cut rather than taking the whole screen. */
    @Test
    void blankLinesAreDroppedAndLongLinesTruncated(@TempDir Path dir) throws Exception {
        File file = dir.resolve("app.log").toFile();
        assertTrue(file.createNewFile());
        List<String> lines = new CopyOnWriteArrayList<>();

        try (AppOutputLog log = AppOutputLog.forTesting(file, lines::add, 10, 8)) {
            append(file, "\n   \n0123456789abcdef\n");
            await(lines, 1);

            assertEquals(List.of("[App] test: 01234567…"), lines);
        }
    }

    /**
     * Past the cap the echo stops and points at the file instead — and says so exactly once, rather than
     * emitting the pointer for every remaining line.
     */
    @Test
    void theEchoStopsAtTheCapAndNamesTheFile(@TempDir Path dir) throws Exception {
        File file = dir.resolve("app.log").toFile();
        assertTrue(file.createNewFile());
        List<String> lines = new CopyOnWriteArrayList<>();

        try (AppOutputLog log = AppOutputLog.forTesting(file, lines::add, 3, 100)) {
            StringBuilder flood = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                flood.append("line ").append(i).append('\n');
            }
            append(file, flood.toString());
            await(lines, 4);
            awaitQuiet(); // give a runaway tailer time to emit more, so "exactly 4" means something

            assertEquals(4, lines.size(), lines.toString());
            assertEquals("[App] test: line 0", lines.get(0));
            assertEquals("[App] test: line 2", lines.get(2));
            assertTrue(lines.get(3).endsWith(file.getAbsolutePath()), lines.get(3));
            assertFalse(lines.get(3).contains("line 3"), "the cap line replaces the content, not accompanies it");
        }
    }

    /**
     * The composition that matters in production: a real child process, both streams pointed at
     * {@link AppOutputLog#redirect()}, and <em>stderr</em> arriving — which is where a launcher says why it
     * failed, and which was the stream being discarded.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aRealChildsStdoutAndStderrBothArrive(@TempDir Path dir) throws Exception {
        File file = dir.resolve("app.log").toFile();
        assertTrue(file.createNewFile());
        List<String> lines = new CopyOnWriteArrayList<>();

        try (AppOutputLog log = AppOutputLog.forTesting(file, lines::add, 10, 100)) {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo to-stdout; echo to-stderr >&2");
            pb.redirectOutput(log.redirect());
            pb.redirectError(log.redirect());
            assertEquals(0, pb.start().waitFor());

            await(lines, 2);
            assertTrue(lines.contains("[App] test: to-stdout"), lines.toString());
            assertTrue(lines.contains("[App] test: to-stderr"), lines.toString());
        }
    }

    private static void append(File file, String text) throws IOException {
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(text);
        }
    }

    /** Wait until {@code lines} holds at least {@code count} entries, or fail — never a bare sleep as the assert. */
    private static void await(List<String> lines, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (lines.size() >= count) {
                return;
            }
            Thread.sleep(25);
        }
        fail("expected at least " + count + " echoed line(s) within " + TIMEOUT_MS + "ms, got " + lines);
    }

    /** Long enough for the tailer to have run a few poll cycles, for the assertions about what must NOT appear. */
    private static void awaitQuiet() throws InterruptedException {
        Thread.sleep(500);
    }
}
