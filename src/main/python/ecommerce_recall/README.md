# Ecommerce Recall Runtime Interface

该目录是主工程内的 Python 召回接口层，供主流程调用，不把召回逻辑改写成 Java。

## 适用意图

- `product_recommend_workflow`：模糊商品推荐。
- `product_search_workflow`：语义商品搜索。
- `product_detail_query_workflow`：详情问答前先定位商品。
- `review_summary_workflow`：评价总结前先定位商品和评价 chunk。
- `price_query_workflow` / `inventory_query_workflow`：先召回商品，再用结构化字段回查价格和库存。
- `photo_search_workflow`：图片 query embedding 生成后可复用同一 FAISS 召回链路。

## Python 函数接口

```python
from ecommerce_recall import recall_by_embedding

result = recall_by_embedding(
    query_embedding=[0.1, 0.2],
    top_k=5,
    hydrate=True,
)
```

返回结构：

```json
{
  "topK": 5,
  "resultCount": 1,
  "results": [
    {
      "score": 0.91,
      "faiss_id": 1,
      "chunk_id": "p_beauty_001:product_profile:0",
      "product_id": "p_beauty_001",
      "parent_chunk_id": "p_beauty_001:product_parent:0",
      "chunk_type": "product_profile",
      "embedding_modality": "text",
      "vector_id": "ecom-text:..."
    }
  ],
  "cards": []
}
```

`hydrate=True` 时会追加完整商品卡片：

```text
faiss_id -> chunk_id -> child chunk -> parent_chunk_id -> parent chunk -> product_id -> SPU/SKU/FAQ/Review/Image
```

## CLI 接口

主流程如果不直接 import Python，可以用进程方式调用：

```powershell
conda run -n rag python ai-agent/src/main/python/ecommerce_recall/product_vector_recall.py `
  --embedding-json-file ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-query-sample.json `
  --top-k 5 `
  --output ai-agent/src/main/resources/db/ecommerce_offline/faiss/ecom-product-main-recall-sample.json
```

也可以传请求文件：

```json
{
  "queryEmbedding": [0.1, 0.2],
  "topK": 5,
  "hydrate": true,
  "chunkTypes": ["product_profile", "marketing_description"],
  "modalities": ["text"]
}
```

```powershell
conda run -n rag python ai-agent/src/main/python/ecommerce_recall/product_vector_recall.py --request-file request.json
```

## 单独回查

按 FAISS id 回查：

```powershell
conda run -n rag python ai-agent/src/main/python/ecommerce_recall/product_vector_recall.py --mode hydrate-faiss --faiss-ids 1,2
```

按 chunk id 回查：

```powershell
conda run -n rag python ai-agent/src/main/python/ecommerce_recall/product_vector_recall.py --mode hydrate-chunk --chunk-ids p_beauty_001:official_faq:0
```
