# Agent SSE Protocol

`POST /public/agent/turn`

Request:

```json
{
  "userId": "u1",
  "conversationId": "c1",
  "message": "推荐 300 元以下的双肩包",
  "turnId": "t-001"
}
```

Response content type: `text/event-stream`.

Each SSE frame uses:

- `id`: per-event UUID, not persisted in W1
- `event`: event name below
- `comment`: `correlationId`
- `data`: JSON payload

Events:

| event | data |
|---|---|
| `turn.started` | `{ "turnId", "conversationId", "model" }` |
| `intent.detected` | `{ "intent", "confidence", "source", "slots" }` |
| `tool.calling` | `{ "toolName", "args" }` |
| `tool.result` | `{ "toolName", "cards", "facetsApplied", "compareMatrix"?, "excludedFacets"? }` |
| `answer.delta` | `{ "text" }` |
| `citation` | `{ "refId", "spuId", "chunkId" }` |
| `notice` | `{ "code", "message", "severity" }` |
| `turn.completed` | `{ "turnId", "latencyMs", "tokensIn", "tokensOut", "generatedByModel" }` |
| `turn.error` | `{ "code", "message", "recoverable" }` |

Expected W1 happy-path order:

```text
turn.started
intent.detected
tool.calling
tool.result
answer.delta *
citation *
turn.completed
```

## W2 字段补丁

### `tool.result.excludedFacets`

W2 反选加分项：当用户消息里出现"不含 / 非 / 不要 X"等否定语义时，agent 会把 X
按 tags / brands / ingredients 分桶下推到 Milvus 做反向过滤；top-50 召回再过
NegationRerankFilter（LLM 二分类）剔除假阳。最终被剔除的属性会通过
`excludedFacets: string[]` 字段回传给客户端，展示成「已为您排除：酒精、香精」。

例：

```json
{
  "toolName": "search_products",
  "cards": [...],
  "facetsApplied": {
    "categoryHint": "防晒霜",
    "mustNot": {"tags": [], "brands": [], "ingredients": ["酒精", "香精"]}
  },
  "excludedFacets": ["酒精", "香精"]
}
```

`excludedFacets` 缺省或空数组表示本轮没有反选剔除项；客户端可据此决定是否渲染
「已为您排除」徽章。

