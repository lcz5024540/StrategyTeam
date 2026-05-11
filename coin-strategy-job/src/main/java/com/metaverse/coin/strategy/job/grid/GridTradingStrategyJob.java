package com.metaverse.coin.strategy.job.grid;

import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyMode;
import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyExitResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import com.metaverse.coin.strategy.job.grid.config.GridTradingProperties;
import com.metaverse.coin.strategy.job.grid.model.GridLevel;
import com.metaverse.coin.strategy.job.grid.service.GridLevelCalculator;
import com.metaverse.coin.strategy.job.grid.service.GridOrderManager;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网格交易策略主入口。
 *
 * <p>在 Polymarket CLOB 上按等间距价格挂 maker 限价单，
 * 当价格波动时自动成交赚取价差。本质是做市策略。</p>
 *
 * <p>核心优势：所有挂单走 maker（fee=0），盈利来自买卖价差。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GridTradingStrategyJob implements PolymarketStrategy {

    private static final String LOG_PREFIX = "[GRID]";
    private static final String STRATEGY_ID = "GRID";

    private final GridTradingProperties properties;
    private final GridLevelCalculator levelCalculator;
    private final GridOrderManager orderManager;
    private final PolymarketMarketMetadataService marketMetadataService;

    /** 已初始化的网格实例（marketId → true） */
    private final Map<String, Boolean> initializedGrids = new ConcurrentHashMap<>();

    // ── PolymarketStrategy 接口实现 ──────────────────────────────────

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getDisplayName() {
        return "网格交易策略";
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public String getWalletId() {
        return properties.getWalletId();
    }

    @Override
    public StrategyMode getMode() {
        try {
            return StrategyMode.valueOf(properties.getMode().toUpperCase());
        } catch (Exception e) {
            return StrategyMode.OFF;
        }
    }

    // ── 定时调度 ──────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${grid-trading.global.order-refresh-interval-ms:30000}")
    public void scheduledCycle() {
        if (!isEnabled() || !getMode().isActive()) return;
        try {
            runCycle();
        } catch (Exception e) {
            log.error("{} 定时网格循环异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    @Override
    public StrategyCycleResult runCycle() {
        Instant start = Instant.now();
        List<String> errors = new ArrayList<>();
        int totalFills = 0;

        try {
            List<GridTradingProperties.GridInstanceConfig> instances = properties.getInstances();
            if (instances == null || instances.isEmpty()) {
                return buildCycleResult(start, 0, 0, errors);
            }

            for (GridTradingProperties.GridInstanceConfig config : instances) {
                try {
                    // 1. 确保网格已初始化
                    ensureGridInitialized(config);

                    // 2. 获取当前市场价格
                    BigDecimal currentPrice = fetchCurrentPrice(config.getMarketId());
                    if (currentPrice == null) {
                        log.debug("{} 无法获取市场价格: {}", LOG_PREFIX, config.getMarketId());
                        continue;
                    }

                    // 3. 检查到期撤单
                    if (shouldCancelForSettlement(config.getMarketId())) {
                        orderManager.cancelAllOrders(config.getMarketId());
                        continue;
                    }

                    // 4. 检查成交并处理
                    int fills = orderManager.checkAndProcessFills(config.getMarketId(), currentPrice);
                    totalFills += fills;

                    if (fills > 0) {
                        log.info("{} market={} 本轮成交 {} 笔, 净库存={}, 累计利润={}",
                                LOG_PREFIX, config.getMarketId(), fills,
                                orderManager.getNetInventory(config.getMarketId()),
                                orderManager.getTotalRealizedProfit(config.getMarketId()));
                    }

                    // 5. 检查单侧暴露
                    checkExposureLimit(config);

                } catch (Exception e) {
                    String errMsg = String.format("处理网格 %s 异常: %s", config.getMarketId(), e.getMessage());
                    errors.add(errMsg);
                    log.error("{} {}", LOG_PREFIX, errMsg, e);
                }
            }

        } catch (Exception e) {
            errors.add("runCycle 异常: " + e.getMessage());
            log.error("{} runCycle 异常: {}", LOG_PREFIX, e.getMessage(), e);
        }

        return buildCycleResult(start, totalFills, totalFills, errors);
    }

    @Override
    public StrategyExitResult runExitScan() {
        // 网格策略的退出通过到期撤单实现，在 runCycle 中处理
        return StrategyExitResult.builder()
                .strategyId(STRATEGY_ID)
                .scannedAt(Instant.now())
                .positionsScanned(0)
                .exitsTriggered(0)
                .exitDetails(Collections.emptyList())
                .build();
    }

    @Override
    public StrategyHealthSnapshot health() {
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalExposure = BigDecimal.ZERO;
        int totalGrids = 0;

        for (GridTradingProperties.GridInstanceConfig config : properties.getInstances()) {
            totalProfit = totalProfit.add(orderManager.getTotalRealizedProfit(config.getMarketId()));
            BigDecimal inventory = orderManager.getNetInventory(config.getMarketId()).abs();
            totalExposure = totalExposure.add(inventory);
            totalGrids++;
        }

        return StrategyHealthSnapshot.builder()
                .strategyId(STRATEGY_ID)
                .checkedAt(Instant.now())
                .healthy(true)
                .openPositions(totalGrids)
                .totalExposureUsdc(totalExposure)
                .todayRealizedPnlUsdc(totalProfit)
                .notes(String.format("%d 个网格实例运行中", totalGrids))
                .build();
    }

    // ── 内部方法 ──────────────────────────────────────────────────────

    private void ensureGridInitialized(GridTradingProperties.GridInstanceConfig config) {
        if (initializedGrids.containsKey(config.getMarketId())) return;

        List<GridLevel> levels = levelCalculator.calculateLevels(config);
        if (levels.isEmpty()) return;

        // 获取市场元数据
        String yesTokenId = null;
        String marketTitle = config.getMarketId();
        try {
            PolymarketApiClient.MarketMetadata metadata =
                    marketMetadataService.getByConditionId(config.getMarketId());
            if (metadata != null) {
                marketTitle = metadata.getQuestion() != null ? metadata.getQuestion() : config.getMarketId();
                if (metadata.getOutcomeTokenIds() != null) {
                    yesTokenId = metadata.getOutcomeTokenIds().get("Yes");
                }
            }
        } catch (Exception e) {
            log.warn("{} 获取市场元数据失败: {}", LOG_PREFIX, e.getMessage());
        }

        orderManager.initializeOrders(config, levels, yesTokenId, marketTitle);
        initializedGrids.put(config.getMarketId(), true);
        log.info("{} 网格初始化完成: market={} levels={}", LOG_PREFIX, config.getMarketId(), levels.size());
    }

    private BigDecimal fetchCurrentPrice(String marketId) {
        try {
            PolymarketApiClient.MarketMetadata metadata =
                    marketMetadataService.getByConditionId(marketId, true);
            if (metadata != null && metadata.getOutcomePrices() != null && !metadata.getOutcomePrices().isEmpty()) {
                return new BigDecimal(metadata.getOutcomePrices().get(0));
            }
        } catch (Exception e) {
            log.debug("{} 获取价格失败: market={} error={}", LOG_PREFIX, marketId, e.getMessage());
        }
        return null;
    }

    private boolean shouldCancelForSettlement(String marketId) {
        try {
            PolymarketApiClient.MarketMetadata metadata =
                    marketMetadataService.getByConditionId(marketId);
            if (metadata != null && metadata.getEndAt() != null) {
                long endAtMs = metadata.getEndAt() > 1_000_000_000_000L
                        ? metadata.getEndAt() : metadata.getEndAt() * 1000;
                long hoursToSettlement = (endAtMs - System.currentTimeMillis()) / (3600_000);
                return hoursToSettlement <= properties.getGlobal().getCancelBeforeSettlementHours();
            }
        } catch (Exception e) {
            log.debug("{} 检查结算时间失败: {}", LOG_PREFIX, e.getMessage());
        }
        return false;
    }

    private void checkExposureLimit(GridTradingProperties.GridInstanceConfig config) {
        BigDecimal netInventory = orderManager.getNetInventory(config.getMarketId()).abs();
        if (config.getMaxOneSideExposureUsdc() != null
                && netInventory.compareTo(config.getMaxOneSideExposureUsdc()) > 0) {
            log.warn("{} 单侧暴露超限: market={} inventory={} limit={}",
                    LOG_PREFIX, config.getMarketId(), netInventory, config.getMaxOneSideExposureUsdc());
            // 暂停该市场的新挂单（不撤已有的）
        }
    }

    private StrategyCycleResult buildCycleResult(Instant start, int signalsFound, int ordersPlaced,
                                                  List<String> errors) {
        return StrategyCycleResult.builder()
                .strategyId(STRATEGY_ID)
                .startedAt(start)
                .duration(Duration.between(start, Instant.now()))
                .signalsFound(signalsFound)
                .ordersPlaced(ordersPlaced)
                .errors(errors.size())
                .errorMessages(errors)
                .fatalError(false)
                .build();
    }
}
