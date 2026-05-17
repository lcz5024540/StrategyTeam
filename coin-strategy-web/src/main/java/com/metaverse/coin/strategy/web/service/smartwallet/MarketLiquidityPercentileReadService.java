package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PmMarketLiquidityPercentileDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmMarketLiquidityPercentileDailyEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.MarketLiquidityPercentileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Web BFF 端 W2-T1.2 流动性分位查询.
 *
 * 直查 {@code pm_market_liquidity_percentile_daily}, 支持:
 *   - 最新一日所有 category 的 snapshot (latest)
 *   - 单一 category 的 N 天历史 trend (history)
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十六节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLiquidityPercentileReadService {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 365;

    private final PmMarketLiquidityPercentileDailyMapper dailyMapper;

    /**
     * 返回最近一日各 category 的快照. asOfDate 不传时取最大日期; 若指定 asOfDate 则只返回该日.
     */
    public List<MarketLiquidityPercentileView> listLatest(LocalDate asOfDate) {
        LocalDate effective = asOfDate;
        if (effective == null) {
            PmMarketLiquidityPercentileDailyEntity newest = dailyMapper.selectOne(
                    new LambdaQueryWrapper<PmMarketLiquidityPercentileDailyEntity>()
                            .orderByDesc(PmMarketLiquidityPercentileDailyEntity::getAsOfDate)
                            .last("limit 1"));
            if (newest == null) return List.of();
            effective = newest.getAsOfDate();
        }
        List<PmMarketLiquidityPercentileDailyEntity> rows = dailyMapper.selectList(
                new LambdaQueryWrapper<PmMarketLiquidityPercentileDailyEntity>()
                        .eq(PmMarketLiquidityPercentileDailyEntity::getAsOfDate, effective)
                        .orderByAsc(PmMarketLiquidityPercentileDailyEntity::getCategory));
        return rows.stream().map(MarketLiquidityPercentileView::from).collect(Collectors.toList());
    }

    /**
     * 返回单一 category 的最近 N 天 trend, 按 as_of_date desc.
     */
    public List<MarketLiquidityPercentileView> historyForCategory(String category, Integer limitDays) {
        if (!StringUtils.hasText(category)) return List.of();
        int lim = limitDays == null || limitDays <= 0 ? DEFAULT_LIMIT : Math.min(limitDays, MAX_LIMIT);
        String normalized = category.trim().toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate since = today.minusDays(lim);
        List<PmMarketLiquidityPercentileDailyEntity> rows = dailyMapper.selectList(
                new LambdaQueryWrapper<PmMarketLiquidityPercentileDailyEntity>()
                        .eq(PmMarketLiquidityPercentileDailyEntity::getCategory, normalized)
                        .ge(PmMarketLiquidityPercentileDailyEntity::getAsOfDate, since)
                        .orderByDesc(PmMarketLiquidityPercentileDailyEntity::getAsOfDate)
                        .last("limit " + lim));
        return rows.stream().map(MarketLiquidityPercentileView::from).collect(Collectors.toList());
    }
}
