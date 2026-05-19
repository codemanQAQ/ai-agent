package com.involutionhell.backend.rag.indexing.web;

import com.involutionhell.backend.rag.common.api.ApiResponse;
import com.involutionhell.backend.rag.indexing.api.IndexingQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagIndexJobView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineView;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(value = "/public/rag/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class RagIndexingController {

    private final IndexingQueryFacade indexingQueryFacade;

    public RagIndexingController(IndexingQueryFacade indexingQueryFacade) {
        this.indexingQueryFacade = indexingQueryFacade;
    }

    /**
     * 返回当前文档版本对应的离线索引 job 快照，便于排查当前阶段、状态与 messageId。
     */
    @GetMapping(value = "/index-job/{documentId}")
    public ApiResponse<RagIndexJobView> getIndexJob(@PathVariable @Positive Long documentId) {
        return ApiResponse.ok(indexingQueryFacade.getIndexJob(documentId));
    }

    /**
     * 返回完整索引时间线，包含 document/job/outbox/audit 四类视图。
     * 其中 outbox.messageId 会直接透给调用方，方便与 MQ 消费侧日志对账。
     */
    @GetMapping(value = "/index-timeline/{documentId}")
    public ApiResponse<RagIndexTimelineView> getIndexTimeline(@PathVariable @Positive Long documentId) {
        return ApiResponse.ok(indexingQueryFacade.getIndexTimeline(documentId));
    }
}
