package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;
import com.bytedance.ai.graph.session.UnifiedQueryScope;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PythonFaissImageVectorRecallAdapter implements ImageVectorRecallPort {

    private static final Logger log = LoggerFactory.getLogger(PythonFaissImageVectorRecallAdapter.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final RagJsonCodec jsonCodec;

    private final PythonProcessRunner processRunner;

    private final AdapterOptions options;

    @Autowired
    public PythonFaissImageVectorRecallAdapter(RagJsonCodec jsonCodec) {
        this(jsonCodec, new DefaultPythonProcessRunner(), AdapterOptions.fromEnvironment());
    }

    PythonFaissImageVectorRecallAdapter(
            RagJsonCodec jsonCodec,
            PythonProcessRunner processRunner,
            AdapterOptions options
    ) {
        this.jsonCodec = jsonCodec;
        this.processRunner = processRunner;
        this.options = options == null ? AdapterOptions.fromEnvironment() : options;
    }

    @Override
    public List<ProductRecallCandidate> recallByImage(UnifiedQueryContext queryContext, int limit) {
        if (queryContext == null || (!StringUtils.hasText(queryContext.imageEmbeddingRef())
                && !StringUtils.hasText(queryContext.imageRef()))) {
            return List.of();
        }
        try {
            ProcessResult result = processRunner.run(command(), environment(), requestPayload(queryContext, limit),
                    options.timeout());
            if (result.exitCode() != 0) {
                log.warn("Python FAISS image recall failed, exitCode={}, stderr={}", result.exitCode(), result.stderr());
                return List.of();
            }
            return toCandidates(jsonCodec.readMap(result.stdout()));
        } catch (Exception exception) {
            log.warn("Python FAISS image recall adapter returned no candidates: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<String> command() {
        return List.of(
                options.pythonExecutable(),
                "-m",
                "ecommerce_recall.photo_search_main_recall",
                "--request-file",
                "{{REQUEST_FILE}}"
        );
    }

    private Map<String, String> environment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PYTHONPATH", options.mainPythonPath().toString());
        return environment;
    }

    private Map<String, Object> requestPayload(UnifiedQueryContext queryContext, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topK", Math.max(1, limit));
        putIfPresent(payload, "queryText", queryContext.queryText());
        putIfPresent(payload, "imageRef", queryContext.imageRef());
        putIfPresent(payload, "imageEmbeddingRef", queryContext.imageEmbeddingRef());
        UnifiedQueryScope scope = queryContext.scope();
        if (scope != null) {
            putIfNotEmpty(payload, "productIds", scope.productIds());
            putIfNotEmpty(payload, "externalRefs", scope.externalRefs());
            putIfNotEmpty(payload, "catalogSpuIds", scope.catalogSpuIds());
        }
        putIfPresent(payload, "previewChars", options.previewChars());
        return payload;
    }

    private List<ProductRecallCandidate> toCandidates(Map<String, Object> response) {
        List<ProductRecallCandidate> candidates = new ArrayList<>();
        for (Object item : listValue(response.get("products"))) {
            Map<String, Object> product = mapValue(item);
            candidates.add(toCandidate(product));
        }
        return List.copyOf(candidates);
    }

    private ProductRecallCandidate toCandidate(Map<String, Object> product) {
        return new ProductRecallCandidate(
                stringValue(product.get("productId")),
                stringValue(product.get("spuId")),
                stringValue(product.get("skuId")),
                stringValue(product.get("externalRef")),
                stringValue(product.get("title")),
                stringValue(product.get("brand")),
                stringList(product.get("categoryPath")),
                decimalValue(product.get("price")),
                integerValue(product.get("stock")),
                stringValue(product.get("imageUrl")),
                ProductRecallSource.IMAGE_VECTOR,
                doubleValue(product.get("rawScore")),
                doubleValue(product.get("rankScore")),
                cleanMap(mapValue(product.get("matchedSlots"))),
                evidenceList(product.get("evidence"))
        );
    }

    private List<ProductRecallEvidence> evidenceList(Object value) {
        List<ProductRecallEvidence> evidences = new ArrayList<>();
        for (Object item : listValue(value)) {
            Map<String, Object> evidence = mapValue(item);
            evidences.add(new ProductRecallEvidence(
                    ProductRecallSource.IMAGE_VECTOR,
                    stringValue(evidence.get("evidenceType")),
                    stringValue(evidence.get("title")),
                    stringValue(evidence.get("content")),
                    stringValue(evidence.get("chunkId")),
                    stringValue(evidence.get("parentChunkId")),
                    stringValue(evidence.get("productId")),
                    cleanMap(mapValue(evidence.get("metadata")))
            ));
        }
        return List.copyOf(evidences);
    }

    private Map<String, Object> cleanMap(Map<String, Object> raw) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        return cleaned;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), item);
                }
            });
            return normalized;
        }
        return Map.of();
    }

    private List<?> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    private List<String> stringList(Object value) {
        List<String> values = new ArrayList<>();
        for (Object item : listValue(value)) {
            String text = stringValue(item);
            if (text != null) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = stringValue(value);
        return text == null ? null : new BigDecimal(text);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        return text == null ? null : Integer.valueOf(text);
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = stringValue(value);
        return text == null ? 0.0d : Double.parseDouble(text);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putIfNotEmpty(Map<String, Object> target, String key, List<?> value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    interface PythonProcessRunner {

        ProcessResult run(
                List<String> commandTemplate,
                Map<String, String> environment,
                Map<String, Object> requestPayload,
                Duration timeout
        ) throws IOException, InterruptedException;
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    static final class DefaultPythonProcessRunner implements PythonProcessRunner {

        @Override
        public ProcessResult run(
                List<String> commandTemplate,
                Map<String, String> environment,
                Map<String, Object> requestPayload,
                Duration timeout
        ) throws IOException, InterruptedException {
            Path requestFile = writeRequestFile(requestPayload);
            try {
                List<String> command = commandTemplate.stream()
                        .map(item -> "{{REQUEST_FILE}}".equals(item) ? requestFile.toString() : item)
                        .toList();
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.environment().putAll(environment);
                Process process = builder.start();
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new ProcessResult(124, "", "Python FAISS recall timed out");
                }
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return new ProcessResult(process.exitValue(), stdout, stderr);
            } finally {
                Files.deleteIfExists(requestFile);
            }
        }

        private Path writeRequestFile(Map<String, Object> requestPayload) throws IOException {
            Path directory = resolveProjectRoot().resolve("target").resolve("ecommerce-recall");
            Files.createDirectories(directory);
            Path file = Files.createTempFile(directory, "image-recall-request-", ".json");
            String json = new RagJsonCodec(tools.jackson.databind.json.JsonMapper.builder().build())
                    .write(requestPayload);
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file;
        }
    }

    record AdapterOptions(
            String pythonExecutable,
            Path mainPythonPath,
            Duration timeout,
            int previewChars
    ) {

        static AdapterOptions fromEnvironment() {
            return new AdapterOptions(
                    env("ECOM_RECALL_PYTHON", "python"),
                    envPath("ECOM_RECALL_MAIN_PYTHON_PATH", resolveProjectRoot()
                            .resolve("src").resolve("main").resolve("python")),
                    Duration.ofSeconds(longEnv("ECOM_RECALL_IMAGE_TIMEOUT_SECONDS", DEFAULT_TIMEOUT.toSeconds())),
                    (int) longEnv("ECOM_RECALL_PREVIEW_CHARS", 240)
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
