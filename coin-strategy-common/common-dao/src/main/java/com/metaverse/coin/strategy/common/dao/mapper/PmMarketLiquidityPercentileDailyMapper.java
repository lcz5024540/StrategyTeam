package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmMarketLiquidityPercentileDailyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * W2-T1.2 市场流动性 + 价差日级分位快照 mapper.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十六节.
 */
@Mapper
public interface PmMarketLiquidityPercentileDailyMapper
        extends BaseMapper<PmMarketLiquidityPercentileDailyEntity> {
}
