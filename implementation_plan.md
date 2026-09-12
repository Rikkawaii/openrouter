# 实现一个类似 OpenRouter 的 LLM 路由分发服务 - 适配与迁移至独立模块

目标设定：统一对外提供 OpenAI 兼容格式的 API 接口，内置基于 Spring WebFlux (Reactor) 全异步响应式调用的路由基座。

## 架构设计原则与约束 (CRITICAL)

1. **独立 Maven 模块**：全部代码置于独立的 `yu-ai-code-openrouter` 模块下，并且脱离原由 Web MVC 管理的项目代码，成为独立的高性能路由中间件模块。
2. **全面拥抱 WebFlux 原生非阻塞流**：放弃之前的 `SseEmitter` 桥接与 `OkHttp`，在新模块的 [pom.xml](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/pom.xml) 中引入 `spring-boot-starter-webflux`。底层发起大模型厂商请求完全使用原生的 `WebClient`，而业务各层方法间使用 `Flux<String>` 完美流转。
3. **纯净自研 API 对接**：拒绝使用黑盒的高级 AI SDK。手写请求组装并反序列化厂商（OpenAI / Gemini）返回的流块，完全接管底层结构，为后续的精确 Token 控制打下基础。

## Proposed Changes

### 1. 顶级模块构建与 DTO 层 (`com.openrouter.model`)
- 构建独立的 [pom.xml](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/pom.xml)
- 标准化实体：`ChatCompletionMessage`, `ChatCompletionRequest`, `ChatCompletionResponse`。

### 2. 核心响应式抽象层 (`com.openrouter.adapter`)
- [LlmClientAdapter.java](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/src/main/java/com/openrouter/adapter/LlmClientAdapter.java)：将返回值修改为原生的 `Flux<String> streamChat(ChatCompletionRequest request)`，真正实现 Reactive Stream。
- [ModelRoutingStrategy.java](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/src/main/java/com/openrouter/adapter/ModelRoutingStrategy.java)：定义通过请求找到对应下游 Adapter 的路由接口。

### 3. 响应式客户端具体实现 (`com.openrouter.adapter.impl`)
- [OpenAiLlmAdapter.java](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/src/main/java/com/openrouter/adapter/impl/OpenAiLlmAdapter.java)：直接通过注入的 `WebClient` 发出 `Accept: text/event-stream` 请求，并将服务器端响应出的流数据 Body 原封不动以 `Flux<String>` 向上传递。
- [GeminiLlmAdapter.java](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/src/main/java/com/openrouter/adapter/impl/GeminiLlmAdapter.java)：利用 `WebClient` 给 Gemini API 发生流式请求，并且在 `Flux` 的生命周期利用 `.map` 算子做协议截获与转换。

### 4. 服务控制端 (`com.openrouter.controller`)
- [LlmRouterController.java](file:///d:/code/java_code/idea_java_projects/yu-ai-code-mother/src/main/java/com/openrouter/controller/LlmRouterController.java)：直接挂载 `@PostMapping("/v1/chat/completions")` 并在响应头声明 EventStream，最优雅地 `return service.streamChat(...)`。
