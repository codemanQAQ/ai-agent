package org.springframework.ai.zhipuai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;

/**
 * Compatibility shim for Spring AI Alibaba Graph 1.1.2.3.
 *
 * <p>The graph core eagerly registers a serializer for this optional Spring AI
 * ZhiPuAI message type during {@code StateGraph} static initialization. The
 * application does not use ZhiPuAI; keeping this minimal class avoids pulling in
 * the ZhiPuAI client dependency just to satisfy that hard-coded serializer.
 */
public class ZhiPuAiAssistantMessage extends AssistantMessage {

    // todo 为什么要使用zhipu ai？
    private String reasoningContent;

    public ZhiPuAiAssistantMessage(String content) {
        super(content);
    }

    public ZhiPuAiAssistantMessage(
            String content,
            String reasoningContent,
            Map<String, Object> metadata,
            List<ToolCall> toolCalls,
            List<Media> media
    ) {
        super(content, metadata, toolCalls, media);
        this.reasoningContent = reasoningContent;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }
}
