package com.bytedance.ai.graph.input;

import com.bytedance.ai.shared.support.RagJsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PythonDoubaoImageInputProcessor implements MultimodalInputProcessor {

    private static final Logger log = LoggerFactory.getLogger(PythonDoubaoImageInputProcessor.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final RagJsonCodec jsonCodec;
    private final Options options;

    @Autowired
    public PythonDoubaoImageInputProcessor(RagJsonCodec jsonCodec) {
        this(jsonCodec, Options.fromEnvironment());
    }

    PythonDoubaoImageInputProcessor(RagJsonCodec jsonCodec, Options options) {
        this.jsonCodec = jsonCodec;
        this.options = options == null ? Options.fromEnvironment() : options;
    }

    @Override
    public Optional<MultimodalInputProcessingResult> processImage(
            String imageRef,
            boolean generateCaption,
            boolean generateEmbedding
    ) {
        if (!StringUtils.hasText(imageRef) || (!generateCaption && !generateEmbedding)) {
            return Optional.empty();
        }
        try {
            ProcessResult processResult = runPython(imageRef, generateCaption, generateEmbedding);
            if (processResult.exitCode() != 0) {
                log.warn("Python image input processor failed, exitCode={}, stderr={}",
                        processResult.exitCode(), processResult.stderr());
                return Optional.empty();
            }
            Map<String, Object> output = jsonCodec.readMap(jsonPayload(processResult.stdout()));
            String caption = stringValue(output.get("caption"));
            String embeddingRef = null;
            Object embedding = output.get("image_embedding");
            if (embedding instanceof List<?> list && !list.isEmpty()) {
                embeddingRef = writeEmbeddingRef(list);
            }

            Map<String, Object> metadata = new LinkedHashMap<>(output);
            metadata.remove("image_embedding");
            if (embeddingRef != null) {
                metadata.put("imageEmbeddingRef", embeddingRef);
            }
            metadata.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
            return Optional.of(new MultimodalInputProcessingResult(caption, embeddingRef, metadata));
        } catch (Exception exception) {
            log.warn("Python image input processor returned no result", exception);
            return Optional.empty();
        }
    }

    private String jsonPayload(String stdout) {
        if (stdout == null) {
            return "";
        }
        int start = stdout.indexOf('{');
        int end = stdout.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return stdout.substring(start, end + 1);
        }
        return stdout;
    }

    private ProcessResult runPython(String imageRef, boolean generateCaption, boolean generateEmbedding)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(options.pythonExecutable());
        command.add("-m");
        command.add("ecommerce_recall.image_input_processor");
        command.add("--image-ref");
        command.add(imageRef);
        command.add("--include-embedding");
        if (generateCaption && !generateEmbedding) {
            command.add("--caption-only");
        }
        if (generateEmbedding && !generateCaption) {
            command.add("--embedding-only");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONPATH", options.mainPythonPath().toString());
        Process process = builder.start();
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readUtf8(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readUtf8(process.getErrorStream()));
        boolean finished = process.waitFor(options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdout.cancel(true);
            stderr.cancel(true);
            return new ProcessResult(124, "", "Python image input processor timed out");
        }
        return new ProcessResult(process.exitValue(), await(stdout), await(stderr));
    }

    private String readUtf8(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String await(CompletableFuture<String> future) throws InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            return "";
        }
    }

    private String writeEmbeddingRef(List<?> embedding) throws IOException {
        Path directory = resolveProjectRoot()
                .resolve("target")
                .resolve("ecommerce-recall")
                .resolve("image-input-embeddings");
        Files.createDirectories(directory);
        Path file = directory.resolve("image-embedding-" + UUID.randomUUID() + ".json");
        Files.writeString(file, jsonCodec.write(embedding), StandardCharsets.UTF_8);
        return file.toAbsolutePath().normalize().toString();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    record Options(String pythonExecutable, Path mainPythonPath, Duration timeout) {

        static Options fromEnvironment() {
            return new Options(
                    env("ECOM_RECALL_PYTHON", "python"),
                    envPath("ECOM_RECALL_MAIN_PYTHON_PATH", resolveProjectRoot()
                            .resolve("src").resolve("main").resolve("python")),
                    Duration.ofSeconds(longEnv("ECOM_IMAGE_INPUT_TIMEOUT_SECONDS", DEFAULT_TIMEOUT.toSeconds()))
            );
        }

        private static String env(String key, String defaultValue) {
            String value = System.getenv(key);
            return StringUtils.hasText(value) ? value : defaultValue;
        }

        private static Path envPath(String key, Path defaultValue) {
            String value = System.getenv(key);
            return StringUtils.hasText(value) ? Paths.get(value).toAbsolutePath().normalize() : defaultValue;
        }

        private static long longEnv(String key, long defaultValue) {
            String value = System.getenv(key);
            if (!StringUtils.hasText(value)) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }

    private static Path resolveProjectRoot() {
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(userDir.resolve("src").resolve("main").resolve("python"))) {
            return userDir;
        }
        Path nested = userDir.resolve("ai-agent");
        if (Files.isDirectory(nested.resolve("src").resolve("main").resolve("python"))) {
            return nested;
        }
        return userDir;
    }
}
