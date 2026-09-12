# OpenRouter (AI 开源路由器)

OpenRouter 是一个基于 **Spring WebFlux** 打造的极速、高可用、可拓展的大语言模型 (LLM) API 路由网关。它对外提供标准兼容 OpenAI 规范的 API 接口，对内能将请求智能分配给云端的多个异构 AI 厂商（例如 OpenAI, DeepSeek, Google Gemini 等），以实现最低接口延迟、高可用容灾以及精准颗粒度的调用监控。

## ✨ 核心特性

- **🚀 统一下游 API**：对外暴露出 100% 兼容 OpenAI 格式的 `/v1/chat/completions` 接口，无缝接入主流前端生态（如 NextChat, Chatbox, OpenAI 原生 SDK 等）。
- **🧠 智能动态打分路由**：结合 **静态配置权重、模型响应延迟、并发流请求阻力、以及报错宕机惩罚** 计算出综合环境最优解，每次连接都将请求精准秒级派发至最健康的服务器。
- **🛡️ 自动故障屏蔽与转移 (Failover)**：当选中的最优节点出现网络超时、连接挂断或限流（429/500）时，系统会在底层自动重塑请求流并平滑顺延给备用同级节点，用户端几乎实现"无感知秒切"。
- **👁️ 模型能力嗅探**：自带请求结构感知器，自动解析出多模态（视觉图片和文件支持）等场景，并在实际拉起路由前过滤掉不支持相关能力的"偏科"渠道。
- **🎓 导师规则 (Mentor Rule)**：极具新意的硬核策略。在新开会话的第一句（系统检测出无记忆上下文）遇到时，可以直接强行重定向到指定的全能高智商"导师模型"（如 `gemini-2.5-flash-preview` / `gpt-4o`），实现最严谨准确的第一次破冰回答。
- **📊 极客级全链路观测大盘**：内置基于 **混合高性能数据聚合策略** 的 Admin 面板。系统支持秒级的"全生命周期"请求回访，能够精准追溯每一路 API 的 `prompt` / `completion` Token 消耗。
- **📈 智能大盘趋势归档**：引入每日自动切片归档机制 (`daily_stats`)，将亿级原始请求日志浓缩为轻量级趋势报表，支持跨度数月的秒级历史用量回溯分析。
- **⚡ 零阻塞/低延迟持久化架构**：拥抱 `Project Reactor + Netty` 底层架构，配合基于 `Schedulers.boundedElastic()` 的异步 `SQLite` 存储层。所有计费与监控落盘均为"旁路非阻塞"，确保网关吞吐性能不受磁盘 I/O 波动影响。
- **🔒 持久化动态配置中心**：支持通过 API 实时开关渠道、调整权重，修改后的配置将自动持久化至数据库，重启后依然生效。
- **🛡️ 毫秒级开机预热 (Zero-Warmup)**：采用原生 `CommandLineRunner` 预加载机制，启动即从历史日志中光速重建各通道的 EMA (指数平滑移动平均) 健康模型，做到"开机即巅峰"。
- **📡 实时日志推送**：基于 WebSocket 的实时日志终端，运维人员可在 Dashboard 中实时查看请求全链路追踪信息，快速定位问题。

## 🛠️ 技术栈

| 类别 | 技术选型 |
|------|----------|
| 基础运行环境 | Java 17+, Maven |
| 核心服务框架 | Spring Boot 3.3.6, Spring WebFlux, Project Reactor |
| 数据库与持久化 | SQLite (原生 JdbcTemplate + 复合覆盖索引 + 跨天混合查询算法) |
| 网络通信架构 | WebClient (支持 Reactive 流式 SSE 深度监听与无损代理转发) |
| 前端 Dashboard | Vue 3 + Tailwind CSS (零构建，CDN 直引) |
| 实时通信 | WebSocket (日志推送) |

## 📂 项目结构

项目分为 `backend`（Spring Boot 后端）与 `frontend`（React 前端）两个目录：

```
openrouter/
├── backend/                             # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/openrouter/
│       ├── OpenRouterApplication.java       # 应用入口
│   │   ├── adapter/                         # 适配器层
│   │   │   ├── LlmClientAdapter.java        # LLM 客户端适配器接口
│   │   │   ├── ModelRoutingStrategy.java    # 路由策略接口
│   │   │   └── impl/
│   │   │       ├── DynamicModelRoutingStrategy.java  # 动态打分路由核心实现
│   │   │       ├── OpenAiLlmAdapter.java    # OpenAI 协议适配器
│   │   │       └── GeminiLlmAdapter.java    # Gemini 协议适配器
│   │   ├── config/                          # 配置层
│   │   │   ├── RouterProperties.java        # 渠道配置属性
│   │   │   ├── ModelCapabilitiesProperties.java  # 模型能力配置
│   │   │   ├── WebClientConfig.java         # WebClient 配置
│   │   │   └── WebSocketConfig.java         # WebSocket 配置
│   │   ├── controller/                      # 控制器层
│   │   │   ├── LlmRouterController.java     # 核心 Chat 接口
│   │   │   ├── AdminController.java         # Dashboard API
│   │   │   └── ModelsController.java        # /v1/models 接口
│   │   ├── filter/
│   │   │   └── ApiKeyAuthFilter.java        # API Key 鉴权过滤器
│   │   ├── handler/
│   │   │   └── WebSocketLogHandler.java     # WebSocket 日志处理器
│   │   ├── metrics/
│   │   │   ├── MetricsRegistry.java         # 指标注册中心
│   │   │   ├── ModelMetrics.java            # 单渠道指标模型
│   │   │   └── MetricsInitializer.java      # 启动预热初始化器
│   │   ├── model/                           # 数据模型
│   │   │   ├── ChatCompletionRequest.java
│   │   │   ├── ChatCompletionResponse.java
│   │   │   └── ChatCompletionMessage.java
│   │   ├── service/                         # 业务服务层
│   │   │   ├── LlmRouterService.java        # 路由核心服务
│   │   │   ├── UsageDatabaseService.java    # 使用量持久化服务
│   │   │   ├── DailyStatsService.java       # 每日统计归档服务
│   │   │   └── RequestCapabilityDetector.java  # 请求能力检测器
│   │   └── trace/                           # 全链路追踪
│   │       ├── RequestTraceContext.java     # 请求追踪上下文
│   │       ├── TraceEvent.java              # 追踪事件
│   │       └── TraceLogger.java             # 追踪日志门面
│   └── src/main/resources/
│       ├── application.yaml                 # 渠道/密钥/端口配置
│       └── static/index.html                # 旧版管理台页面（将被 React 前端替代）
└── frontend/                            # React 前端 (Vite)
    ├── vite.config.js                   # 开发服务器代理：/v1、/api -> localhost:10086
    ├── package.json
    └── src/
```

## 📦 快速启动

### 1. 环境准备

确保机器已正确配置：
- Java 17+ 运行环境
- Maven 构建工具

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

- `channels`：上游节点本身（`baseUrl` 结尾不要带 `/v1`；`baseWeight` 越大越优先被调度）
- `models`：模型声明，通过 `channels` 引用与节点多对多关联，`capabilities` 声明能力边界（路由时会过滤能力不足的渠道）

网关鉴权密钥、管理页登录密码在管理页「基础设置」中配置；`application.yaml` 仅保留管理页登录密码作为首次部署的引导值。

### 4. 运行服务

```bash
java -jar target/openrouter-0.0.1-SNAPSHOT.jar
```

项目默认运行于 **10086** 端口。

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

> **Tip:** 将请求 JSON 里的 `model` 字段设为 `auto` 可以让路由器抛下包袱，自动根据当前最高分节点为你挑选最顶级的节点和模型响应！如果你指定模型名字，系统会寻找支持该名字的次级模型发起请求。

### 2. 查看可用模型列表

```bash
curl http://localhost:10086/v1/models \
  -H "Authorization: Bearer sk-your-secret-key"
```

返回结果包含一个特殊的 `auto` 模型，以及各渠道配置的模型列表。

### 3. 访问控制台与 Dashboard 面板

在浏览器打开管理台页面。开发期推荐使用 React 前端（在 `frontend/` 目录下执行 `npm run dev` 后访问）：

```
http://localhost:5173
```

后端在 `backend/src/main/resources/static/index.html` 仍保留一份旧版页面，可直接访问 `http://localhost:10086/index.html` 使用。

你可以直观操控启停具体某个接口渠道、调整由于特定代理商促销所引起的基础分权重变化，以及享受全局的流媒体指标检测与指定时间段的用量回溯分析。

#### Dashboard 功能一览

- **全局统计卡片**：活跃节点数、平均响应延迟、Token 消耗、请求成功率
- **渠道矩阵**：每个渠道的实时评分、延迟、错误数、并发数、Token 分账
- **时间范围切换**：实时模式（2秒刷新）/ 历史 1h / 24h / 7d
- **实时日志终端**：WebSocket 推送的全链路追踪日志，支持展开/收起

## 🏗️ 代码与架构进阶亮点

### 1. 精细化并行的 Token 计费策略

系统在 WebFlux 流式报文的监听口植入正则解析与事件监控节点，精确分别抓取 `prompt` 和 `completion` token 且在不影响性能的前提下汇总。

### 2. Hybrid High Performance Aggregation (混合聚合查询)

独创的 `Range Stats` 算法。在跨天报表时，系统能自动将历史整日数据从 `daily_stats` 抽离，并实时计算起始/末尾残缺日的原始 `request_log`。这种"空间换时间"的策略使得百万级数据的报表呈现依然能维持在毫秒量级。

### 3. Zero Blocking Data Layer (零阻塞账本)

系统将所有计费、耗时检测的落盘 SQL 直接推入缓存的 `Schedulers.boundedElastic()` 线程池中排队，主路由转发与代理通道实现 100% 旁路解耦隔离。

### 4. Dual-Layer Logging (双层审计日志)

区分 `request_log` (呈现给用户的最终请求) 与 `model_call_log` (每一次底层的真实尝试)。这种设计使得系统能清晰记录下由于 Failover 机制引发的所有"重试心路历程"，让运维不再有盲区。

### 5. 优雅重启恢复策略

采用 `MetricsInitializer`，在启动的第一毫秒即可反向预热提取最新的 SQLite 成功响应记录，自动重建并填充系统内存里的健康打分池。

### 6. 智能路由打分公式

```
最终得分 = 基础权重 - (近期错误数 × 50) - (平均延迟 × 0.05) - (当前并发数 × 5)
```

系统通过该公式为每个渠道实时打分，选出综合最优节点。

## 📊 数据库设计

### 核心表结构

| 表名 | 用途 |
|------|------|
| `request_log` | 记录最终交付给用户的请求全链路数据 |
| `model_call_log` | 记录每一次底层模型的调用细节（含重试） |
| `daily_stats` | 每日统计归档，加速跨天查询 |

### 性能索引

- `idx_request_created_at`: 请求日志时间索引，支撑时间范围聚合查询
- `idx_call_composite_stats`: 复合索引 (channel_id, success, created_at)，支撑启动预热与错误统计

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

### Admin Dashboard

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/admin/dashboard` | GET | 获取 Dashboard 全量数据 |
| `/api/admin/channels/{id}/toggle` | POST | 开关指定渠道 |
| `/api/admin/channels/{id}/weight/{val}` | PUT | 调整渠道权重 |
| `/api/admin/stats/range` | GET | 获取指定时间范围统计 |
| `/api/admin/logs/ws` | WebSocket | 实时日志推送 |

## 🚀 生产环境建议

1. **API Key 安全**：务必在管理页「基础设置」中配置强密钥的网关鉴权（或选择无需 key），避免网关被滥用
2. **渠道冗余**：建议至少配置 2 个以上渠道，确保 Failover 机制生效
3. **模型能力声明**：准确配置各模型的 `vision` / `function-calling` 能力，避免路由到不支持的模型
4. **监控告警**：关注 Dashboard 中的错误率指标，及时发现上游服务异常

## 📝 更新日志

### v0.0.1-SNAPSHOT

- 核心路由功能：智能动态打分、自动故障转移
- 导师规则：首发询问强制路由到高智商模型
- 模型能力嗅探：自动过滤不满足请求能力的渠道
- Dashboard 可视化面板：实时监控渠道状态与用量统计
- WebSocket 实时日志推送：全链路追踪可视化
- 每日统计归档：支持历史数据秒级查询

---

*试着设置 gemini 系模型作为导师模型，体验哈基米风味的活人感回复*
