# OpenRouter (AI 开源路由器)

OpenRouter 是一个基于 **Spring WebFlux** 打造的极速、高可用、可拓展的大语言模型 (LLM) API 路由网关。它对外提供标准兼容 OpenAI 规范的 API 接口，对内能将请求智能分配给云端的多个异构 AI 厂商（例如 OpenAI, DeepSeek, Google Gemini 等），以实现最低接口延迟、高可用容灾以及精准颗粒度的调用监控。

## ✨ 核心特性

- **🚀 统一下游 API**：对外暴露出 100% 兼容 OpenAI 格式的 `/v1/chat/completions` 接口，无缝接入主流前端生态（如 NextChat, Chatbox, OpenAI 原生 SDK 等）。
- **🧠 智能动态打分路由**：结合 **静态配置权重、动态网络延迟 (TTFT)、并发流请求阻力、以及报错宕机惩罚** 计算出综合环境最优解，每次连接都将请求精准秒级派发至最健康的服务器。
- **🛡️ 自动故障屏蔽与转移 (Failover)**：当选中的最优节点出现网络超时、连接挂断或限流（429/500）时，系统会在底层自动重塑请求流并平滑顺延给备用同级节点，用户端几乎实现“无感知秒切”。
- **👁️ 模型能力嗅探**：自带请求结构感知器，自动解析出多模态（视觉图片和文件支持）等场景，并在实际拉起路由前过滤掉不支持相关能力的“偏科”渠道。
- **🎓 导师规则 (Mentor Rule)**：极具新意的硬核策略。在新开会话的第一句（系统检测出无记忆上下文）遇到时，可以直接强行重定向到指定的全能高智商“导师模型”（如 `gemini-3-flash-preview` / `gpt-4o`），实现最严谨准确的第一次破冰回答。
- **📊 极客级数据看板**：内置前后端 Admin 面板数据接口，一网打尽所有渠道的存活状态、即时综合环境打分详情、时间首字延迟 (TTFT)、全周期总耗时 (Total Duration)，以及精确到每个模型的 Prompt / Completion Token 消费明细“流水账本”。
- **⚡ 纯异步非阻塞架构**：拥抱 `Project Reactor + Netty` 底层架构（默认解除 256KB 大报文限流死区），辅以 Schedulers 隔离下的免阻塞 `SQLite JDBC` 存储框架，全链路彻底解决线程排队和锁等待，打造超级并发神兽。
- **🔒 安全准入机制**：网关本身自带 `Bearer Token` 拦截验证器，防止接口暴露在外被白嫖怪滥刷。

## 🛠️ 技术栈
- 基础运行环境：Java 17+, Maven
- 核心服务框架：Spring Boot 3.x, Spring WebFlux, Project Reactor
- 数据库与持久化：SQLite (底层抛弃笨重的 JPA，使用原生且异步的 Schedulers `JdbcTemplate` 以提升极限吞吐量)
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

* **精细化并行的 Token 计费策略**：系统在 WebFlux 流式报文的监听口 (transform chunking stream) 植入正则解析与事件监控节点，精确分别抓取 `prompt` 和 `completion` token 且在不影响性能的前提下汇总。
* **Zero Blocking Data Layer (零阻塞账本)**：系统将所有计费、耗时检测的落盘 SQL 直接推入缓存的 `Schedulers.boundedElastic()` 线程池中排队，主路由转发与代理通道实现 100% 旁路解耦隔离。
* **优雅重启恢复策略**：采用原生 `CommandLineRunner` 规范下的 `MetricsInitializer`，在启动的第一毫秒即可反向预热提取近 100 条最新的 SQLite 成功响应记录，自动光速重建并填充系统内存里的 EMA (指数平滑移动平均) 打分池。做到了“开机即巅峰，无需等待第一笔流量进行唤醒和侧记”。

---
* 试着设置gemini系模型作为导师模型,体验哈基米风味的活人感回复~ *
