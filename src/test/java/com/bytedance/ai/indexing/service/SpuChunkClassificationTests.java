package com.bytedance.ai.indexing.service;

import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.graph.catalog.application.SpuMarkdownRenderer;
import com.bytedance.ai.indexing.model.RagTextChunk;
import com.bytedance.ai.shared.markdown.MarkdownDocumentParser;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.metadata.RagChunkTypeClassifier;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagOpenAiTokenCounter;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：SpuMarkdownRenderer 渲染出的 markdown 经过 RagTextChunker 切分后，
 * RagChunkTypeClassifier 能为每个 chunk 产出正确的 chunk_type。
 *
 * <p>这条链路就是 catalog 导入 -> 索引 -> 检索时实际走的路径，确保 W1-IDX-02
 * 「商品按 4 类文本 chunk 切分」的行为符合预期。
 */
class SpuChunkClassificationTests {

    private final SpuMarkdownRenderer renderer = new SpuMarkdownRenderer();
    private final RagChunkTypeClassifier classifier = new RagChunkTypeClassifier();
    private final RagProperties ragProperties = RagProperties.defaults();
    private final RagTextChunker chunker = new RagTextChunker(
            ragProperties,
            new MarkdownDocumentParser(new RagJsonCodec(JsonMapper.builder().build())),
            new RagOpenAiTokenCounter(ragProperties)
    );

    @Test
    void spuMarkdownChunksAreClassifiedIntoTitleDescAttr() {
        CatalogSpuCreateRequest spu = new CatalogSpuCreateRequest(
                "SPU-CLS-1",
                "轻量通勤双肩包",
                "Acme",
                "服装/箱包/双肩包",
                new BigDecimal("199"),
                new BigDecimal("299"),
                10,
                "面向都市通勤场景的轻量双肩包，主舱可放 14 寸笔记本，外层 600D 涂层防泼面料，雨天通勤不需额外雨罩。",
                List.of(),
                null,
                List.of(
                        new CatalogSpuCreateRequest.SkuDraft("SKU-A", Map.of("color", "黑色"), new BigDecimal("199"), 5),
                        new CatalogSpuCreateRequest.SkuDraft("SKU-B", Map.of("color", "藏青"), new BigDecimal("299"), 5)
                )
        );

        String markdown = renderer.render(spu);
        List<RagTextChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).as("SPU 渲染后至少应被切成 3 段：H1 标题段 / 商品描述 / 规格").hasSizeGreaterThanOrEqualTo(3);

        List<RagChunkType> types = chunks.stream()
                .map(c -> classifier.classify("catalog-spu", c.chunkIndex(), c.headingPath(), c.blockMetadata()))
                .toList();

        assertThat(types)
                .as("应至少同时覆盖 TITLE / DESC / ATTR 三种类型，便于后续检索按 chunk_type 加权")
                .contains(RagChunkType.TITLE, RagChunkType.DESC, RagChunkType.ATTR);
    }

    @Test
    void nonCatalogMarkdownStaysBody() {
        String markdown = """
                # 普通文档标题

                ## 概览
                这是一段普通正文，与电商无关。

                ## 详细
                正文段落。
                """;
        List<RagTextChunk> chunks = chunker.chunk(markdown);

        for (RagTextChunk c : chunks) {
            RagChunkType chunkType = classifier.classify("markdown", c.chunkIndex(), c.headingPath(), c.blockMetadata());
            assertThat(chunkType)
                    .as("非 catalog-spu 来源不走启发式，统一归 BODY")
                    .isEqualTo(RagChunkType.BODY);
        }
    }
}
