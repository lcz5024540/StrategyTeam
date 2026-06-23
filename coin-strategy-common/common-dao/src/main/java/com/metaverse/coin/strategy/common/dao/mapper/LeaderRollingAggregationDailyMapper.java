package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LeaderRollingAggregationDailyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * W2-T2 灰度自动化 mapper.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-17.md 第三节.
 */
@Mapper
public interface LeaderRollingAggregationDailyMapper
        extends BaseMapper<LeaderRollingAggregationDailyEntity> {
}
