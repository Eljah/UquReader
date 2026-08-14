package com.example.uqureader.webapp.reader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RhvoiceTtsService {
    public static final String TALGAT_VOICE = "Talgat";
    private static final int MAX_TEXT_CHARS = 30_000;

    public boolean isConfigured() {
        return commandExists(defaultCommand());
    }

    public byte[] synthesize(String text) throws IOException, InterruptedException {
        String safeText = text == null ? "" : text.trim();
        if (safeText.isEmpty()) {
            throw new IOException("No text to synthesize");
        }
        if (safeText.length() > MAX_TEXT_CHARS) {
            safeText = safeText.substring(0, MAX_TEXT_CHARS);
        }
        Path tempDir = createTempDirectory();
        Path input = tempDir.resolve("input.txt");
        Path output = tempDir.resolve("output.wav");
        Files.writeString(input, safeText, StandardCharsets.UTF_8);
        try {
            List<String> command = buildCommand(input, output);
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("RHVoice synthesis timed out");
            }
            byte[] processOutput = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0) {
                String outputText = new String(processOutput, StandardCharsets.UTF_8);
                throw new IOException("RHVoice synthesis failed: " + outputText);
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                if (isWaveAudio(processOutput)) {
                    return processOutput;
                }
                String outputText = new String(processOutput, StandardCharsets.UTF_8);
                throw new IOException("RHVoice did not produce audio output"
                        + (outputText.isBlank() ? "" : ": " + outputText));
            }
            return Files.readAllBytes(output);
        } finally {
            deleteIfExists(input);
            deleteIfExists(output);
            deleteIfExists(tempDir);
        }
    }

    private List<String> buildCommand(Path input, Path output) {
        List<String> command = new ArrayList<>();
        command.add(defaultCommand());
        command.add("-p");
        command.add(TALGAT_VOICE);
        command.add("-i");
        command.add(input.toAbsolutePath().toString());
        command.add("-o");
        command.add(output.toAbsolutePath().toString());
        return command;
    }

    private String defaultCommand() {
        String value = System.getenv("RHVOICE_COMMAND");
        return value == null || value.isBlank() ? "RHVoice-test" : value;
    }

    private Path createTempDirectory() throws IOException {
        String override = System.getenv("UQUREADER_TTS_TMP_DIR");
        if (override != null && !override.isBlank()) {
            Path parent = Path.of(override);
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent, "uqureader-tts-");
        }
        Path serviceDataDir = Path.of("/opt", "uqureader", "data", "tts");
        if (Files.isDirectory(serviceDataDir) || Files.isDirectory(serviceDataDir.getParent())) {
            Files.createDirectories(serviceDataDir);
            return Files.createTempDirectory(serviceDataDir, "uqureader-tts-");
        }
        return Files.createTempDirectory("uqureader-tts-");
    }

    private boolean isWaveAudio(byte[] value) {
        return value != null
                && value.length > 44
                && value[0] == 'R'
                && value[1] == 'I'
                && value[2] == 'F'
                && value[3] == 'F'
                && value[8] == 'W'
                && value[9] == 'A'
                && value[10] == 'V'
                && value[11] == 'E';
    }

    private boolean commandExists(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        String[] extensions = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? new String[]{".exe", ".cmd", ".bat", ""}
                : new String[]{""};
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String extension : extensions) {
                if (Files.isRegularFile(Path.of(dir, command + extension))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary files are best-effort cleanup.
        }
    }
}
