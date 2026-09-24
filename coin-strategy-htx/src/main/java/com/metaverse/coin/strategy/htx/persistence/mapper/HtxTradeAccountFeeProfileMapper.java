package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTradeAccountFeeProfileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface HtxTradeAccountFeeProfileMapper extends BaseMapper<HtxTradeAccountFeeProfileEntity> {

    String COLUMNS = "id, account_id, account_label, exchange, market_type, contract_code, open_maker_rate, "
            + "open_taker_rate, close_maker_rate, close_taker_rate, effective_taker_rate, source, fallback, "
            + "current_profile_key, effective_from, effective_to, last_observed_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_trade_account_fee_profile WHERE account_id = #{accountId} "
            + "AND market_type = #{marketType} AND contract_code = #{contractCode} "
            + "AND current_profile_key IS NOT NULL ORDER BY id DESC LIMIT 1")
    HtxTradeAccountFeeProfileEntity findCurrent(@Param("accountId") long accountId,
                                                @Param("marketType") String marketType,
                                                @Param("contractCode") String contractCode);

    @Update("UPDATE htx_trade_account_fee_profile SET last_observed_at = #{observedAt}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND current_profile_key IS NOT NULL")
    int touchCurrent(@Param("id") long id, @Param("observedAt") LocalDateTime observedAt);

    @Update("UPDATE htx_trade_account_fee_profile SET effective_to = #{effectiveTo}, current_profile_key = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND current_profile_key IS NOT NULL")
    int closeCurrent(@Param("id") long id, @Param("effectiveTo") LocalDateTime effectiveTo);
}
