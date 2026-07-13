package io.github.chzarles.paperloom.paper.parser;

import org.opendataloader.pdf.api.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class OpenDataLoaderProcessRunner {

    private final OpenDataLoaderWorkerCommandFactory commandFactory;

    OpenDataLoaderProcessRunner(OpenDataLoaderWorkerCommandFactory commandFactory) {
        this.commandFactory = commandFactory;
    }

    static OpenDataLoaderProcessRunner production() {
        return new OpenDataLoaderProcessRunner(new DefaultOpenDataLoaderWorkerCommandFactory());
    }

    void processFile(Path inputPdf, Config config, Duration timeout, String originalFilename) {
        if (inputPdf == null) {
            throw new PaperParsingException("OpenDataLoader input PDF path must not be null");
        }
        if (config == null || config.getOutputFolder() == null || config.getOutputFolder().isBlank()) {
            throw new PaperParsingException("OpenDataLoader output folder must be configured");
        }
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(300)
                : timeout;
        Path outputDir = Path.of(config.getOutputFolder());
        Path workerLog = outputDir.resolve("opendataloader-worker.log");

        Process process = null;
        try {
            Files.createDirectories(outputDir);
            ProcessBuilder builder = new ProcessBuilder(commandFactory.buildCommand(inputPdf, config));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(workerLog.toFile()));
            process = builder.start();

            if (!process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new PaperParsingException("OpenDataLoader timed out after "
                        + effectiveTimeout.toSeconds()
                        + " seconds while parsing "
                        + safeName(originalFilename));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new PaperParsingException("OpenDataLoader worker exited with code "
                        + exitCode
                        + " while parsing "
                        + safeName(originalFilename)
                        + logSuffix(workerLog));
            }
        } catch (IOException exception) {
            throw new PaperParsingException("Failed to start OpenDataLoader worker for " + safeName(originalFilename), exception);
        } catch (InterruptedException exception) {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            Thread.currentThread().interrupt();
            throw new PaperParsingException("Interrupted while parsing PDF with OpenDataLoader: " + safeName(originalFilename), exception);
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private String logSuffix(Path workerLog) {
        try {
            if (!Files.exists(workerLog)) {
                return "";
            }
            String log = Files.readString(workerLog).trim();
            if (log.isBlank()) {
                return "";
            }
            String tail = log.length() > 1000 ? log.substring(log.length() - 1000) : log;
            return ": " + tail;
        } catch (IOException ignored) {
            return "";
        }
    }

    private String safeName(String originalFilename) {
        return originalFilename == null || originalFilename.isBlank() ? "<unknown PDF>" : originalFilename;
    }
}
