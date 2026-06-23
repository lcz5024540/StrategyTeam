package com.metaverse.coin.strategy.job.grid.service;

import com.metaverse.coin.strategy.job.grid.config.GridTradingProperties;
import com.metaverse.coin.strategy.job.grid.model.GridLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 网格价位计算器。
 *
 * <p>根据中心价格和间距，计算所有网格层的挂单价位和数量。</p>
 * <ul>
 *   <li>中心价以下的层：BUY 挂单（低买高卖）</li>
 *   <li>中心价以上的层：SELL 挂单（高卖低买）</li>
 *   <li>所有价格对齐到 Polymarket 最小 tick size</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GridLevelCalculator {

    private static final String LOG_PREFIX = "[GRID_LEVEL]";

    private final GridTradingProperties properties;

    /**
     * 为指定配置计算所有网格层。
     */
    public List<GridLevel> calculateLevels(GridTradingProperties.GridInstanceConfig config) {
        BigDecimal center = config.getCenterPrice();
        BigDecimal spacing = config.getGridSpacing();
        int levels = config.getGridLevels();
        BigDecimal amountPerLevel = config.getAmountPerLevelUsdc();
        BigDecimal tick = properties.getGlobal().getMinimumTickSize();

        List<GridLevel> result = new ArrayList<>();

        // 买单层（中心价以下）
        for (int i = 1; i <= levels; i++) {
            BigDecimal price = center.subtract(spacing.multiply(BigDecimal.valueOf(i)));
            price = snapToTick(price, tick);

            if (price.compareTo(new BigDecimal("0.01")) < 0) break; // 最低价 1 cent
            if (price.compareTo(new BigDecimal("0.99")) > 0) break; // 最高价 99 cents

            BigDecimal quantity = amountPerLevel.divide(price, 4, RoundingMode.HALF_UP);

            result.add(GridLevel.builder()
                    .levelIndex(-i)
                    .price(price)
                    .side("BUY")
                    .quantity(quantity)
                    .amountUsdc(amountPerLevel)
                    .status("PENDING")
                    .build());
        }

        // 卖单层（中心价以上）
        for (int i = 1; i <= levels; i++) {
            BigDecimal price = center.add(spacing.multiply(BigDecimal.valueOf(i)));
            price = snapToTick(price, tick);

            if (price.compareTo(new BigDecimal("0.01")) < 0) break;
            if (price.compareTo(new BigDecimal("0.99")) > 0) break;

            BigDecimal quantity = amountPerLevel.divide(price, 4, RoundingMode.HALF_UP);

            result.add(GridLevel.builder()
                    .levelIndex(i)
                    .price(price)
                    .side("SELL")
                    .quantity(quantity)
                    .amountUsdc(amountPerLevel)
                    .status("PENDING")
                    .build());
        }

        log.info("{} 计算完成: center={} spacing={} levels={} total={}",
                LOG_PREFIX, center, spacing, levels, result.size());
        return result;
    }

    /**
     * 对齐到 Polymarket 最小 tick size。
     */
    private BigDecimal snapToTick(BigDecimal price, BigDecimal tick) {
        if (tick == null || tick.compareTo(BigDecimal.ZERO) == 0) return price;
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
    }
}
