# OpenRouter (AI 开源路由器)

OpenRouter 是一个基于 **Spring WebFlux** 打造的极速、高可用、可拓展的大语言模型 (LLM) API 路由网关。它对外提供标准兼容 OpenAI 规范的 API 接口，对内能将请求智能分配给云端的多个异构 AI 厂商（例如 OpenAI, DeepSeek, Google Gemini 等），以实现最低接口延迟、高可用容灾以及精准颗粒度的调用监控。

## ✨ 核心特性

- **🚀 统一下游 API**：对外暴露出 100% 兼容 OpenAI 格式的 `/v1/chat/completions` 接口，无缝接入主流前端生态（如 NextChat, Chatbox, OpenAI 原生 SDK 等）。
- **🧠 智能动态打分路由**：基础权重叠加**健康度（近期失败率）、延迟、负载**三项归一化子分计算综合得分，得分有界、不会出现负分或量纲失衡。指标按 **(渠道, 模型)** 分桶，避免同一渠道下快慢模型互相污染；无样本的渠道走"冷启动中性值"，既不会天然最优也不会被误判为最慢。
- **⚖️ 可热更的打分参数**：健康/延迟/负载权重、延迟与并发参考值、EMA 平滑系数、失败率衰减、冷启动分数、样本门槛、探索概率全部可在管理页调整，保存即生效、无需重启。
- **🛡️ 自动故障屏蔽与转移 (Failover)**：当选中的最优节点出现网络超时、连接挂断或限流（429/5xx）时，系统会自动排除该节点并在剩余候选中重新打分，平滑顺延给备用节点，用户端几乎"无感知秒切"。
- **👁️ 模型能力嗅探**：自带请求结构感知器，自动解析出多模态（视觉图片和文件支持）等场景，并在实际拉起路由前过滤掉不支持相关能力的"偏科"渠道。
- **🎓 导师规则 (Mentor Rule)**：极具新意的硬核策略。在新开会话的第一句（系统检测出无记忆上下文）遇到时，可以直接强行重定向到指定的全能高智商"导师模型"（如 `gemini-2.5-flash-preview` / `gpt-4o`），实现最严谨准确的第一次破冰回答。
- **📐 分层指标体系**：内存指标只服务实时打分（O(1) 写入、含时间衰减），看板的分位数（P50/P95）与首包延迟（TTFT）直接从 SQLite 精确统计。两套口径各自准确、互不将就，也不会因为重启而产生展示漂移。
- **📊 极客级全链路观测大盘**：内置 Admin 面板，支持秒级"全生命周期"请求回访，可精准追溯每一路 API 的 `prompt` / `completion` Token 消耗、尝试级延迟分布与首包延迟。
- **📈 智能大盘趋势归档**：引入每日自动切片归档机制 (`daily_stats`)，将海量原始请求日志浓缩为轻量级趋势报表，支持跨度数月的秒级历史用量回溯分析。
- **⚡ 零阻塞/低延迟持久化架构**：拥抱 `Project Reactor + Netty` 底层架构，配合基于 `Schedulers.boundedElastic()` 的异步 `SQLite` 存储层。所有计费与监控落盘均为"旁路非阻塞"，确保网关吞吐性能不受磁盘 I/O 波动影响。
- **🔒 持久化动态配置中心**：支持通过管理页实时开关渠道、调整权重与路由参数，修改后的配置自动持久化到 `backend/config/channels.json`（原子写入），重启后依然生效。
- **🛡️ 开机预热 (Zero-Warmup)**：启动时由 `CommandLineRunner` 从历史日志重建各 (渠道, 模型) 的延迟/首包 EMA 与调用计数，做到"开机即巅峰"；失败率刻意不预热，重启即翻篇。
- **📡 实时日志推送**：基于 WebSocket 的实时日志终端，运维人员可在 Dashboard 中实时查看请求全链路追踪信息，快速定位问题。

## 🛠️ 技术栈

| 类别 | 技术选型 |
|------|----------|
| 基础运行环境 | Java 17+, Maven |
| 核心服务框架 | Spring Boot 3.3.6, Spring WebFlux, Project Reactor |
| 数据库与持久化 | SQLite (原生 JdbcTemplate + 复合覆盖索引 + 跨天混合查询算法) |
| 网络通信架构 | WebClient (支持 Reactive 流式 SSE 深度监听与无损代理转发) |
| 前端控制台 | React 19 + Vite + 原生 CSS（`frontend/`，开发态经 Vite 代理访问后端） |
| 实时通信 | WebSocket (日志推送) |

## 📂 项目结构

项目分为 `backend`（Spring Boot 后端）与 `frontend`（React 控制台）两个目录，`docs/` 存放设计文档：

```
openrouter/
├── docs/
│   ├── routing-score.md                     # 路由打分说明（指标、公式、权重配置、调参）
│   └── routing-metrics-redesign.md          # 指标体系重构设计稿
├── backend/                             # Spring Boot 后端
│   ├── pom.xml
│   ├── config/channels.json             # 运行时配置（渠道/模型/鉴权/打分参数）
│   └── src/main/
│       ├── java/com/openrouter/
│       │   ├── OpenRouterApplication.java       # 应用入口
│       │   ├── adapter/                         # 适配器层
│       │   │   ├── LlmClientAdapter.java        # LLM 客户端适配器接口
│       │   │   ├── ModelRoutingStrategy.java    # 路由策略接口
│       │   │   └── impl/
│       │   │       ├── DynamicModelRoutingStrategy.java  # 动态打分路由核心实现
│       │   │       ├── OpenAiLlmAdapter.java    # OpenAI 协议适配器
│       │   │       └── GeminiLlmAdapter.java    # Gemini 协议适配器
│       │   ├── config/                          # 配置层
│       │   │   ├── ChannelConfig.java           # 单个渠道声明
│       │   │   ├── ModelEntry.java              # 模型声明与能力
│       │   │   ├── ChannelConfigStore.java      # channels.json 唯一事实源（热更 + 原子落盘）
│       │   │   ├── RoutingConfig.java           # 打分权重等可调参数
│       │   │   ├── RouterProperties.java        # 网关级配置（引导值）
│       │   │   ├── ModelCapabilitiesProperties.java  # 模型能力注册表
│       │   │   ├── AdminAuthFilter.java         # 管理页鉴权过滤器
│       │   │   ├── WebClientConfig.java         # WebClient 配置
│       │   │   └── WebSocketConfig.java         # WebSocket 配置
│       │   ├── controller/                      # 控制器层
│       │   │   ├── LlmRouterController.java     # 核心 Chat 接口
│       │   │   ├── AdminController.java         # Dashboard / 配置 / 统计 API
│       │   │   ├── RequestLogController.java    # 请求日志查询 API
│       │   │   └── ModelsController.java        # /v1/models 接口
│       │   ├── filter/
│       │   │   └── ApiKeyAuthFilter.java        # API Key 鉴权过滤器
│       │   ├── handler/
│       │   │   └── WebSocketLogHandler.java     # WebSocket 日志处理器
│       │   ├── metrics/                         # 指标采集
│       │   │   ├── MetricsRegistry.java         # 指标注册中心（含全局大盘计数）
│       │   │   ├── ModelMetrics.java            # 渠道级 + (渠道,模型) 级指标状态机
│       │   │   ├── ErrorKind.java               # 错误分类（鉴权/限流/超时/网络…）
│       │   │   └── MetricsInitializer.java      # 启动预热初始化器
│       │   ├── model/                           # 数据模型
│       │   │   ├── ChatCompletionRequest.java
│       │   │   ├── ChatCompletionResponse.java
│       │   │   └── ChatCompletionMessage.java
│       │   ├── service/                         # 业务服务层
│       │   │   ├── LlmRouterService.java        # 路由核心服务（选路 + 故障转移 + 收尾落库）
│       │   │   ├── UsageDatabaseService.java    # 使用量持久化服务（含补列迁移）
│       │   │   ├── DailyStatsService.java       # 每日统计归档服务
│       │   │   └── RequestCapabilityDetector.java  # 请求能力检测器
│       │   └── trace/                           # 全链路追踪
│       │       ├── RequestTraceContext.java     # 请求追踪上下文
│       │       ├── TraceEvent.java              # 追踪事件
│       │       └── TraceLogger.java             # 追踪日志门面
│       └── resources/
│           ├── application.yaml                 # 端口 / 引导值配置
│           ├── seed-channels.json               # channels.json 首次生成模板
│           └── static/index.html                # 旧版单文件管理台（整份已注释停用）
└── frontend/                            # React 控制台 (Vite)
    ├── vite.config.js                   # 开发服务器代理：/v1、/api -> localhost:10086
    ├── package.json
    └── src/
        ├── App.jsx                      # 极简 hash 路由与全局轮询
        ├── api.js                       # fetch 封装 + 日志 WebSocket
        ├── pages/                       # Dashboard / Settings / BasicSettings / RoutingParams / Logs / RequestLogs / Login
        └── components/                  # 渠道卡片、侧边栏、统计卡等
```

## 📦 快速启动

### 1. 环境准备

确保机器已正确配置：
- Java 17+ 运行环境
- Maven 构建工具
- Node.js 20.19+ / 22.12+（仅控制台需要，受 Vite 版本要求）

### 2. 下载代码与构建

```bash
git clone <本仓库地址>
cd openrouter
mvn clean package -DskipTests        # 根目录的聚合 pom 会自动构建 backend
# 或：cd backend && mvn clean package -DskipTests
```

### 3. 配置你的渠道与密钥

渠道与模型声明保存在 `backend/config/channels.json`（首次启动会自动从内置模板生成），也可以登录管理页在「渠道与模型」页可视化编辑，保存后热生效、无需重启：

```json
{
  "settings": {
    "apiKeyEnabled": true,
    "apiKey": "sk-your-gateway-key",
    "adminPassword": "your-admin-password"
  },
  "channels": [
    {
      "id": "deepseek",
      "type": "openai",
      "baseUrl": "https://api.deepseek.com",
      "apiKey": "sk-your-deepseek-key",
      "baseWeight": 120,
      "enabled": true
    }
  ],
  "models": [
    {
      "name": "deepseek-chat",
      "channels": ["deepseek"],
      "capabilities": { "vision": false, "functionCalling": true, "longContext": false }
    }
  ]
}
```

- `channels`：上游节点本身。`baseUrl` 是上游 API 的根地址，网关会按渠道协议在其后拼接具体路径（`openai` 协议拼 `/chat/completions`，`gemini` 协议拼 `/v1beta/models/{model}:streamGenerateContent`），因此按上游文档决定是否带 `/v1`（如 `https://api.deepseek.com` 与 `https://xxx/v1` 都是合法写法）；`baseWeight` 是该渠道的容量/优先级上限，越大越优先被调度。
- `models`：模型声明，通过 `channels` 引用与节点多对多关联，`capabilities` 声明能力边界（路由时会过滤能力不足的渠道）。
- `settings`：网关鉴权密钥、管理页登录密码、导师模型，以及 `routing`（打分权重等参数）。这些字段也可以只写在 `application.yaml` 里作为首次部署的引导值，一旦在管理页保存过就以 `channels.json` 为准。

### 4. 运行服务

```bash
# 在仓库根目录执行（数据库用的是相对路径 backend/openrouter.db）
java -jar backend/target/openrouter-0.0.1-SNAPSHOT.jar
```

项目默认运行于 **10086** 端口。首次启动会自动创建/迁移数据库表结构（新增字段采用幂等补列，不会影响既有数据）。

### 5. 启动控制台（可选）

后端不再托管管理台页面（`static/index.html` 为已停用的旧版），请单独启动 React 控制台：

```bash
cd frontend
npm install
npm run dev        # 访问 http://localhost:5173，接口经 Vite 代理到 10086
```

## 🕹️ 接入与使用指南

### 1. 发起聊天请求 (兼容 OpenAI 规范)

你可以直接把启动后的地址填进第三方软件里面充当官方节点，也可以通过命令行直接测试：

```bash
curl http://localhost:10086/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-your-secret-key" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "帮我写一首关于春天的现代诗"}],
    "stream": true
  }'
```

> **Tip:** 将请求 JSON 里的 `model` 字段设为 `auto`，路由器会按当前评分选出最优**渠道**，再取该渠道模型列表中的第一个模型响应（多模态请求取第一个具备 vision 能力的模型）。如果你指定了具体模型名，系统只会在声明支持该模型且已启用的渠道之间打分选择，不会替换成别的模型。

### 2. 查看可用模型列表

```bash
curl http://localhost:10086/v1/models \
  -H "Authorization: Bearer sk-your-secret-key"
```

返回结果包含一个特殊的 `auto` 模型，以及各渠道配置的模型列表。

### 3. 访问控制台与 Dashboard 面板

启动前端后访问 `http://localhost:5173`，登录密码即「基础设置」中的管理页密码。

#### Dashboard 功能一览

- **全局统计卡片**：活跃渠道数、全链路平均响应、**全链路 P95**、Token 消耗（输入/输出占比）、请求成功率
- **渠道矩阵**：每个渠道的实时评分、**近期失败率**、渠道平均延迟（诊断口径）、当前并发、Token 分账，以及**按模型拆分的延迟与首包延迟**
- **同模型渠道对比**：选择模型与时间范围，查看各渠道的**尝试延迟 P50/P95 与首包延迟 P50/P95**（含样本数，样本不足会明确标注）
- **时间范围切换**：实时模式（2 秒刷新）/ 历史 1h / 24h / 7d（P95 由数据库精确统计）
- **实时日志终端**：WebSocket 推送的全链路追踪日志，支持展开/收起
- **系统配置 → 路由调参**：健康/延迟/负载权重、参考值、EMA 系数、衰减与冷启动策略、探索概率（独立子页面）

## 🏗️ 代码与架构进阶亮点

### 1. 精细化并行的 Token 计费策略

系统在 WebFlux 流式报文的监听口植入正则解析与事件监控节点，精确分别抓取 `prompt` 和 `completion` token 且在不影响性能的前提下汇总。

### 2. Hybrid High Performance Aggregation (混合聚合查询)

独创的 `Range Stats` 算法。在跨天报表时，系统能自动将历史整日数据从 `daily_stats` 抽离，并实时计算起始/末尾残缺日的原始 `request_log`。这种"空间换时间"的策略使得百万级数据的报表呈现依然能维持在毫秒量级。

### 3. Zero Blocking Data Layer (零阻塞账本)

系统将所有计费、耗时检测的落盘 SQL 直接推入缓存的 `Schedulers.boundedElastic()` 线程池中排队，主路由转发与代理通道实现 100% 旁路解耦隔离。

### 4. Dual-Layer Logging (双层审计日志)

区分 `request_log` (呈现给用户的最终请求) 与 `model_call_log` (每一次底层的真实尝试，含首包延迟)。这种设计使得系统能清晰记录下由于 Failover 机制引发的所有"重试心路历程"，让运维不再有盲区。

### 5. 优雅重启恢复策略

采用 `MetricsInitializer`，启动时按 (渠道, 模型) 反向预热 SQLite 中的历史成功记录，自动重建内存里的延迟/首包 EMA 与调用计数；失败率不预热，重启即翻篇。

### 6. 智能路由打分

> 打分公式、三项子分的计算口径、权重参数表与调参示例，详见 **[docs/routing-score.md](docs/routing-score.md)**。
> （调整权重请走管理页「系统配置 → 路由调参」）

## 📊 数据库设计

### 核心表结构

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `request_log` | 最终交付给用户的请求全链路数据 | `total_duration_ms`、`ttft_ms`、`retry_count`、`trace_events`、tokens |
| `model_call_log` | 每一次底层模型的调用尝试（含重试与失败） | `duration_ms`、`ttft_ms`、`success`、`error_msg` |
| `daily_stats` | 每日统计归档，加速跨天查询 | `stat_date`、`avg_duration`、tokens、请求数 |

> `ttft_ms`（首包延迟）只在流式请求成功时写入，非流式与首包前失败为 `NULL`；老库启动时会自动补列，历史行保持 `NULL`。

### 性能索引

- `idx_request_created_at`：请求日志时间索引，支撑时间范围聚合查询
- `idx_call_composite_stats`：(channel_id, success, created_at)，支撑启动预热与错误统计
- `idx_call_channel_model_time`：(channel_id, model, created_at)，支撑按模型的分位数与首包延迟查询

## 🔌 API 接口文档

### Chat Completions

```
POST /v1/chat/completions
```

兼容 OpenAI Chat Completions API，支持流式 (SSE) 和非流式两种模式。

### Models

```
GET /v1/models
```

返回可用模型列表，包含特殊的 `auto` 模型。

### Admin API

除 `login` 外均需在请求头携带 `Authorization: <管理页密码>`。

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/admin/login` | POST | 管理页登录 |
| `/api/admin/verify` | GET | 校验登录态 |
| `/api/admin/dashboard` | GET | Dashboard 全量数据（含生效的路由参数、分模型指标） |
| `/api/admin/channels-config` | GET / PUT | 读取 / 全量替换渠道与模型配置 |
| `/api/admin/basic-settings` | GET / PUT | 读取 / 更新基础设置；「路由调参」子页面复用该接口提交 `routing` 段 |
| `/api/admin/channels/{id}/toggle` | POST | 开关指定渠道 |
| `/api/admin/channels/{id}/weight/{val}` | PUT | 调整渠道基础权重 |
| `/api/admin/stats/range` | GET | 指定时间范围统计（含 P95） |
| `/api/admin/model-comparison` | GET | 同模型跨渠道的延迟与首包分位数对比 |
| `/api/admin/request-logs` | GET | 请求日志列表（分页/筛选） |
| `/api/admin/request-logs/{id}` | GET | 单条请求详情（含 trace_events、原始请求响应） |
| `/api/admin/logs/ws` | WebSocket | 实时日志推送 |

## 🚀 生产环境建议

1. **API Key 安全**：务必在管理页「基础设置」中配置强密钥的网关鉴权（或选择无需 key），避免网关被滥用
2. **渠道冗余**：建议至少配置 2 个以上渠道，确保 Failover 机制生效
3. **模型能力声明**：准确配置各模型的 `vision` / `function-calling` 能力，避免路由到不支持的模型
4. **监控告警**：关注 Dashboard 中的失败率与全链路 P95，及时发现上游异常；行为异常时按 [docs/routing-score.md](docs/routing-score.md) 第 9 节排查选路原因
5. **参数调优**：多渠道路由参数（权重、参考值、EMA 系数）都可在「系统配置 → 路由调参」热更，调参方向见打分文档第 5 节

## 📝 更新日志(按时间升序)

- 核心路由功能：智能动态打分、自动故障转移
- 导师规则：首发询问强制路由到高智商模型
- 模型能力嗅探：自动过滤不满足请求能力的渠道
- Dashboard 可视化面板：实时监控渠道状态与用量统计
- WebSocket 实时日志推送：全链路追踪可视化
- 每日统计归档：支持历史数据秒级查询
- 前端重构为React,design风格为Linear
- 配置统一收敛到 `channels.json`（原子落盘 + 热生效），管理台迁移为独立 React 应用
- 指标与打分体系重构：指标下钻到 (渠道, 模型)、归一化打分公式、权重参数化并可热更、错误分类、冷启动中性化
- 首包延迟 (TTFT) 独立采集落库，看板新增全链路 P95 与同模型跨渠道对比

---

*试着设置 gemini 系模型作为导师模型，体验哈基米风味的活人感回复*
