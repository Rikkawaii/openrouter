package com.openrouter.config;

import lombok.Data;

/**
 * 路由打分的可调参数。
 * <p>
 * 运行时权威值持久化在 channels.json 的 settings.routing 段（随管理页保存热生效），
 * application.yaml 的 openrouter.routing.* 仅作为首次部署的引导值。
 * <p>
 * 打分公式（各项子分均已归一化到 [0,1]，结果有界）：
 * <pre>
 * score = baseWeight × (1 - healthWeight×H - latencyWeight×L - loadWeight×C)
 * </pre>
 */
@Data
public class RoutingConfig {

    /** 健康度（近期失败率）权重 */
    private double healthWeight = 0.5;

    /** 延迟权重 */
    private double latencyWeight = 0.3;

    /** 负载（并发）权重 */
    private double loadWeight = 0.2;

    /** 延迟归一化的参考值（ms）：延迟达到该值时子分约为 0.5 */
    private long latencyReferenceMs = 1500;

    /** 并发归一化的容量参考值：并发达到该值时子分约为 0.5 */
    private int concurrencyCapacity = 8;

    /** 延迟 EMA 平滑系数：越小越平滑、越不受单次抖动影响 */
    private double ewmaAlpha = 0.1;

    /** 失败率的时间半衰期（秒）：闲置该时长后失败率减半 */
    private long errorDecaySeconds = 60;

    /** 失败率的事件衰减系数：每发生一次调用，历史值乘该系数 */
    private double errorDecayFactor = 0.5;

    /** 冷启动（无延迟样本）时的延迟子分：不得为 0，避免「未知即最优」 */
    private double coldStartLatencyRatio = 0.5;

    /** 延迟样本数达到该值才认为估计可信 */
    private int minLatencySamples = 5;

    /** 探索概率：以该概率在得分前两名中随机选择，避免流量完全锁定（0 = 关闭） */
    private double explorationRate = 0.0;

    /**
     * 校验参数合法性。非法时抛出 IllegalArgumentException，调用方据此拒绝保存。
     */
    public void validate() {
        checkWeight("healthWeight", healthWeight);
        checkWeight("latencyWeight", latencyWeight);
        checkWeight("loadWeight", loadWeight);
        if (healthWeight + latencyWeight + loadWeight > 1.0 + 1e-9) {
            throw new IllegalArgumentException("三个权重之和不能超过 1");
        }
        if (latencyReferenceMs <= 0) {
            throw new IllegalArgumentException("latencyReferenceMs 必须大于 0");
        }
        if (concurrencyCapacity < 1) {
            throw new IllegalArgumentException("concurrencyCapacity 不能小于 1");
        }
        if (ewmaAlpha <= 0 || ewmaAlpha > 1) {
            throw new IllegalArgumentException("ewmaAlpha 必须落在 (0, 1] 区间");
        }
        if (errorDecaySeconds < 10) {
            throw new IllegalArgumentException("errorDecaySeconds 不能小于 10");
        }
        if (errorDecayFactor <= 0 || errorDecayFactor >= 1) {
            throw new IllegalArgumentException("errorDecayFactor 必须落在 (0, 1) 区间");
        }
        if (coldStartLatencyRatio < 0 || coldStartLatencyRatio > 1) {
            throw new IllegalArgumentException("coldStartLatencyRatio 必须落在 [0, 1] 区间");
        }
        if (minLatencySamples < 1) {
            throw new IllegalArgumentException("minLatencySamples 不能小于 1");
        }
        if (explorationRate < 0 || explorationRate > 0.5) {
            throw new IllegalArgumentException("explorationRate 必须落在 [0, 0.5] 区间");
        }
    }

    /** 深拷贝：避免外部对象与内存态共享引用 */
    public RoutingConfig copy() {
        RoutingConfig c = new RoutingConfig();
        c.setHealthWeight(healthWeight);
        c.setLatencyWeight(latencyWeight);
        c.setLoadWeight(loadWeight);
        c.setLatencyReferenceMs(latencyReferenceMs);
        c.setConcurrencyCapacity(concurrencyCapacity);
        c.setEwmaAlpha(ewmaAlpha);
        c.setErrorDecaySeconds(errorDecaySeconds);
        c.setErrorDecayFactor(errorDecayFactor);
        c.setColdStartLatencyRatio(coldStartLatencyRatio);
        c.setMinLatencySamples(minLatencySamples);
        c.setExplorationRate(explorationRate);
        return c;
    }

    private void checkWeight(String name, double value) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " 必须落在 [0, 1] 区间");
        }
    }
}
