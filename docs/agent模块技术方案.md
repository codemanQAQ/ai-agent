# Agent 模块技术方案（W1-AGT-01..10）

> 服务端 Agent 编排核心：把既有的 retrieval / catalog 底座升级为「意图识别 → 槽位抽取 → 工具规划 → 工具执行 → 流式答案」的多轮 Agent。本文档是 W1 第三块工作的设计依据。
> 配套：`竞赛任务拆解清单.md`、`索引链路运行手册.md`、`catalog 模块` 落地代码。

---

## 0. 设计原则

1. **上层包裹，零侵入复用**：retrieval / catalog 一行代码不改（除了给 retrieval 暴露一个 SPI），agent 全部通过 facade / spi 调用它们。
2. **流式同构**：agent 的 SSE 事件结构与既有 `RagAskStreamEvent` 同形（`{id, event, correlationId, data}`），客户端解码器一份代码两边用。
3. **幂等与会话复用**：直接套既有 `RagConversationService.beginAsk / completeAsk / failAsk` 链路，**不另起 agent_turn 表**（详见 §10）。
4. **Tool 自己写不接 Spring AI tool-calling**：Spring AI 2.0.0-M6 的 `@Tool` 注解可用，但学习成本不可控、调试不直观。W1 自定义 `Tool` 接口（5 行），W3 视情况升级。
5. **规则前置 + LLM 兜底**：意图识别 70% 走正则关键词，剩下 30% 走 LLM；省 token、提速、可解释。

---

## 1. 模块边界

### 1.1 新增模块

```
com.bytedance.ai.agent
@ApplicationModule(
    displayName = "RAG Agent",
    allowedDependencies = {
        "common", "shared",
        "catalog::api",
        "retrieval::api",      // 既有：RagAskFacade（兜底用，不主用）
        "retrieval::spi"       // 新增：ProductSearchSpi（主用）
    }
)
```

### 1.2 既有模块改动（一次性）

> 这是 W1-AGT 唯一动既有模块的地方，必须在 AGT-06 之前完成。

新增 `com.bytedance.ai.retrieval.spi`：

```
retrieval/spi/
├── package-info.java                @NamedInterface("spi")
├── ProductSearchSpi.java            interface
├── ProductSearchRequest.java        record (query, filter, topK, includeChunkTypes)
└── ProductSearchHit.java            record (spuId, docId, score, snippet, chunkType, metadata)
```

`ProductSearchSpiAdapter`（位于 `retrieval.application`）实现 SPI，内部委托 `HybridRagRetriever.search(RagRetrievalRequest)` 并按 `documentId` 聚合 chunk → SPU。

retrieval 顶层 `package-info.java` 同步追加：

```java
allowedDependencies = {"common", "shared", "indexing::api", "catalog::api"}
```

> 加 catalog::api 是为了让 `ProductSearchSpiAdapter` 在聚合 SPU 时调 `CatalogQueryFacade.getSpu` 获取 price/stock 等业务字段（不能从 chunk 里取——chunk 内容可能滞后）。

---

## 2. 包结构

```
com.bytedance.ai.agent
├── package-info.java                            @ApplicationModule
├── api/                                          ← @NamedInterface("api")
│   ├── package-info.java
│   ├── AgentTurnFacade.java                     入口 SPI
│   ├── AgentTurnRequest.java                    （userId, conversationId, question, requestId, history?）
│   ├── AgentStreamEvent.java                    通用流式信封（id/event/correlationId/data）
│   ├── SpuCardView.java                         商品卡片 DTO（客户端渲染用）
│   ├── ToolCallView.java                        Tool 调用快照（intent/tool name/args/latency）
│   ├── IntentType.java                          枚举：9 种意图
│   ├── Slot.java                                must/must_not/range/category/scenario
│   └── events/                                   ← 各事件的 data payload
│       ├── TurnStartedPayload.java
│       ├── IntentDetectedPayload.java
│       ├── ToolCallingPayload.java
│       ├── ToolResultPayload.java
│       ├── AnswerDeltaPayload.java
│       ├── CitationPayload.java
│       ├── TurnCompletedPayload.java
│       └── TurnErrorPayload.java
├── application/
│   ├── AgentTurnService.java                    实现 AgentTurnFacade，主流程编排
│   ├── AgentTurnPersistenceAdapter.java         （仅当我们决定独立 agent_turn 表时启用，见 §10）
│   └── AgentSseEventFactory.java                构造 9 类 AgentStreamEvent 的工厂
├── intent/
│   ├── IntentClassifier.java                    接口
│   ├── RuleBasedIntentMatcher.java              正则 / 关键词前置
│   ├── LlmIntentRouter.java                     LLM 兜底（Router Prompt < 200 token）
│   └── HybridIntentClassifier.java              规则优先 + 置信度阈值 → 兜底
├── slot/
│   ├── SlotExtractor.java                       接口
│   ├── LlmSlotExtractor.java                    function_call JSON Schema 实现
│   └── SlotSchemas.java                         （JSON Schema 字符串常量）
├── tool/
│   ├── Tool.java                                接口（name / description / execute(ctx)）
│   ├── ToolContext.java                         turn 维度上下文
│   ├── ToolResult.java                          统一返回（cards / facts / error）
│   ├── ToolRegistry.java                        按 intent → tools 调度表
│   └── impl/
│       └── SearchProductsTool.java              调 ProductSearchSpi
├── answer/
│   ├── AgentAnswerGenerator.java                包裹 RagAnswerGenerator + agent prompt
│   └── prompts/
│       └── answer-prompt-v1.txt                 Resource，强约束模板
├── web/
│   └── AgentTurnController.java                 POST /public/agent/turn (SSE)
└── support/
    ├── AgentLogFields.java                      OTel / ECS 日志字段常量
    └── AgentMetrics.java                        Micrometer 计数器
```

文件总数约 30 个，与 catalog 模块同量级。

---

## 3. 核心 API 与数据契约

### 3.1 AgentTurnRequest

复用 `RagAskRequest` 的字段集，只重命名 question → message 以匹配 agent 语义：

```java
public record AgentTurnRequest(
    @NotBlank @Size(max=64)  String userId,
    @NotBlank @Size(max=64)  String conversationId,
    @NotBlank @Size(max=2000) String message,
    @Size(max=64)            String turnId,           // 幂等键，等价于 RagAskRequest.requestId
    @Size(max=64)            String imageRef,         // W3 才填，W1 字段先占位但 IGNORE
    List<AgentConversationTurn> history              // 仅当客户端要求覆盖时
) {}
```

> 不传 topK / filter — agent 自己根据 intent + slot 决定，客户端不该越权传。

### 3.2 AgentStreamEvent（9 类）

```java
public record AgentStreamEvent(String id, String event, String correlationId, Object data) {}
```

| event 名 | data payload | 触发时机 | 客户端动作 |
|---|---|---|---|
| `turn.started` | `TurnStartedPayload(turnId, conversationId, model)` | 入口立刻发 | 开聊天气泡 |
| `intent.detected` | `IntentDetectedPayload(intent, confidence, slots)` | classifier + extractor 完成后 | 可选：意图小标签 |
| `tool.calling` | `ToolCallingPayload(toolName, args)` | 每个 tool 执行前 | 显示「正在搜索…」 |
| `tool.result` | `ToolResultPayload(toolName, cards: SpuCardView[], facets)` | 工具完成后立刻发 | **卡片先于 token 出现** |
| `answer.delta` | `AnswerDeltaPayload(text)` | LLM 每个 token | 流式追加 |
| `citation` | `CitationPayload(refId, spuId, chunkId)` | LLM 输出 `[#N]` 标记时 | 卡片高亮 |
| `notice` | `NoticePayload(code, message, severity)` | 降级 / 限流 / 部分失败 | toast |
| `turn.completed` | `TurnCompletedPayload(turnId, latencyMs, tokensIn, tokensOut, generatedByModel)` | 最后 | 关闭 cursor |
| `turn.error` | `TurnErrorPayload(code, message, recoverable)` | 不可恢复异常 | 错误气泡 |

> `correlationId` 沿用 RagAskRunRecord 的 `correlation_id` 字段，便于 trace 全链路。

### 3.3 SpuCardView

客户端卡片渲染 DTO。**与 `CatalogSpuView` 区分**：CatalogSpuView 是落地页全量，SpuCardView 是 turn 内的瘦版：

```java
public record SpuCardView(
    Long spuId,
    String externalRef,
    String title,
    String brand,
    String image,                 // 首图
    BigDecimal priceMin,
    BigDecimal priceMax,
    Integer stock,
    Double score,                 // 检索分数（0-1）
    List<String> badges,          // ["热销", "新品"] — W1 可空
    List<String> reasons,         // ["匹配'轻量'与'防水'"] — 由 reason builder 生成
    String refId                  // [#N] 中的 N，answer 引用时回填
) {}
```

### 3.4 IntentType 枚举（9 种）

| 枚举值 | 触发场景 | W1 是否实现 |
|---|---|---|
| `RECOMMEND_VAGUE` | "推荐适合油皮的洗面奶" | ✅ W1 |
| `FILTER_BY_ATTR` | "200 以下蓝牙耳机" | ✅ W1 |
| `REFINE` | "要轻量的" | W2 |
| `COMPARE` | "A 和 B 哪个保湿" | W2 |
| `EXCLUDE` | "不要日系品牌" | W2 |
| `SCENARIO_BUNDLE` | "三亚度假从防晒到穿搭" | W3 |
| `CART_OP` | "把它加到购物车" | W3 |
| `IMAGE_SEARCH` | 上传图片 + 文字 | W3 |
| `OUT_OF_SCOPE` | "帮我写代码" | ✅ W1（直接拒绝） |

W1 只实现 3 个意图的端到端，其余意图分类器能识别但 planner 回退到 `RECOMMEND_VAGUE` 兜底。

### 3.5 Slot 数据结构

```java
public record Slot(
    List<String> must,                // 必含关键词（防水 / 防晒）
    List<String> mustNot,             // 必排（酒精 / 日系）— W2 才主用
    PriceRange priceRange,            // {min, max}（单位元）
    String categoryHint,              // "美妆/护肤/防晒"
    List<String> brands,              // ["Anessa", "Curel"]
    String scenario                   // "三亚度假"（W3）
) {
    public record PriceRange(BigDecimal min, BigDecimal max) {}
    public boolean isEmpty() { ... }
}
```

W1 SlotExtractor 只填 `must / priceRange / categoryHint / brands` 四项；mustNot/scenario 由 W2/W3 接管。

---

## 4. 单 turn 数据流（时序）

```
Client                         AgentTurnController                AgentTurnService
   │  POST /public/agent/turn       │                                      │
   ├──────────────────────────────►│  askStream(req) → Flux<event>        │
   │                                ├─────────────────────────────────────►│
   │                                │                                      │
   │  ① turn.started               │  emit                                │  RagConversationService.beginAsk()
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│  ↓ 拿到 runId + correlationId + 历史
   │                                │                                      │
   │                                │  IntentClassifier.classify(msg)      │  ↓ 规则前置：含"推荐"/"找"/价格表达式
   │                                │                                      │
   │                                │  SlotExtractor.extract(msg, intent)  │  ↓ function_call JSON
   │  ② intent.detected            │  emit                                │
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│
   │                                │                                      │
   │                                │  ToolRegistry.plan(intent, slot)     │  → [SearchProductsTool]
   │                                │                                      │
   │  ③ tool.calling               │  emit                                │
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│
   │                                │                                      │
   │                                │  Tool.execute(ctx)                   │  → ProductSearchSpi → HybridRetriever
   │                                │                                      │     → CatalogQueryFacade.getSpu (price/stock)
   │  ④ tool.result (cards)        │  emit                                │
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│
   │                                │                                      │
   │                                │  AgentAnswerGenerator.stream(...)    │  → RagAnswerGenerator.generateStream
   │  ⑤ answer.delta × N           │  emit each token                     │     + 强约束 system prompt
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│
   │  ⑥ citation × M               │  emit                                │
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│
   │                                │                                      │  RagConversationService.completeAsk()
   │  ⑦ turn.completed             │  emit                                │
   │ ◄──────────────────────────────│ ◄────────────────────────────────────│
   │                                │  Flux<event> 关闭                    │
```

异常路径：任何阶段 `onErrorResume` → 发 `turn.error` + 调 `failAsk()` 标记 run 失败。

---

## 5. IntentClassifier 设计

### 5.1 分层混合

```
HybridIntentClassifier.classify(message, history)
  ├─ RuleBasedIntentMatcher.match(message)   ← 优先
  │   命中且 confidence ≥ 0.85 → return
  └─ LlmIntentRouter.classify(message)        ← 兜底
      LLM 输出 JSON：{intent, confidence}
```

### 5.2 规则示例（W1 实现 3 类）

| 规则类型 | 模式 | 推断 intent | confidence |
|---|---|---|---|
| 价格表达式 | `\d+\s*元以下\|低于\s*\d+\|\d+\s*-\s*\d+\s*元` | `FILTER_BY_ATTR` | 0.9 |
| 显式推荐意图 | `推荐\|帮我找\|有没有\|来款\|来个` | `RECOMMEND_VAGUE` | 0.85 |
| OOS 关键词 | `写代码\|讲笑话\|股票\|新闻` | `OUT_OF_SCOPE` | 0.95 |

### 5.3 Router Prompt（< 200 token）

```
你是电商意图路由器。仅输出 JSON：
{"intent": "...", "confidence": 0.0~1.0}

候选 intent（必须从中选）：
RECOMMEND_VAGUE / FILTER_BY_ATTR / REFINE / COMPARE /
EXCLUDE / SCENARIO_BUNDLE / CART_OP / IMAGE_SEARCH / OUT_OF_SCOPE

历史：{lastTurnSummary or "无"}
当前：{message}
```

W1 仅作分类不抽 slot，让 LLM 调用最短。

---

## 6. SlotExtractor 设计

### 6.1 Function-Call Schema

```json
{
  "name": "extract_shopping_slots",
  "parameters": {
    "type": "object",
    "properties": {
      "must": { "type": "array", "items": {"type": "string"} },
      "priceRange": {
        "type": "object",
        "properties": {
          "min": {"type": "number"}, "max": {"type": "number"}
        }
      },
      "categoryHint": {"type": "string"},
      "brands": {"type": "array", "items": {"type": "string"}}
    }
  }
}
```

调用通过 Spring AI ChatClient 的 prompt + JSON output（W1 不接 tool-calling，直接让 LLM 输出 JSON 然后 jsonCodec.readMap）。

### 6.2 兜底

LLM 返回非 JSON / 解析失败 → 返回 `Slot.empty()`，由 retrieval 兜底走纯文本检索。

---

## 7. Tool 接口设计

```java
public interface Tool {
    String name();
    String description();
    Flux<AgentStreamEvent> execute(ToolContext ctx);
}

public record ToolContext(
    String turnId,
    String correlationId,
    String message,
    IntentType intent,
    Slot slot,
    List<AgentConversationTurn> history,
    int topK
) {}

public record ToolResult(
    String toolName,
    List<SpuCardView> cards,            // 主要载荷
    List<String> facetsApplied,         // ["品牌:Apple", "价格<200"]
    String snippet                      // 给 LLM 用的纯文本上下文（带 [#N]）
) {}
```

ToolRegistry 的调度表 W1 极简：

```java
class ToolRegistry {
  List<Tool> plan(IntentType intent, Slot slot) {
    return switch (intent) {
      case RECOMMEND_VAGUE, FILTER_BY_ATTR, REFINE -> List.of(searchProductsTool);
      case OUT_OF_SCOPE                            -> List.of();      // 不调工具
      default                                      -> List.of(searchProductsTool);
    };
  }
}
```

---

## 8. SearchProductsTool 实现

```java
@Component
class SearchProductsTool implements Tool {
    private final ProductSearchSpi productSearchSpi;
    private final CatalogQueryFacade catalogQueryFacade;
    private final AgentSseEventFactory events;

    Flux<AgentStreamEvent> execute(ToolContext ctx) {
        // 1. 把 slot 翻译成 ProductSearchRequest（filter 含 brand/category，topK 根据 intent）
        ProductSearchRequest req = mapToSearchRequest(ctx);
        // 2. 调 SPI（阻塞，但跑在 ragBlockingScheduler 上）
        return Mono.fromCallable(() -> productSearchSpi.search(req))
                   .subscribeOn(ragBlockingScheduler)
                   .flatMapMany(hits -> {
                       List<SpuCardView> cards = enrichWithCatalog(hits);   // 拉 price/stock
                       return Flux.just(events.toolResult(name(), cards, ctx));
                   });
    }
}
```

**为什么 SearchProductsTool 用 CatalogQueryFacade 二次拉数据**：
- 检索 chunk 的 metadata 是索引时的快照
- 价格 / 库存可能在导入后变化（W4 价格一致性专项）
- 拉一次 catalog 主表，保证答案数据"新鲜"

`enrichWithCatalog` 性能：N 个 SPU × 1 次 PG 主键查询，N ≤ 10，可接受；W4 改为 IN 查询批量加速。

---

## 9. AgentOrchestrator 主流程

```java
@Service
class AgentTurnService implements AgentTurnFacade {

    public Flux<AgentStreamEvent> turnStream(AgentTurnRequest req) {
        return Mono.defer(() -> {
            // ① 进会话（复用 RagConversationService.beginAsk）
            AskConversationState state = conversationService.beginAskForAgent(req);
            // 幂等命中：直接 replay 历史 event 流
            if (state.replay()) return replayHistoricalEvents(state);
            return Mono.just(state);
        })
        .flatMapMany(state -> {
            String turnId = state.runId();
            String corrId = state.correlationId();

            // ② 发 turn.started
            return Flux.concat(
                Mono.just(events.turnStarted(turnId, corrId, req)),

                // ③ 意图 + 槽位（同步调）
                Mono.fromCallable(() -> classifyAndExtract(req.message(), state.history()))
                    .subscribeOn(blockingScheduler)
                    .flatMapMany(intentSlot -> Flux.concat(
                        Mono.just(events.intentDetected(intentSlot, corrId)),

                        // ④ 执行工具
                        executeTools(intentSlot, state),

                        // ⑤ 流式答案
                        generateAnswer(intentSlot, state)
                    ))
            );
        })
        .doOnComplete(() -> conversationService.completeAskForAgent(...))
        .onErrorResume(ex -> {
            conversationService.failAskForAgent(..., ex);
            return Flux.just(events.turnError(ex));
        });
    }
}
```

**executeTools 的并发**：W1 单工具 SearchProductsTool 串行即可；W2 引入 CompareProductsTool 时改 `Flux.merge(tools.stream().map(...))`。

---

## 10. ⚠️ agent_turn 表的取舍

W1 任务清单原文：「落 `agent_turn` 表（参考 `RagAskRunRecord`），`turnId` 幂等」。

**重新评估后**：既有 `rag_ask_runs` 表已经具备：
- `run_id` UNIQUE（agent 的 turnId 就映射这个）
- `correlation_id` UNIQUE
- `UNIQUE(user_id, conversation_id, request_id)` 的幂等键
- `status: RUNNING/SUCCEEDED/FAILED`
- `retrieval_question / top_k / filters / retrieval_queries / retrieved_contexts` 字段

agent 单 turn 与 ask 单 turn **结构上 95% 一致**，仅缺少 4 个 agent-specific 字段：`intent / slots_json / tools_called / generated_by_model`。

### 三种选项

| 方案 | 改动量 | 解耦度 | 后续 W2/W3 难度 |
|---|---|---|---|
| **A. 复用 rag_ask_runs + 加 4 列** | 改 schema + 改 RagAskRunRecord + 改 ConversationService（让它支持"agent kind"语义） | 低（耦合 retrieval） | 简单 |
| **B. 新建 agent_turn 表 + 包装 ConversationService** | 新表 + 新 record + 新 repository；ConversationService 调用方式与 agent 几乎一致，但走独立 adapter | 中 | 中 |
| **C. 在 rag_ask_runs 加 1 列 run_kind + 1 列 agent_metadata JSONB** | 改 schema 加 2 列；ConversationService 不动 | 最低（agent 数据塞 JSONB） | 简单但查询不雅观 |

### 推荐：**方案 C**

理由：
- W1 时间紧，不值得为"语义干净"而新增整套表 + repository + service
- agent 与 ask 的 turn 生命周期完全一样（开始 → 流式 → 完成/失败），核心字段无差异
- agent 特有字段 (intent / slots / tools_called) 量小，塞 JSONB 完全足够
- W4 真有需要时可单独迁移到独立表，迁移脚本简单（按 run_kind 拆库即可）

**Schema 变更**：

```sql
ALTER TABLE rag_ask_runs ADD COLUMN IF NOT EXISTS run_kind VARCHAR(16) NOT NULL DEFAULT 'ask'
    CHECK (run_kind IN ('ask', 'agent'));
ALTER TABLE rag_ask_runs ADD COLUMN IF NOT EXISTS agent_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX IF NOT EXISTS idx_rag_ask_runs_kind_started ON rag_ask_runs(run_kind, started_at DESC);
```

`agent_metadata` 包含：

```json
{
  "intent": "RECOMMEND_VAGUE",
  "intentConfidence": 0.9,
  "slot": {...},
  "toolsCalled": ["search_products"],
  "generatedByModel": true,
  "tokensIn": 312,
  "tokensOut": 187,
  "latencyMs": 4321
}
```

### 实施

`AgentTurnPersistenceAdapter` 通过新增的 `RagAskRunRepository.appendAgentMetadata(runId, json)` 方法回填，**ConversationService 完全不动**。

> 如果评审 / 客户端体验需要查询 agent 的多维分析数据（按 intent 聚合等），W4 再独立 agent_turn 表。

---

## 11. SSE event schema 冻结到协议文档

W1-AGT-12 单独产出 `docs/agent-sse-protocol.md`，包含 9 类 event 完整 JSON 示例。本文档负责定义，那份文档负责签字。客户端按那份对接。

---

## 12. Answer Prompt v1

落到 `src/main/resources/prompts/agent-answer-v1.txt`：

```text
你是电商导购助手。基于检索到的【商品卡片】回答用户问题。

强约束：
1. 只能推荐 context 里出现的 SPU。每次提到具体商品必须以 [#N] 引用（N=卡片序号）。
2. 价格 / 库存 / 优惠券：必须直接复述 context 中的字段，禁止编造数字。
3. 若 context 不够回答：明确说"目前没有完全匹配的商品"并复述用户需求里的关键约束，引导补充信息。
4. 输出语言与用户一致；中文用户用简体中文。
5. 不超过 300 字；不要堆砌广告语；像朋友一样推荐。

【context】
{contexts_with_index}

【用户消息】
{message}
```

`AgentAnswerGenerator` 把这段 prompt 喂给现有 `RagAnswerGenerator.generateStream`（system 部分覆写、user 部分构造），保持降级 / 超时 / 空响应兜底逻辑不变。

---

## 13. 复用清单（必须，越多越好）

| 复用 | 路径 | 用途 |
|---|---|---|
| `RagConversationService.beginAsk / completeAsk / failAsk` | retrieval/application | 多轮记忆 + 幂等 + run 记录 |
| `RagAskRunRecord` 表与 repository | retrieval/persistence | turn 持久化（方案 C） |
| `HybridRagRetriever` 经 `ProductSearchSpi` | retrieval/service → retrieval/spi（新增） | 检索 |
| `RagAnswerGenerator` | retrieval/service | 流式答案 + 降级 |
| `CatalogQueryFacade.getSpu` | catalog/api | 卡片实时 price/stock |
| `RagStreamErrorView` 风格 | retrieval/api | `turn.error` payload 模板 |
| `RagRetrievalMetrics` | retrieval/observability | 计数器命名一致 |
| `RagLogFields / RagLogHelper` | shared/support | ECS 日志字段一致 |
| `ApiResponse` | common/api | 非 SSE 接口（如健康检查）的统一响应 |
| `ragBlockingScheduler` bean | infrastructure/config | Reactor 阻塞调度 |
| `ChatClient.create(chatModel)` 模式 | 见 RagAnswerGenerator/LlmAttributeExtractor | LLM 调用统一风格 |

---

## 14. Commit 拆分（W1-AGT-01..10 → 7 个 commit）

| # | Commit | 任务 | 主要文件 |
|---|---|---|---|
| 1 | `feat：暴露 retrieval SPI 给上层 agent` | （新增前置） | `retrieval/spi/*` + adapter |
| 2 | `feat：新增 agent 模块包结构与 Modulith 边界` | AGT-01 | 2 个 package-info |
| 3 | `feat：定义 agent API 契约（请求/事件/视图）` | AGT-02 + AGT-03 | ~14 个 DTO/枚举 |
| 4 | `feat：实现 agent 意图识别（规则前置 + LLM 兜底）` | AGT-04 | intent/* (4 个类) + 测试 |
| 5 | `feat：实现 agent 槽位抽取与工具注册` | AGT-05 + AGT-06 | slot/* + tool/* (含接口) |
| 6 | `feat：实现 SearchProductsTool 并接入 catalog 实时数据` | AGT-07 | tool/impl/SearchProductsTool |
| 7 | `feat：装配 agent 主流程并暴露 SSE 入口` | AGT-08 + AGT-09 + AGT-11 + AGT-10 | AgentTurnService + Controller + answer-prompt-v1 + schema ALTER |

W1-AGT-12（协议文档）+ AGT-13（异常事件）合并到 commit 7 完成。

---

## 15. 测试策略

| 测试 | 范围 | 工具 |
|---|---|---|
| `IntentClassifierTests` | 单元，10+ case 覆盖规则 + LLM 兜底 mock | Mockito |
| `SlotExtractorTests` | 单元，function-call 输出解析 + 异常兜底 | Mockito |
| `ToolRegistryTests` | 单元，intent → tools 调度表 | 纯 Java |
| `SearchProductsToolTests` | 单元，mock ProductSearchSpi + CatalogQueryFacade | Mockito |
| `AgentTurnServiceTests` | 单元，mock 上下游，验证 event 序列与 conversation 调用 | Reactor StepVerifier |
| `AgentModuleTests` | 端到端 @SpringBootTest（沿用 catalog 测试套路），跑通 SSE | StepVerifier + WebTestClient |
| `ApplicationModulesVerificationTests` | 既有，验证新增模块边界 | Modulith |

回归门槛：`AgentTurnService` 单测覆盖率 ≥ 80%，意图分类器 ≥ 90%（这是 W2 加分项的基础）。

---

## 16. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| retrieval 没有暴露 SPI，agent 破 Modulith 边界 | 模块边界测试失败 | 第一步就先做 retrieval::spi，再做 agent |
| LLM 输出非纯 JSON 拖垮 SlotExtractor | 槽位丢失 → 退化到纯文本检索 | 已设计兜底返回 `Slot.empty()`；retrieval 仍能跑 |
| 多个 Flux 信号交错导致事件顺序乱 | UI 错位（卡片晚于答案） | 单工具时用 `Flux.concat` 而非 `merge`；W2 引入多工具改 `concatMap` |
| 幂等 replay 时事件无法重放（流式不可重放） | 客户端拿到 200 OK 但无内容 | 幂等命中时返回**完整的最终答案**作为单条 `turn.completed`（不重放中间 delta） |
| Doubao tool-calling 与现有 prompt 调用冲突 | SlotExtractor 解析失败 | W1 用普通 prompt + 输出 JSON 字符串，不用 Spring AI function-calling |
| `enrichWithCatalog` 把每 SPU 查一次 PG | latency 在 N 大时不可接受 | W1 N ≤ 10 直接循环；W4 改成 `findAllByIdIn` |
| Agent 事件 schema 与客户端 SSE 解码不一致 | 联调阻塞 | W1-AGT-12 协议文档冻结 + 给客户端 fixture JSON |

---

## 17. 决策记录（待用户拍板）

> 这些点会影响实施细节，需要你过一遍：

1. **agent_turn 表 vs rag_ask_runs 加列**（§10）— 我推荐方案 C，要不要按这个走？
2. **retrieval::spi 改动是否在 agent 模块本周完成**？还是先放 agent 内部包，下周再迁出来？
   - 推荐：本周先迁，免得 W2 还要返工。
3. **W1 是否启用 LLM 兜底意图分类**？还是只靠规则前置，未命中默认 `RECOMMEND_VAGUE`？
   - 推荐：只规则前置 + RECOMMEND_VAGUE 兜底（省 token，更快 demo）；W2 再开 LLM 兜底。
4. **答案 prompt 是否在 W1 引入 "[#N]" 引用强约束**？
   - 推荐：W1 引入，因为客户端 `citation` 事件依赖它；prompt 失败时降级为不输出 [#N]，event 不发就行。
5. **AgentSseEventFactory 是否要做事件 id 持久化**（用于断线重连 Last-Event-ID）？
   - 推荐：W1 不做；W4 视客户端反馈再加。

定下这 5 点后即可开工，预计 7 个 commit / 2–3 天工作量。
