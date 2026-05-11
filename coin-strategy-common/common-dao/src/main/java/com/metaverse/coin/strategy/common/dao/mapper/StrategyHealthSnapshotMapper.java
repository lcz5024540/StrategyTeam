package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyHealthSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 策略健康快照 Mapper。
 */
@Mapper
public interface StrategyHealthSnapshotMapper extends BaseMapper<StrategyHealthSnapshotEntity> {
}
