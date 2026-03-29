package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmartWalletTradeEventMapper extends BaseMapper<SmartWalletTradeEventEntity> {
}
