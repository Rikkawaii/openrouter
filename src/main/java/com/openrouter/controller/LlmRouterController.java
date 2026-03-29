package com.openrouter.controller;

import com.openrouter.config.RouterProperties;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.service.LlmRouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

//curl http://localhost:8080/v1/chat/completions \
// -H "Content-Type: application/json" \
// -d '{
//   "model": "deepseek-chat",
//   "messages": [
//     {"role": "user", "content": "帮我写一首关于春天的短诗"}
//   ]
//}'
@Slf4j
@RestController
@RequestMapping("/v1/chat")
@CrossOrigin(origins = "*")
public class LlmRouterController {

    private final LlmRouterService llmRouterService;
    private final RouterProperties routerProperties;

    public LlmRouterController(LlmRouterService llmRouterService, RouterProperties routerProperties) {
        this.llmRouterService = llmRouterService;
        this.routerProperties = routerProperties;
    }

    /**
     * 兼容 OpenAI 的核心端点。
     * 支持流式（SSE）和非流式的调用。
     */
    @PostMapping(value = "/completions")
    public ResponseEntity<Object> chatCompletions(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // 🛡️ API Key 鉴权
        String expectedKey = routerProperties.getApiKey();
        if (StringUtils.hasText(expectedKey)) {
            if (authHeader == null || !authHeader.replace("Bearer ", "").trim().equals(expectedKey.trim())) {
                log.warn("🚨 拦截到未授权的非法请求: authHeader={}", authHeader);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Collections.singletonMap("error", "Unauthorized access to router endpoint."));
            }
        }

        // 🎓 导师规则：无历史上下文（发新对话）时，强制重定向给高智商的导师模型首发
        // 判断标准优化：只要消息列表中不存在 role 为 assistant 的消息，即代表这是首轮对话（兼容带 system 提示词的场景）
        if (request.getMessages() != null && request.getMessages().stream().noneMatch(m -> "assistant".equalsIgnoreCase(m.getRole()))) {
            String mentor = routerProperties.getMentorModel();
            if (org.springframework.util.StringUtils.hasText(mentor)) {
                log.info("🎓 触发导师规则：首轮对话无历史记忆，请求由 {} 强制重定向至导师模型 {}", request.getModel(), mentor);
                request.setModel(mentor);
            }
        }
        log.info(request.toString());
        log.info("📧 收到 Chat 请求: model={}, stream={}", request.getModel(), request.getStream());

        if (Boolean.TRUE.equals(request.getStream())) {
            // 关键点：流式返回必须显式指定 MediaType.TEXT_EVENT_STREAM，
            // 否则 Spring WebFlux 会尝试搜集所有 Flux 数据聚合成一个 JSON Array 返回给前端。

            // 不指定，返回结果如下，下游可能无法解析，且这种会阻塞
            // D:\>curl -H "Content-Type: application/json" -d "{\"model\": \"auto\",
            // \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}], \"stream\": true}"
            // http://localhost:8080/v1/chat/completions
            // ["{\"id\":\"4bdb52bb-dbdc-4837-a585-a6b6faf63df4\",\"object\":\"chat.completion.chunk\",\"created\":1774592685,\"model\":\"deepseek-chat\",\"system_fingerprint\":\"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}","{\"id\":\"4bdb52bb-dbdc-4837-a585-a6b6faf63df4\",\"object\":\"chat.completion.chunk\",\"created\":1774592685,\"model\":\"deepseek-chat\",\"system_fingerprint\":\"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}","{\"id\":\"4bdb52bb-dbdc-4837-a585-a6b6faf63df4\",\"object\":\"chat.completion.chunk\",\"created\":1774592685,\"model\":\"deepseek-chat\",\"system_fingerprint\":\"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"！\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}","{\"id\":\"4bdb52bb-dbdc-4837-a585-a6b6faf63df4\",\"object\":\"chat.completion.chunk\",\"created\":1774592685,\"model\":\"deepseek-chat\",\"system_fingerprint\":\"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"很高兴\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}",

            // 指定后，返回的是原生sse格式
            // D:\>curl -H "Content-Type: application/json" -d "{\"model\": \"auto\",
            // \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}], \"stream\": true}"
            // http://localhost:8080/v1/chat/completions
            // data:{"id":"28eff074-c2ae-4a2c-b03a-1ef3df99757e","object":"chat.completion.chunk","created":1774602044,"model":"deepseek-chat","system_fingerprint":"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache","choices":[{"index":0,"delta":{"role":"assistant","content":""},"logprobs":null,"finish_reason":null}],"usage":null}
            //
            // data:{"id":"28eff074-c2ae-4a2c-b03a-1ef3df99757e","object":"chat.completion.chunk","created":1774602044,"model":"deepseek-chat","system_fingerprint":"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache","choices":[{"index":0,"delta":{"content":"你好"},"logprobs":null,"finish_reason":null}],"usage":null}
            //
            // ...
            // data:[DONE]
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(llmRouterService.streamChat(request));
        } else {
            // 非流式，正常返回 Mono
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(llmRouterService.chat(request));
        }
    }
}
