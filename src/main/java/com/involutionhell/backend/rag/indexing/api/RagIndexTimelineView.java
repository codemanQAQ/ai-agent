package com.involutionhell.backend.rag.indexing.api;

import java.util.List;

/**
 * 文档当前索引链路的聚合时间线视图。
 *
 * @param document 当前文档快照
 * @param job 当前文档版本的 job 视图
 * @param outbox 当前文档版本的 outbox 视图
 * @param transitions 当前文档版本的状态转移审计列表
 */
public record RagIndexTimelineView(
        RagIndexTimelineDocumentView document,
        RagIndexJobView job,
        RagIndexOutboxView outbox,
        List<RagIndexTransitionView> transitions
) {
}
