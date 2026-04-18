package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于检索到的上下文生成最终回答。
 */
@Service
public class RagAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerGenerator.class);

    private final ObjectProvider<ChatModel> chatModelProvider;

    public RagAnswerGenerator(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * 优先调用 ChatClient 生成答案；若未配置或调用失败，则回退到本地摘要。
     */
    public RagAnswerResult generate(String question, List<RagRetrievedChunk> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            log.debug("Answer generation skipped because no context was retrieved: questionPreview={}", RagLogHelper.previewQuestion(question));
            return fallback(question, contexts, null);
        }
        ChatClient chatClient = resolveChatClient();
        if (chatClient == null) {
            log.debug("Answer generation falling back because ChatClient is unavailable: contextCount={}", contexts.size());
            return fallback(question, contexts, null);
        }

        try {
            String answer = chatClient.prompt()
                    .system("""
                            你是一个后端 RAG 助手。
                            只能依据给定上下文回答问题，不要编造。
                            如果上下文不足，请明确说明“根据当前知识库无法确认”。
                            回答请使用简体中文，并尽量简洁。
                            """)
                    .user(buildPrompt(question, contexts))
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                log.warn("Answer generation returned empty content, falling back to summary: contextCount={}", contexts.size());
                return fallback(question, contexts, "OpenAI 调用失败，已回退为检索摘要。");
            }
            log.debug("Answer generation completed with ChatClient: contextCount={}", contexts.size());
            return new RagAnswerResult(answer, true);
        } catch (Exception exception) {
            log.warn("Answer generation failed, falling back to summary: error={}", RagLogHelper.errorSummary(exception));
            return fallback(question, contexts, "大模型生成失败，已回退为检索摘要。");
        }
    }

    public RagAnswerResult fallback(String question, List<RagRetrievedChunk> contexts, String notice) {
        String answer = contexts == null || contexts.isEmpty()
                ? "我没有检索到可用的知识片段，当前无法基于仓库数据回答这个问题。"
                : buildFallbackAnswer(question, contexts);
        if (StringUtils.hasText(notice)) {
            answer = answer + "\n\n[说明] " + notice;
        }
        return new RagAnswerResult(answer, false);
    }

    private ChatClient resolveChatClient() {
        try {
            ChatModel chatModel = chatModelProvider.getIfAvailable();
            return chatModel == null ? null : ChatClient.create(chatModel);
        } catch (Exception exception) {
            log.warn("ChatModel is unavailable, falling back to summary: error={}", RagLogHelper.errorSummary(exception));
            return null;
        }
    }

    private String buildPrompt(String question, List<RagRetrievedChunk> contexts) {
        log.debug(
                "Building answer prompt: questionLength={}, questionPreview={}, contextCount={}",
                question.length(),
                RagLogHelper.previewQuestion(question),
                contexts.size()
        );
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(question).append("\n\n");
        builder.append("可用上下文：\n");
        for (int i = 0; i < contexts.size(); i++) {
            RagRetrievedChunk chunk = contexts.get(i);
            builder.append(i + 1)
                    .append(". [documentId=").append(chunk.documentId())
                    .append(", chunkIndex=").append(chunk.chunkIndex())
                    .append(", sourceUri=").append(chunk.sourceUri() == null ? "" : chunk.sourceUri())
                    .append(", title=").append(chunk.title() == null ? "" : chunk.title())
                    .append(", blockType=").append(chunk.blockType() == null ? "" : chunk.blockType())
                    .append(", headingPath=").append(chunk.headingPath() == null ? List.of() : chunk.headingPath())
                    .append("]\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        builder.append("请基于以上上下文作答，并在结尾简短指出主要依据来自哪些片段。");
        return builder.toString();
    }

    private String buildFallbackAnswer(String question, List<RagRetrievedChunk> contexts) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前未启用大模型生成，以下是与问题“")
                .append(question)
                .append("”最相关的知识片段摘要：\n");
        for (RagRetrievedChunk chunk : contexts) {
            builder.append("- [documentId=")
                    .append(chunk.documentId())
                    .append(", chunkIndex=")
                    .append(chunk.chunkIndex())
                    .append(", sourceUri=")
                    .append(chunk.sourceUri() == null ? "" : chunk.sourceUri())
                    .append(", blockType=")
                    .append(chunk.blockType() == null ? "" : chunk.blockType())
                    .append(", headingPath=")
                    .append(chunk.headingPath() == null ? List.of() : chunk.headingPath())
                    .append("] ")
                    .append(chunk.content())
                    .append('\n');
        }
        return builder.toString().trim();
    }
}
