package com.openrouter.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 请求日志查询接口：按日期（最多 30 天）、模型、渠道筛选，分页返回 request_log。
 * 不返回 full_request / full_response 等大字段，列表页只取摘要列。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class RequestLogController {

    private static final int MAX_RANGE_DAYS = 30;

    private final JdbcTemplate jdbcTemplate;

    public RequestLogController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/request-logs")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false, defaultValue = "") String model,
            @RequestParam(required = false, defaultValue = "") String channel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Mono.fromCallable(() -> query(start, end, model, channel, page, size))
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    if (e instanceof IllegalArgumentException) {
                        return Mono.just(ResponseEntity.badRequest()
                                .body(Map.of("success", false, "message", e.getMessage())));
                    }
                    log.error("查询请求日志失败", e);
                    return Mono.just(ResponseEntity.internalServerError()
                            .body(Map.of("success", false, "message", "查询失败: " + e.getMessage())));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 单条日志详情：包含 full_request / full_response / trace_events 等大字段。
     */
    @GetMapping("/request-logs/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM request_log WHERE id = ?", id);
            if (rows.isEmpty()) {
                return ResponseEntity.status(404)
                        .body(Map.<String, Object>of("success", false, "message", "日志不存在: " + id));
            }
            return ResponseEntity.ok(rows.get(0));
        })
        .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> query(String start, String end, String model, String channel,
            int page, int size) {
        LocalDate endDate;
        LocalDate startDate;
        try {
            endDate = end != null && !end.isBlank() ? LocalDate.parse(end) : LocalDate.now();
            startDate = start != null && !start.isBlank() ? LocalDate.parse(start) : endDate.minusDays(6);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式不合法，应为 yyyy-MM-dd");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("日期范围最多 " + MAX_RANGE_DAYS + " 天");
        }
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));

        // 组装过滤条件（值全部走参数绑定）
        StringBuilder where = new StringBuilder("WHERE created_at >= ? AND created_at <= ?");
        List<Object> args = new ArrayList<>();
        args.add(startDate + " 00:00:00");
        args.add(endDate + " 23:59:59");
        if (model != null && !model.isBlank()) {
            where.append(" AND model = ?");
            args.add(model);
        }
        if (channel != null && !channel.isBlank()) {
            where.append(" AND channel_id = ?");
            args.add(channel);
        }

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log " + where, Integer.class, args.toArray());

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, trace_id, channel_id, model, prompt_tokens, completion_tokens, " +
                        "total_tokens, total_duration_ms, retry_count, success, error_msg, created_at " +
                        "FROM request_log " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                queryArgs.toArray());

        return Map.of("items", items, "total", total != null ? total : 0,
                "page", page, "size", size);
    }
}
