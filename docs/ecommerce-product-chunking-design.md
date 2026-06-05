# 电商商品数据 Chunk 切分方案

本文基于 `ecommerce_agent_dataset` 商品 JSON 格式，定义商品数据进入 RAG/向量索引前的父子层级切分方案。

目标：

- 支持商品召回、商品详情问答、FAQ 问答、评价总结、负向过滤、SKU 查询和拍照找货。
- 避免将完整商品 JSON 作为单个 embedding，降低噪声。
- 保留商品级父子关系，方便从命中的 chunk 回溯到商品卡片。

## 1. 输入数据结构

当前单个商品 JSON 包含：

```json
{
  "product_id": "p_digital_001",
  "title": "...",
  "brand": "...",
  "category": "...",
  "sub_category": "...",
  "base_price": 8999.0,
  "image_path": "...",
  "skus": [
    {
      "sku_id": "...",
      "properties": {},
      "price": 8999.0
    }
  ],
  "rag_knowledge": {
    "marketing_description": "...",
    "official_faq": [],
    "user_reviews": []
  }
}
```

## 2. 总体层级

建议采用父子文档模型：

```text
Product Parent
├── product_profile
├── sku_specs
├── marketing_description
├── official_faq
├── user_review
├── review_summary
├── image_caption
└── image_embedding
```

父级代表商品 SPU，子级代表可独立检索的语义片段。

不是所有数据都需要 embedding。推荐按三类处理：

| 数据类型 | 是否做文本 embedding | 主要用途 |
| --- | --- | --- |
| 商品父级 `product_parent` | 可选 | 聚合与回溯，通常不直接检索 |
| 标题、品牌、类目、价格、SKU 属性 | 不优先做 embedding | 结构化过滤、排序、商品卡片展示 |
| 营销描述、FAQ、评价、图片 Caption | 需要 | 语义检索、问答、推荐理由、负向过滤 |
| 商品图片 | 不做文本 embedding，做图片 embedding | 拍照找货、视觉相似召回 |

## 3. 父级文档

每个商品生成一个父级文档，用于聚合和回溯。

父级不一定需要参与 embedding；如果参与，也只用于粗召回。

索引方式：

- 文本 embedding：可选。
- 结构化索引：需要。
- 主要用途：从 child chunk 回查商品卡片，不作为主要语义召回来源。

### parent metadata

```json
{
  "chunkLevel": "parent",
  "chunkType": "product_parent",
  "productId": "p_digital_001",
  "spuId": 123,
  "externalRef": "p_digital_001",
  "title": "...",
  "brand": "Apple 苹果",
  "category": "数码电子",
  "subCategory": "智能手机",
  "categoryPath": "数码电子/智能手机",
  "priceMin": 8999,
  "priceMax": 12499,
  "imagePath": "2_数码电子/images/p_digital_001_live.jpg"
}
```

## 4. 子 Chunk 类型

### 4.1 product_profile

用途：

- 模糊推荐
- 商品搜索
- 类目召回
- 商品卡片摘要

索引方式：

- 文本 embedding：建议做。
- 结构化索引：同时保留。
- 原因：适合承接“推荐一款适合办公的手机”“有没有抗初老精华”这类模糊需求。

内容模板：

```md
# Apple iPhone 17 Pro 6.3英寸 A19 Pro 256GB 全网通旗舰手机

品牌：Apple 苹果
类目：数码电子/智能手机
价格区间：8999 ~ 12499
图片：2_数码电子/images/p_digital_001_live.jpg

商品摘要：
Apple iPhone 17 Pro 搭载 A19 Pro 芯片，适合科技爱好者、内容创作者、商务办公人群。
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "product_profile",
  "productId": "p_digital_001",
  "parentId": "p_digital_001"
}
```

### 4.2 sku_specs

用途：

- 价格查询
- 规格查询
- 库存查询
- 购物车 SKU 选择
- 条件筛选

索引方式：

- 文本 embedding：可选，不是首选。
- 结构化索引：必须做，优先使用 `catalog_sku.spec_json`、价格、库存字段。
- 原因：SKU 是强结构化信息，颜色、容量、尺码、价格等用 SQL/过滤器更可靠。

切分规则：

- SKU 数量少时，一个商品一个 `sku_specs` chunk。
- SKU 数量多时，每 5 到 10 个 SKU 一个 chunk。

内容模板：

```md
## SKU 规格

- sku_id=s_p_digital_001_1：存储=256GB / 颜色=宇宙橙 Cosmic Orange / 版本=全网通版，价格=8999
- sku_id=s_p_digital_001_2：存储=256GB / 颜色=远峰蓝 / 版本=全网通版，价格=8999
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "sku_specs",
  "productId": "p_digital_001",
  "parentId": "p_digital_001",
  "skuIds": ["s_p_digital_001_1", "s_p_digital_001_2"]
}
```

### 4.3 marketing_description

用途：

- 商品详情问答
- 推荐理由
- 适用人群判断
- 使用建议
- 注意事项识别

索引方式：

- 文本 embedding：必须做。
- 结构化索引：可抽取适用人群、功效、注意事项等标签后补充。
- 原因：营销描述承载大量自然语言语义，是推荐理由和详情问答的主要来源。

内容模板：

```md
## 营销描述

Apple iPhone 17 Pro 搭载全新 A19 Pro 芯片...
适合追求极致体验的科技爱好者、内容创作者以及需要高效办公的商务人群。
注意：不同存储版本价格差异较大，选购时需根据自身需求选择。
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "marketing_description",
  "productId": "p_digital_001",
  "parentId": "p_digital_001"
}
```

### 4.4 official_faq

用途：

- 精确商品问答
- 参数解释
- 使用建议
- 适用性判断

索引方式：

- 文本 embedding：必须做。
- 结构化索引：保留 `question`、`productId`、`chunkType`。
- 原因：FAQ 是问答型语料，一问一答 embedding 能显著提升精确问答命中率。

切分规则：

- 一问一答一个 chunk。
- FAQ 问题写入 metadata，便于调试和过滤。

内容模板：

```md
## 官方 FAQ

问题：256GB版本是否足够日常使用？
回答：256GB版本适合大多数用户的日常需求...
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "official_faq",
  "productId": "p_digital_001",
  "parentId": "p_digital_001",
  "question": "256GB版本是否足够日常使用？"
}
```

### 4.5 user_review

用途：

- 评价检索
- 负向过滤
- 优缺点分析
- 口碑判断

索引方式：

- 文本 embedding：建议做。
- 结构化索引：保留评分、情绪标签、商品 ID。
- 原因：评价适合语义检索“续航差不差”“敏感肌会不会刺激”等主观反馈问题。

切分规则：

- 一条用户评价一个 chunk。
- 评分写入 metadata。
- 后续可增加情绪标签，例如 `positive`、`negative`、`mixed`。

内容模板：

```md
## 用户评价

用户：林小雨
评分：4
内容：手机整体性能不错，A19 Pro芯片确实很强大，玩游戏几乎没有卡顿...
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "user_review",
  "productId": "p_digital_001",
  "parentId": "p_digital_001",
  "rating": 4,
  "sentiment": "mixed"
}
```

### 4.6 review_summary

用途：

- 评价总结
- 快速口碑召回
- 推荐理由和风险提示

索引方式：

- 文本 embedding：建议做。
- 结构化索引：保留评分分布、评价数量、平均分。
- 原因：评价摘要适合快速召回商品整体口碑，单条评价适合追溯证据。

生成方式：

- 初期可用规则汇总评分分布。
- 后续可由离线 LLM 总结优点、缺点、适用人群和常见投诉。

内容模板：

```md
## 用户评价摘要

平均评分：3.3
好评关注：性能强、拍照好、外观有质感。
差评关注：续航、价格、屏幕闪烁、客服体验。
适合人群：重视性能、拍照和内容创作体验的用户。
风险提示：对续航和售后敏感的用户需谨慎。
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "review_summary",
  "productId": "p_digital_001",
  "parentId": "p_digital_001",
  "reviewCount": 3,
  "averageRating": 3.3
}
```

### 4.7 image_caption

用途：

- 图片文本化检索
- 拍照找货补充召回
- 图文融合推荐

索引方式：

- 文本 embedding：建议做。
- 图片 embedding：不在本 chunk 中做，但应与同一 `imagePath` 关联。
- 原因：Caption 可以把视觉特征转成文本语义，辅助图文混合查询。

内容模板：

```md
## 图片描述

商品图片展示 Apple iPhone 17 Pro 宇宙橙配色，背部三摄模组，哑光玻璃质感。
```

metadata：

```json
{
  "chunkLevel": "child",
  "chunkType": "image_caption",
  "productId": "p_digital_001",
  "parentId": "p_digital_001",
  "imagePath": "2_数码电子/images/p_digital_001_live.jpg"
}
```

### 4.8 image_embedding

图片向量不一定写入文本 chunk 表，但必须保存与商品的关联。

用途：

- 拍照找货
- 相似商品检索
- 多模态召回

索引方式：

- 文本 embedding：不做。
- 图片 embedding：必须做。
- 结构化索引：保留 `productId`、`spuId`、`imagePath`、`categoryPath`。

metadata：

```json
{
  "embeddingType": "image",
  "model": "clip",
  "productId": "p_digital_001",
  "spuId": 123,
  "externalRef": "p_digital_001",
  "imagePath": "2_数码电子/images/p_digital_001_live.jpg",
  "categoryPath": "数码电子/智能手机"
}
```

## 5. Chunk 与流程图映射

| 流程场景 | 优先使用的 chunk |
| --- | --- |
| 单轮模糊推荐 | `product_profile`, `marketing_description`, `review_summary` |
| 条件筛选 | `catalog_spu`, `catalog_sku`, `sku_specs` |
| 多轮追问与细化 | `product_profile`, `sku_specs`, 历史候选快照 |
| 对比决策 | `product_profile`, `sku_specs`, `official_faq`, `review_summary` |
| 反选/排除约束 | `user_review`, `review_summary`, `marketing_description` |
| 场景化组合推荐 | `product_profile`, `marketing_description` |
| 拍照找货 | `image_embedding`, `image_caption`, `product_profile` |
| 商品详情问答 | `marketing_description`, `official_faq` |
| 评价总结 | `review_summary`, `user_review` |
| 价格/规格/库存 | `catalog_sku`, `sku_specs` |

## 6. Embedding 决策表

| 内容 | 文本 embedding | 图片 embedding | 结构化存储/过滤 | 说明 |
| --- | --- | --- | --- | --- |
| `product_id` / `externalRef` | 否 | 否 | 是 | 主键和回查字段 |
| `title` | 可选 | 否 | 是 | 可进入 `product_profile`，但单独字段主要用于关键词/展示 |
| `brand` | 否 | 否 | 是 | 品牌过滤比语义检索更可靠 |
| `category/sub_category` | 否 | 否 | 是 | 类目过滤和召回路由 |
| `base_price` / SKU 价格 | 否 | 否 | 是 | 数值过滤和排序 |
| `skus[].properties` | 可选 | 否 | 是 | 首选结构化过滤，可进入 `sku_specs` 辅助自然语言问答 |
| `marketing_description` | 是 | 否 | 可选 | 推荐理由、详情问答、适用人群 |
| `official_faq` | 是 | 否 | 是 | 一问一答切分，适合精确问答 |
| `user_reviews` | 是 | 否 | 是 | 评价总结、负向过滤、口碑检索 |
| `review_summary` | 是 | 否 | 是 | 后续生成，用于快速口碑召回 |
| `image_caption` | 是 | 否 | 是 | 图文融合查询 |
| 原始商品图片 | 否 | 是 | 是 | 拍照找货和视觉相似检索 |

## 7. Metadata 建议

所有 child chunk 都应包含：

```json
{
  "chunkLevel": "child",
  "chunkType": "...",
  "productId": "...",
  "parentId": "...",
  "externalRef": "...",
  "spuId": 123,
  "brand": "...",
  "category": "...",
  "subCategory": "...",
  "categoryPath": "...",
  "priceMin": 0,
  "priceMax": 0,
  "imagePath": "..."
}
```

强烈建议保留：

- `productId`：数据集商品 ID。
- `externalRef`：Catalog 外部业务 ID，当前等于 `productId`。
- `spuId`：Catalog 内部 SPU ID。
- `chunkType`：检索路由和结果解释的关键字段。
- `parentId`：回溯商品父级。

## 8. 最小落地版本

第一阶段建议先实现：

- `product_profile`
- `sku_specs`
- `marketing_description`
- `official_faq`
- `user_review`

这五类已经能覆盖：

- 商品搜索
- 条件筛选
- 商品详情问答
- FAQ 问答
- 评价检索
- 负向过滤

第二阶段再补：

- `review_summary`
- `image_caption`
- `image_embedding`

## 9. 注意事项

- 不建议把完整商品 JSON 直接作为单个 chunk；字段混杂会降低召回精度。
- 不建议给纯结构化字段单独做 embedding，例如价格、库存、品牌、类目、SKU ID。
- FAQ 必须一问一答切分，避免多个问题互相干扰。
- 用户评价建议单条切分，方便负向约束和投诉点检索。
- SKU 信息应同时保留在结构化表和文本 chunk 中；结构化表用于过滤，文本 chunk 用于自然语言问答。
- 图片 embedding 与文本 embedding 应分库存储或至少用 `embeddingType` 区分。
- 检索命中 child chunk 后，最终商品卡片应通过 `productId/spuId` 回查父级商品。

## 10. Child 命中后的父级回溯

向量检索命中的通常是 child chunk，例如 `official_faq`、`user_review` 或 `marketing_description`。最终返回给用户时不能只返回 chunk 文本，而要回溯到父级商品并组装完整商品结构。

推荐回溯链路：

```text
命中 child chunk
    ↓
读取 child metadata
    ↓
取 parentId / productId / externalRef / spuId
    ↓
CatalogQueryFacade.findSpuByExternalRef 或 CatalogQueryFacade.getSpu
    ↓
获取 CatalogSpuView + CatalogSkuView
    ↓
合并命中证据 chunk
    ↓
输出商品卡片 / 对比项 / 详情回答
```

### 10.1 child metadata 必填字段

每个可召回 child chunk 必须携带：

```json
{
  "chunkLevel": "child",
  "chunkType": "official_faq",
  "parentId": "p_digital_001",
  "productId": "p_digital_001",
  "externalRef": "p_digital_001",
  "spuId": 123,
  "documentId": 456,
  "chunkId": 789
}
```

字段用途：

| 字段 | 用途 |
| --- | --- |
| `chunkType` | 判断命中来源，例如 FAQ、评价、营销描述 |
| `parentId` | 逻辑父级，通常等于 `productId` |
| `productId` / `externalRef` | 回查 Catalog 的稳定业务 ID |
| `spuId` | 回查 Catalog 的内部主键 |
| `documentId` / `chunkId` | 追溯 RAG 来源和调试 |

### 10.2 父级回查优先级

建议优先级：

```text
1. spuId 存在：CatalogQueryFacade.getSpu(spuId)
2. externalRef 存在：CatalogQueryFacade.findSpuByExternalRef(externalRef)
3. productId 存在：CatalogQueryFacade.findSpuByExternalRef(productId)
4. parentId 存在：CatalogQueryFacade.findSpuByExternalRef(parentId)
```

原因：

- `spuId` 是内部主键，速度和准确性最好。
- `externalRef` 是业务稳定 ID，适合跨系统和离线索引。
- 当前数据集中 `product_id` 会映射为 `externalRef`。

### 10.3 多个 child 命中同一父级

同一商品可能命中多个 child chunk，例如一个 FAQ 和两条评价。

聚合规则：

```text
按 productId/spuId 分组
    ↓
组内保留 topN evidence chunks
    ↓
聚合最高分、平均分、命中类型
    ↓
每个商品只输出一张商品卡
```

商品候选聚合结构建议：

```json
{
  "productId": "p_digital_001",
  "spuId": 123,
  "score": 0.82,
  "matchedChunkTypes": ["official_faq", "user_review"],
  "evidenceChunks": [
    {
      "chunkId": 789,
      "chunkType": "official_faq",
      "score": 0.82,
      "text": "问题：256GB版本是否足够日常使用？..."
    }
  ]
}
```

### 10.4 完整商品卡片结构

父级回查后，建议输出结构：

```json
{
  "productId": "p_digital_001",
  "spuId": 123,
  "title": "Apple iPhone 17 Pro 6.3英寸 A19 Pro 256GB 全网通旗舰手机",
  "brand": "Apple 苹果",
  "categoryPath": "数码电子/智能手机",
  "priceMin": 8999,
  "priceMax": 12499,
  "imagePath": "2_数码电子/images/p_digital_001_live.jpg",
  "skus": [
    {
      "skuId": 1,
      "skuCode": "s_p_digital_001_1",
      "specJson": {
        "存储": "256GB",
        "颜色": "宇宙橙 Cosmic Orange",
        "版本": "全网通版"
      },
      "price": 8999,
      "stock": 100
    }
  ],
  "matchedReasons": [
    "FAQ 命中：256GB 适合大多数日常用户",
    "评价命中：用户反馈性能强、视频剪辑流畅"
  ],
  "evidenceChunks": [
    {
      "chunkType": "official_faq",
      "chunkId": 789,
      "score": 0.82
    }
  ]
}
```

### 10.5 不同召回源的回溯方式

| 召回源 | 命中对象 | 回溯字段 | 回查方式 |
| --- | --- | --- | --- |
| 文本向量 | child chunk | `spuId` / `externalRef` | 查 Catalog SPU |
| 关键词 RAG | child chunk | `spuId` / `externalRef` | 查 Catalog SPU |
| 结构化过滤 | Catalog SPU/SKU | `spuId` | 已在 Catalog 内 |
| 图片向量 | image embedding record | `spuId` / `externalRef` / `imagePath` | 查 Catalog SPU |
| 候选快照 | candidate snapshot item | `spuId` / `externalRef` / `skuId` | 查 Catalog SPU/SKU |

### 10.6 推荐实现位置

后续可新增一个聚合服务：

```text
ProductRecallHydrationService
```

职责：

- 接收文本/图片/结构化召回结果。
- 按 `spuId/externalRef` 聚合 child hits。
- 回查 `CatalogQueryFacade` 获取完整商品。
- 输出统一 `ProductRecallCandidate` 或商品卡片 DTO。

伪代码：

```java
List<ProductRecallCandidate> hydrate(List<ChunkHit> hits) {
    Map<ProductKey, List<ChunkHit>> grouped = groupByProduct(hits);
    return grouped.entrySet().stream()
            .map(entry -> {
                CatalogSpuView spu = loadSpu(entry.getKey());
                return ProductRecallCandidate.from(spu, entry.getValue());
            })
            .toList();
}
```
