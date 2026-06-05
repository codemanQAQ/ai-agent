# 多模态电商 Agent 流程补齐 TODO

本文按《多模态电商推荐系统工作流程图》的执行顺序整理代码模块补齐项，并以 `ecommerce_agent_dataset` 商品 JSON 格式作为商品数据参考。

## 0. 商品数据接入准备

### 0.1 数据集格式适配

- [x] 新增数据集导入适配器：将 `ecommerce_agent_dataset/**/data/*.json` 转换为 `CatalogImportRequest`。
  - 实现：`EcommerceDatasetCatalogImportAdapter`
  - 入口：`POST /admin/catalog/import/ecommerce-dataset`
- [x] 建立字段映射：
  - 实现：`EcommerceDatasetCatalogImportAdapter#adapProduct`
  - `product_id` -> `externalRef`
  - `title` -> `title`
  - `brand` -> `brand`
  - `category/sub_category` -> `categoryPath`，格式为 `category/sub_category`
  - `base_price` 与 `skus[].price` -> `priceMin/priceMax`，优先使用 SKU 价格区间，缺失时回退 `base_price`
  - `image_path` -> `images`，当前转为单元素图片列表
  - `skus[].sku_id` -> `skuCode`
  - `skus[].properties` -> `specJson`
  - `rag_knowledge` -> `descriptionMd`，包含营销描述、官方 FAQ、用户评价
- [x] 将 `rag_knowledge.marketing_description`、`official_faq`、`user_reviews` 渲染为稳定 Markdown。
  - 实现：`EcommerceDatasetCatalogImportAdapter#renderDescription`
  - 渲染顺序：`营销描述` -> `官方 FAQ` -> `用户评价`
  - 测试：`EcommerceDatasetCatalogImportAdapterTests#adaptsDatasetJsonToCatalogImportRequest`
- [x] 统一目录类目与商品类目命名，例如 `4_食品生活` 与 JSON 中 `食品饮料` 的映射。
  - 实现：`EcommerceDatasetCatalogImportAdapter#normalizeCategory`
  - 当前别名：`食品生活` -> `食品饮料`
  - 目录名会去除数字前缀，例如 `4_食品生活` -> `食品生活` -> `食品饮料`
- [x] 补库存默认策略：数据集中没有 `stock`，导入时需要默认库存或测试库存生成规则。
  - 当前策略：每个 SKU 默认库存 `100`
  - SPU 总库存：所有 SKU 默认库存求和
  - 测试：`EcommerceDatasetCatalogImportAdapterTests#adaptsDatasetJsonToCatalogImportRequest`

### 0.2 离线主数据入库

- [x] 复用现有 `CatalogImportService` 将适配后的 SPU/SKU 写入 `catalog_spu` 与 `catalog_sku`。
- [x] 复用现有导入链路将商品 Markdown 双写到 `rag_documents`。
- [x] 新增离线导入编排服务：`EcommerceDatasetImportService`。
- [x] 新增启动时一次性导入 runner：`EcommerceDatasetImportRunner`，由 `rag.catalog.dataset-import.enabled=true` 显式开启。
- [x] 增加离线导入运行说明：[电商数据集离线入库运行说明](ecommerce-dataset-import-runbook.md)。
- [x] 增加端到端导入验证：100 个商品 JSON 可通过适配器批量导入 Catalog 与 RAG 文档。
  - 说明：[电商数据集离线入库运行说明](ecommerce-dataset-import-runbook.md) §4
  - 校验项：`catalog_spu=100`、`catalog_sku=585`、`rag_documents=100`、`document_id` 已回填
- [x] 增加导入幂等策略或重复导入处理说明，避免同一 `product_id/externalRef` 重复写入导致批量失败。
  - 说明：[电商数据集离线入库运行说明](ecommerce-dataset-import-runbook.md) §5
  - 当前策略：重复 `externalRef` 记录到 `CatalogImportSummary.failures`，批次其它商品继续导入
  - 已有测试：`CatalogModuleTests#importBatchPartialSuccessReportsFailures`

### 0.3 离线文本向量化

- [x] 按 [电商商品数据 Chunk 切分方案](ecommerce-product-chunking-design.md) 实现父子层级 chunk。
  - 实现：`scripts/ecommerce_offline/product_chunking.py`、`scripts/ecommerce_offline/chunk_products.py`
  - 输出：`src/main/resources/db/ecommerce_offline/ecom-product-chunks.jsonl`
  - 统计：`product_parent=100`、`product_profile=100`、`marketing_description=100`、`official_faq=439`、`user_review=453`、`review_summary=100`、`image_embedding=100`
  - [x] 只对自然语言内容做文本 embedding：`product_profile`、`marketing_description`、`official_faq`、`user_review`、`review_summary`
  - [x] 图片只生成 `image_embedding` chunk，实际 CLIP/image embedding 放到 0.4
  - [x] 结构化字段不单独做 embedding：品牌、类目、价格、库存、SKU ID、SKU 属性进入 SQL/metadata 过滤
  - [x] child chunk metadata 携带 `parentId`、`productId/externalRef`、`chunkType`、`brand`、`categoryPath`、`priceMin/priceMax`、`stock`、`imagePath` 等可过滤字段；离线库以 `product_id` 作为父级回表键，应用 Catalog 接入时再补 `spuId/catalogSpuId` 映射
  - [x] 实现 child 命中后回查 parent/SPU，并组装完整商品卡片结构
    - 实现：`scripts/ecommerce_offline/hydrate_product_card.py`
    - 搜索集成：`scripts/ecommerce_offline/search_faiss_index.py --hydrate`
    - 主流程接口：`src/main/python/ecommerce_recall/product_vector_recall.py`
    - 可调用函数：`ecommerce_recall.recall_by_embedding`
    - 输入：`faiss_id` 或 `chunk_id`
    - 链路：`faiss_id -> chunk_id -> child chunk -> parent_chunk_id -> parent chunk -> product_id -> SPU/SKU/FAQ/Review/Image`
    - 样例输出：`src/main/resources/db/ecommerce_offline/faiss/ecom-product-hydration-sample.json`
- [x] 增加豆包 API 多模态 embedding Python 脚本：统一读取 `ecom-product-chunks.jsonl` 中 `embedding_required=true` 的文本和图片 chunk。
  - 实现：`scripts/ecommerce_offline/embed_chunks_doubao.py`
  - 默认接口：`https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal`
  - 默认模型：`doubao-embedding-vision-251215`
  - dry run 已验证：`selected_chunk_count=1292`、`text=1192`、`image=100`
  - 多模态接口一次返回一个多模态输入的向量，脚本固定逐 chunk 请求
- [x] 执行真实豆包 embedding 调用并生成 `ecom-product-embeddings.jsonl`。
  - 结果：`1292` 条向量，维度 `2048`
  - 分布：`text=1192`、`image=100`
- [x] 写入本地 FAISS 向量库，并生成 `faiss_id -> chunk_id` 映射。
  - 实现：`scripts/ecommerce_offline/build_faiss_index.py`
  - 输出：`src/main/resources/db/ecommerce_offline/faiss/ecom-product.index`
  - 映射：`src/main/resources/db/ecommerce_offline/faiss/ecom-product-id-map.json`
  - [x] id-map 同步携带最新 chunk `metadata/source_ref`，支持离线测试按 `externalRef/productId/catalogSpuId` 做范围过滤
- [x] 将 embedding 状态回写 SQL：`vector_id`、`embedding_status`、`embedding_model`、`embedding_dimension`。
  - 实现：`scripts/ecommerce_offline/sync_embedding_status_sql.py`
  - 输出：`src/main/resources/db/ecommerce_offline/update-embedding-status.sql`
- [x] 为商品文档 metadata 增加可过滤字段：`externalRef`、`productId`、`spuId/catalogSpuId`、`category`、`subCategory`、`categoryPath`、`brand`、`priceMin`、`priceMax`、`stock`、`imagePath`。
  - 实现：`CatalogImportService#buildDocumentMetadata`、`RagIndexingService#copyFilterableDocumentMetadata`
  - 索引：`db/migration/schema.sql` 增加 `rag_documents/rag_chunks` metadata 表达式索引；离线库 `db/ecommerce_offline/schema-postgres.sql` 同步 chunk metadata 索引
- [x] 为营销描述、FAQ、用户评价增加稳定 chunk 类型，便于详情问答、评价总结和负向过滤。
  - 类型：`MARKETING_DESCRIPTION`、`OFFICIAL_FAQ`、`USER_REVIEW`、`REVIEW_SUMMARY`
  - 实现：`RagChunkType`、`RagChunkTypeClassifier`
  - 离线 chunk 类型：`marketing_description`、`official_faq`、`user_review`、`review_summary`
- [x] 支持按商品 `externalRef/productId` 或 Catalog SPU ID 限定 RAG 检索范围。
  - 请求字段：`RagAskRequest.externalRefs/productIds/catalogSpuIds`
  - 主流程：`RagAskService` -> `RagSearchFilter`
  - 召回过滤：`RagMilvusNativeExpressionBuilder`、`RagChunkMetadataHelper`、`JdbcRagChunkRepository`、`KeywordRagRetriever`
  - Python/FAISS 测试召回：`ecommerce_recall.recall_by_embedding` 支持 `external_refs/product_ids/catalog_spu_ids`

### 0.4 离线图片向量化

- [x] 读取 `image_path` 对应的本地商品图片。
  - 实现：`scripts/ecommerce_offline/product_chunking.py` 生成 `image_embedding` chunk
  - 统计：`image_embedding=100`
- [x] 生成 image embedding。
  - 实现：`scripts/ecommerce_offline/embed_chunks_doubao.py`
  - 模型：`doubao-embedding-vision-251215`
  - 结果：`image=100`，维度 `2048`
- [x] 将图片向量写入本地 FAISS vector store，并保存 `product_id`、`image_path` 等 metadata。
  - 实现：`scripts/ecommerce_offline/build_faiss_index.py`
  - 输出：`src/main/resources/db/ecommerce_offline/faiss/ecom-product.index`
  - 映射：`src/main/resources/db/ecommerce_offline/faiss/ecom-product-id-map.json`
  - 说明：当前文本和图片向量共用一个 FAISS 库，通过 `embedding_modality=image` 区分
- [x] 建立图片向量与 Catalog SPU/SKU 的反查关系。
  - 链路：`faiss_id -> chunk_id -> image_embedding child chunk -> parent_chunk_id -> product_id/externalRef -> Catalog SPU/SKU`
  - id-map 已携带 `metadata/source_ref`，包含 `productId/externalRef/imagePath`
- [x] 图片向量召回后按 `productId/externalRef` 或 `spuId` 回查 Catalog，组装完整商品卡片。
  - 实现：`src/main/python/ecommerce_recall/product_vector_recall.py`
  - 可调用函数：`ecommerce_recall.recall_by_embedding`
  - 支持 `modalities={"image"}` 限定图片 chunk 召回
- [x] 接入用户上传 `imageRef` 到拍照找货主流程，包括图片预处理、图片 embedding 生成、图文多路融合排序。
  - 实现：`src/main/python/ecommerce_recall/photo_search_recall.py`
  - 可调用函数：`preprocess_image_ref`、`embed_image_with_doubao`、`photo_search_by_embedding`、`photo_search_by_image_ref`
  - 测试入口：`src/test/python/ecommerce_recall/run_image_recall_report.py`
  - 融合策略：图片向量召回 `image_embedding` + 可选文本召回 `product_profile`，按商品 `productId` 做 weighted RRF 聚合
  - 说明：当前完成的是 Python/FAISS 本地拍照找货链路；Java Agent 图上的 `imageRef` 入参和 workflow 接入仍在第 1、5.7 阶段跟进
- [x] 增加图片向量化任务的失败重试和缺图处理策略。
  - 缺图：本地 `imageRef` 无法解析时抛出明确 `FileNotFoundError`
  - 格式：仅接受 `jpeg/png/webp` 或 data URL
  - 大小：默认限制 `<=5MB`
  - 质量提示：输出 `IMAGE_DIMENSION_UNKNOWN`、`IMAGE_LONG_EDGE_GT_1024`、`IMAGE_TOO_SMALL`、`IMAGE_EXTREME_ASPECT_RATIO` 等 warnings
  - 重试：豆包图片/text embedding 调用支持 `max_retries` 和指数退避

## 1. 输入预处理

- [x] 完善 `AgentTurnRequest` / `GuideAgentTurnController` 的多模态入参模型，主链路只接收文本与图片引用。
  - 实现：`AgentTurnRequest.message/imageRef`
  - 说明：音频不进入 Agent 主图，也不是独立召回模态；客户端或前置适配层完成 ASR 后，将识别文本作为普通 `message` 调用 `/agent/turn`。
- [x] 新增输入归一化预处理：归一化 `message`，图片纯输入时补默认文本意图。
  - 实现：`GuideInputPreprocessor`
  - 规则：`message` 去空白归一化；仅有 `imageRef` 时写入 `根据图片找相似商品`
  - 输出：`GuideGraphRequest.message/originalMessage/inputModalities/imageRef`
- [x] 明确 ASR 前置协议：将客户端音频在 `/agent/turn` 之前转换为文本，并把识别结果直接写入 `message`。
  - 实现：`src/main/python/ecommerce_asr/doubao_asr.py`
  - 可调用函数：`ecommerce_asr.transcribe_audio_text`，返回值直接作为 `/agent/turn.message`
  - CLI：`python ai-agent/src/main/python/ecommerce_asr/doubao_asr.py --audio query.wav`
  - 协议：火山 OpenSpeech 大模型 ASR WebSocket 二进制帧，默认端点 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel`
  - 鉴权：通过环境变量读取 `DOUBAO_ASR_APP_KEY` + `DOUBAO_ASR_ACCESS_KEY`
  - 注意：ASR 只负责“音频 -> 文本”，不产生音频 embedding，不写入 graph state，也不参与商品向量召回；ASR 后文本与用户手输文本走同一条链路。
- [x] 新增图片预处理模块：
  - [x] 图片引用解析。
    - 实现：`src/main/python/ecommerce_recall/image_input_processor.py#preprocess_image_ref`
    - 支持：本地路径、数据集相对路径、`data:` URL
    - 校验：`jpeg/png/webp`、默认 `<=5MB`、尺寸与质量 warnings
  - [x] Caption 生成或接收。
    - 实现：`src/main/python/ecommerce_recall/image_input_processor.py#caption_image_with_doubao`
    - 模型：`Doubao-Seed-2.0-lite`，model id `ep-20260514111645-lmgt2`
    - 接口：Ark OpenAI-compatible `chat/completions`
    - 配置：`DOUBAO_CAPTION_API_KEY` / `ARK_CHAT_API_KEY`，`DOUBAO_CHAT_API_URL`，`DOUBAO_CAPTION_MODEL`
  - [x] 图片 embedding 生成，统一使用 `doubao-embedding-vision-251215`。
    - 实现：`src/main/python/ecommerce_recall/image_input_processor.py#process_image_input`
    - 配置：`DOUBAO_EMBEDDING_API_KEY`，`DOUBAO_MULTIMODAL_EMBEDDING_API_URL`，`DOUBAO_EMBEDDING_MODEL`
    - 输出：`caption`、`imageEmbeddingDimension`、图片质量信息；主流程可通过返回对象读取完整 `image_embedding`
- [x] 在 `GuideGraphStateKeys` 中补齐多模态字段：`ORIGINAL_MESSAGE`、`INPUT_MODALITIES`、`IMAGE_CAPTION`、`IMAGE_EMBEDDING_REF` 等。
  - 已有：`IMAGE_REF`
  - 新增：`ORIGINAL_MESSAGE`、`INPUT_MODALITIES`、`IMAGE_CAPTION`、`IMAGE_EMBEDDING_REF`
  - 实现：`GuideGraphRequest` / `AgentTurnRequest` 增加图片 caption 与图片 embedding 引用字段
  - 实现：`GuideGraphStreamService#initialState` 写入多模态字段，供后续路由和 workflow 使用
  - 实现：`GuideAgentTurnController` 接入 `GuideInputPreprocessor`，避免绕过输入归一化
  - 测试：`GuideInputPreprocessorTests`

## 2. 理解层

- [x] 保留现有 `main_intent_router` 作为一次路由入口。
- [x] 调整 `main-intent-router-v1.txt` 为“一次理解到可执行路由”，对齐流程图并避免二次 LLM 调用。
  - 推荐子场景直接输出：`FUZZY_RECOMMEND`、`CONDITION_FILTER`、`MULTI_TURN_REFINE`、`PRODUCT_COMPARE`、`NEGATIVE_CONSTRAINT`、`SCENE_BUNDLE_RECOMMEND`、`PHOTO_SEARCH`。
  - 商品价格、库存、详情、评价等问题不单独作为一级意图，按语义归到对应推荐子场景。
  - 保留业务操作类：`CART_MANAGE`、`ORDER_MANAGE`。
  - 追问不作为 LLM 意图输出；流程内但信息不足时，仍先归到最接近的推荐/购物车/订单场景，并通过 `needClarify/missingSlots` 表达是否需要追问。
  - 当 `needClarify=true` 时，同步输出 `clarifyQuestion`，作为可直接展示给用户的追问话术。
  - 非流程内意图统一归入 `OTHER`。
  - 同步抽取轻量约束：`slots.positiveConstraints` 与 `slots.negativeConstraints`，用于后续召回、过滤、追问判断。
  - 同步输出 `subIntent` 和 workflow 内动作参数，避免购物车/订单 workflow 再做第二次 LLM 意图识别：
    - 推荐：`subIntent` 等于推荐子场景，例如 `FUZZY_RECOMMEND`、`PHOTO_SEARCH`。
    - 购物车：`slots.action.type/targetRef/quantity/skuSpec`。
    - 订单：`slots.action.type/source/addressRef/orderRef`。
  - 代码：`MainIntent`、`GuideGraphIntent`、`MainIntentWorkflowMapping`、`GuideGraphWorkflows` 已补齐新路由。
  - 测试：`MainIntentPromptFactoryTest`
- [x] 从意图识别 prompt 中移除 `CLARIFY` 意图输出要求。
  - 说明：`CLARIFY` 可以继续作为后端内部状态使用，例如 LLM 低置信、路由异常、workflow slot 检查失败后的等待用户补充状态，但不应由 main intent LLM 直接作为 intent 产出。
  - prompt 约束：允许输出 `needClarify=true`、`missingSlots` 与 `clarifyQuestion`，但 intent 必须仍是最接近的可执行场景。
- [x] 在意图识别 JSON 中新增 `clarifyQuestion`。
  - 作用：`needClarify=true` 时直接生成用户可见追问话术，后续 workflow 可直接读取 JSON/state 使用，不必再次调用 LLM 生成追问。
  - 语言：与用户输入保持一致，中文输入生成中文追问。
  - 代码：`MainIntentDecision.clarifyQuestion`、`GuideGraphStateKeys.CLARIFY_QUESTION`、`GuideStateGraphFactory#mainIntentRouter`。
  - 状态写入：`clarifyQuestion`；当 `needClarify=true` 且无路由错误时，同步写入 `node_message`。
  - 测试：`MainIntentPromptFactoryTest`、`MainIntentDecisionNormalizerTest`、`GuideStateGraphFactoryTests`。
- [x] 在一次理解层补充正反向约束抽取。
  - 正向约束：`category/subCategory/brand/priceMin/priceMax/scenario/audience/usageContext/attributes/productRefs`
  - 反向约束：`brands/categories/attributes/price/reviewSignals`
  - 约束抽取原则：只抽取用户明确表达或强烈暗示的信息，未知字段使用 `null` 或空数组。
  - 后端归一化：`MainIntentDecisionNormalizer` 保留 LLM 返回的 `needClarify` 和 `missingSlots`，不再只由后端低置信或 legacy slot 规则决定。
- [x] 调整 `MainIntentDecisionNormalizer` / `MainIntentWorkflowMapping`，兼容一次路由 prompt 的输出，避免再要求价格、库存、评价总结等细意图必须由一级 router 产出。
- [x] 新增加载全局历史状态节点：`load_agent_session_state`。
  - 说明：流程图中的“加载历史状态”不应只理解为上轮推荐约束，而是读取整个会话级业务状态。
  - 已有能力：当前主图已有 `LOAD_MEMORY`，可读取普通会话消息并提供 `conversationMemory`。
  - 已实现：节点接在 `LOAD_MEMORY/INIT_CONVERSATION` 之后、`SAVE_USER_MESSAGE` 之前，从最近消息构建结构化 `AgentSessionState` 并写入 `agentSessionState`。
  - 待补能力：将结构化状态持久化，并由后续状态合并节点持续更新。
- [x] 新增全局状态模型：`AgentSessionState`。
  - `conversationState`：`conversationId/currentTurn/recentMessages`
  - `recommendationState`：`activeIntent/scenario/accumulatedConstraints/negativeConstraints/missingSlots/clarifyQuestion/candidateSnapshot/lastRecommendationResult`
  - `multimodalState`：当前及历史 `imageRef/imageCaption/imageEmbeddingRef/imageQualityWarnings`，以及本轮 `inputModalities`
  - `cartState`：`cartId/cartItems/pendingCartAction`
  - `orderState`：`pendingOrderId/orderDraft/pendingOrderAction`
  - `preferenceState`：用户偏好、拒绝品牌/类目、行为信号
- [x] 新增本轮模态统一节点或服务：`CurrentTurnMultimodalUnifier`。
  - 执行顺序：加载历史 `AgentSessionState` 后，先执行 `current_turn_multimodal_unifier`，再保存用户消息和识别意图。
  - 输入：历史 `AgentSessionState.multimodalState`、本轮 `message`、本轮 `imageRef/imageCaption/imageEmbeddingRef`、本轮 `inputModalities`。
  - 输出：本轮 `CurrentTurnMultimodalContext`，作为写入全局状态的 patch。
  - 已实现：写入 `currentTurnMultimodalContext`，并生成 `queryTextForRecall`、`hasImage`、`imageFromHistory` 等字段。
  - 说明：若本轮有新图，优先使用本轮图片上下文；若本轮只是引用历史图片，例如“找类似这张但便宜点”，则从历史 `multimodalState` 解析最近有效图片上下文并写入本轮 patch。
  - 音频说明：若用户原始输入为音频，ASR 结果在进入 Agent 前已变成普通 `message`；该节点不感知原始音频，也不处理音频向量。
- [x] 新增状态合并节点或服务：`AgentSessionStateMerger`。
  - 输入：历史 `AgentSessionState`、本轮意图识别结果 `MainIntentDecision`、本轮输入预处理结果、`CurrentTurnMultimodalContext`。
  - 已实现：`agent_session_state_merger` 接在 `main_intent_router` 之后、业务 workflow 之前，合并 `activeIntent`、正反向约束、`missingSlots/clarifyQuestion` 和 `multimodalState.current`。
  - 核心原则：历史状态是 base，本轮理解结果与本轮模态上下文是 patch；合并后得到当前轮可执行状态。
  - 正向约束合并：本轮显式字段覆盖历史同字段；本轮为空字段不清空历史。
  - 反向约束合并：以追加为主，去重；若用户明确撤销某个排除条件，需要记录为 override 操作。
  - 意图切换：新任务意图应开启新的 recommendation session；多轮细化、对比、反选延续当前 recommendation session。
  - 追问合并：若本轮补齐上轮 `missingSlots`，清除对应缺口；若仍不足，保留/更新 `missingSlots` 和 `clarifyQuestion`。
  - 模态合并：将 `CurrentTurnMultimodalContext` 写入 `multimodalState.current`，并按策略更新历史图片上下文；避免在状态合并前直接覆盖历史图像。
  - 候选快照：本轮无新召回前保留历史 `candidateSnapshot`，用于“第一个/第二个/刚才那个”等引用解析。
  - 购物车/订单状态：只由对应 workflow 写入，推荐流程只读取必要引用。
  - 待补能力：结构化状态持久化、意图切换时开启新 recommendation session、撤销型约束 override、workflow 结束后回写候选快照。
- [x] 新增召回查询上下文构建服务：`UnifiedQueryContextBuilder`。
  - 执行顺序：状态合并完成后，在 `product_recommend_workflow` 内部从最新 `AgentSessionState` 构建统一召回上下文，不放到主图场景路由前。
  - 输入：合并后的 `AgentSessionState`。
  - 输出：`UnifiedQueryContext`，包含文本查询、图片向量引用、caption、正反向约束、历史候选引用，供召回和排序使用。
  - 已实现：推荐 workflow 写入 `unifiedQueryContext`，并把该上下文放入 `workflowResult.unifiedQueryContext`，后续文本/图片/SQL 召回节点可直接消费。

## 3. 场景路由

- [x] 保持主图路由：`MainIntentWorkflowMapping` -> 大 workflow node。
  - 当前主图大 workflow：`product_recommend_workflow`、`cart_manage_workflow`、`order_manage_workflow`、`clarify_workflow`。
  - 旧细 workflow（`product_search_workflow`、`product_compare_workflow`、`product_detail_query_workflow`、`price_query_workflow`、`inventory_query_workflow`、`review_summary_workflow`、`policy_qa_workflow`）不再作为推荐主路径补齐目标；后续若保留常量，仅作为 legacy/兼容占位。
- [x] 补齐 `product_recommend_workflow` 的场景路由实现。
  - [x] `fuzzyRecommendStrategy`：处理 `FUZZY_RECOMMEND`
  - [x] `conditionFilterStrategy`：处理 `CONDITION_FILTER`
  - [x] `multiTurnRefineStrategy`：处理 `MULTI_TURN_REFINE`
  - [x] `productCompareStrategy`：处理 `PRODUCT_COMPARE`
  - [x] `negativeConstraintStrategy`：处理 `NEGATIVE_CONSTRAINT`
  - [x] `sceneBundleRecommendStrategy`：处理 `SCENE_BUNDLE_RECOMMEND`
  - [x] `photoSearchStrategy`：处理 `PHOTO_SEARCH`
  - [x] `detailFaqReviewAnswerStrategy`：处理商品详情、FAQ、评价总结、价格和库存等商品相关问答；不再作为一级 workflow。
  - 说明：本节完成的是 workflow/sub-scene/strategy 分发；各策略内部的召回、过滤、融合、轻量排序和商品卡片生成继续在第 4、5 节补齐。
- [x] 在 `product_recommend_workflow` 内按推荐子场景分发，不再新增第二次 LLM 意图识别。
- [x] 定义推荐子场景枚举：
  - [x] `FUZZY_RECOMMEND`
  - [x] `CONDITION_FILTER`
  - [x] `MULTI_TURN_REFINE`
  - [x] `PRODUCT_COMPARE`
  - [x] `NEGATIVE_CONSTRAINT`
  - [x] `SCENE_BUNDLE_RECOMMEND`
  - [x] `PHOTO_SEARCH`
  - [x] `DETAIL_FAQ_REVIEW_ANSWER`

## 4. 候选汇合、融合与轻量排序

### 4.1 推荐公共模型

- [x] 新增统一候选模型：`ProductRecallCandidate`。
  - 已实现字段：`productId/spuId`、`skuId`、`externalRef`、`title`、`brand`、`categoryPath`、`price`、`stock`、`imageUrl`、`source`、`rawScore`、`rankScore`、`matchedSlots`、`evidence`。
  - 已实现 `ProductRecallSource`：`CATALOG_KEYWORD`、`CATALOG_FILTER`、`RAG_CHUNK`、`IMAGE_VECTOR`、`HISTORY_SNAPSHOT`、`PREFERENCE`。
  - 已实现 `ProductRecallEvidence`，用于保存 RAG chunk、图片召回、结构化过滤等证据。
- [x] 升级 `CandidateSnapshot`。
  - 已从当前 `productIds` 列表升级为 `productIds + items + updatedAt`。
  - 已新增 `CandidateSnapshotItem`：`rank/productId/skuId/externalRef/title/spec/price/imageUrl/source/reason`。
  - 保留旧构造 `new CandidateSnapshot(productIds, updatedAt)` 与 `productIds()`，兼容购物车候选序号解析。
- [x] 新增商品卡片模型：`ProductCard`。
  - 已实现字段：`productId/skuId/externalRef/title/brand/price/stock/imageUrl/spec/recommendReason/evidence`。

### 4.2 多路召回服务

- [x] Catalog keyword recall。
  - 输入：`UnifiedQueryContext.queryText`、类目/品牌/关键词。
  - 输出：按商品名、品牌、类目命中的 `ProductRecallCandidate`。
  - 已实现：`CatalogKeywordProductRecallService`。
- [x] Catalog structured filter recall。
  - 输入：`positiveConstraints`、`negativeConstraints`。
  - 输出：满足结构化条件的候选，保留过滤命中字段。
  - 已实现：`CatalogStructuredFilterProductRecallService`，支持品牌、类目、价格、库存、SKU 规格等基础过滤。
- [x] RAG chunk recall。
  - 输入：query text、scope、chunk type 偏好。
  - 输出：根据命中 chunk 回查 parent/SPU 后生成候选，并携带证据片段。
  - 已实现：`RagChunkProductRecallService`，基于 `IndexingChunkQueryFacade` 关键词召回 chunk，再按 `externalRef/productId` 回查 Catalog SPU。
- [x] Image vector recall。
  - 输入：`imageEmbeddingRef/imageRef/imageCaption`。
  - 输出：图片相似商品候选，与 Caption 文本召回结果合并。
  - 已实现：`ImageVectorProductRecallService` + `ImageVectorRecallPort` + `PythonFaissImageVectorRecallAdapter`。
  - 已实现：Java adapter 调用 `ecommerce_recall.photo_search_main_recall`，将 Python/FAISS 最终召回结果映射为 `ProductRecallCandidate`。
  - 运行要求：配置 `ECOM_RECALL_PYTHON` 可指定 Python 解释器，默认 `python`；Embedding 调用仍读取 `DOUBAO_EMBEDDING_API_KEY/ARK_EMBEDDING_API_KEY` 等环境变量。
- [x] History preference recall。
  - 输入：历史候选、用户偏好、拒绝项。
  - 输出：偏好增强或历史候选加权信号。
  - 已实现：`HistoryPreferenceProductRecallService`，从 `CandidateSnapshot.items` 生成历史候选。

### 4.3 融合、过滤、轻量排序

- [x] 新增 RRF 融合服务：`RrfFusionService`。
  - 合并多路候选，按 `productId/skuId/externalRef` 去重。
  - 保留每路召回来源和证据。
- [x] 新增负向过滤服务：`NegativeConstraintFilter`。
  - 过滤品牌、类目、价格、属性、评价负向信号。
  - 输出被过滤原因，供解释和调试。
- [x] 新增轻量排序规则。
  - 只做确定性分数计算，不引入独立 rerank 服务，不调用 LLM 排序。
  - 建议分数：`rankScore = recallScore + constraintMatchScore + sourceBoost + stockBoost - negativePenalty`。
  - 排序信号：召回分、约束命中、库存可售、价格区间、历史偏好、负向过滤惩罚。
- [x] 保存本轮候选商品快照，用于多轮细化、对比、加购。
  - 已实现：`ProductCandidatePostProcessor` 串联融合、负向过滤、轻量排序与 TopN 截断。
  - 已实现：`CandidateSnapshotMapper` 将最终候选写成 `CandidateSnapshot.items`。

### 4.4 推荐 workflow 接入顺序

- [x] 第一步：实现 `ProductRecallCandidate`、`ProductCard`、升级 `CandidateSnapshot`。
- [x] 第二步：实现 Catalog keyword recall，并接入 `FUZZY_RECOMMEND`。
- [x] 第三步：实现 Catalog structured filter recall，并接入 `CONDITION_FILTER`。
- [x] 第四步：接入 Python/FAISS 或 Java RAG chunk recall，完成 `RAG_CHUNK` 候选回查 SPU。
- [x] 第五步：接入 image vector recall，完成 `PHOTO_SEARCH`。
- [x] 第六步：接入 RRF、负向过滤、轻量排序。
- [x] 第七步：统一输出 `ProductCard` 和写回 `CandidateSnapshot`。

## 5. 推荐意图子流程

- [x] 追问与推荐流程解耦。
  - 追问判断由一次理解层输出 `needClarify/missingSlots/clarifyQuestion`。
  - 用户可见追问由外层 `clarify_workflow` 承接。
  - `product_recommend_workflow` 不再保存或执行推荐内部追问节点，只负责推荐策略分发、召回、过滤、融合、轻量排序和商品卡片构建。
- [x] 推荐子场景不做阻断式输入校验。
  - 设计决策：一次理解层负责 `needClarify/missingSlots/clarifyQuestion`；推荐 workflow 不再二次判断“能不能执行”。
  - 执行原则：进入 `product_recommend_workflow` 后直接按 `subScene/strategy` 执行召回。
  - 策略选择由一次理解层决定：`FUZZY_RECOMMEND` 走宽召回，`CONDITION_FILTER` 走过滤召回，`NEGATIVE_CONSTRAINT` 叠加负向过滤，`MULTI_TURN_REFINE` 使用历史候选范围。
  - 推荐 workflow 不再按条件多少做二次路由，只消费 `subScene/strategy`、`UnifiedQueryContext` 和已抽取约束。
  - 召回为空或置信不足时，由后续输出层给出无结果/弱结果说明，不在推荐 workflow 内生成追问。
- [x] 实现推荐公共底座。
  - 已实现：`product_recommend_workflow` 路由、`subScene/strategy` 分发、`UnifiedQueryContextBuilder`。
  - 已实现：统一候选模型、多路召回、融合、负向过滤、轻量排序、商品卡片、候选快照写回。
  - 已实现：图片向量召回 Java 端口与 Python/FAISS 桥接适配器。

### 5.1 单轮模糊推荐

- [x] 使用推荐公共底座生成候选：`UnifiedQueryContext.queryText` + `positiveConstraints` -> `ProductRecallCandidate`。
  - 前置依赖：第 4.1 节 `ProductRecallCandidate/ProductCard/CandidateSnapshot`。
  - 已实现：`ProductRecallPlan` + `ProductRecommendStrategyPlanner`，`product_recommend_workflow` 按策略 plan 执行公共召回底座。
- [x] 默认启用宽召回：Catalog keyword recall + RAG chunk recall + 可选历史偏好召回。
  - 已实现：`FUZZY_RECOMMEND` 启用 `CATALOG_KEYWORD/RAG_CHUNK/HISTORY_SNAPSHOT/PREFERENCE`，不启用 `CATALOG_FILTER/IMAGE_VECTOR`。
- [x] 弱过滤：只对价格、库存、类目做硬过滤；其他属性进入排序特征。
  - 已实现：模糊推荐不走结构化过滤源；公共后处理只执行负向过滤、库存/价格/约束命中轻量排序。
- [x] 输出商品卡片、推荐理由、`matchedSlots`、`candidateSnapshot`。
  - 已实现：`ProductCardMapper` 输出推荐理由与证据，`CandidateSnapshotMapper` 写回本轮候选快照。

### 5.2 条件筛选

- [x] 使用推荐公共底座生成候选，但先执行结构化过滤。
  - 前置依赖：第 4.1 节公共模型、第 4.2 节 Catalog structured filter recall。
  - 已实现：`CONDITION_FILTER` 策略 plan 优先启用 `CATALOG_FILTER`，再补充 `RAG_CHUNK/CATALOG_KEYWORD/HISTORY_SNAPSHOT`。
- [x] 过滤字段：品牌、类目、价格、库存、规格、颜色、容量、尺码等。
  - 已实现：`PositiveConstraintFilter` 在融合后执行正向条件硬过滤，覆盖品牌、类目、价格区间、库存和 SKU 规格类约束。
- [x] 数据来源：`catalog_spu`、`catalog_sku.spec_json`、商品 metadata。
  - 已实现：`CatalogStructuredFilterProductRecallService` 使用 Catalog SPU/SKU 做结构化召回；RAG/关键词召回候选需通过正向条件过滤后才能进入最终结果。
- [x] 过滤后再做 RAG/文本相关性排序；保留过滤命中的证据字段到 `matchedSlots`。
  - 已实现：`ProductCandidatePostProcessor` 按 `ProductRecallPlan.enforcePositiveConstraints` 执行正向过滤，再做负向过滤、轻量排序和候选快照写回。

### 5.3 多轮细化

- [x] 读取合并后的 `AgentSessionState.recommendationState` 与 `UnifiedQueryContext.candidateSnapshot`。
  - 前置依赖：第 4.1 节升级版 `CandidateSnapshot`。
  - 已实现：`UnifiedQueryContextBuilder` 从合并后的全局状态构建本轮查询上下文，并携带上一轮候选快照。
- [x] 使用累计正向/负向约束作为本轮召回过滤条件。
  - 已实现：`MULTI_TURN_REFINE` 复用累计 `positiveConstraints/negativeConstraints`，并通过 `ProductRecallPlan.enforcePositiveConstraints=true` 执行正向硬过滤。
- [x] 支持候选引用：第一个、第二个、刚才那个、更便宜的、类似这款。
  - 已实现：`CandidateSnapshotReferenceResolver` 将候选序号/“刚才那个/这款”解析为 `productIds/externalRefs/candidateRank`。
  - 已实现：“更便宜/便宜点”等表达会根据引用候选或历史快照价格补充 `priceMax`。
  - 已实现：“类似/相似/同款”等表达保留 `similarToProductIds` 作为后续相似召回信号。
- [x] 优先在历史候选范围内重新计算候选分数；必要时再补充宽召回。
  - 已实现：`MULTI_TURN_REFINE` 策略优先启用 `HISTORY_SNAPSHOT`，同时启用 `CATALOG_FILTER/RAG_CHUNK/CATALOG_KEYWORD` 作为补充召回。
  - 已实现：`ProductCandidatePostProcessor` 对上一轮快照内候选做多轮加权；若明确引用某个候选，则通过正向商品范围过滤收窄结果。
- [x] 生成新的 `candidateSnapshot` 覆盖本轮推荐结果。
  - 已实现：多轮细化后仍通过 `CandidateSnapshotMapper` 写回新的候选快照，覆盖本轮推荐结果。

### 5.4 对比决策

- [x] 从 `slots.positiveConstraints.productRefs` 与 `candidateSnapshot` 解析待对比商品。
  - 前置依赖：第 4.1 节升级版 `CandidateSnapshot`、第 9 节商品相关 chunk 检索。
- [x] 支持候选序号引用：第一个/第二个/刚才那个。
  - 已实现：`CandidateSnapshotReferenceResolver` 在 `PRODUCT_COMPARE` 下支持多候选引用，例如“第一个和第二个对比”，并生成 `productIds/externalRefs/candidateRanks/compareProductIds`。
- [x] 并行查询商品详情、SKU、FAQ、评价 chunk。
  - 当前实现：对比流程复用公共多路召回底座，启用 `HISTORY_SNAPSHOT/RAG_CHUNK/CATALOG_KEYWORD`，由各召回源分别补充 Catalog 商品、SKU 与 RAG 证据；未单独新增并发查询节点。
- [x] 输出 `comparisonItems`、差异点、适用人群、购买建议。
  - 已实现：`product_recommend_workflow` 在 `PRODUCT_COMPARE` 下输出 `comparison.comparisonItems/differencePoints/decisionAdvice`。
  - 当前轻量对比维度：品牌、价格、库存、推荐理由、证据类型；适用人群后续由回答生成层结合 query/context 表达。
- [x] 对比结果也写入 `candidateSnapshot`，便于“把更适合我的那个加入购物车”。
  - 已实现：对比结果仍通过 `CandidateSnapshotMapper` 写回本轮候选快照。

### 5.5 反选与排除约束

- [x] 使用推荐公共底座生成候选，同时读取 `negativeConstraints`。
  - 已实现：`NEGATIVE_CONSTRAINT` 通过 `ProductRecommendStrategyPlanner#negativeConstraintStrategy` 进入公共召回底座。
  - 策略：启用 `HISTORY_SNAPSHOT/CATALOG_KEYWORD/RAG_CHUNK/CATALOG_FILTER`，不强制执行正向过滤，只叠加负向排除。
- [x] 负向过滤字段：品牌、类目、价格、属性、评价信号。
  - 已实现：`NegativeConstraintFilter` 支持 `brands/categories/品类/priceMax/budget/attributes/tags/ingredients/reviewSignals/评价信号` 等字段。
  - 价格支持普通数值和 `{max/maxPrice/priceMax/上限}` 等嵌套写法。
- [x] 对商品描述、FAQ、评价 chunk 做负向命中识别。
  - 已实现：负向关键词会匹配 `ProductRecallEvidence` 的 title/content/metadata；RAG chunk、FAQ、用户评价证据都可参与排除。
- [x] 在融合后、轻量排序前执行 `NegativeConstraintFilter`；保留排除原因用于解释。
  - 已实现：`ProductCandidatePostProcessor` 在融合后执行负向过滤，再进入轻量排序。
  - 输出：保留 `ProductExclusion.reason`，`product_recommend_workflow` 在反选场景输出 `negativeSummary.negativeConstraints/excludedCount/keptCount/reasonCounts`。

### 5.6 场景化组合推荐

- [x] 读取 `scenario/usageContext/audience`，例如通勤、露营、送礼、熬夜护肤、办公学习。
  - 已实现：`SceneBundlePlanner` 优先读取 `positiveConstraints.scenario/usageContext/audience`，没有时从 query 文本兜底识别。
- [x] 将场景拆成多个商品角色，例如“露营” -> 照明/收纳/防晒/补水。
  - 已实现：露营/户外、通勤/办公、送礼、熬夜护肤等规则角色拆分；未知场景回退为核心商品/搭配商品/预算友好。
- [x] 对每个角色走公共召回底座，支持跨类目多路召回。
  - 已实现：`SCENE_BUNDLE_RECOMMEND` 在 `product_recommend_workflow` 内按角色构造 `UnifiedQueryContext`，逐角色复用 `ProductMultiRecallService` 和 `ProductCandidatePostProcessor`。
- [x] 新增组合编排逻辑，输出套装/搭配型商品卡片和组合理由。
  - 已实现：`workflowResult.bundle.scenario/roles/bundleReason`，每个 role 输出 `products/recallSummary/exclusions`；汇总结果仍输出 `products/candidateSnapshot/recallSummary`。
  - 测试：`ProductRecallServicesTest#sceneBundlePlannerSplitsScenarioIntoExecutableRoles`、`GuideStateGraphFactoryTests#sceneBundleWorkflowOutputsRolesProductsAndSnapshot`。

### 5.7 拍照找货

- [x] 读取 `UnifiedQueryContext.imageRef/imageCaption/imageEmbeddingRef`。
  - 前置依赖：第 4.2 节 Image vector recall、Caption 文本召回接入。
- [x] 使用图片向量召回商品候选；使用 Caption/文本约束补充文本向量融合。
  - 已实现：`PythonFaissImageVectorRecallAdapter` 触发 `photo_search_main_recall.py`，支持 `imageEmbeddingRef` 或 `imageRef`。
- [x] 与文本约束、SKU 属性约束、负向约束做融合排序。
  - 已实现：图片候选进入公共 `ProductCandidatePostProcessor`，统一做融合、负向过滤和轻量排序。
- [x] 输出视觉匹配证据：相似图片、caption 命中点、规格差异。
  - 已实现：adapter 将 `matchedChunk/channelScores/faissId/vectorId` 映射到候选 evidence；规格差异以 SKU/属性约束命中结果进入后续商品卡片。

## 6. 业务操作流程

- [x] 已有 `cart_manage_workflow` 子图骨架。
- [x] 已有购物车 Slot filling、候选选择、库存检查、加购/删除/改数量/查看购物车。
- [x] 已有 `order_manage_workflow` 下单子流程。
- [x] 调整购物车/订单 workflow，优先消费一次意图识别输出的 `subIntent` 与 `slots.action`，不再默认二次调用 LLM 做动作识别。
  - 购物车：`CartManageSubgraphFactory#cartResolveAction/cartResolveTarget` 优先读取 `slots.action.type/targetRef/quantity/skuSpec`，再兼容旧 `cart_action/product_name/product_id/sku_id` 和自然语言规则。
  - 订单：`OrderManageSubgraphFactory#orderResolveAction` 优先读取 `slots.action.type` 与 `subIntent`，再兼容待处理订单状态和自然语言规则。
  - 测试：`CartManageSubgraphFactoryTest#directAddUsesUnifiedActionSlotsFirst`、`OrderManageSubgraphFactoryTest#checkoutUsesUnifiedActionSlotsFirst`、`OrderManageSubgraphFactoryTest#cancelUsesUnifiedActionSlotsFirst`。
- [x] workflow 内优先做状态校验、参数校验、规则兜底和业务执行，不再把动作识别交回主意图识别。
  - 购物车：已覆盖查看、清空、加购、删除、改数量、候选选择、库存校验和用户补充。
  - 订单：已覆盖购物车结算、补地址、确认下单、取消待确认订单。
- [x] 统一业务 workflow 的追问状态输出。
  - 目标：当大 workflow 明确但内部动作/参数缺失时，由当前 workflow 返回 `needUserInput=true`、`nodeMessage`，不回到 main intent router。
  - 已实现：`build_answer_context` 输出统一 `needUserInput/workflowStatus/operationResult`；当业务 workflow 需要补充信息时，外层统一映射为 `WAITING_CLARIFICATION` 语义。
  - 内部兼容：购物车继续使用 `workflow_status/node_message/need_user_input`；订单继续使用 `orderStatus/nodeMessage/needUserInput`。
- [x] 补齐订单非下单类动作。
  - 已支持：`CREATE_ORDER`、`FILL_ADDRESS`、`CONFIRM_ORDER`、`CANCEL_ORDER`、`ORDER_QUERY`、`LOGISTICS_QUERY`。
  - 已消费字段：`slots.action.orderRef`；未提供订单号时默认查询当前会话最近模拟订单。
- [x] 补齐订单地址/来源参数直读。
  - 目标：`FILL_ADDRESS` 可优先读取 `slots.action.addressRef/source`，再兜底解析当前 message。
  - 已实现：`OrderManageSubgraphFactory#orderResolveAddress` 优先使用 `slots.action.addressRef/source` 作为地址解析输入。
- [x] 将推荐候选快照与购物车加购打通，支持“把刚才第二个加入购物车”。
  - 依赖：第 4 节保存本轮候选商品快照。
  - 输入：`slots.action.targetRef` 中的“刚才第二个/第 2 个/这款”等引用。
  - 输出：解析为明确 `productId/skuId` 后进入购物车加购。
  - 当前边界：`CandidateSnapshot` 现阶段只保存 productId 顺序；若用户未提供 SKU/规格，仍由购物车 workflow 追问或走 catalog 候选选择。
- [x] 增强商品 ID / SKU ID / 候选序号解析，统一从 `CandidateSnapshot`、`catalog_spu` 和 `catalog_sku` 中解析。
  - 已实现：购物车优先解析 `slots.action.targetRef/skuSpec`；候选序号从 `AgentSessionState.recommendationState.candidateSnapshot.productIds` 解析；商品名/规格候选继续通过 `ProductCatalogResolver`，其生产实现基于 `CatalogQueryFacade.searchActiveSpus/listSkus`。
  - 待第 4 节增强：将 `CandidateSnapshot` 从 productId 列表升级为完整候选项，直接保存 `productId/skuId/externalRef/title/spec/rank`。
- [x] 补业务操作结果的结构化输出：操作确认、购物车项、订单信息、错误原因。
  - 已实现：`answerContext.operationResult` 统一输出 `workflow/action/status/needUserInput/message/orderNo/cart/errorCode/errorMessage/errorReason` 等字段。

## 7. 汇合输出

- [x] 扩展 `build_answer_context`，支持商品卡片和操作确认并存。
  - 已实现：`answerContext` 保留原始 `workflowResult`，同时汇合推荐输出、业务操作结果和追问状态。
  - 操作结果：继续输出 `operationResult`，可与商品推荐字段并存。
- [x] 定义推荐输出结构：
  - [x] `answer`
  - [x] `products`
  - [x] `recommendationReasons`
  - [x] `matchedSlots`
  - [x] `missingSlots`
  - [x] `candidateSnapshotId`
  - 已实现：`buildAnswerContext` 从 `workflowResult.products/candidateSnapshot/unifiedQueryContext` 统一抽取上述字段。
- [x] SSE 输出支持结构化商品卡片事件。
  - 已实现：新增 `AgentStreamEventType.PRODUCT_CARDS`，事件名为 `product.cards`。
  - 输出时机：当 `answerContext.products` 非空时，在 `answer.delta` 前发送结构化商品卡片列表。
- [x] 详情问答、评价总结、价格查询、库存查询输出需要携带来源商品和证据片段。
  - 已实现：`answerContext.evidence` 从商品卡片 evidence 中汇合来源商品、证据类型、chunkId、parentChunkId、content 和 metadata。
  - 覆盖：推荐、对比、反选、场景组合、拍照找货，以及详情/评价/价格/库存类走商品推荐 workflow 的输出。

## 8. 终态写回

- [x] 写回推荐侧状态：
  - [x] 约束槽位。
  - [x] 轮次。
  - [x] 候选商品快照。
  - [x] 用户偏好记忆。
  - 已实现：新增 `terminal_state_writeback` 节点，统一输出 `terminalStateWriteback.recommendation`。
  - 推荐状态来源：`AgentSessionState.recommendationState`，包含 `positiveConstraints/negativeConstraints/candidateSnapshot/lastRecommendationResult`。
- [x] 写回购物车状态：沿用现有 `shopping_cart` 与 `cart_item`。
  - 已实现：购物车业务执行仍由现有购物车 workflow/服务写入；终态节点汇总 `cart.lastCartAction/pendingActionId` 并标记存储为 `shopping_cart/cart_item`。
- [x] 写回订单状态：沿用现有 `customer_order` 与 `order_item`。
  - 已实现：订单业务执行仍由现有订单 workflow/服务写入；终态节点汇总 `order.pendingOrderActionId/lastOrderId` 并标记存储为 `customer_order/order_item`。
- [x] 新增用户行为反馈入口：
  - [x] 点击商品。
  - [x] 加购商品。
  - [x] 跳过商品。
  - [x] 不感兴趣。
  - 已实现：`POST /public/agent/feedback`，支持 `CLICK_PRODUCT/ADD_TO_CART/SKIP_PRODUCT/NOT_INTERESTED` 及常用别名。
- [x] 新增偏好更新服务，根据行为反馈更新用户偏好记忆。
  - 已实现：`UserPreferenceMemoryService` 根据正/负反馈维护 `positiveProductIds/negativeProductIds/feedbackCount`。
  - 当前实现：服务层内存偏好记忆；生产持久化可后续替换为数据库 repository。

## 9. RAG 与索引补齐

- [x] 确认商品导入后 `rag_documents` 能稳定生成切片。
  - 验证：`SpuMarkdownRenderer -> RagTextChunker -> RagChunkTypeClassifier` 可稳定生成 `TITLE/DESC/ATTR`，并覆盖商品数据中的 `MARKETING_DESCRIPTION/OFFICIAL_FAQ/USER_REVIEW`。
  - 测试：`SpuChunkClassificationTests#importedEcommerceKnowledgeMarkdownKeepsStableChunkTypes`
- [x] 为商品文档 metadata 增加可过滤字段：`externalRef`、`productId`、`spuId/catalogSpuId`、`category`、`subCategory`、`categoryPath`、`brand`、`priceMin`、`priceMax`、`stock`、`imagePath`。
  - 实现：`CatalogImportService#buildDocumentMetadata`、`RagIndexingService#copyFilterableDocumentMetadata`
- [x] 为 FAQ、评价、营销描述增加 chunk 类型标识。
  - 类型：`MARKETING_DESCRIPTION`、`OFFICIAL_FAQ`、`USER_REVIEW`、`REVIEW_SUMMARY`
- [x] 详情问答和评价总结优先使用商品相关 chunk。
  - 实现：`RagSearchFilter` 新增 `chunkTypes`，并下推到 RAG API、Milvus 原生表达式、keyword SQL fallback、metadata helper。
  - 推荐链路：`DETAIL_FAQ_REVIEW_ANSWER` 先查 `OFFICIAL_FAQ/USER_REVIEW/REVIEW_SUMMARY/MARKETING_DESCRIPTION`，不足时再在同一商品范围内宽召回补齐。
  - 测试：`ProductRecallServicesTest#detailFaqReviewRecallPrioritizesProductKnowledgeChunkTypes`
- [x] 支持按商品 `externalRef/productId` 或 Catalog SPU ID 限定 RAG 检索范围。
  - 支持字段：`externalRefs`、`productIds`、`catalogSpuIds`
  - 覆盖链路：RAG API 请求、Milvus 原生表达式、keyword SQL fallback、metadata helper、Python FAISS 测试召回
  - 补充：RAG API 可同时传 `chunkTypes`，用于商品详情、FAQ、评价总结等定向检索。

## 10. 验证清单

- [x] 数据导入：100 个商品 JSON 可成功导入 Catalog 与 RAG 文档。
  - 覆盖：`catalog_spu=100`、`catalog_sku=585`、`rag_documents=100`、`catalog_spu.document_id` 全量回填。
  - 测试：`EcommerceOfflineDatabaseBuildTest#buildsOfflineDatabaseFromFullDataset`
- [x] 搜索：用户输入品牌、类目、价格、SKU 属性时能返回正确商品。
  - 覆盖：品牌/类目关键词召回、价格上限、库存、SKU 规格结构化过滤。
  - 测试：`ProductRecallServicesTest#catalogKeywordRecallReturnsProductCandidates`、`ProductRecallServicesTest#catalogStructuredFilterRecallAppliesBrandCategoryPriceAndSpecs`、`ProductCandidatePostProcessorTest#conditionFilterPlanEnforcesPositiveConstraintsAfterFusion`
- [x] 推荐：模糊需求能返回商品卡片和推荐理由。
  - 覆盖：`FUZZY_RECOMMEND` 宽召回、候选融合、商品卡片、推荐理由、证据和候选快照。
  - 测试：`GuideStateGraphFactoryTests#productRecommendWorkflowRunsRecallAndWritesProductCardsAndSnapshot`
- [x] 追问：缺少关键槽位时能主动追问。
  - 覆盖：无法识别/约束不足时进入 clarify workflow，设置 `needUserInput=true` 并输出追问文案。
  - 测试：`GuideStateGraphFactoryTests#defaultsMockRouterToClarifyWorkflow`、`GuideStateGraphFactoryTests#clarifyWorkflowWritesUserVisibleMessageAndNeedUserInput`
- [x] 多轮：用户补充条件后能基于历史约束继续推荐。
  - 覆盖：加载历史状态、合并本轮约束、候选快照引用解析、多轮细化候选保留与补充。
  - 测试：`GuideStateGraphFactoryTests#secondRequestLoadsMemoryThenSavesUserMessage`、`ProductCandidatePostProcessorTest#multiTurnRefineBoostsSnapshotCandidatesButAllowsSupplementCandidates`、`ProductCandidatePostProcessorTest#multiTurnExplicitCandidateReferenceNarrowsToReferencedProduct`
- [x] 对比：能对两个候选商品输出差异和建议。
  - 覆盖：`PRODUCT_COMPARE` 子场景、comparison payload、差异点、决策建议和候选快照。
  - 测试：`GuideStateGraphFactoryTests#productCompareWorkflowOutputsComparisonPayloadAndSnapshot`
- [x] 反选：能处理“不要某类缺点/不要某品牌/不要太贵”。
  - 覆盖：品牌、类目、价格上限、属性/成分/评价信号，以及商品描述/FAQ/评价证据命中。
  - 测试：`ProductCandidatePostProcessorTest`、`ProductRecallServicesTest`、`GuideStateGraphFactoryTests`。
- [x] 图片：传入 `imageRef` 后能走拍照找货链路。
  - 覆盖：本轮多模态统一、`PHOTO_SEARCH` 路由、图片向量召回适配器、图片召回结果回填商品卡片。
  - 测试：`GuideStateGraphFactoryTests#unifiesCurrentTurnMultimodalContextBeforeIntentAndMergesSessionStateAfterIntent`、`ProductRecallServicesTest#imageVectorRecallDelegatesToRegisteredPort`、`ProductRecallServicesTest#pythonFaissImageVectorAdapterMapsMainRecallOutput`
- [x] 加购：能将推荐结果中的指定商品/SKU 加入购物车。
  - 覆盖：候选序号/商品/SKU 解析、库存校验、pending 确认、加购成功 answer 输出。
  - 测试：`CartManageSubgraphFactoryTest`、`GuideStateGraphFactoryTests#cartAddSuccessStillReturnsAddSuccessMessage`
- [x] 下单：购物车结算能进入下单流程。
  - 覆盖：`CREATE_ORDER` 路由到订单 workflow、购物车快照、地址追问、确认后创建订单。
  - 测试：`GuideStateGraphFactoryTests#createOrderUsesOrderManageSubgraphWhenFactoryProvided`、`OrderManageSubgraphFactoryTest`
- [x] 写回：候选快照、偏好、购物车、订单状态都可追踪。
  - 覆盖：`terminal_state_writeback` 汇总 recommendation/cart/order，候选快照与最后推荐结果写入 `AgentSessionState`，反馈偏好记忆可更新。
  - 测试：`GuideStateGraphFactoryTests#productRecommendWorkflowRunsRecallAndWritesProductCardsAndSnapshot`、`GuideStateGraphFactoryTests#routesEveryIntentToExpectedWorkflow`、`UserPreferenceMemoryServiceTest`
