package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageRiskSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SingleExchangeArbitrageRiskSnapshotMapper extends BaseMapper<SingleExchangeArbitrageRiskSnapshot> {
}
