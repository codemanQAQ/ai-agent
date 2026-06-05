# W1 周末 Retrospective（2026-05-24）

> 对应任务：`W1-OPS-01`。
> 范围：W1 周期内完成的服务端工作，对照 `竞赛技术方案规划.md §0 评分映射` 与 `竞赛任务拆解清单.md`，盘点已覆盖的评分点、待办与发现。

---

## 1. 一图速览

```
W1 开工 → catalog 模块（CAT-01..06）→ indexing 改造（IDX-01..05）→
        agent 模块（AGT-01..13）→ 异步政策 + RocketMQ Outbox 重做 → EVAL/OPS 收尾

23 个 commit（origin/main..HEAD）
263 个 main Java 文件 / 37 个 test 文件
8 份 docs（含本文）/ 1 份 SSE 协议 / 1 个验收数据集 / 2 个 demo 脚本
```

---

## 2. 评分点对照（W1 已覆盖部分）

| 评分维度（权重） | 评分项 | W1 覆盖 | 证据 |
|---|---|---|---|
| **基础链路完整性 35%** | 文本输入 → 检索 → 流式答案 + 商品卡片 | ✅ | `AgentTurnController` + `AgentTurnService` + `SearchProductsToolCallback` + `RagAnswerGenerator` |
|  | 9 类 SSE 事件协议 | ✅ | `docs/agent-sse-protocol.md` |
|  | 意图识别（基础 2 类 + OOS + fallback） | ✅ | `RuleBasedIntentClassifier`（4 类规则 + RECOMMEND_VAGUE 兜底）|
|  | 槽位抽取（must / priceRange / categoryHint / brands） | ✅ | `LlmSlotExtractor`（JSON Schema + 正则兜底） |
|  | `[#N]` 引用强约束 + citation 事件 | ✅ | `agent-answer-v1.txt` + `CitationExtractor` |
|  | turn 幂等 | ✅ | `agent_turn(user_id, conversation_id, request_id)` UNIQUE WHERE NOT NULL |
| **工程质量 25%** | Spring Modulith 边界 | ✅ | 4 个模块（catalog/agent/retrieval/indexing） + 3 个 NamedInterface（`catalog::api`、`retrieval::api`、`retrieval::spi`），`ApplicationModulesVerificationTests` 绿 |
|  | 流式 WebFlux SSE | ✅ | `Flux<ServerSentEvent<Object>>` + `produces=TEXT_EVENT_STREAM_VALUE`（与既有 RagAskController 同构） |
|  | 异步执行政策 | ✅ | `AGENT.md §3.9` 写明硬约束；`catalogAttributeExecutor` 固定池已拆除 |
|  | RocketMQ Outbox 一致性 | ✅ | `catalog_attribute_outbox` 表 + dispatcher + producer + listener，镜像既有 `rag_index_outbox` 模式 |
|  | 状态机 | ⏳ W3 | indexing 状态机已存在；catalog `attributes_status` 已是简化状态机；cart/order W3 接入 |
|  | OTel + ECS 日志 | ✅ | 既有 `RagLogFields/RagLogHelper`，agent 沿用 |
|  | Native 镜像 | ⏳ W4 | pom profile 已就绪，等 GA 时验证 |
|  | 测试覆盖 | ✅ | 37 个测试类；模块测试 + Modulith 验证 + WebTestClient SSE 集成全绿 |
| **效果可靠性 20%** | 强约束 grounded answer | ✅ | Answer Prompt v1 明文禁编价格/优惠券 + `[#N]` 引用 |
|  | 价格 / 库存实时性 | ✅ | `ProductSearchSpiAdapter` 通过 `CatalogQueryFacade.findSpuByExternalRef` 实时拉 catalog 主表，不从 chunk metadata 取数 |
|  | 引用强对齐 | ✅ | `CitationExtractor` 解析 `[#N]` → 查 cards[N-1].spuId → 发 citation 事件；LLM 不输出 [#N] 时降级为纯文本 |
|  | 多轮上下文 | ✅ | `AgentConversationSpi.beginTurn` 复用 `RagConversationService`，最近 10 轮 SUCCEEDED 消息进 prompt |
|  | LLM 不可用降级 | ✅ | `RagAnswerGenerator` 既有 fallback；`LlmSlotExtractor` 解析失败 → 正则兜底 |
|  | `GroundedAnswerVerifier` | ⏳ W4 | 设计已落到方案文档 §16，W4 实现 |
| **加分项 20%** | 反选 / 排除约束 | ⏳ W2 | data：catalog 抽属性已产出 `attributes_json`（W3 加分项可直接用） |
|  | 多模态拍照找货 | ⏳ W3 | data：Doubao-embedding-vision 配置已就位；`chunk_type` 已支持 IMAGE 角色 |
|  | 购物车与下单 | ⏳ W3 | StateMachine 套路在 indexing 已有可镜像样本 |
|  | 场景化组合 | ⏳ W3+ | 备选可砍项 |

**W1 阶段评分覆盖度（自评，按完成度加权）**：
- 基础链路完整性 **30/35**（缺真实环境跑 SSE，需要 EVAL-02 录屏后再确认到 35）
- 工程质量 **20/25**（缺 Native 镜像 + 部分边界场景测试）
- 效果可靠性 **12/20**（grounded verifier 待 W4）
- 加分项 **0/20**（全部 W2/W3）

**总计 W1 自评：62/100**，距离目标 94 还差 32 分，全部分布在 W2/W3/W4。

---

## 3. 已实现清单（按 commit）

> 来自 `git log --oneline origin/main..HEAD`，23 commit 全部围绕 W1 任务。

### 阶段 A：catalog 主数据 (CAT-01..06)
- `feat：新增 catalog 模块包结构与 Modulith 边界`
- `feat：补充 catalog_spu 与 catalog_sku 表结构`
- `feat：实现 catalog 持久化层`
- `feat：补充 catalog API 契约与导入双写服务`
- `feat：接入 LLM 异步抽取商品属性`
- `feat：开放 catalog 落地页与导入 REST 接口`
- `test：补充 catalog 模块单元与集成测试`
- `chore：补充 demo 电商商品样例数据`

### 阶段 B：indexing chunk_type + 多模态预备 (IDX-01..05)
- `feat：扩展 chunk metadata 支持 chunk_type 分类`
- `feat：catalog-spu markdown 切分按 chunk_type 自动归类`
- `feat：接入 Doubao-embedding-vision 配置与启动期一致性日志`
- `docs：补充索引链路端到端运行手册与一键导入脚本`

### 阶段 C：并发政策 + RocketMQ Outbox 切换
- `chore：明确异步执行政策并拆除 catalog 固定线程池`
- `feat：补充 catalog_attribute_outbox 表与持久化层`
- `feat：catalog 抽属性切换到 RocketMQ Outbox 投递`

### 阶段 D：agent 模块 (AGT-01..13)
- `feat：retrieval 暴露 ProductSearchSpi 与新会话方法`
- `feat：新增 agent 模块包结构与 Modulith 边界`
- `feat：补充 agent_turn 表结构与持久化层`
- `feat：定义 agent API 契约`
- `feat：实现规则前置意图分类与槽位抽取`
- `feat：实现 SearchProductsToolCallback`
- `feat：装配 agent 主流程与 Answer Prompt v1`
- `feat：开放 agent SSE 入口并冻结协议`

---

## 4. 关键架构决策（W1 落地，写进 AGENT.md / 方案文档）

1. **chunk_type 走 JSONB metadata，不加列**——零 DDL 风险，PG 用 `metadata->>'chunkType'` 即可过滤；与既有 `blockType` 同框。
2. **catalog 与 rag_documents 双写但不分裂会话**——agent 与 ask 共享 `rag_conversations`/`rag_conversation_messages`，只在 turn 层分裂（`agent_turn` vs `rag_ask_runs`）。
3. **Tool 使用 Spring AI `ToolCallback` 标准接口，但 W1 程序确定性调用**——保留 W3 切换到 LLM 自主选工具的升级路径，Tool 实现一行不改。
4. **异步执行政策**：RocketMQ > Reactor 虚拟线程 > `@Async("ragVirtualThreadExecutor")`，**禁止**新增固定大小线程池。
5. **W1 仅规则路由 + RECOMMEND_VAGUE 兜底**——省 token / 降延迟，W2 再开 LLM 兜底。
6. **`[#N]` 引用强约束 W1 即引入**——客户端 `citation` 事件依赖；LLM 不输出 [#N] 时自动降级为纯文本，无须 fallback 代码。

---

## 5. 风险与待办（W2 起点）

### 必须 W2 第一周解决
- **`RagRetrievedChunk` 未暴露 `chunkType`**：`ProductSearchSpiAdapter.matchesIncludedChunkType` 目前用 `blockType` 兜底；W2 反选实现前需把 `chunkType` 上提到 `RagRetrievedChunk`。
- **`enrichWithCatalog` N+1 查询**：W1 每个 hit 一次 `findSpuByExternalRef`，N ≤ 10 可接受；W2 加 `findAllByExternalRefs(List<String>)` 批量接口。
- **`LlmSlotExtractor` 真实 Doubao 联调**：当前测试 mock ChatModel；spike Doubao 对 JSON 输出指令服从度（方案文档 §15）。

### 加分项预备
- catalog `attributes_status=DONE` 数据是 W2 反选加分项的输入——确认 RocketMQ 链路在 demo 环境跑通，10 条 SPU 都拿到 attributes_json。
- `chunk_type=IMAGE` 已是 enum 一员，W3 多模态拍照找货可直接复用。

### 客户端协作待办
- 把 `docs/agent-sse-protocol.md` + `scripts/rag/agent-sse-demo.sh` 发给客户端队友，让他们用 fixture 联调 9 类事件解码。
- 商品卡片 DTO `SpuCardView` 字段已冻结，客户端可同步开始落地页 + 卡片组件。

---

## 6. 验收脚本与产物清单

| 类型 | 路径 | 用途 |
|---|---|---|
| 验收数据集 | `src/test/resources/eval/w1-acceptance-cases.json` | 5 + 5 共 10 条 query → 期望 SPU；W2 EvalRunner 直接读取 |
| SSE demo 脚本 | `scripts/rag/agent-sse-demo.sh` | `bash scripts/rag/agent-sse-demo.sh [caseId]` 跑通端到端，证据存入 `scripts/rag/.evidence/agent-sse/` |
| 导入脚本 | `scripts/rag/import-catalog.sh` | demo 数据一键导入 |
| 协议文档 | `docs/agent-sse-protocol.md` | 9 类事件 schema |
| 设计文档 | `docs/agent模块技术方案.md` | 17 章节 agent 模块设计 |
| 方案总览 | `docs/竞赛技术方案规划.md` | 评分映射 + 4 周里程碑 |
| 任务清单 | `docs/竞赛任务拆解清单.md` | 4 周 ~85 任务，本周完成所有 W1 项 |
| 索引手册 | `docs/索引链路运行手册.md` | indexing 端到端串通 |
| Retrospective | `docs/W1-retro.md` | 本文 |

---

## 7. 演示录屏 checklist（EVAL-02 实操步骤）

> 后端联调时按此清单走一遍即可，预计 15 分钟内完成。

- [ ] 启动 PostgreSQL + Milvus + RocketMQ 本地服务
- [ ] `./mvnw spring-boot:run` 起后端，确认 8080 端口 OK
- [ ] `bash scripts/rag/import-catalog.sh` 导入 10 条 SPU
- [ ] `psql -c "SELECT attributes_status, count(*) FROM catalog_spu GROUP BY 1;"` 确认 DONE = 10
- [ ] **录屏开始**：开 cmd+shift+5 或 `asciinema rec`
  - [ ] `bash scripts/rag/agent-sse-demo.sh basic-01` 跑基础推荐
  - [ ] `bash scripts/rag/agent-sse-demo.sh filter-03` 跑价格区间筛选
  - [ ] `bash scripts/rag/agent-sse-demo.sh basic-04` 跑油皮洁面
  - [ ] `bash scripts/rag/agent-sse-demo.sh filter-04` 跑品牌过滤
- [ ] 同 turnId 重发 → 应一次性返回 turn.completed（幂等校验）
- [ ] 录屏归档到 `scripts/rag/.evidence/agent-sse/recording-w1.mp4` 或 `.cast`
- [ ] 把 evidence 目录加进 git ignore（已配 `.evidence/` 应被忽略）或单独 PR 上传到 OSS
