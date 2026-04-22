package com.involutionhell.backend.rag.shared.support;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 使用 OpenAI 兼容 tokenizer 进行真实 token 计数。
 */
@Component
public class RagOpenAiTokenCounter {

    private final Encoding encoding;

    public RagOpenAiTokenCounter(RagProperties ragProperties) {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = resolveEncoding(registry, ragProperties.embeddingModel());
    }

    public int count(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return encoding.countTokensOrdinary(normalized);
    }

    private Encoding resolveEncoding(EncodingRegistry registry, String modelName) {
        String normalizedModel = modelName == null ? "" : modelName.trim().toLowerCase();
        if (StringUtils.hasText(normalizedModel)) {
            if (normalizedModel.startsWith("gpt-4o") || normalizedModel.startsWith("o1") || normalizedModel.startsWith("o3")) {
                return registry.getEncoding(EncodingType.O200K_BASE);
            }
            if (normalizedModel.startsWith("text-embedding-3")
                    || normalizedModel.startsWith("text-embedding-ada-002")
                    || normalizedModel.startsWith("gpt-4")
                    || normalizedModel.startsWith("gpt-3.5")) {
                return registry.getEncoding(EncodingType.CL100K_BASE);
            }
        }
        // 对项目内的别名模型（如 text-embedding-v4）默认回退到 cl100k_base。
        return registry.getEncoding(EncodingType.CL100K_BASE);
    }
}
