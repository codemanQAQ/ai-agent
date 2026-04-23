package com.involutionhell.backend.rag.document.web;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.rag.document.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.apache.logging.log4j.util.Strings.trimToNull;

@RestController
@Validated
@RequestMapping(value = "/public/rag/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private final DocumentCommandFacade documentCommandFacade;
    private final DocumentQueryFacade documentQueryFacade;

    public RagDocumentController(
            DocumentCommandFacade documentCommandFacade,
            DocumentQueryFacade documentQueryFacade
    ) {
        this.documentCommandFacade = documentCommandFacade;
        this.documentQueryFacade = documentQueryFacade;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, value = "/create")
    public ApiResponse<RagDocumentView> createDocument(@Valid @RequestBody RagDocumentCreateRequest request) {
        return ApiResponse.ok("文档已接收，开始索引", documentCommandFacade.createDocument(request));
    }

    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RagDocumentView> createDocument(
            @Valid
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "sourceType") String sourceType,
            @RequestParam(value = "sourceUri") String sourceUri,
            @RequestParam(value = "externalRef", required = false) String externalRef,
            @RequestParam(value = "title") String title
    ) {
        // 1. Web 层的文件基础校验（防空文件、防超大文件 OOM）
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("上传文件过大，限制为 10MB");
        }

        // 2. 解析文件名与推导默认属性
        String originalFilename = resolveOriginalFilename(file);

        // 🛡️ 防火墙 2：严格校验 Markdown 文件类型，防恶意文件上传
        if (!isMarkdownFile(originalFilename, file.getContentType())) {
            log.warn("非法文件上传尝试: filename={}, contentType={}", originalFilename, file.getContentType());
            throw new IllegalArgumentException("安全拦截：只允许上传 Markdown 文件 (.md 或 .markdown)");
        }

        // 3. 读取文件内容为字符串
        String content;
        try (InputStream inputStream = file.getInputStream()) {
            // 直接从流中复制为 String，避免中间产生巨大的 byte[] 垃圾
            content = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);

            // 防二进制空字符注入 (Null-Byte Injection)
            if (content.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("安全拦截：文件内容包含非法二进制空字符");
            }
        } catch (IllegalArgumentException e) {
            throw e; // 直接向上抛出安全拦截异常
        } catch (Exception exception) {
            log.error("读取上传文件失败: filename={}", originalFilename, exception);
            throw new IllegalStateException("读取上传文件失败，请检查文件编码", exception);
        }

        // 4. 组装 Web 层附加的元数据
        Map<String, Object> uploadMetadata = new LinkedHashMap<>();
        if (StringUtils.hasText(originalFilename)) uploadMetadata.put("originalFilename", originalFilename);
        if (StringUtils.hasText(file.getContentType())) uploadMetadata.put("contentType", file.getContentType());
        uploadMetadata.put("uploadMode", "multipart");
        uploadMetadata.put("fileSize", file.getSize());
        ZoneId aetZone = ZoneId.of("Australia/Sydney");
        uploadMetadata.put("uploadTime", Time.valueOf(LocalTime.now(aetZone)));
        logUpload(file, originalFilename, sourceType, sourceUri, title);

        // 5. 构建纯粹的领域请求对象，丢给 Service 层
        RagDocumentCreateRequest request = new RagDocumentCreateRequest(
                sourceType,
                sourceUri,
                trimToNull(externalRef),
                title,
                content,
                uploadMetadata
        );

        return ApiResponse.ok(
                "文档已接收，开始索引",
                documentCommandFacade.createDocument(request)
        );
    }


    @GetMapping(value = "/get/{documentId}")
    public ApiResponse<RagDocumentView> getDocument(@PathVariable @Positive Long documentId) {
        return ApiResponse.ok(documentQueryFacade.getDocument(documentId));
    }

    @PutMapping(value = "/update/{documentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RagDocumentView> updateDocument(
            @PathVariable @Positive Long documentId,
            @Valid @RequestBody RagDocumentUpdateRequest request
    ) {
        return ApiResponse.ok("文档已更新，开始重建索引", documentCommandFacade.updateDocument(documentId, request));
    }

    @PostMapping(value = "/reindex/{documentId}")
    public ApiResponse<RagDocumentView> reindexDocument(@PathVariable @Positive Long documentId) {
        return ApiResponse.ok("文档已重新提交索引", documentCommandFacade.reindexDocument(documentId));
    }

    @DeleteMapping(value = "/del/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable @Positive Long documentId) {
        documentCommandFacade.deleteDocument(documentId);
        return ApiResponse.okMessage("文档已进入删除流程");
    }

    private String resolveOriginalFilename(MultipartFile file) {
        String originalFilename = trimToNull(StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename())));
        if (!StringUtils.hasText(originalFilename)) {
            return "uploaded-document.md";
        }

        int lastSlash = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        return lastSlash >= 0 ? originalFilename.substring(lastSlash + 1) : originalFilename;
    }

    /**
     * 严格验证是否为 Markdown 文件
     */
    private boolean isMarkdownFile(String filename, String contentType) {
        if (!StringUtils.hasText(filename)) return false;
        String lowerFilename = filename.toLowerCase();

        // 1. 强校验后缀
        boolean hasValidExtension = lowerFilename.endsWith(".md") || lowerFilename.endsWith(".markdown") || lowerFilename.endsWith(".mdx");
        if (!hasValidExtension) {
            return false;
        }

        // 2. 如果前端传了 contentType，拒绝明确的危险类型 (如 text/html, application/x-sh)
        if (StringUtils.hasText(contentType)) {
            String lowerContentType = contentType.toLowerCase();
            return !lowerContentType.contains("html") && !lowerContentType.contains("javascript") && !lowerContentType.contains("shell");
        }
        return true;
    }

    private void logUpload(MultipartFile file, String originalFilename, String sourceType, String sourceUri, String title) {
        log.info(
                "RAG upload received: filename={}, sourceType={}, sourceUri={}, title={}, size={}",
                originalFilename,
                sourceType,
                sourceUri,
                title.trim(),
                file.getSize()
        );
    }
}
