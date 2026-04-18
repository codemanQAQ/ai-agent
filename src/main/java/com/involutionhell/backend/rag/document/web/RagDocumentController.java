package com.involutionhell.backend.rag.document.web;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.rag.document.api.DocumentCommandFacade;
import com.involutionhell.backend.rag.document.api.DocumentQueryFacade;
import com.involutionhell.backend.rag.document.api.RagDocumentCreateRequest;
import com.involutionhell.backend.rag.document.api.RagDocumentUpdateRequest;
import com.involutionhell.backend.rag.document.api.RagDocumentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping(value = "/public/rag/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class RagDocumentController {

    private final DocumentCommandFacade documentCommandFacade;
    private final DocumentQueryFacade documentQueryFacade;

    public RagDocumentController(
            DocumentCommandFacade documentCommandFacade,
            DocumentQueryFacade documentQueryFacade
    ) {
        this.documentCommandFacade = documentCommandFacade;
        this.documentQueryFacade = documentQueryFacade;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,value = "/create")
    public ApiResponse<RagDocumentView> createDocument(@Valid @RequestBody RagDocumentCreateRequest request) {
        return ApiResponse.ok("文档已接收，开始索引", documentCommandFacade.createDocument(request));
    }

    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RagDocumentView> createDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "sourceUri", required = false) String sourceUri,
            @RequestParam(value = "externalRef", required = false) String externalRef,
            @RequestParam(value = "title", required = false) String title,
            @RequestPart(value = "metadata", required = false) java.util.Map<String, Object> metadata
    ) {
        return ApiResponse.ok(
                "文档已接收，开始索引",
                documentCommandFacade.createDocument(file, sourceType, sourceUri, externalRef, title, metadata)
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
}
