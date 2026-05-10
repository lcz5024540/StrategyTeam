package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SmartWalletTradeOrderMapper extends BaseMapper<SmartWalletTradeOrderEntity> {

    /**
     * Find trade orders that are still OPEN but whose positions have reached terminal states.
     * These are stale and need to be rebuilt.
     */
    @Select("SELECT t.position_id FROM smart_wallet_trade_order t"
            + " JOIN smart_wallet_position p ON p.id = t.position_id"
            + " WHERE t.status = 'OPEN'"
            + "   AND p.status IN ('CLOSED', 'RESOLVED_UNCLAIMED', 'CLAIMED')"
            + " LIMIT 500")
    List<Long> findStaleOpenPositionIds();
}
