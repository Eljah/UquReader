package com.example.uqureader.webapp.reader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class RhvoiceTtsService {
    public static final String TALGAT_VOICE = "Talgat";
    public static final String CACHE_VERSION = "rhvoice-talgat-v1";
    private static final int MAX_TEXT_CHARS = 30_000;
    private static final Set<String> WARMUP_QUEUED = ConcurrentHashMap.newKeySet();

    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "uqureader-tts-warmup");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final AtomicLong warmupQueued = new AtomicLong();
    private final AtomicLong warmupCompleted = new AtomicLong();
    private final AtomicLong warmupFailed = new AtomicLong();

    public boolean isConfigured() {
        return commandExists(defaultCommand());
    }

    public CachedAudio synthesizeCached(String text) throws IOException, InterruptedException {
        String safeText = normalizeText(text);
        Path cacheFile = cachePath(safeText);
        if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) > 0) {
            return new CachedAudio(Files.readAllBytes(cacheFile), true, cacheFile);
        }
        String key = cacheKey(safeText);
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) > 0) {
                    return new CachedAudio(Files.readAllBytes(cacheFile), true, cacheFile);
                }
                byte[] audio = synthesize(safeText);
                Files.createDirectories(cacheFile.getParent());
                Path temp = Files.createTempFile(cacheFile.getParent(), cacheFile.getFileName().toString(), ".tmp");
                try {
                    Files.write(temp, audio);
                    Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ex) {
                    Files.deleteIfExists(temp);
                    throw ex;
                }
                return new CachedAudio(audio, false, cacheFile);
            }
        } finally {
            locks.remove(key, lock);
        }
    }

    public void warmupCached(String text) {
        if (!isConfigured()) {
            return;
        }
        String safeText;
        try {
            safeText = normalizeText(text);
        } catch (IOException ex) {
            return;
        }
        String key = cacheKey(safeText);
        if (!WARMUP_QUEUED.add(key)) {
            return;
        }
        warmupQueued.incrementAndGet();
        warmupExecutor.submit(() -> {
            try {
                synthesizeCached(safeText);
                warmupCompleted.incrementAndGet();
            } catch (IOException | InterruptedException ex) {
                warmupFailed.incrementAndGet();
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    public void close() {
        warmupExecutor.shutdownNow();
    }

    public CacheStatus cacheStatus() throws IOException {
        Path root = cacheRoot();
        long files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".wav"))
                    .filter(Files::isRegularFile)
                    .count();
        }
        return new CacheStatus(root, files, warmupQueued.get(), warmupCompleted.get(), warmupFailed.get());
    }

    public byte[] synthesize(String text) throws IOException, InterruptedException {
        String safeText = normalizeText(text);
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

    private String normalizeText(String text) throws IOException {
        String safeText = text == null ? "" : text.trim();
        if (safeText.isEmpty()) {
            throw new IOException("No text to synthesize");
        }
        if (safeText.length() > MAX_TEXT_CHARS) {
            safeText = safeText.substring(0, MAX_TEXT_CHARS);
        }
        return safeText;
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

    private Path cachePath(String text) throws IOException {
        String key = cacheKey(text);
        Path root = cacheRoot();
        return root.resolve(key.substring(0, 2)).resolve(key + ".wav");
    }

    private Path cacheRoot() throws IOException {
        String override = System.getenv("UQUREADER_TTS_CACHE_DIR");
        Path root;
        if (override != null && !override.isBlank()) {
            root = Path.of(override);
        } else {
            root = Path.of("/opt", "uqureader", "data", "tts-cache");
            if (!Files.isDirectory(root.getParent()) && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                root = Path.of(".codex", "tts-cache");
            }
        }
        Files.createDirectories(root);
        return root;
    }

    private String cacheKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(TALGAT_VOICE.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
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

    public record CachedAudio(byte[] audio, boolean cacheHit, Path path) {
    }

    public record CacheStatus(Path root, long wavFiles, long warmupQueued, long warmupCompleted, long warmupFailed) {
    }
}
