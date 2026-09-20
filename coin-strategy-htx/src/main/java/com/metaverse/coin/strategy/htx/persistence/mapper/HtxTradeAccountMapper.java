package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTradeAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HtxTradeAccountMapper extends BaseMapper<HtxTradeAccountEntity> {

    String COLUMNS = "id, account_label, exchange, market_type, account_role, enabled, trend_enabled, mirror_enabled, "
            + "api_key_cipher, secret_key_cipher, base_url, sync_leverage, block_entry_on_leverage_sync_failure, "
            + "block_primary_close_until_synced, sort_order, note, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_trade_account WHERE enabled = 1 AND trend_enabled = 1 "
            + "AND account_role = 'PRIMARY' ORDER BY sort_order, id LIMIT 1")
    HtxTradeAccountEntity findPrimaryTrendAccount();

    @Select("SELECT " + COLUMNS + " FROM htx_trade_account WHERE enabled = 1 AND trend_enabled = 1 "
            + "AND mirror_enabled = 1 AND account_role <> 'PRIMARY' ORDER BY sort_order, id")
    List<HtxTradeAccountEntity> findTrendMirrorAccounts();
}
