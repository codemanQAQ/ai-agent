# 电商 Agent 统一 JSON Schema

本文档整理当前多模态电商推荐流程中的主要 JSON 结构。原则是：**主流程、API、RAG 输出统一使用 camelCase；原始数据集和 SQL 字段保持其既有 snake_case**。

## 0. 命名规则

- 主流程 JSON：camelCase，例如 `productId`、`positiveConstraints`、`needClarify`。
- 离线数据库列名：snake_case，例如 `product_id`、`price_min`、`parent_chunk_id`。
- 原始商品数据：保持数据集原格式，例如 `product_id`、`sub_category`、`base_price`、`rag_knowledge`。
- 购物车 legacy slots：当前仍保留 snake_case，例如 `product_name`、`cart_action`，用于兼容既有购物车子流程。

## 1. 用户输入 API

状态：已实现。

对应代码：`AgentTurnRequest`。

ASR 不进入 graph 专门建模。音频先在外部调用 ASR，返回文本后作为普通 `message` 传入。

```json
{
  "userId": "u_001",
  "conversationId": "c_001",
  "message": "推荐一款适合油皮的洗面奶",
  "turnId": "turn_001",
  "requestId": "req_001",
  "imageRef": null,
  "imageCaption": null,
  "imageEmbeddingRef": null,
  "history": [
    {
      "role": "user",
      "content": "上次推荐的第一个怎么样？"
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | string | 用户 ID，必填 |
| `conversationId` | string | 会话 ID，必填 |
| `message` | string/null | 当前轮文本。ASR 文本也放这里 |
| `turnId` | string/null | 当前轮 ID；为空时内部生成 `runId` |
| `requestId` | string/null | 请求 ID；为空时内部生成 |
| `imageRef` | string/null | 图片引用，可以是图片路径或预处理后引用 |
| `imageCaption` | string/null | 图片 caption 结果 |
| `imageEmbeddingRef` | string/null | 图片向量引用，不直接传大数组 |
| `history` | array | 前端或调用方传入的轻量历史 |

`history` 当前只支持 `role/content` 两个字段；服务端真实历史加载由会话模块负责。

## 2. Graph 内部请求

状态：已实现。

对应代码：`GuideGraphRequest`。

```json
{
  "userId": "u_001",
  "conversationId": "c_001",
  "message": "推荐一款适合油皮的洗面奶",
  "runId": "turn_001",
  "requestId": "req_001",
  "imageRef": null,
  "imageCaption": null,
  "imageEmbeddingRef": null,
  "originalMessage": "推荐一款适合油皮的洗面奶",
  "inputModalities": ["text"],
  "correlationId": "req_001",
  "initialIntent": null,
  "history": []
}
```

`originalMessage` 用于保留用户原始输入，`message` 后续可以被预处理改写或补充；`inputModalities` 用于让流程知道本轮输入包含 `text`、`image` 等模态。

## 3. 意图识别输出

状态：已实现，prompt 已统一为 camelCase。

对应代码：`MainIntentDecision`、`main-intent-router-v1.txt`。

```json
{
  "intent": "FUZZY_RECOMMEND",
  "confidence": 0.86,
  "needClarify": false,
  "writeAction": false,
  "targetWorkflow": "product_recommend_workflow",
  "subIntent": "FUZZY_RECOMMEND",
  "reason": "用户希望获得适合油皮的洗面奶推荐",
  "clarifyQuestion": null,
  "slots": {
    "positiveConstraints": {
      "category": "洗面奶",
      "subCategory": null,
      "brand": null,
      "priceMin": null,
      "priceMax": null,
      "scenario": null,
      "audience": null,
      "usageContext": null,
      "attributes": ["适合油皮"],
      "productRefs": []
    },
    "negativeConstraints": {
      "brands": [],
      "categories": [],
      "attributes": [],
      "price": null,
      "reviewSignals": []
    },
    "action": {
      "type": null,
      "targetRef": null,
      "quantity": null,
      "skuSpec": null,
      "source": null,
      "addressRef": null,
      "orderRef": null
    },
    "bundleRoles": []
  },
  "missingSlots": []
}
```

`bundleRoles` 仅在 `intent=SCENE_BUNDLE_RECOMMEND` 时填充，用于场景化组合推荐——由意图模型在同一次调用里把场景拆成 3-5 个角色，每个元素形如：

```json
{ "role": "防晒护肤", "category": "美妆护肤", "keywords": "防晒 补水" }
```

其中 `category` 必须是真实目录类目之一（`美妆护肤` / `服饰运动` / `数码电子` / `食品饮料`），否则该角色会被丢弃。其它 intent 下 `bundleRoles` 为 `[]`。

允许的 `intent`：

```json
[
  "FUZZY_RECOMMEND",
  "CONDITION_FILTER",
  "MULTI_TURN_REFINE",
  "PRODUCT_COMPARE",
  "NEGATIVE_CONSTRAINT",
  "SCENE_BUNDLE_RECOMMEND",
  "PHOTO_SEARCH",
  "CART_MANAGE",
  "ORDER_MANAGE",
  "OTHER"
]
```

说明：

- `needClarify=true` 时，`clarifyQuestion` 必须是可直接展示给用户的追问话术。
- 追问不是一种 intent，而是任意可执行 intent 上的动作判断。
- `writeAction=true` 仅用于购物车/订单修改类操作。
- `subIntent` 用于一次识别内完成 workflow 内细化：推荐类通常等于推荐子场景；购物车/订单类可写具体动作。
- `reason`、`slots` 中的自然语言值应与用户输入语言一致。
- 推荐 workflow 消费 `positiveConstraints/negativeConstraints`；场景化组合（SCENE_BUNDLE_RECOMMEND）额外消费 `slots.bundleRoles`（无则退到内置场景规则/类目推断）。
- 购物车 workflow 消费 `slots.action.type/targetRef/quantity/skuSpec`。
- 订单 workflow 消费 `slots.action.type/source/addressRef/orderRef`。
- 为兼容历史代码，后端可临时把旧的 `cartAction/productRef` 等字段归一化为 `slots.action`；新 prompt 和新接口不再生成这些扁平字段。

允许的 `subIntent`：

```json
{
  "recommendation": [
    "FUZZY_RECOMMEND",
    "CONDITION_FILTER",
    "MULTI_TURN_REFINE",
    "PRODUCT_COMPARE",
    "NEGATIVE_CONSTRAINT",
    "SCENE_BUNDLE_RECOMMEND",
    "PHOTO_SEARCH"
  ],
  "cart": [
    "VIEW_CART",
    "ADD_TO_CART",
    "REMOVE_FROM_CART",
    "UPDATE_CART_ITEM",
    "CLEAR_CART",
    "REPLACE_SKU"
  ],
  "order": [
    "CREATE_ORDER",
    "CONFIRM_ORDER",
    "CANCEL_ORDER",
    "ORDER_QUERY",
    "LOGISTICS_QUERY",
    "FILL_ADDRESS"
  ]
}
```

## 4. Graph State

状态：部分已实现。

对应代码：`GuideGraphStateKeys`。

```json
{
  "userId": "u_001",
  "conversationId": "c_001",
  "conversationExists": true,
  "conversationInternalId": 1001,
  "message": "推荐一款适合油皮的洗面奶",
  "runId": "turn_001",
  "requestId": "req_001",
  "imageRef": null,
  "originalMessage": "推荐一款适合油皮的洗面奶",
  "inputModalities": ["text"],
  "imageCaption": null,
  "imageEmbeddingRef": null,
  "correlationId": "req_001",
  "initialIntent": null,
  "intent": "FUZZY_RECOMMEND",
  "mainIntent": "FUZZY_RECOMMEND",
  "intentConfidence": 0.86,
  "needClarify": false,
  "writeAction": false,
  "targetWorkflow": "product_recommend_workflow",
  "subIntent": "FUZZY_RECOMMEND",
  "intentReason": "用户希望获得适合油皮的洗面奶推荐",
  "clarifyQuestion": null,
  "intentSlots": {},
  "missingSlots": [],
  "routeSource": "llm",
  "llmCalled": true,
  "graphStatus": "COMPLETED",
  "lastNodeResult": {},
  "nodeResults": [],
  "workflowResult": {},
  "evidence": [],
  "businessResult": {},
  "answerContext": {},
  "recentMessages": [],
  "messageCount": 1
}
```

兼容字段：

- `workflow_status`、`clarify_reason`、`need_user_input`、`node_message` 为部分子流程 legacy state key。
- `cart_action`、`product_name`、`product_candidates` 为购物车子流程字段。

## 5. 全局会话状态

状态：模型和状态合并节点已实现；结构化状态持久化待实现。

建议 schema：

```json
{
  "schemaVersion": "1.0",
  "userId": "u_001",
  "conversationId": "c_001",
  "updatedAt": "2026-06-02T12:00:00Z",
  "recommendationState": {
    "activeIntent": "FUZZY_RECOMMEND",
    "scenario": null,
    "accumulatedConstraints": {
      "positiveConstraints": {},
      "negativeConstraints": {}
    },
    "missingSlots": [],
    "clarifyQuestion": null,
    "candidateSnapshot": {
      "productIds": [],
      "updatedAt": null
    },
    "lastRecommendationResult": {
      "products": []
    }
  },
  "multimodalState": {
    "current": null,
    "history": []
  },
  "cartState": {
    "lastCartAction": null,
    "pendingActionId": null
  },
  "orderState": {
    "pendingOrderActionId": null,
    "lastOrderId": null
  }
}
```

合并顺序建议：

1. 加载历史 `AgentSessionState`。
2. 统一本轮模态信息为 `CurrentTurnMultimodalContext`。
3. 将本轮意图识别结果和本轮模态 patch 合并进 `AgentSessionState`。
4. 进入推荐 workflow 后，从合并后的状态构建统一召回上下文。

当前 graph 顺序：

```json
[
  "check_conversation",
  "load_memory 或 init_conversation",
  "load_agent_session_state",
  "current_turn_multimodal_unifier",
  "save_user_message",
  "main_intent_router",
  "agent_session_state_merger",
  "业务 workflow",
  "build_answer_context"
]
```

## 6. 本轮模态统一上下文

状态：已实现。

```json
{
  "schemaVersion": "1.0",
  "inputModalities": ["text", "image"],
  "originalMessage": "找一款类似图片里的包，适合通勤",
  "message": "找一款类似图片里的包，适合通勤",
  "imageRef": "bags/images/p_bag_001_live.jpg",
  "imageCaption": "图片中是一款黑色通勤双肩包，外观简洁，适合办公和日常通勤。",
  "imageEmbeddingRef": "ecom-image-query:req_001",
  "queryTextForRecall": "找一款类似图片里的包，适合通勤\n图片中是一款黑色通勤双肩包，外观简洁，适合办公和日常通勤。",
  "hasImage": true,
  "imageFromHistory": false
}
```

该结构由 `current_turn_multimodal_unifier` 写入 `currentTurnMultimodalContext`。如果本轮没有新图片但用户引用“刚才那张图”，节点会尝试从 `AgentSessionState.multimodalState` 取最近有效图片上下文，并将 `imageFromHistory` 置为 `true`。

## 7. 状态合并输出

状态：已实现。

对应节点：`agent_session_state_merger`。

输入来源：

- 历史 `agentSessionState`
- 当前 `mainIntent` / `intent`
- 当前 `intentSlots`
- 当前 `missingSlots`
- 当前 `clarifyQuestion`
- 当前 `currentTurnMultimodalContext`

输出仍写回 `agentSessionState`：

```json
{
  "agentSessionState": {
    "schemaVersion": "1.0",
    "recommendationState": {
      "activeIntent": "PHOTO_SEARCH",
      "scenario": null,
      "accumulatedConstraints": {
        "category": "包",
        "scenario": "通勤"
      },
      "negativeConstraints": {},
      "missingSlots": [],
      "clarifyQuestion": null,
      "candidateSnapshot": {
        "productIds": [],
        "updatedAt": null
      },
      "lastRecommendationResult": {
        "products": []
      }
    },
    "multimodalState": {
      "current": {
        "inputModalities": ["text", "image"],
        "imageRef": "bags/images/p_bag_001_live.jpg",
        "imageCaption": "图片中是一款黑色通勤双肩包。",
        "hasImage": true,
        "imageFromHistory": false
      },
      "history": []
    }
  }
}
```

当前合并策略是轻量版：本轮正向约束覆盖历史同字段，空值不清空历史；负向约束按字段合并；模态上下文写入 `multimodalState.current`，旧 current 会进入 `history`。

## 8. 统一召回查询上下文

状态：已实现。

对应服务：`UnifiedQueryContextBuilder`。

该结构不在主图路由前构建，而是在 `product_recommend_workflow` 内部构建，写入 `unifiedQueryContext`，同时放入 `workflowResult.unifiedQueryContext`。

```json
{
  "schemaVersion": "1.0",
  "intent": "PHOTO_SEARCH",
  "queryText": "找类似图片里的包，适合通勤\n图片中是一款黑色通勤双肩包。",
  "inputModalities": ["text", "image"],
  "imageRef": "bags/images/p_bag_001_live.jpg",
  "imageCaption": "图片中是一款黑色通勤双肩包。",
  "imageEmbeddingRef": "ecom-image-query:req_001",
  "positiveConstraints": {
    "category": "双肩包",
    "scenario": "通勤",
    "priceMax": 300
  },
  "negativeConstraints": {
    "attributes": ["太重"]
  },
  "scope": {
    "productIds": ["p_bag_002"],
    "externalRefs": ["p_bag_001"],
    "catalogSpuIds": [101]
  },
  "candidateSnapshot": {
    "productIds": ["p_bag_002"],
    "updatedAt": "2026-06-02T12:00:00Z"
  },
  "needClarify": false,
  "missingSlots": [],
  "clarifyQuestion": null
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `queryText` | 文本召回输入，由用户文本和图片 caption 组合而来 |
| `imageRef/imageCaption/imageEmbeddingRef` | 图片召回和图文融合召回输入 |
| `positiveConstraints` | 正向结构化约束，来自合并后的推荐状态 |
| `negativeConstraints` | 反向约束，供召回过滤或后排过滤使用 |
| `scope` | 限定检索范围，支持 `productIds/externalRefs/catalogSpuIds` |
| `candidateSnapshot` | 历史候选快照，用于“第一个/刚才那个”等引用 |
| `needClarify/missingSlots/clarifyQuestion` | 召回前是否还需要追问 |

## 9. ASR 输出

状态：已实现于 Python，主流程只使用 `text`。

对应代码：`DoubaoAsrResult`、`transcribe_audio_text`。

下方是进入主流程前建议使用的统一 camelCase 视图。Python 本地 `public_view()` 由 `dataclasses.asdict` 生成，当前调试输出仍是 snake_case，例如 `is_final`、`elapsed_ms`。

```json
{
  "text": "推荐一款适合油皮的洗面奶",
  "segments": [
    {
      "text": "推荐一款适合油皮的洗面奶",
      "isFinal": true,
      "startTimeMs": 0,
      "endTimeMs": 2400,
      "raw": {}
    }
  ],
  "elapsedMs": 1200.5,
  "endpoint": "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel",
  "resourceId": "volc.bigasr.sauc.duration",
  "requestId": "req_001",
  "connectId": "connect_001",
  "audio": {
    "path": "wav0.wav",
    "contentType": "audio/wav",
    "sizeBytes": 102400
  }
}
```

业务接入时通常只调用 `transcribe_audio_text(audioPath)`，得到字符串后传入 `AgentTurnRequest.message`。

## 10. 图片输入处理输出

状态：已实现于 Python。

对应代码：`ImageInputResult`。

下方是进入主流程前建议使用的统一 camelCase 视图。Python 本地 `public_view()` 由 `dataclasses.asdict` 生成，当前调试输出仍是 snake_case，例如 `image_ref`、`resolved_ref`、`content_type`、`size_bytes`、`image_embedding`。

```json
{
  "image": {
    "imageRef": "1_美妆护肤/images/p_beauty_001_live.jpg",
    "resolvedRef": "C:/development/ecommerce_agent_dataset/1_美妆护肤/images/p_beauty_001_live.jpg",
    "contentType": "image/jpeg",
    "sizeBytes": 345678,
    "width": 800,
    "height": 800,
    "warnings": []
  },
  "caption": "图片中是一瓶护肤精华，瓶身为棕色，适合护肤场景。",
  "imageEmbedding": null,
  "imageEmbeddingDimension": 2048,
  "captionModel": "ep-20260514111645-lmgt2",
  "embeddingModel": "doubao-embedding-vision-251215",
  "elapsedMs": 1800.2
}
```

说明：

- 默认不输出完整 `imageEmbedding`，只输出 `imageEmbeddingDimension`。
- 进入 Java graph 时，推荐传 `imageCaption` 和 `imageEmbeddingRef`，不要传大向量数组。

## 11. 原始商品数据

状态：数据集原格式。

```json
{
  "product_id": "p_digital_001",
  "title": "商品标题",
  "brand": "品牌",
  "category": "数码电子",
  "sub_category": "智能手机",
  "base_price": 8999.0,
  "image_path": "2_数码电子/images/p_digital_001_live.jpg",
  "skus": [
    {
      "sku_id": "s_p_digital_001_1",
      "properties": {
        "存储": "256GB",
        "颜色": "黑色"
      },
      "price": 8999.0,
      "stock": 100
    }
  ],
  "rag_knowledge": {
    "marketing_description": "商品营销描述",
    "official_faq": [
      {
        "question": "常见问题",
        "answer": "官方回答"
      }
    ],
    "user_reviews": [
      {
        "nickname": "用户昵称",
        "rating": 5,
        "content": "用户评价内容"
      }
    ]
  }
}
```

字段映射到主流程时统一为：

| 原始字段 | 主流程字段 |
| --- | --- |
| `product_id` | `productId` / `externalRef` |
| `sub_category` | `subCategory` |
| `base_price` | `basePrice` |
| `image_path` | `imagePath` |
| `rag_knowledge.marketing_description` | `knowledge.marketingDescription` |
| `rag_knowledge.official_faq` | `knowledge.officialFaq` |
| `rag_knowledge.user_reviews` | `knowledge.userReviews` |

## 12. 商品 Chunk JSONL

状态：已实现。

对应文件：`ecom-product-chunks.jsonl`。

```json
{
  "chunkId": "p_beauty_001:product_profile:0",
  "productId": "p_beauty_001",
  "parentChunkId": "p_beauty_001:product_parent:0",
  "chunkLevel": "child",
  "chunkType": "product_profile",
  "chunkIndex": 0,
  "textContent": "# 商品标题\n品牌：...",
  "contentSha256": "83ad1934f695f00c...",
  "embeddingRequired": true,
  "embeddingModality": "text",
  "sourceRef": {
    "field": "product_profile"
  },
  "metadata": {
    "productId": "p_beauty_001",
    "externalRef": "p_beauty_001",
    "title": "商品标题",
    "brand": "品牌",
    "category": "美妆护肤",
    "subCategory": "精华",
    "categoryPath": "美妆护肤/精华",
    "priceMin": 720.0,
    "priceMax": 1260.0,
    "stock": 300,
    "imagePath": "1_美妆护肤/images/p_beauty_001_live.jpg",
    "chunkLevel": "child",
    "chunkType": "product_profile",
    "parentId": "p_beauty_001"
  }
}
```

当前 JSONL 文件中实际字段是 snake_case，如 `chunk_id`、`product_id`、`parent_chunk_id`。上面是进入主流程或接口时建议使用的 camelCase 视图。

稳定 `chunkType`：

```json
[
  "product_parent",
  "product_profile",
  "marketing_description",
  "official_faq",
  "user_review",
  "image"
]
```

Embedding 规则：

- `product_parent` 不做 embedding，只作为父节点和完整回表锚点。
- `product_profile`、`marketing_description`、`official_faq`、`user_review` 做文本 embedding。
- `image` 做图片 embedding。
- 品牌、类目、价格、库存、SKU 属性不单独 embedding，进入 SQL 或 metadata 过滤。

## 13. FAISS ID Map

状态：已实现。

对应文件：`faiss/ecom-product-id-map.json`。

```json
{
  "faissId": 1,
  "chunkId": "p_beauty_001:product_profile:0",
  "productId": "p_beauty_001",
  "parentChunkId": "p_beauty_001:product_parent:0",
  "chunkType": "product_profile",
  "chunkIndex": 0,
  "embeddingModality": "text",
  "embeddingFingerprint": "83ad1934f695f00c...",
  "model": "doubao-embedding-vision-251215",
  "dimension": 2048,
  "vectorId": "ecom-text:doubao-embedding-vision-251215:p_beauty_001:product_profile:0",
  "metadata": {
    "productId": "p_beauty_001",
    "externalRef": "p_beauty_001",
    "brand": "品牌",
    "category": "美妆护肤",
    "subCategory": "精华",
    "categoryPath": "美妆护肤/精华",
    "priceMin": 720.0,
    "priceMax": 1260.0,
    "stock": 300,
    "imagePath": "1_美妆护肤/images/p_beauty_001_live.jpg",
    "chunkLevel": "child",
    "chunkType": "product_profile",
    "parentId": "p_beauty_001"
  },
  "sourceRef": {
    "field": "product_profile"
  }
}
```

当前文件实际字段同样是 snake_case，如 `faiss_id`、`chunk_id`、`vector_id`。进入业务输出时转为 camelCase。

## 14. 向量召回请求

状态：Python main 接口已实现。

对应代码：`recall_by_embedding`。

```json
{
  "queryEmbedding": [0.01, 0.02],
  "topK": 5,
  "hydrate": true,
  "chunkTypes": ["product_profile", "marketing_description", "official_faq", "user_review"],
  "modalities": ["text"],
  "externalRefs": ["p_beauty_001"],
  "productIds": ["p_beauty_001"],
  "catalogSpuIds": [101],
  "previewChars": 240
}
```

过滤字段：

- `chunkTypes`：限制命中的 chunk 类型。
- `modalities`：限制 `text` 或 `image`。
- `externalRefs` / `productIds` / `catalogSpuIds`：限定检索范围。

## 15. RAG 最终召回输出

状态：测试报告已实现，主流程推荐工作流待接入。

对应代码：`run_text_recall_report.py`、`run_image_recall_report.py`。

```json
{
  "products": [
    {
      "rank": 1,
      "score": 0.8234,
      "product": {
        "productId": "p_beauty_001",
        "externalRef": "p_beauty_001",
        "title": "商品标题",
        "brand": "品牌",
        "categoryPath": "美妆护肤/洁面",
        "priceMin": 99.0,
        "priceMax": 129.0,
        "stockTotal": 300,
        "imagePath": "1_美妆护肤/images/p_beauty_001_live.jpg"
      },
      "skus": [
        {
          "skuId": "s_p_beauty_001_1",
          "properties": {
            "规格": "150ml"
          },
          "price": 99.0,
          "stock": 100
        }
      ],
      "evidences": [
        {
          "score": 0.8234,
          "chunkId": "p_beauty_001:product_profile:0",
          "chunkType": "product_profile",
          "embeddingModality": "text",
          "textPreview": "命中的 chunk 文本摘要"
        }
      ]
    }
  ],
  "speedTest": {
    "queryEmbeddingMs": 520.1,
    "imageEmbeddingMs": null,
    "textEmbeddingMs": null,
    "recallWithHydrationMs": 12.3,
    "endToEndMs": 532.4,
    "recallOnly": {
      "iterations": 3,
      "topK": 5,
      "averageMs": 2.1,
      "minMs": 1.8,
      "maxMs": 2.6
    }
  }
}
```

图片召回额外字段：

```json
{
  "image": {
    "imageRef": "1_美妆护肤/images/p_beauty_001_live.jpg",
    "resolvedRef": "...",
    "contentType": "image/jpeg",
    "sizeBytes": 345678,
    "width": 800,
    "height": 800,
    "warnings": []
  },
  "visionSignals": {
    "confidence": "HIGH"
  },
  "fusion": {
    "channels": ["image", "text"],
    "strategy": "weighted"
  }
}
```

## 16. 完整商品卡片

状态：Python 回表组装已实现。

对应代码：`assemble_product_card`。

```json
{
  "product": {
    "productId": "p_beauty_001",
    "externalRef": "p_beauty_001",
    "title": "商品标题",
    "brand": "品牌",
    "category": "美妆护肤",
    "subCategory": "洁面",
    "categoryPath": "美妆护肤/洁面",
    "basePrice": 99.0,
    "priceMin": 99.0,
    "priceMax": 129.0,
    "stockTotal": 300,
    "imagePath": "1_美妆护肤/images/p_beauty_001_live.jpg",
    "datasetFile": "1_美妆护肤/data/p_beauty_001.json"
  },
  "skus": [],
  "knowledge": {
    "marketingDescription": "营销描述",
    "officialFaq": [],
    "userReviews": [],
    "faqCount": 0,
    "reviewSummary": {
      "reviewCount": 0,
      "averageRating": null,
      "ratingDistribution": {
        "1": 0,
        "2": 0,
        "3": 0,
        "4": 0,
        "5": 0
      }
    }
  },
  "evidence": {
    "faissId": 1,
    "vectorId": "ecom-text:...",
    "score": 0.8234,
    "matchedChunk": {
      "chunkId": "p_beauty_001:product_profile:0",
      "chunkType": "product_profile",
      "chunkIndex": 0,
      "embeddingModality": "text",
      "sourceRef": {
        "field": "product_profile"
      },
      "metadata": {},
      "textPreview": "命中文本"
    },
    "parentChunk": {
      "chunkId": "p_beauty_001:product_parent:0",
      "chunkType": "product_parent",
      "metadata": {}
    }
  },
  "lookupTrace": {
    "faissIdMap": "faiss_id -> chunk_id",
    "chunkLookup": "SELECT * FROM ecommerce_offline.ecom_product_chunk WHERE chunk_id = :chunkId",
    "parentLookup": "SELECT * FROM ecommerce_offline.ecom_product_chunk WHERE chunk_id = :parentChunkId",
    "productLookup": "SELECT * FROM ecommerce_offline.ecom_product WHERE product_id = :productId",
    "skuLookup": "SELECT * FROM ecommerce_offline.ecom_sku WHERE product_id = :productId",
    "knowledgeLookup": []
  }
}
```

推荐工作流真正给 LLM 的上下文建议使用第 13 节的精简 `products`，完整商品卡片用于调试、回表、详情页或需要完整结构的场景。

## 17. SSE 事件

状态：已实现。

对应代码：`AgentStreamEvent`。

```json
{
  "id": "event_001",
  "event": "turn.started",
  "correlationId": "req_001",
  "data": {}
}
```

常见 event：

```json
[
  "turn.started",
  "node.started",
  "node.completed",
  "node.failed",
  "answer.delta",
  "answer.completed",
  "turn.completed",
  "turn.error"
]
```

## 18. 节点结果

状态：已实现。

对应代码：`GuideNodeResult`。

```json
{
  "nodeName": "main_intent_router",
  "status": "COMPLETED",
  "routeIntent": "FUZZY_RECOMMEND",
  "errorCode": null,
  "errorMessage": null,
  "startedAt": "2026-06-02T12:00:00Z",
  "completedAt": "2026-06-02T12:00:01Z",
  "metadata": {
    "llmCalled": true
  }
}
```

## 19. Graph 最终摘要

状态：已实现。

对应代码：`GuideGraphFinalSummary`。

```json
{
  "runId": "turn_001",
  "requestId": "req_001",
  "correlationId": "req_001",
  "conversationId": "c_001",
  "userId": "u_001",
  "intent": "FUZZY_RECOMMEND",
  "targetWorkflow": "product_recommend_workflow",
  "status": "COMPLETED",
  "finalNode": "product_recommend_final_response",
  "errorCode": null,
  "errorMessage": null,
  "completedAt": "2026-06-02T12:00:02Z"
}
```

## 20. 统一兼容性结论

当前已经统一的部分：

- 意图识别 JSON 使用 camelCase。
- Graph API 和 Graph state 主要字段使用 camelCase。
- 图片输入、召回测试输出、商品卡片输出使用 camelCase。
- 商品文档 metadata 使用 camelCase，可直接做过滤。

仍需注意的部分：

- 原始商品数据和离线 SQL 使用 snake_case，这是数据层格式，不建议强行改成 camelCase。
- `ecom-product-chunks.jsonl` 和 `faiss id-map` 当前文件内仍有 snake_case 顶层字段，进入主流程或接口输出时应转换为 camelCase。
- 购物车子流程仍有 legacy snake_case slots，后续若要完全统一，需要单独做兼容迁移。
- `AgentSessionState` 模型、`load_agent_session_state`、`current_turn_multimodal_unifier`、`agent_session_state_merger` 已实现。
- `UnifiedQueryContextBuilder` 已在 `product_recommend_workflow` 内实现；后续真实召回节点可直接消费 `unifiedQueryContext`。
