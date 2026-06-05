# ai-agent · 电商导购对话智能体

基于 **Spring Boot 4 / Java 25 + Spring AI Alibaba Graph** 的电商导购对话智能体。一条用户消息进入一张状态图（StateGraph），完成「意图理解 → 商品召回 → 业务操作 → 流式作答」的完整链路，对话结果通过 **服务器推送事件（SSE）** 逐字推送给前端。

---

## 一、功能介绍

### 多意图导购对话
统一入口 `GET /public/agent/turn`（SSE 流式），由 **DeepSeek** 做意图路由，自动分流到四类工作流：

| 工作流 | 覆盖意图 | 说明 |
|--------|---------|------|
| **商品推荐** `product_recommend` | 模糊推荐 / 条件筛选 / 多轮细化 / 商品对比 / 反向排除 / 场景组合 / **拍照找货** / 价格 / 库存 / 详情 / 评价总结 | 7 个以上子场景，不同场景对应不同召回策略 |
| **购物车** `cart_manage` | 加购 / 删除 / 改数量 / 查看 | 多轮槽位填充 + 候选选择，结果落库 |
| **下单** `order_manage` | 结算 / 填地址 / 确认 / 取消 / 订单查询 / 物流查询 | 支持「暂停-恢复」闭环（等待地址、等待确认），模拟下单落库 |
| **澄清** `clarify` | 闲聊 / 政策问答 / 低置信 / 其它 | 兜底澄清追问 |

### 多模态输入
- **文本**：直接进入意图理解。
- **图片**（参数 `imageRef`）：调用豆包做 **图片描述（caption）+ 多模态向量（embedding）**，两者并行执行；再经 **FAISS** 视觉向量召回，找同款 / 相似商品。
- 图像处理链路与文本意图链路在状态图中**并行分叉（fan-out）**后汇合，缩短整体响应时延。

### 混合多路召回
- **目录关键词 / 结构化过滤**：基于 PostgreSQL 商品目录的关键词与结构化条件过滤。
- **图片向量召回**：Python + FAISS 离线索引（位于 `src/main/resources/db/ecommerce_offline/faiss`）。
- **语义分块 / 历史快照 / 用户偏好**：多来源补充召回。
- 召回结果经 RRF 融合 + 正向/负向约束过滤 + 轻量重排，最终交给文案生成。

### 流式作答
推荐文案由大模型**逐字流式生成**（`answer.delta` 事件），首字延迟可达亚秒级；商品卡片、终态状态、用户行为反馈分事件推送。

### 会话与持久化
- 多轮会话状态、对话历史、轮次级路由均落 PostgreSQL。
- 购物车、订单、商品目录都有数据库持久化。

---

## 二、技术栈

- **Spring Boot 4.0.6 / Java 25**，Web + WebFlux（用于 SSE）
- **Spring AI Alibaba Graph**（状态图编排）
- **DeepSeek**（意图路由、文案生成，OpenAI 兼容接口）
- **豆包 Doubao**（图片描述 + 多模态向量）
- **FAISS**（Python 子进程，图片向量召回）
- **PostgreSQL 18**（会话 / 购物车 / 订单 / 商品目录）
- 可选组件：Milvus、RocketMQ、邮件告警（默认关闭）

---

## 三、如何启动服务

### 1. 环境依赖
- JDK **25**、Maven（直接用自带的 `./mvnw` 即可）
- **PostgreSQL 18**（建库并导入表结构与数据，见下）
- **Python 环境**，已安装 `faiss-cpu`、`numpy`（供图片召回子进程调用）

### 2. 准备数据库
```bash
# 建表（应用运行所需的 public.* 表）
psql "$数据库连接串" -f src/main/resources/db/migration/schema.sql
# 离线商品库 + 100 条商品种子数据
psql "$数据库连接串" -f src/main/resources/db/ecommerce_offline/schema-postgres.sql
psql "$数据库连接串" -f src/main/resources/db/ecommerce_offline/seed-ecommerce-dataset.sql
```
> 商品目录表（`public.catalog_spu / catalog_sku`）由应用启动时的数据集导入填充，或调用 `POST /admin/catalog/import/ecommerce-dataset` 手动导入。

### 3. 配置环境变量
应用通过 `spring.config.import` 从项目根目录的 `.env` 或系统环境变量读取配置。复制模板并填入真实值：
```bash
cp .env.example .env
```

需要配置的变量：

| 变量 | 用途 | 是否必需 |
|------|------|:---:|
| `DEEPSEEK_API_KEY` | 意图路由 + 推荐文案生成 | 必需 |
| `DOUBAO_CAPTION_API_KEY` | 图片描述（走图片输入时） | 图片场景必需 |
| `DOUBAO_EMBEDDING_API_KEY` | 图片 / 文本多模态向量 | 图片场景必需 |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL 连接（会话/购物车/订单持久化） | 必需 |
| `ECOM_RECALL_PYTHON` | 指向装好 faiss 的 Python 解释器路径 | 图片召回必需 |

默认关闭、无需配置：Milvus、RocketMQ、邮件告警。

两个实践建议（避免启动 / 运行报错）：
- 数据源连接串建议追加 `?stringtype=unspecified`（库中存在 jsonb 列，否则写入会报类型错误）。
- 不使用 Spring AI OpenAI 自动配置时，设置 `SPRING_AI_MODEL_CHAT=none`、`SPRING_AI_MODEL_EMBEDDING=none`，否则启动期会强制要求 OpenAI key。

### 4. 启动应用
```bash
./mvnw spring-boot:run
```
默认监听端口 **8080**（开发环境无统一前缀）。

### 5. 调用示例

文本推荐（SSE 流式返回）：
```bash
curl -N -G http://localhost:8080/public/agent/turn \
  --data-urlencode "userId=u1" \
  --data-urlencode "conversationId=c1" \
  --data-urlencode "message=推荐一款适合油性皮肤的精华"
```

拍照找货（带图片）：
```bash
curl -N -G http://localhost:8080/public/agent/turn \
  --data-urlencode "userId=u1" \
  --data-urlencode "conversationId=c1" \
  --data-urlencode "message=帮我找找这个商品" \
  --data-urlencode "imageRef=/绝对路径/商品图.jpg"
```

完整成交闭环（同一个 `conversationId` 连续多轮）：
```
推荐一款适合油性皮肤的精华
把第一个加入购物车
结算购物车下单
收货人张三，电话13800138000，地址北京市朝阳区建国路1号
确认下单
```

### 主要接口

| 方法 / 路径 | 说明 |
|------------|------|
| `GET /public/agent/turn` | 导购对话（SSE）。参数：`userId`、`conversationId`、`message`、`imageRef`（可选）、`streamMode`（可选） |
| `GET /public/agent/feedback/...` | 用户行为反馈 / 会话记忆 |
| `POST /admin/catalog/import/ecommerce-dataset` | 导入商品数据集到目录表 |

> 把 `streamMode` 设为 `trace`，可观察状态图每个节点的执行与耗时，便于调试。

---

## 四、目录结构（节选）
```
src/main/java/com/bytedance/ai/graph/        # 导购状态图与各工作流
  ├── web/                 # SSE 入口 GuideAgentTurnController
  ├── intent/              # DeepSeek 意图路由
  ├── productrecommend/    # 推荐策略 / 多路召回 / 流式文案
  ├── cartmanage/          # 购物车工作流（子图）
  ├── ordermanage/         # 下单工作流（子图，支持暂停恢复）
  └── catalog/             # 商品目录导入与查询
src/main/python/ecommerce_recall/            # 图片描述 / 向量 + FAISS 召回
src/main/resources/db/                       # 表结构、离线商品库与 FAISS 索引
```
