# OpenRouter (AI 开源路由器)

OpenRouter 是一个基于 **Spring WebFlux** 打造的极速、高可用、可拓展的大语言模型 (LLM) API 路由网关。它对外提供标准兼容 OpenAI 规范的 API 接口，对内能将请求智能分配给云端的多个异构 AI 厂商（例如 OpenAI, DeepSeek, Google Gemini 等），以实现最低接口延迟、高可用容灾以及精准颗粒度的调用监控。

## ✨ 核心特性

- **🚀 统一下游 API**：对外暴露出 100% 兼容 OpenAI 格式的 `/v1/chat/completions` 接口，无缝接入主流前端生态（如 NextChat, Chatbox, OpenAI 原生 SDK 等）。
- **🧠 智能动态打分路由**：结合 **静态配置权重、动态网络延迟 (TTFT)、并发流请求阻力、以及报错宕机惩罚** 计算出综合环境最优解，每次连接都将请求精准秒级派发至最健康的服务器。
- **🛡️ 自动故障屏蔽与转移 (Failover)**：当选中的最优节点出现网络超时、连接挂断或限流（429/500）时，系统会在底层自动重塑请求流并平滑顺延给备用同级节点，用户端几乎实现“无感知秒切”。
- **👁️ 模型能力嗅探**：自带请求结构感知器，自动解析出多模态（视觉图片和文件支持）等场景，并在实际拉起路由前过滤掉不支持相关能力的“偏科”渠道。
- **🎓 导师规则 (Mentor Rule)**：极具新意的硬核策略。在新开会话的第一句（系统检测出无记忆上下文）遇到时，可以直接强行重定向到指定的全能高智商“导师模型”（如 `gemini-3-flash-preview` / `gpt-4o`），实现最严谨准确的第一次破冰回答。
- **📊 极客级全链路观测大盘**：内置基于 **混合高性能数据聚合策略** 的 Admin 面板。系统支持秒级的“全生命周期”请求回访，能够精准追溯每一路 API 的 `prompt` / `completion` Token 消耗。
- **📈 智能大盘趋势归档**：引入每日自动切片归档机制 (`daily_stats`)，将亿级原始请求日志浓缩为轻量级趋势报表，支持跨度数月的秒级历史用量回溯分析。
- **⚡ 零阻塞/低延迟持久化架构**：拥抱 `Project Reactor + Netty` 底层架构，配合基于 `Schedulers.boundedElastic()` 的异步 `SQLite` 存储层。所有计费与监控落盘均为“旁路非阻塞”，确保网关吞吐性能不受磁盘 I/O 波动影响。
- **🔒 持久化动态配置中心**：支持通过 API 实时开关渠道、调整权重，修改后的配置将自动持久化至数据库，重启后依然生效。
- **🛡️ 毫秒级开机预热 (Zero-Warmup)**：采用原生 `CommandLineRunner` 预加载机制，启动即从历史日志中光速重建各通道的 EMA (指数平滑移动平均) 健康模型，做到“开机即巅峰”。

## 🛠️ 技术栈
- 基础运行环境：Java 17+, Maven
- 核心服务框架：Spring Boot 3.x, Spring WebFlux, Project Reactor
- 数据库与持久化：SQLite (底层极致优化：采用原生 JdbcTemplate + 复合覆盖索引 + 跨天混合查询算法，彻底消灭全表扫描)
- 网络通信架构：WebClient (支持基于 Reactive 编程的流式 SSE 深度监听与无损代理转发)

## 📦 快速启动

1. **环境准备**：确保机器已正确配置 Java 17+ 运行环境和 Maven 构建工具。
2. **下载代码与构建**：
   ```bash
   git clone <本仓库地址>
   cd openrouter
   mvn clean package -DskipTests
   ```
3. **配置你的渠道与密钥**：
   修改 `src/main/resources/application.yaml` 文件中的以下部分配置：
   - `openrouter.api-key`: 网关对外的暴露密码，防止被滥用。
   - `openrouter.mentor-model`: 导师模型名字，处理一切首发无上下文询问。
   - `openrouter.channels`: 这是下挂模型节点，包含各厂家的 API keys、权重分配和支持的具体模型数组。
   
4. **运行服务**：
   ```bash
   java -jar target/openrouter-0.0.1-SNAPSHOT.jar
   ```
   *(项目默认运行于 `10086` 端口)*

## 🕹️ 接入与使用指南

### 1. 发起聊天请求 (兼容 OpenAI 规范)
你可以直接把启动后的地址填进第三方软件里面充当官方节点，也可以通过命令行直接测试：
```bash
curl http://localhost:10086/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-openrouter-admin-key" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "帮我写一首关于春天的现代诗"}],
    "stream": true
  }'
```
> **Tip:** 将请求 JSON 里的 `model` 字段设为 `auto` 可以让路由器抛下包袱，自动根据当前最高分节点为你挑选最顶级的节点和模型响应！如果你指定模型名字，系统会寻找支持该名字的次级模型发起请求。

### 2. 访问控制台与 Dashboard 面板
在浏览器打开您的前端可视化页面入口：
`http://localhost:10086/index.html` 
你可以直观操控启停具体某个接口渠道、调整由于特定代理商促销所引起的基础分权重变化，以及享受全局的流媒体指标检测与指定时间段的用量回溯分析。

## 🏗️ 代码与架构进阶亮点

* **精细化并行的 Token 计费策略**：系统在 WebFlux 流式报文的监听口植入正则解析与事件监控节点，精确分别抓取 `prompt` 和 `completion` token 且在不影响性能的前提下汇总。
* **Hybrid High Performance Aggregation (混合聚合查询)**：独创的 `Range Stats` 算法。在跨天报表时，系统能自动将历史整日数据从 `daily_stats` 抽离，并实时计算起始/末尾残缺日的原始 `request_log`。这种“空间换时间”的策略使得百万级数据的报表呈现依然能维持在毫秒量级。
* **Zero Blocking Data Layer (零阻塞账本)**：系统将所有计费、耗时检测的落盘 SQL 直接推入缓存的 `Schedulers.boundedElastic()` 线程池中排队，主路由转发与代理通道实现 100% 旁路解耦隔离。
* **Dual-Layer Logging (双层审计日志)**：区分 `request_log` (呈现给用户的最终请求) 与 `model_call_log` (每一次底层的真实尝试)。这种设计使得系统能清晰记录下由于 Failover 机制引发的所有“重试心路历程”，让运维不再有盲区。
* **优雅重启恢复策略**：采用 `MetricsInitializer`，在启动的第一毫秒即可反向预热提取最新的 SQLite 成功响应记录，自动重建并填充系统内存里的健康打分池。

---
***试着设置gemini系模型作为导师模型,体验哈基米风味的活人感回复***
