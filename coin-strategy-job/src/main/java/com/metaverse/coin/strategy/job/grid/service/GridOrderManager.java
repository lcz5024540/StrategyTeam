package com.metaverse.coin.strategy.job.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.GridOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.GridOrderEntity;
import com.metaverse.coin.strategy.job.grid.config.GridTradingProperties;
import com.metaverse.coin.strategy.job.grid.model.GridLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 网格挂单管理器。
 *
 * <p>负责：</p>
 * <ul>
 *   <li>初始挂单：根据网格层计算的价位挂出 maker 限价单</li>
 *   <li>成交检测：定期检查挂单状态，发现成交</li>
 *   <li>对侧补单：买单成交后在上方挂卖单，卖单成交后在下方挂买单</li>
 *   <li>缺失补全：补全因故取消的网格层挂单</li>
 *   <li>到期撤单：市场临近到期时撤掉所有挂单</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GridOrderManager {

    private static final String LOG_PREFIX = "[GRID_ORDER]";
    private static final String STRATEGY_ID = "GRID";

    private final GridTradingProperties properties;
    private final GridOrderMapper orderMapper;

    /**
     * 为一个市场初始化所有网格挂单。
     *
     * @param instanceConfig 网格实例配置
     * @param levels         计算好的网格层
     * @param yesTokenId     YES token ID
     * @param marketTitle    市场标题
     */
    public void initializeOrders(GridTradingProperties.GridInstanceConfig instanceConfig,
                                  List<GridLevel> levels,
                                  String yesTokenId,
                                  String marketTitle) {
        String executionMode = properties.getMode().equalsIgnoreCase("LIVE") ? "LIVE" : "PAPER";

        for (GridLevel level : levels) {
            // 检查是否已有该层的活跃挂单
            Long existing = orderMapper.selectCount(
                    new LambdaQueryWrapper<GridOrderEntity>()
                            .eq(GridOrderEntity::getMarketId, instanceConfig.getMarketId())
                            .eq(GridOrderEntity::getLevelIndex, level.getLevelIndex())
                            .in(GridOrderEntity::getStatus, List.of("PENDING", "LIVE")));
            if (existing > 0) continue;

            GridOrderEntity order = new GridOrderEntity()
                    .setStrategyId(STRATEGY_ID)
                    .setWalletId(properties.getWalletId())
                    .setMarketId(instanceConfig.getMarketId())
                    .setTokenId(yesTokenId)
                    .setMarketTitle(marketTitle)
                    .setLevelIndex(level.getLevelIndex())
                    .setOutcome("YES") // 网格策略在 YES 侧操作，买低卖高
                    .setSide(level.getSide())
                    .setPrice(level.getPrice())
                    .setQuantity(level.getQuantity())
                    .setAmountUsdc(level.getAmountUsdc())
                    .setStatus("PENDING")
                    .setExecutionMode(executionMode)
                    .setFilledQuantity(BigDecimal.ZERO)
                    .setRealizedProfitUsdc(BigDecimal.ZERO)
                    .setCreatedAt(new Date())
                    .setUpdatedAt(new Date());

            orderMapper.insert(order);

            // TODO: LIVE 模式 → 通过 PolymarketClobClientRouter 下真实 maker 挂单
            if ("LIVE".equals(executionMode)) {
                log.info("{} [LIVE] 挂单: market={} level={} side={} price={} qty={}",
                        LOG_PREFIX, instanceConfig.getMarketId(), level.getLevelIndex(),
                        level.getSide(), level.getPrice(), level.getQuantity());
            } else {
                order.setStatus("LIVE"); // Paper 模式直接标记为 LIVE
                orderMapper.updateById(order);
            }
        }

        log.info("{} 初始化挂单完成: market={} orders={}", LOG_PREFIX, instanceConfig.getMarketId(), levels.size());
    }

    /**
     * 检查并处理成交。
     * <p>Paper 模式通过价格比较模拟成交：当前市价 <= 买单价 → 买单成交，当前市价 >= 卖单价 → 卖单成交。</p>
     *
     * @param marketId     市场 ID
     * @param currentPrice 当前 YES 价格
     * @return 本轮成交数
     */
    public int checkAndProcessFills(String marketId, BigDecimal currentPrice) {
        if (currentPrice == null) return 0;

        List<GridOrderEntity> liveOrders = orderMapper.selectList(
                new LambdaQueryWrapper<GridOrderEntity>()
                        .eq(GridOrderEntity::getMarketId, marketId)
                        .eq(GridOrderEntity::getStatus, "LIVE")
                        .eq(GridOrderEntity::getStrategyId, STRATEGY_ID));

        int fills = 0;
        for (GridOrderEntity order : liveOrders) {
            boolean filled = false;

            if ("BUY".equals(order.getSide()) && currentPrice.compareTo(order.getPrice()) <= 0) {
                filled = true;
            } else if ("SELL".equals(order.getSide()) && currentPrice.compareTo(order.getPrice()) >= 0) {
                filled = true;
            }

            if (filled) {
                processFill(order, currentPrice);
                fills++;
            }
        }
        return fills;
    }

    /**
     * 处理单个挂单成交。
     */
    private void processFill(GridOrderEntity order, BigDecimal fillPrice) {
        order.setStatus("FILLED");
        order.setFilledQuantity(order.getQuantity());
        order.setFilledAvgPrice(fillPrice);
        order.setUpdatedAt(new Date());

        // 计算本次成交利润（如果是对侧补单的成交）
        if (order.getCounterOrderId() != null) {
            GridOrderEntity counterOrder = orderMapper.selectById(order.getCounterOrderId());
            if (counterOrder != null && counterOrder.getFilledAvgPrice() != null) {
                BigDecimal priceDiff = "SELL".equals(order.getSide())
                        ? fillPrice.subtract(counterOrder.getFilledAvgPrice())
                        : counterOrder.getFilledAvgPrice().subtract(fillPrice);
                BigDecimal profit = priceDiff.multiply(order.getFilledQuantity()).setScale(4, RoundingMode.HALF_UP);
                order.setRealizedProfitUsdc(profit);
            }
        }

        orderMapper.updateById(order);
        log.info("{} 成交: market={} level={} side={} price={} qty={} profit={}",
                LOG_PREFIX, order.getMarketId(), order.getLevelIndex(),
                order.getSide(), fillPrice, order.getQuantity(), order.getRealizedProfitUsdc());

        // 在对侧挂出补单
        placeCounterOrder(order);
    }

    /**
     * 在对侧挂出补单（买成交 → 挂卖，卖成交 → 挂买）。
     */
    private void placeCounterOrder(GridOrderEntity filledOrder) {
        GridTradingProperties.GridInstanceConfig instanceConfig = findInstanceConfig(filledOrder.getMarketId());
        if (instanceConfig == null) return;

        BigDecimal spacing = instanceConfig.getGridSpacing();
        String counterSide = "BUY".equals(filledOrder.getSide()) ? "SELL" : "BUY";
        BigDecimal counterPrice;

        if ("BUY".equals(filledOrder.getSide())) {
            // 买单成交 → 在上方一格挂卖单
            counterPrice = filledOrder.getPrice().add(spacing);
        } else {
            // 卖单成交 → 在下方一格挂买单
            counterPrice = filledOrder.getPrice().subtract(spacing);
        }

        // 价格边界检查
        if (counterPrice.compareTo(new BigDecimal("0.01")) < 0
                || counterPrice.compareTo(new BigDecimal("0.99")) > 0) {
            log.warn("{} 对侧补单价格超出范围: {}", LOG_PREFIX, counterPrice);
            return;
        }

        BigDecimal quantity = filledOrder.getQuantity();

        GridOrderEntity counterOrder = new GridOrderEntity()
                .setStrategyId(STRATEGY_ID)
                .setWalletId(properties.getWalletId())
                .setMarketId(filledOrder.getMarketId())
                .setTokenId(filledOrder.getTokenId())
                .setMarketTitle(filledOrder.getMarketTitle())
                .setLevelIndex(filledOrder.getLevelIndex())
                .setOutcome(filledOrder.getOutcome())
                .setSide(counterSide)
                .setPrice(counterPrice)
                .setQuantity(quantity)
                .setAmountUsdc(counterPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP))
                .setStatus("LIVE") // Paper 直接 LIVE
                .setExecutionMode(filledOrder.getExecutionMode())
                .setFilledQuantity(BigDecimal.ZERO)
                .setRealizedProfitUsdc(BigDecimal.ZERO)
                .setCounterOrderId(filledOrder.getId())
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());

        orderMapper.insert(counterOrder);

        log.info("{} 对侧补单: market={} side={} price={} qty={}",
                LOG_PREFIX, filledOrder.getMarketId(), counterSide, counterPrice, quantity);
    }

    /**
     * 撤掉指定市场的所有活跃挂单。
     */
    public int cancelAllOrders(String marketId) {
        List<GridOrderEntity> liveOrders = orderMapper.selectList(
                new LambdaQueryWrapper<GridOrderEntity>()
                        .eq(GridOrderEntity::getMarketId, marketId)
                        .in(GridOrderEntity::getStatus, List.of("PENDING", "LIVE"))
                        .eq(GridOrderEntity::getStrategyId, STRATEGY_ID));

        for (GridOrderEntity order : liveOrders) {
            order.setStatus("CANCELLED");
            order.setUpdatedAt(new Date());
            orderMapper.updateById(order);
            // TODO: LIVE 模式需要通过 CLOB API 撤单
        }

        log.info("{} 撤销所有挂单: market={} cancelled={}", LOG_PREFIX, marketId, liveOrders.size());
        return liveOrders.size();
    }

    /**
     * 获取指定市场的净库存（正=持有 YES，负=等效持有 NO）。
     */
    public BigDecimal getNetInventory(String marketId) {
        List<GridOrderEntity> filled = orderMapper.selectList(
                new LambdaQueryWrapper<GridOrderEntity>()
                        .eq(GridOrderEntity::getMarketId, marketId)
                        .eq(GridOrderEntity::getStatus, "FILLED")
                        .eq(GridOrderEntity::getStrategyId, STRATEGY_ID));

        BigDecimal net = BigDecimal.ZERO;
        for (GridOrderEntity order : filled) {
            BigDecimal qty = order.getFilledQuantity() != null ? order.getFilledQuantity() : BigDecimal.ZERO;
            if ("BUY".equals(order.getSide())) {
                net = net.add(qty);
            } else {
                net = net.subtract(qty);
            }
        }
        return net;
    }

    /**
     * 获取指定市场的累计已实现利润。
     */
    public BigDecimal getTotalRealizedProfit(String marketId) {
        List<GridOrderEntity> filled = orderMapper.selectList(
                new LambdaQueryWrapper<GridOrderEntity>()
                        .eq(GridOrderEntity::getMarketId, marketId)
                        .eq(GridOrderEntity::getStatus, "FILLED")
                        .eq(GridOrderEntity::getStrategyId, STRATEGY_ID));

        return filled.stream()
                .filter(o -> o.getRealizedProfitUsdc() != null)
                .map(GridOrderEntity::getRealizedProfitUsdc)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private GridTradingProperties.GridInstanceConfig findInstanceConfig(String marketId) {
        return properties.getInstances().stream()
                .filter(i -> marketId.equals(i.getMarketId()))
                .findFirst()
                .orElse(null);
    }
}
