package com.yizhaoqi.smartpai.paper.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendataloader.pdf.api.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenDataLoaderProcessRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void terminatesWorkerProcessWhenTimeoutExpires() throws Exception {
        Path marker = tempDir.resolve("worker-still-running.marker");
        String classpath = testClasspath();
        OpenDataLoaderWorkerCommandFactory commandFactory = (inputPdf, config) -> List.of(
                javaBinary(),
                "-cp",
                classpath,
                SlowWorker.class.getName(),
                marker.toString()
        );
        OpenDataLoaderProcessRunner runner = new OpenDataLoaderProcessRunner(commandFactory);
        Config config = new Config();
        config.setOutputFolder(tempDir.toString());

        long startedAt = System.nanoTime();
        PaperParsingException exception = assertThrows(PaperParsingException.class, () ->
                runner.processFile(tempDir.resolve("input.pdf"), config, Duration.ofMillis(200), "slow.pdf")
        );
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(exception.getMessage().contains("timed out"), exception.getMessage());
        assertTrue(elapsedMillis < 1500, "timeout should return control to the caller quickly");
        Thread.sleep(1200);
        assertFalse(Files.exists(marker), "timed-out worker process should be killed before it writes output");
    }

    private String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private String testClasspath() throws Exception {
        return Path.of(SlowWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class SlowWorker {
        private SlowWorker() {
        }

        public static void main(String[] args) throws Exception {
            Thread.sleep(900);
            Files.writeString(Path.of(args[0]), "still running");
        }
    }
}
