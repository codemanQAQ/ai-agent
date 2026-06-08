package com.bytedance.ai.graph.intent;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.intent.config.IntentLlmProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class MainIntentPromptFactory {

    private static final String USER_MESSAGE_PLACEHOLDER = "{{userMessage}}";
    private static final String CONVERSATION_MEMORY_PLACEHOLDER = "{{conversationMemory}}";
    private static final String CATEGORIES_PLACEHOLDER = "{{categories}}";
    // 目录暂不可用/为空时的兜底类目（与当前数据集顶级类目一致）。
    private static final String DEFAULT_CATEGORIES =
            "数码电子, 食品饮料, 服饰运动, 美妆护肤, 图书文具, 宠物用品, 家居家电, 母婴用品, 汽车用品";
    private static final long CATEGORIES_TTL_MS = 300_000L;

    private final IntentLlmProperties properties;
    private final ObjectProvider<CatalogQueryFacade> catalogQueryFacadeProvider;

    private volatile String cachedCategories;
    private volatile long cachedAtMs;

    private String template;

    @Autowired
    public MainIntentPromptFactory(IntentLlmProperties properties,
                                   ObjectProvider<CatalogQueryFacade> catalogQueryFacadeProvider) {
        this.properties = properties;
        this.catalogQueryFacadeProvider = catalogQueryFacadeProvider;
    }

    /** 便利构造器（测试/无目录场景）：不接目录，类目回退到默认清单。 */
    public MainIntentPromptFactory(IntentLlmProperties properties) {
        this(properties, null);
    }

    @PostConstruct
    public void init() {
        String templatePath = properties.getPromptTemplatePath();

        if (!StringUtils.hasText(templatePath)) {
            throw new IllegalStateException("graph.agent.intent-llm.prompt-template-path must not be empty");
        }

        this.template = loadTemplate(templatePath);
        validateTemplate(this.template, templatePath);
    }

    public String build(String userMessage, String conversationMemory) {
        ensureInitialized();

        return template
                .replace(CATEGORIES_PLACEHOLDER, resolveCategories())
                .replace(USER_MESSAGE_PLACEHOLDER, sanitizeUserMessage(userMessage))
                .replace(CONVERSATION_MEMORY_PLACEHOLDER, sanitizeConversationMemory(conversationMemory));
    }

    /** 运行时从目录读取真实顶级类目（带 5 分钟缓存），目录不可用/为空时回退默认清单。 */
    private String resolveCategories() {
        long now = System.currentTimeMillis();
        String cached = cachedCategories;
        if (cached != null && (now - cachedAtMs) < CATEGORIES_TTL_MS) {
            return cached;
        }
        String resolved = DEFAULT_CATEGORIES;
        CatalogQueryFacade facade = catalogQueryFacadeProvider == null ? null : catalogQueryFacadeProvider.getIfAvailable();
        if (facade != null) {
            try {
                List<String> categories = facade.listActiveTopCategories();
                if (categories != null && !categories.isEmpty()) {
                    resolved = String.join(", ", categories);
                }
            } catch (RuntimeException ignored) {
                // 目录查询失败时静默回退默认清单，不影响意图识别主流程。
            }
        }
        cachedCategories = resolved;
        cachedAtMs = now;
        return resolved;
    }

    private String sanitizeUserMessage(String value) {
        if (value == null) {
            return "";
        }
        return value.strip();
    }

    private String sanitizeConversationMemory(String value) {
        if (value == null) {
            return "";
        }

        String stripped = value.strip();

        int maxChars = properties.getMaxMemoryChars() == null
                ? 3000
                : properties.getMaxMemoryChars();

        if (stripped.length() <= maxChars) {
            return stripped;
        }

        return stripped.substring(stripped.length() - maxChars);
    }

    private String loadTemplate(String templatePath) {
        try {
            ClassPathResource resource = new ClassPathResource(templatePath);

            if (!resource.exists()) {
                throw new IllegalStateException("Main intent prompt template not found: " + templatePath);
            }

            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load main intent prompt template: " + templatePath, exception);
        }
    }

    private void validateTemplate(String template, String templatePath) {
        if (!template.contains(USER_MESSAGE_PLACEHOLDER)) {
            throw new IllegalStateException(
                    "Main intent prompt template missing placeholder "
                            + USER_MESSAGE_PLACEHOLDER
                            + ": "
                            + templatePath
            );
        }

        if (!template.contains(CONVERSATION_MEMORY_PLACEHOLDER)) {
            throw new IllegalStateException(
                    "Main intent prompt template missing placeholder "
                            + CONVERSATION_MEMORY_PLACEHOLDER
                            + ": "
                            + templatePath
            );
        }
    }

    private void ensureInitialized() {
        if (template == null) {
            throw new IllegalStateException("Main intent prompt template has not been initialized");
        }
    }
}