package com.metaverse.coin.strategy.job.engine;

import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 策略注册中心。
 *
 * <p>通过 Spring 依赖注入自动发现所有实现 {@link PolymarketStrategy} 的 Bean，
 * 提供统一的策略查询、生命周期管理和健康检查接口。</p>
 *
 * <p><b>零侵入设计</b>：现有的跟单策略和天气策略在未实现 PolymarketStrategy 之前，
 * 此 Registry 为空（或仅包含新策略），不影响任何现有功能。</p>
 */
@Component
@Slf4j
public class StrategyRegistry {

    private static final String LOG_PREFIX = "[STRATEGY_REGISTRY]";

    private final Map<String, PolymarketStrategy> strategies;

    /**
     * Spring 会自动注入所有实现 PolymarketStrategy 的 Bean。
     * 如果没有任何实现，注入空列表（Optional 兜底）。
     */
    public StrategyRegistry(Optional<List<PolymarketStrategy>> strategyBeans) {
        Map<String, PolymarketStrategy> map = new LinkedHashMap<>();
        strategyBeans.ifPresent(beans -> {
            for (PolymarketStrategy strategy : beans) {
                String id = strategy.getStrategyId();
                if (map.containsKey(id)) {
                    log.warn("{} 发现重复策略ID: {} — 后注册的将覆盖前者", LOG_PREFIX, id);
                }
                map.put(id, strategy);
            }
        });
        this.strategies = Collections.unmodifiableMap(map);
    }

    @PostConstruct
    public void init() {
        if (strategies.isEmpty()) {
            log.info("{} 初始化完成，当前无已注册策略（Phase 1 适配后将自动发现）", LOG_PREFIX);
        } else {
            log.info("{} 初始化完成，已注册 {} 个策略: {}", LOG_PREFIX, strategies.size(),
                    strategies.values().stream()
                            .map(s -> String.format("%s(%s/%s)", s.getStrategyId(), s.getMode(), s.isEnabled() ? "ON" : "OFF"))
                            .collect(Collectors.joining(", ")));
        }
    }

    // ── 查询 API ──────────────────────────────────────────────────────

    /** 根据策略 ID 获取策略 */
    public PolymarketStrategy getStrategy(String strategyId) {
        return strategies.get(strategyId);
    }

    /** 列出所有已注册策略 */
    public List<PolymarketStrategy> listAll() {
        return List.copyOf(strategies.values());
    }

    /** 列出所有已启用的策略（mode != OFF && enabled == true） */
    public List<PolymarketStrategy> listEnabled() {
        return strategies.values().stream()
                .filter(s -> s.isEnabled() && s.getMode().isActive())
                .collect(Collectors.toList());
    }

    /** 已注册策略数 */
    public int size() {
        return strategies.size();
    }

    /** 是否包含指定策略 */
    public boolean contains(String strategyId) {
        return strategies.containsKey(strategyId);
    }

    // ── 批量操作 ──────────────────────────────────────────────────────

    /** 对所有启用的策略执行健康检查 */
    public List<StrategyHealthSnapshot> healthCheckAll() {
        return listEnabled().stream()
                .map(strategy -> {
                    try {
                        return strategy.health();
                    } catch (Exception e) {
                        log.error("{} 策略 {} 健康检查失败: {}", LOG_PREFIX, strategy.getStrategyId(), e.getMessage(), e);
                        return StrategyHealthSnapshot.builder()
                                .strategyId(strategy.getStrategyId())
                                .healthy(false)
                                .notes("健康检查异常: " + e.getMessage())
                                .build();
                    }
                })
                .collect(Collectors.toList());
    }
}
