package com.involutionhell.backend.rag.retrieval.support;

/**
 * RAG 某个阶段发生超时时抛出的内部异常。
 *
 * <p>该异常用于在服务内部做降级分流，不会直接暴露给前端。
 */
public class RagStageTimeoutException extends RuntimeException {

    private final String stage;
    private final long timeoutMillis;

    public RagStageTimeoutException(String stage, long timeoutMillis, String message) {
        super(message);
        this.stage = stage;
        this.timeoutMillis = timeoutMillis;
    }

    public String stage() {
        return stage;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }
}
