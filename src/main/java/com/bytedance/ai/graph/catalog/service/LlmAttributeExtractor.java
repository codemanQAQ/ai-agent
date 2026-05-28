package com.bytedance.ai.graph.catalog.service;

import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调 Doubao（或任意 Spring AI ChatModel）从商品描述抽出结构化属性 JSON。
 *
 * <p>构造路径与 {@code RagAnswerGenerator} 对齐：通过 {@link ObjectProvider} 延迟解析 ChatModel，
 * 让没有 LLM 凭据的环境（H2 单测、Native 编译期）也能正常加载 bean。
 *
 * <p>容错策略：
 * <ul>
 *   <li>ChatModel 不可用 → 抛 {@link LlmExtractionException}，由 worker 标 FAILED 并保留错误。</li>
 *   <li>模型输出非纯 JSON（带 ```json 代码块 / 前后解释）→ 用正则提取首个 {...} 片段后再解析。</li>
 *   <li>JSON 解析失败 → 抛 {@link LlmExtractionException}，错误信息保留原文片段帮助 oncall 排查。</li>
 * </ul>
 */
@Component
public class LlmAttributeExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmAttributeExtractor.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final int RAW_OUTPUT_PREVIEW = 300;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagProperties ragProperties;
    private final RagJsonCodec jsonCodec;

    public LlmAttributeExtractor(
            ObjectProvider<ChatModel> chatModelProvider,
            RagProperties ragProperties,
            RagJsonCodec jsonCodec
    ) {
        this.chatModelProvider = chatModelProvider;
        this.ragProperties = ragProperties;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 对单条商品描述做一次属性抽取。
     *
     * @param description 商品长描述（建议传 SPU 的 Markdown 原文，至少几十字）
     * @return JSON 解析结果（保证非 null，可能为空 map）
     * @throws LlmExtractionException 当模型不可用、超时或解析失败
     */
    public Map<String, Object> extract(String description) {
        if (!StringUtils.hasText(description)) {
            log.debug("attribute extraction skipped because description is blank");
            return new LinkedHashMap<>();
        }
        ChatClient chatClient = resolveChatClient();
        String systemPrompt = ragProperties.catalog().attributeExtractionSystemPrompt();
        String rawOutput;
        try {
            rawOutput = chatClient.prompt()
                    .system(systemPrompt)
                    .user(description)
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            log.warn(
                    "LLM attribute extraction call failed: error={}",
                    RagLogHelper.errorSummary(exception)
            );
            throw new LlmExtractionException("LLM 调用失败：" + exception.getMessage(), exception);
        }

        return parse(rawOutput);
    }

    private ChatClient resolveChatClient() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmExtractionException("ChatModel 未配置，无法抽取商品属性");
        }
        return ChatClient.create(chatModel);
    }

    private Map<String, Object> parse(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            throw new LlmExtractionException("LLM 返回空内容");
        }
        String json = extractJsonObject(rawOutput);
        try {
            return jsonCodec.readMap(json);
        } catch (RuntimeException exception) {
            String preview = rawOutput.length() > RAW_OUTPUT_PREVIEW
                    ? rawOutput.substring(0, RAW_OUTPUT_PREVIEW) + "..."
                    : rawOutput;
            log.warn(
                    "LLM attribute extraction produced unparsable JSON: preview={}, error={}",
                    preview,
                    RagLogHelper.errorSummary(exception)
            );
            throw new LlmExtractionException("LLM 输出无法解析为 JSON：" + exception.getMessage(), exception);
        }
    }

    /**
     * 从 LLM 原始输出中提取首个 {...} 片段。若已经是纯 JSON 则直接返回。
     */
    private String extractJsonObject(String rawOutput) {
        String trimmed = rawOutput.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        throw new LlmExtractionException("LLM 输出未包含 JSON 对象片段");
    }

    /**
     * 抽取流程的领域异常。
     */
    public static class LlmExtractionException extends RuntimeException {
        public LlmExtractionException(String message) {
            super(message);
        }

        public LlmExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
