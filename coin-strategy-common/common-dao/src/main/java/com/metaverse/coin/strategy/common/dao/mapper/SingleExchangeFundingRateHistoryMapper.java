package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeFundingRateHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SingleExchangeFundingRateHistoryMapper extends BaseMapper<SingleExchangeFundingRateHistory> {
}
