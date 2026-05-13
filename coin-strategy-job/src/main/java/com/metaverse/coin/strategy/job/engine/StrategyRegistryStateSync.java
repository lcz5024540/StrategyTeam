package com.metaverse.coin.strategy.job.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRegistryStateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRegistryStateEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.List;

/**
 * 策略注册状态同步器。
 *
 * <p>把 {@link StrategyRegistry} 中已注册策略的运行时元数据
 * （enabled / mode / displayName / walletId）UPSERT 到 strategy_registry_state，
 * 供 web 端 /strategy-engine/monitor/overview、/control/status 查询。</p>
 *
 * <p>运行时机：</p>
 * <ul>
 *   <li>启动后 @PostConstruct 同步一次</li>
 *   <li>之后每 30 秒由 @Scheduled 同步</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StrategyRegistryStateSync {

    private static final String LOG_PREFIX = "[STRATEGY_REGISTRY_SYNC]";

    private final StrategyRegistry strategyRegistry;
    private final StrategyRegistryStateMapper registryStateMapper;

    @PostConstruct
    public void onStart() {
        try {
            syncOnce();
        } catch (Exception e) {
            log.error("{} 启动期间同步失败: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${strategy-engine.registry-sync-interval-ms:30000}")
    public void scheduledSync() {
        try {
            syncOnce();
        } catch (Exception e) {
            log.error("{} 定时同步失败: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    public void syncOnce() {
        List<PolymarketStrategy> strategies = strategyRegistry.listAll();
        if (strategies.isEmpty()) return;

        Date now = new Date();
        int inserted = 0;
        int updated = 0;
        for (PolymarketStrategy strategy : strategies) {
            String strategyId = strategy.getStrategyId();
            String mode = strategy.getMode() != null ? strategy.getMode().name() : StrategyMode.OFF.name();
            StrategyRegistryStateEntity existing = registryStateMapper.selectOne(
                    new LambdaQueryWrapper<StrategyRegistryStateEntity>()
                            .eq(StrategyRegistryStateEntity::getStrategyId, strategyId)
                            .last("LIMIT 1"));
            if (existing == null) {
                StrategyRegistryStateEntity entity = new StrategyRegistryStateEntity()
                        .setStrategyId(strategyId)
                        .setDisplayName(strategy.getDisplayName())
                        .setEnabled(strategy.isEnabled())
                        .setMode(mode)
                        .setWalletId(strategy.getWalletId())
                        .setUpdatedAt(now);
                registryStateMapper.insert(entity);
                inserted++;
            } else {
                boolean changed = !equalsString(existing.getDisplayName(), strategy.getDisplayName())
                        || !Boolean.valueOf(strategy.isEnabled()).equals(existing.getEnabled())
                        || !equalsString(existing.getMode(), mode)
                        || !equalsString(existing.getWalletId(), strategy.getWalletId());
                if (changed) {
                    existing.setDisplayName(strategy.getDisplayName())
                            .setEnabled(strategy.isEnabled())
                            .setMode(mode)
                            .setWalletId(strategy.getWalletId())
                            .setUpdatedAt(now);
                    registryStateMapper.updateById(existing);
                    updated++;
                }
            }
        }
        if (inserted + updated > 0) {
            log.info("{} 同步策略注册状态: inserted={} updated={} total={}",
                    LOG_PREFIX, inserted, updated, strategies.size());
        }
    }

    private static boolean equalsString(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
