# Ecommerce Recall Test Tools

该目录用于测试电商 RAG 召回链路。

## 任意文本端到端召回

输入任意文本，脚本会执行：

```text
query text -> Doubao embedding -> FAISS recall -> child chunk -> parent/SPU -> final product cards
```

运行：

```powershell
conda run -n rag python ai-agent/src/test/python/ecommerce_recall/run_text_recall_report.py --query "推荐一款适合油皮的洗面奶" --top-k 1 --speed-iterations 3 --output ai-agent/target/ecommerce-recall/text-recall-report.json
```

也可以使用环境变量输入 query：

```powershell
$env:ECOM_RECALL_QUERY_TEXT="通勤用的降噪耳机"
conda run -n rag python ai-agent/src/test/python/ecommerce_recall/run_text_recall_report.py
```

依赖环境变量：

```text
DOUBAO_EMBEDDING_API_KEY=你的火山方舟 Embedding API Key
DOUBAO_EMBEDDING_MODEL=doubao-embedding-vision-251215
DOUBAO_MULTIMODAL_EMBEDDING_API_URL=https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal
```

输出 JSON 贴近 RAG 流程实际召回输出，只包含：

- `products`：RAG 最终召回商品结果，已按商品/SPU 聚合，包含商品卡片、SKU 和命中 evidence。
- `speedTest`：query embedding、召回回查、端到端耗时，以及多次纯召回平均耗时。

## 图片 imageRef 端到端召回

输入本地图片路径或数据集相对图片路径，脚本会执行：

```text
imageRef -> image preprocess/validate -> Doubao image embedding -> FAISS image recall -> child chunk -> parent/SPU -> final product cards
```

运行：

```powershell
conda run -n rag python ai-agent/src/test/python/ecommerce_recall/run_image_recall_report.py --image-ref "1_美妆护肤/images/p_beauty_001_live.jpg" --top-k 3 --speed-iterations 3 --output ai-agent/target/ecommerce-recall/image-recall-report.json
```

带文本约束做图文融合：

```powershell
conda run -n rag python ai-agent/src/test/python/ecommerce_recall/run_image_recall_report.py --image-ref "1_美妆护肤/images/p_beauty_001_live.jpg" --query "找类似的油皮护肤品" --top-k 3
```

图片召回输出包含：

- `image`：解析后的 imageRef、content type、尺寸、大小和质量 warnings。
- `visionSignals`：最佳视觉分、置信度和低置信度提示。
- `fusion`：weighted RRF 的图文融合参数和命中数。
- `products`：拍照找货最终商品卡片和 evidence。
