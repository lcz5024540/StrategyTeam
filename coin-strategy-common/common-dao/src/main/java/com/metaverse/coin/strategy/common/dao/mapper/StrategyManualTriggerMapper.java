package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 策略手动触发命令 Mapper。
 */
@Mapper
public interface StrategyManualTriggerMapper extends BaseMapper<StrategyManualTriggerEntity> {
}
