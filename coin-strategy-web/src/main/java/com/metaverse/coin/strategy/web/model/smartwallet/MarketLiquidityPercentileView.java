package com.metaverse.coin.strategy.web.model.smartwallet;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmMarketLiquidityPercentileDailyEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * W2-T1.2 市场流动性分位日级快照 web 视图.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十六节.
 */
@Data
@Builder
public class MarketLiquidityPercentileView {

    private String category;
    private LocalDate asOfDate;
    private Integer sampleCount;
    private BigDecimal liquidityP10;
    private BigDecimal liquidityP30;
    private BigDecimal liquidityP50;
    private BigDecimal liquidityP70;
    private BigDecimal liquidityMean;
    private BigDecimal liquidityMax;
    private BigDecimal spreadBpsP70;
    private Date computedAt;

    public static MarketLiquidityPercentileView from(PmMarketLiquidityPercentileDailyEntity e) {
        if (e == null) return null;
        return MarketLiquidityPercentileView.builder()
                .category(e.getCategory())
                .asOfDate(e.getAsOfDate())
                .sampleCount(e.getSampleCount())
                .liquidityP10(e.getLiquidityP10())
                .liquidityP30(e.getLiquidityP30())
                .liquidityP50(e.getLiquidityP50())
                .liquidityP70(e.getLiquidityP70())
                .liquidityMean(e.getLiquidityMean())
                .liquidityMax(e.getLiquidityMax())
                .spreadBpsP70(e.getSpreadBpsP70())
                .computedAt(e.getComputedAt())
                .build();
    }
}
