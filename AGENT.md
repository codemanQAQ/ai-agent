# Agent 协作规则

> 面向 AI 编程助手的项目规范，与 CLAUDE.md 保持同步。

---

## ⚠️ 最高优先级规则

- 修改代码前，先说明改动范围和思路，等开发者确认再执行
- 新增依赖前，先告知包名和用途，等确认再安装
- 遇到多种实现方案时，列出优劣后由开发者决策，不要自行选择
- 不要自动重构与当前任务无关的代码
- 每次对话结束时，在末尾列出本次所有修改过的文件路径

---

## 1. 项目概览

- **类型**：全栈项目（前端 + 后端）
- **后端**：Java + Spring Boot，AI 编排框架 Spring AI，模块化采用 Spring Modulith
- **数据库**：PostgreSQL + Redis + Milvus（向量库）+ Elasticsearch
- **中间件**：RocketMQ
- **可观测性**：Elastic APM Java Agent + ECS 结构化日志
- **包管理**：sdkman（JDK / Maven）+ brew（本地依赖）

### 1.1 技术栈版本（统一基线）

| 组件              | 版本               | 备注                                             |
|-----------------|------------------|------------------------------------------------|
| JDK             | `25.0.2-graalce` | 通过 sdkman 安装：`sdk install java 25.0.2-graalce` |
| Spring Boot     | `4.0.6`          | 父 POM 统一管理依赖版本                                 |
| Spring Modulith | 启用               | 模块边界由 `@ApplicationModule` 与包结构约束              |
| Maven           | `3.9.14`         | 优先使用项目自带 `./mvnw`，避免本地版本漂移                     |

> 升级 JDK / Spring Boot / Maven 版本前，必须先在 PR 中说明动机与影响范围，等开发者确认。

### 1.2 目录结构

```text
ai-agent/
├── pom.xml                                     # Maven 配置
├── AGENT.md                                    # Agent 协作规则
├── .env / .env.example                         # 本地环境变量（含 OTel 开关）
├── elastic-otel-javaagent.jar                  # Elastic APM Java Agent
├── docs/                                       # 设计 / 规范文档
├── k8s/                                        # 部署清单
├── scripts/rag/pgsql/                          # 数据库脚本
├── .run/                                       # IDEA 运行配置
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── application.properties          # 公共配置（含 ECS 结构化日志）
    │   │   ├── application-dev.properties      # 开发 profile（瘦身版）
    │   │   ├── application-prod.properties     # 生产 profile
    │   │   ├── logback-spring.xml              # 仅 include base.xml + CONSOLE
    │   │   └── db/migration/                   # Flyway 迁移脚本
    │   └── java/com/bytedance/ai/
    │       ├── common/                         # 通用 API / 异常（GlobalExceptionHandler）
    │       │   ├── api/
    │       │   └── error/
    │       ├── shared/                         # 跨模块共享工具
    │       │   ├── markdown/
    │       │   ├── metadata/                   # RagChunkMetadataHelper
    │       │   ├── model/
    │       │   ├── properties/
    │       │   └── support/                    # ★ RagLogHelper 日志辅助
    │       ├── infrastructure/                 # 基础设施
    │       │   ├── config/
    │       │   ├── web/
    │       │   ├── mq/
    │       │   ├── scheduling/
    │       │   ├── observability/              # ★ 可观测性扩展点
    │       │   └── nativeimage/
    │       ├── document/                       # 模块：文档管理（六边形）
    │       │   └── api/  application/  persistence/jdbc/  spi/  web/
    │       ├── indexing/                       # 模块：索引构建
    │       │   ├── api/  application/  service/  workflow/
    │       │   └── messaging/  notification/  persistence/jdbcImpl/  web/
    │       └── retrieval/                      # 模块：检索 + 问答
    │           ├── api/  application/  service/  model/
    │           ├── messaging/  persistence/jdbc/  web/  support/
    │           └── observability/              # 模块自身指标埋点
    └── test/java/com/bytedance/ai/{document,indexing,retrieval}/...
```

模块边界遵循 Spring Modulith 约定：`document / indexing / retrieval` 各自闭合，`shared` 与 `common` 提供横切能力，
`infrastructure` 放配置与适配器。

---

## 2. 代码规范

### 2.1 通用

- 函数体不超过 80 行 / 60 语句，圈复杂度不超过 15，参数不超过 4 个
- 文件行数不超过 1000 行
- 禁止魔法数字，使用命名常量
- 异常必须显式处理或显式抛出，禁止吞异常
- 注释写「为什么」，不写「是什么」
- 禁止硬编码凭据、SQL 拼接、弱加密（MD5 / SHA1）

---

## 3. 日志规范

### 3.1 框架与底座

- 框架：SLF4J + Logback（Spring Boot 默认），统一用 Lombok `@Slf4j`，禁止 `System.out` 与 `printStackTrace()`。
- 配置入口：`logback-spring.xml` 只包含 `base.xml` + CONSOLE，所有 pattern / 字段在 `application.properties` 控制。
- 结构化输出：生产 / 上报链路使用 ECS JSON（`logging.structured.ecs.*` 已配置），本地控制台用文本，trace 关联字段通过
  `logging.pattern.correlation=[%X{trace.id:-} %X{span.id:-}]` 注入。
- Trace / Span 由 Elastic APM Agent 自动写入 MDC，业务代码不要手工塞 `trace.id`。

### 3.2 日志级别使用准则

| 级别    | 用途                | 典型场景                            |
|-------|-------------------|---------------------------------|
| ERROR | 不可恢复的失败、需要告警      | 未捕获异常、外部依赖永久失败、最终 fallback 仍失败  |
| WARN  | 可恢复异常、降级、限流、非法输入  | 文件拒收、Bean 缺失降级、查询改写失败回退         |
| INFO  | 业务关键里程碑（每请求 ≤ 数条） | 接收请求、任务调度结果、对账恢复条数              |
| DEBUG | 细节诊断、内部步骤         | 检索召回明细、Prompt 片段、Workflow 状态机跳转 |
| TRACE | 极少使用              | 仅在排障期临时开启                       |

默认根级别 INFO；`com.bytedance.ai` 包可在 dev profile 开 DEBUG。

### 3.3 统一打印格式（强制）

**结构化键值对**，禁止字符串拼接：

```
// ✅ 正确
log.info("rag.ask accepted: correlationId={}, docId={}, qPreview={}",
        correlationId, docId, RagLogHelper.previewQuestion(question));

// ❌ 错误
log.info("rag.ask accepted " + correlationId + " " + question);
```

约定字段命名（小写驼峰，与现有代码一致）：

| 字段                              | 含义                                                   |
|---------------------------------|------------------------------------------------------|
| `correlationId`                 | 业务关联 ID（端到端贯穿）                                       |
| `docId` / `chunkId`             | 文档 / 切片标识                                            |
| `shortSha`                      | 内容哈希前 8 位（`RagLogHelper.shortSha`）                   |
| `qPreview`                      | 用户问题预览，≤ 96 字符（`previewQuestion`）                    |
| `count` / `size` / `tookMs`     | 数量与耗时                                                |
| `rejectReason` / `errorSummary` | 拒绝原因 / 异常摘要                                          |
| `stage`                         | 流水线阶段（`expand` / `retrieve` / `rerank` / `generate`） |

### 3.4 异常处理

- 异常必须作为最后一个参数传入（不要 `+ e.getMessage()`），让 logback 打完整栈：
  ```
  log.error("RAG ask stream failed: correlationId={}", correlationId, exception);
  ```
- 仅摘要场景（WARN / 降级）用 `RagLogHelper.errorSummary()` 输出 `类名: 截断消息`，避免噪音。
- 未捕获异常统一走 `GlobalExceptionHandler`，业务层不要重复打 ERROR + 抛出。

### 3.5 敏感与体量控制

- 用户问题、Prompt、文档内容必须经 `RagLogHelper.abbreviate` / `previewQuestion` 截断；切勿整段打印。
- 文件内容、向量、token、密钥、邮箱、手机号一律不进日志；上传文件只打 `filename / contentType / size / rejectReason`。
- 哈希用 `shortSha`（8 位）即可，避免刷屏。

### 3.6 模块化事件命名

INFO 消息使用 `<模块>.<动作> <结果>:` 前缀，便于检索与未来切到结构化字段：

```text
document.upload accepted: ...
indexing.outbox dispatched: ...
retrieval.ask streaming: ...
retrieval.recovery recovered: count=3
```

### 3.7 MDC 与上下文

- 入口（Controller / MQ Consumer / Scheduled Task）在处理前用 try-with-resources 写入 `correlationId`、`docId`：
  ```
  try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", id)) {
      // ...
  }
  ```
- 自定义 MDC key 用小写点号分段（`rag.doc.id`），与 ECS 字段保持兼容；不要覆盖 `trace.id` / `span.id`。

### 3.8 提交前自查 checklist

1. 使用 `{}` 占位符，无字符串拼接
2. 异常传栈或显式 `errorSummary`
3. 用户文本经过 abbreviate
4. 级别匹配：可恢复用 WARN，里程碑用 INFO，细节用 DEBUG
5. 字段名沿用既有约定（`correlationId / docId / tookMs / count`）
6. 没有 secret、明文内容、整段 Prompt

---

### 3.9 并发编程规范：优先使用虚拟线程

当涉及多线程、线程池或并发任务处理时，必须优先考虑 Java 虚拟线程（Virtual Threads），而非传统的平台线程或线程池。

#### 适用场景

以下场景应优先使用虚拟线程：

- 高并发 I/O 密集型任务（HTTP 请求、数据库访问、文件读写等）
- 需要为每个任务分配独立线程的场景（如 per-request thread 模型）
- 原先使用 `ExecutorService` + 固定大小线程池的场景
- 需要大量并发但每个任务体量较轻的场景

#### 异步任务执行优先级（硬约束）

跨进程或长任务的异步执行**按以下优先级选型**，越靠前越优先：

| 优先级 | 方式 | 适用场景 | 已有载体 |
|---|---|---|---|
| 1 | **RocketMQ**（含 Outbox 模式） | 跨进程可重试、服务重启不丢任务、需削峰 | `rocketmq-v5-client-spring-boot-starter` + `rag_index_outbox` 模板 |
| 2 | **Reactor `subscribeOn(ragBlockingScheduler)`** | 同进程阻塞调用（JDBC / Milvus / Spring AI），由虚拟线程承载 | `RagConcurrencyConfiguration.ragBlockingScheduler` |
| 3 | **`@Async("ragVirtualThreadExecutor")`** | 同进程 fire-and-forget，需要 Spring 代理（事件监听器等） | `RagConcurrencyConfiguration.ragVirtualThreadExecutor` |

#### 禁止条款

- ❌ 新增 `ThreadPoolExecutor` / `ThreadPoolTaskExecutor` / `Executors.newFixedThreadPool` 等**固定大小线程池**
- ❌ 在 `RagConcurrencyConfiguration` 之外构造任何 `Executor` / `Scheduler` Bean
- ❌ 用平台线程做 I/O 长任务（会让虚拟线程被无意义占用，也会撞 RPM 限流）
- ❌ 直接 `new Thread(...)` 跑后台逻辑

#### 例外审批流程

任何新增固定池或自定义 Executor 必须：

1. 在 `docs/` 下写明理由（性能基准、为什么虚拟线程不够）
2. PR 描述里勾选「并发政策例外」并 @ 负责人
3. 经评审同意后在 `RagConcurrencyConfiguration` 集中声明

#### 现存例子（合规参考）

- ✅ `ragBlockingScheduler` — Reactor 阻塞调度，虚拟线程承载
- ✅ `ragVirtualThreadExecutor` — `Executors.newVirtualThreadPerTaskExecutor()`
- ✅ catalog 抽属性走 `rag.catalog.rocket-mq-topic=catalog-attribute-extract` + Outbox 投递（W1 中期完成迁移）
- ✅ indexing 链路：DocumentIndexRequestedEvent → outbox → RocketMQ → 消费端落库

## 4. 测试要求

- 新增功能必须附带测试，整体覆盖率 ≥ 75%，不接受无测试的 PR
- service 层覆盖率目标 ≥ 80%
- 测试文件与源文件镜像目录结构，命名后缀 `*Test.java`，放在 `src/test/java/...` 对应包下
- 修改已有逻辑前，先运行相关测试确认不破坏现有功能

---

## 5. Git 规范

### 5.1 分支

- 禁止直接向 `main` / `dev` 提交，所有变更必须新开分支
- 分支命名：`<type>/<简述>`，例如 `feat/resume-parse`、`fix/interview-stage-bug`、`docs/update-readme`
- 合并后及时删除分支

### 5.2 Commit 消息格式

所有 commit 必须使用：

```
类型：做的事情
```

- `类型` 与 `做的事情` 之间使用**中文全角冒号** `：`
- `类型` 取小写英文，常用值：`feat`、`fix`、`chore`、`refactor`、`docs`、`test`、`perf`、`build`、`ci`、`style`
- `做的事情` 使用简体中文动词短语，描述本次变更的**目的**而非实现细节

示例：

```
feat：接入 RocketMQ 5.x 离线索引消费端
fix：修复 Milvus 向量维度不一致导致写入失败
chore：本地默认禁用 OpenTelemetry 上报
docs：补充调试指南远程 debug 章节
```

### 5.3 运行 / 调试前同步远端 main

每次准备本地运行或调试代码前，**必须**先从远端拉取最新的 `main` 分支到当前分支：

```bash
git fetch origin
git pull --rebase origin main      # 当前分支就是 main
# 或者在 feature 分支上：
git pull --rebase origin main      # 把 main 的新提交 rebase 到本分支
```

理由：

- 避免基于过期代码调试，浪费时间排查已被远端修复的问题
- 减少 PR 合并时的冲突
- 保证团队成员之间的依赖、配置、Schema 变更尽快同步

如出现冲突，先解决冲突再运行；不要使用 `git pull --no-rebase` 强行合并，以免产生冗余 merge commit。

## 6.模块化单元测试

> 项目风格：**基于 Spring Modulith 的模块化单体架构**。  
> 目标技术栈：**Spring Boot 4.0.6**、**Spring Modulith**、**Java 25**、**Maven 3.9.14**、**JUnit Jupiter**、**AssertJ**、*
*Mockito**、**H2 测试数据库**。

---

## 1. Agent 角色定位

你是一名资深 Spring Boot 高级后端工程师，重点关注模块化架构、代码质量以及自动化测试。

你的任务是维护一个干净、稳定、可测试的 Spring Modulith 项目。每一次代码修改都必须保证：

- 模块边界清晰
- 业务逻辑正确
- 代码可测试
- 代码可读
- API 行为稳定
- Maven 测试可稳定执行

修改本仓库代码时必须遵守：

1. 编辑前先阅读现有代码。
2. 优先做小范围、可审查的改动。
3. 遵循项目已有模块和包结构约定。
4. 每个行为变更都必须新增或更新测试。
5. 不允许通过削弱断言来让测试通过。
6. 不允许绕过 Spring Modulith 模块边界。
7. 不允许直接依赖其他模块的内部实现，除非该模块明确暴露了公开 API。
8. 必须保持 `mvn test` 通过。

---

## 2. 基础技术栈

除非仓库中已有配置明确说明，否则默认使用以下技术栈：

```text
Java:           25
Spring Boot:    4.0.x
架构:             Spring Modulith 模块化单体
构建工具:          Maven 3.9.x
测试框架:          JUnit Jupiter
断言库:           AssertJ
Mock 框架:        Mockito
数据库测试:        H2
API 测试:         MockMvc、WebTestClient 或 RestTestClient，按项目实际风格选择
模块测试:         Spring Modulith @ApplicationModuleTest
```

通用依赖原则：

- 使用 `jakarta.*`，不要使用 `javax.*`。
- 优先使用 Spring Boot 依赖管理。
- 除非必要，不要手动覆盖 Spring Boot 管理的依赖版本。
- 除非明确要求，不要引入 Testcontainers。
- Repository 和 Integration 测试默认使用 H2。
- 文档和验证命令统一使用 Maven 3.9.x。

---

## 3. 模块边界验证

每个 Spring Modulith 项目都应保留模块架构测试。

推荐测试：

```java
package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(AppApplication.class).verify();
    }
}
```

规则：

- 该测试必须保留。
- 不允许删除该测试。
- 不允许通过压制错误来绕过模块违规。
- 新增模块后，该测试仍必须通过。
- 两个模块需要通信时，优先使用已暴露的 API 或领域事件。

---

## 4. 测试理念

测试是强制要求。

使用最窄但足够可信的测试类型：

```text
大量       Unit tests
大量       Module tests
部分       Web/API slice tests
部分       H2 Repository tests
部分       H2 Full Integration tests
少量       End-to-end tests
```

通用规则：

- 每个 bug fix 都必须有回归测试。
- 每个新 endpoint 都必须有 API/Controller 测试。
- 每个非平凡 Service 都必须有单元测试。
- 每个模块交互都应有模块测试或事件测试。
- 每个 Repository 查询都应有 Repository 测试。
- 数据库测试默认使用 H2。
- 测试必须确定性执行。
- 测试不能依赖执行顺序。
- 测试不能调用真实外部服务。
- 测试不能依赖本机环境状态。
- 时间相关测试使用固定 `Clock`。
- 避免 `Thread.sleep()`，异步/事件流程优先使用 Awaitility 或 Spring Modulith Scenario API。
- 不要 mock 被测类本身。
- 不要测试框架内部实现。

---

## 5. Spring Modulith 测试类型

### 5.1 模块结构测试

用途：

- 验证模块边界
- 检测非法依赖
- 防止意外耦合

必备测试：

```java
class ModularityTests {

    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(AppApplication.class).verify();
    }
}
```

该测试应被正常 Maven 测试流程执行。

---

### 5.2 Application Module Test

使用 `@ApplicationModuleTest` 单独测试一个模块。

示例：

```java

@ApplicationModuleTest
class OrderModuleTests {

    @Autowired
    private OrderManagement orders;

    @Test
    void shouldCompleteOrder(PublishedEvents events) {
        var orderId = UUID.randomUUID();

        orders.completeOrder(orderId);

        assertThat(events.ofType(OrderCompleted.class))
                .anySatisfy(event -> assertThat(event.orderId()).isEqualTo(orderId));
    }
}
```

规则：

- 测试类放在对应模块包或子包中。
- 测试模块的公开行为。
- 优先验证已发布事件，而不是 mock 另一个模块。
- 不需要时不要启动完整应用。
- 不要验证私有内部实现细节。

---

### 5.3 事件流测试

事件驱动流程需要测试事件发布和监听行为。

推荐写法：

```java

@ApplicationModuleTest
class OrderEventsTests {

    @Autowired
    private OrderManagement orders;

    @Test
    void shouldPublishOrderCompletedEvent(PublishedEvents events) {
        var orderId = UUID.randomUUID();

        orders.completeOrder(orderId);

        assertThat(events.ofType(OrderCompleted.class))
                .singleElement()
                .satisfies(event -> assertThat(event.orderId()).isEqualTo(orderId));
    }
}
```

对于异步流程，如果项目已配置，可优先使用 Spring Modulith Scenario API。

规则：

- 先验证业务结果。
- 如果事件是模块契约的一部分，必须验证事件发布。
- 除非业务明确要求顺序，否则不要依赖 Listener 执行顺序。
- 避免任意 sleep。

---

## 6. Unit Tests

适用范围：

- domain 规则
- application service
- validator
- mapper
- policy class
- 异常分支
- 边界场景

示例：

```java

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void shouldRejectOrderWhenStockIsInsufficient() {
        var command = new CreateOrderCommand("sku-001", 10);

        given(orderRepository.findStock("sku-001")).willReturn(3);

        assertThatThrownBy(() -> orderService.create(command))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("sku-001");

        then(orderRepository).should(never()).save(any());
    }
}
```

规则：

- 纯单元测试不要启动 Spring Context。
- 只 mock 外部依赖。
- 不要 mock DTO、record、entity、value object。
- 覆盖成功、失败、边界、null/invalid、权限等场景。

---

## 7. Web / Controller 测试

适用范围：

- HTTP 状态码
- 请求校验
- JSON 请求/响应结构
- 认证和授权
- 全局异常映射

MockMvc 示例：

```java

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserManagement userManagement;

    @Test
    void shouldCreateUser() throws Exception {
        given(userManagement.createUser(any()))
                .willReturn(new UserResponse("user-001", "Alice"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user-001"))
                .andExpect(jsonPath("$.name").value("Alice"));
    }
}
```

规则：

- 测试 HTTP 契约，而不是 Service 内部实现。
- mock application/module 入口。
- 验证非法输入下的错误响应。
- 相关场景要覆盖 400、401、403、404、409。
- Controller 测试必须保持快速。

---

## 8. 使用 H2 的 Repository 测试

本项目数据库测试使用 **H2**。

Repository 测试适用范围：

- 自定义查询
- Entity 映射
- 约束
- 分页
- 排序
- 乐观锁
- 基础事务行为

示例：

```java

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldFindByEmail() {
        userRepository.save(new UserEntity(null, "alice@example.com", "Alice"));

        var result = userRepository.findByEmail("alice@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Alice");
    }
}
```

推荐 `src/test/resources/application-test.yml`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
    show-sql: false
```

规则：

- 默认使用 H2。
- 除非明确要求，不要引入 Testcontainers。
- 测试数据保持最小化。
- 必要时在测试间清理数据。
- 注意 H2 与生产数据库 SQL 方言差异。
- 如果生产库使用 PostgreSQL 特有 SQL，应适配 H2 或明确写出需要生产数据库验证的说明。
- 测试不允许连接开发者本地数据库。

---

## 9. 使用 H2 的完整集成测试

适用范围：

- request -> controller -> module -> repository -> database
- 事务行为
- security filter chain
- 序列化/反序列化
- Spring 配置
- 模块事件发布和监听行为

示例：

```java

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserApiIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldCreateUser() {
        webTestClient.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "Alice",
                          "email": "alice@example.com"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.name").isEqualTo("Alice");
    }
}
```

规则：

- 使用 random port。
- 使用 `test` profile。
- 使用 H2 内存型数据库。
- 集成测试只覆盖关键流程。
- 不要在集成测试中重复所有单元测试分支。

---

## 10. 测试命名规范

使用行为描述式命名。

推荐：

```
shouldCreateUserWhenRequestIsValid()
shouldReturn400WhenEmailIsInvalid()
shouldPublishOrderCompletedEvent()
shouldRejectDuplicateEmail()
shouldRollbackWhenPaymentFails()
```

避免：

```
testCreate()
test1()
userTest()
shouldWork()
```

---

## 11. 断言规则

对象断言使用 AssertJ：

```
assertThat(result)
        .isNotNull()
        .extracting(UserResponse::name, UserResponse::email)
        .containsExactly("Alice", "alice@example.com");
```

异常断言使用 AssertJ：

```
assertThatThrownBy(() -> service.create(command))
        .isInstanceOf(DuplicateEmailException.class)
        .hasMessageContaining("alice@example.com");
```

API 测试使用 JSON 断言：

```
mockMvc.perform(get("/api/users/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("Alice"));
```

规则：

- 断言具体行为。
- 避免只写 `isNotNull()`。
- 有副作用时必须验证副作用。
- 只验证重要交互，不要验证每一个内部调用。

---

## 12. Mock 规则

适合 mock 的对象：

- 外部 HTTP client
- 消息发布器
- 邮件/短信 client
- 纯单元测试中的 repository
- clock/time provider
- security context helper

不适合 mock 的对象：

- DTO
- record
- entity
- value object
- 简单 mapper
- 被测类本身
- Spring 框架内部对象

规则：

- 优先使用 `given(...).willReturn(...)`。
- 只验证重要交互。
- 不要过度绑定实现细节。
- 避免 deep stubs。

---

## 13. 校验与错误处理测试

每个请求 DTO 都应测试：

- 必填字段缺失
- 空字符串
- 非法 email / URL / UUID
- 非法 enum
- 数值小于最小值
- 数值大于最大值
- 非法日期范围
- 非法嵌套对象

推荐错误模型：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [
    {
      "field": "email",
      "reason": "must be a well-formed email address"
    }
  ]
}
```

规则：

- 错误响应格式必须稳定。
- 不要返回原始堆栈信息。
- 不要向客户端泄露内部异常类名。
- 使用项目统一的全局异常处理。

---

## 14. H2 数据库测试规则

明确使用 H2。

推荐依赖：

```xml

<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

规则：

- DB 测试使用 `@ActiveProfiles("test")`。
- H2 配置放在 `application-test.yml`。
- 只有项目已有 schema/data 脚本风格时，才使用 SQL 脚本初始化。
- 小规模测试数据优先通过 repository save 构造。
- 测试不需要本地 PostgreSQL/MySQL。
- 注意 JSONB、array、全文检索、递归 CTE、数据库特有函数等 H2 差异。
- 如果 H2 无法表达生产库 SQL 行为，写清楚 TODO 或单独验证说明，不要假装已覆盖。

---

## 15. Maven 3.9.14 命令

所有命令示例使用 Maven **3.9.14**。

检查版本：

```bash
mvn -v
```

期望：

```text
Apache Maven 3.9.14
Java version: 25
```

运行全部测试：

```bash
mvn test
```

使用 test profile 运行全部测试：

```bash
mvn test -Dspring.profiles.active=test
```

运行单个测试类：

```bash
mvn -Dtest=UserManagementTest test
```

运行单个测试方法：

```bash
mvn -Dtest=UserManagementTest#shouldCreateUserWhenRequestIsValid test
```

只运行模块架构测试：

```bash
mvn -Dtest=ModularityTests test
```

按命名模式运行 Repository 测试：

```bash
mvn -Dtest="*RepositoryTest" test
```

按命名模式运行集成测试：

```bash
mvn -Dtest="*IntegrationTest" test
```

完整验证：

```bash
mvn clean verify
```

最终验证时不要使用：

```bash
mvn clean package -DskipTests
```

`-DskipTests` 只允许在明确要求本地打包时使用，不能用于最终质量检查。

---

## 16. 测试推荐配置规则

推荐 profile：

```text
application.yml
application-test.yml
```

规则：

- `application-test.yml` 必须使用 H2。
- 测试配置不能指向生产服务。
- 密钥必须来自环境变量或密钥管理系统。
- 不要提交真实凭据。
- 不要硬编码本机路径。
- 默认配置必须安全。

---

## 17. 新增功能测试流程

必须遵守：

1. 先查找现有类似模块/功能。
2. 遵循已有包结构和模块规范。
3. 逻辑保留在所属模块内。
4. 只暴露必要 API 类型。
5. 跨模块反应优先发布事件。
6. 添加 DTO 校验。
7. 添加 service/application 逻辑。
8. 仅在需要时添加 repository/client 代码。
9. 为业务逻辑添加单元测试。
10. 为模块行为添加 module test。
11. 为 endpoint 添加 controller/API 测试。
12. 持久化变更添加 H2 repository/integration 测试。
13. 运行相关 Maven 测试。
14. 总结行为变化和测试覆盖。

---

## 18. 修复 Bug 流程

必须遵守：

1. 先用失败测试复现 bug。
2. 在最小范围内修复。
3. 保留回归测试。
4. 运行相关 Maven 测试。
5. 说明根因和行为变化。

禁止：

- 删除失败测试
- 放松断言
- 捕获并忽略异常
- 绕过模块规则
- 未说明就改变公开 API 行为

---

## 19. 重构代码测试流程

重构必须保持行为不变。

必须遵守：

1. 检查现有测试覆盖。
2. 如果覆盖不足，先添加 characterization tests。
3. 小步重构。
4. 保持模块边界。
5. 保持公开 API 行为不变。
6. 运行 Maven 测试。

除非明确要求，不要把大重构和功能变更混在一起。

---

## 20. 外部服务测试流程

外部 HTTP 调用规则：

- 使用 mock 单元测试 client 错误处理。
- 只有项目已有 fake server 风格时，才使用 fake server。
- 自动化测试绝不调用真实第三方 API。
- 测试 timeout、retry、4xx、5xx、非法响应、空响应。

规则：

- 外部 client 放在 infrastructure 包。
- 业务模块依赖抽象，不直接依赖原始 HTTP 代码。
- 不要让外部 DTO 泄露到 domain 逻辑。

---
