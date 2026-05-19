package com.involutionhell.backend.rag.infrastructure.nativeimage;

import com.involutionhell.backend.rag.common.api.ApiResponse;
import com.involutionhell.backend.rag.document.api.RagDocumentCreateRequest;
import com.involutionhell.backend.rag.document.api.RagDocumentUpdateRequest;
import com.involutionhell.backend.rag.document.api.RagDocumentView;
import com.involutionhell.backend.rag.indexing.api.RagIndexJobView;
import com.involutionhell.backend.rag.indexing.api.RagIndexOutboxView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineDocumentView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTimelineView;
import com.involutionhell.backend.rag.indexing.api.RagIndexTransitionView;
import com.involutionhell.backend.rag.indexing.messaging.RagIndexMessage;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.retrieval.api.RagAnswerResponse;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import com.involutionhell.backend.rag.retrieval.api.RagContextView;
import com.involutionhell.backend.rag.retrieval.api.RagConversationMessage;
import com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * 为 native image 显式注册当前应用里需要运行时绑定的类型。
 *
 * <p>这里主要覆盖三类对象：
 * 1. Controller 层请求/响应 DTO；
 * 2. RocketMQ 通过统一 JSON codec 读写的消息体；
 * 3. {@link RagProperties} 及其嵌套配置记录。
 */
public class RagRuntimeHints implements RuntimeHintsRegistrar {

    private static final BindingReflectionHintsRegistrar BINDING_HINTS =
            new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        BINDING_HINTS.registerReflectionHints(
                hints.reflection(),
                ApiResponse.class,
                RagAskRequest.class,
                RagDocumentCreateRequest.class,
                RagDocumentUpdateRequest.class,
                RagAnswerResponse.class,
                RagDocumentView.class,
                RagIndexJobView.class,
                RagIndexTimelineDocumentView.class,
                RagIndexTimelineView.class,
                RagContextView.class,
                RagConversationMessage.class,
                RagResponseNoticeView.class,
                RagIndexOutboxView.class,
                RagIndexTransitionView.class,
                RagIndexMessage.class,
                RagProperties.class
        );
    }
}
