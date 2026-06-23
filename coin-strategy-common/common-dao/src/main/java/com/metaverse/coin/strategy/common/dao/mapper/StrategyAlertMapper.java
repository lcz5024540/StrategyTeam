package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 策略告警记录 Mapper。
 */
@Mapper
public interface StrategyAlertMapper extends BaseMapper<StrategyAlertEntity> {
}
