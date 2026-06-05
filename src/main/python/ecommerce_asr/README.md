# Ecommerce ASR Runtime Interface

ASR 是 Agent 前置输入适配层，只负责把用户音频转换成普通文本。识别结果应写入 `/agent/turn` 的 `message`，后续意图识别、RAG 召回和商品推荐与手输文本走同一条链路。

## 运行依赖

```powershell
conda activate rag
pip install websocket-client
```

## 环境变量

ASR WebSocket 鉴权使用 `app-key + access-key`：

```powershell
$env:DOUBAO_ASR_APP_KEY="your-app-key"
$env:DOUBAO_ASR_ACCESS_KEY="your-access-key"
```

可选配置：

```powershell
$env:DOUBAO_ASR_ENDPOINT="wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
$env:DOUBAO_ASR_RESOURCE_ID="volc.bigasr.sauc.duration"
$env:DOUBAO_ASR_AUDIO_FORMAT="auto"
$env:DOUBAO_ASR_SAMPLE_RATE="16000"
```

## Python 接口

```python
from ecommerce_asr import transcribe_audio_text

message = transcribe_audio_text("query.wav")
```

## CLI

```powershell
conda run -n rag python ai-agent/src/main/python/ecommerce_asr/doubao_asr.py `
  --audio query.wav
```

标准输出就是主流程可直接使用的 `message`。调试时可增加 `--json` 查看分段、耗时和请求 ID。
