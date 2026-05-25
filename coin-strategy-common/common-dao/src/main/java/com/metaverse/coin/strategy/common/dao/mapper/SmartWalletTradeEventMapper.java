package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

@Mapper
public interface SmartWalletTradeEventMapper extends BaseMapper<SmartWalletTradeEventEntity> {

    /**
     * T-B7：指定时间窗内 smart_wallet_trade_event 的摄取时延（opened_at→created_at，单位秒）列表，
     * 用于计算 AVG / P90。仅取 opened_at / created_at 均非空的行，按 created_at 倒序取最近的样本，
     * 至多 {@code maxSamples} 条（极端流量下的安全上限）。
     */
    @Select("SELECT TIMESTAMPDIFF(SECOND, opened_at, created_at) "
            + "FROM smart_wallet_trade_event "
            + "WHERE opened_at IS NOT NULL AND created_at IS NOT NULL AND created_at >= #{since} "
            + "ORDER BY created_at DESC "
            + "LIMIT #{maxSamples}")
    List<Long> selectIngestLagSeconds(@Param("since") Date since, @Param("maxSamples") int maxSamples);
}
