package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletCashLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface SmartWalletCashLedgerMapper extends BaseMapper<SmartWalletCashLedgerEntity> {

    /**
     * SUM(amount) for given entry_type with mode filter (unsigned sum).
     */
    @Select("<script>"
            + "SELECT COALESCE(SUM(amount), 0) FROM smart_wallet_cash_ledger"
            + "<where>"
            + "  <if test=\"entryType != null\"> AND entry_type = #{entryType} </if>"
            + "  <if test=\"mode == 'PAPER'\">"
            + "    AND (execution_address IS NULL OR execution_address = 'paper-book')"
            + "  </if>"
            + "  <if test=\"mode == 'LIVE'\">"
            + "    AND execution_address IS NOT NULL AND execution_address != 'paper-book'"
            + "  </if>"
            + "  <if test=\"referenceType != null\"> AND reference_type = #{referenceType} </if>"
            + "  <if test=\"referenceId != null\"> AND reference_id = #{referenceId} </if>"
            + "</where>"
            + "</script>")
    BigDecimal sumAmountByMode(@Param("entryType") String entryType,
                               @Param("mode") String mode,
                               @Param("referenceType") String referenceType,
                               @Param("referenceId") Long referenceId);

    /**
     * SUM with direction sign: IN → +amount, OUT → -amount.
     */
    @Select("<script>"
            + "SELECT COALESCE(SUM(CASE WHEN direction = 'OUT' THEN -amount ELSE amount END), 0)"
            + " FROM smart_wallet_cash_ledger"
            + "<where>"
            + "  <if test=\"entryType != null\"> AND entry_type = #{entryType} </if>"
            + "  <if test=\"mode == 'PAPER'\">"
            + "    AND (execution_address IS NULL OR execution_address = 'paper-book')"
            + "  </if>"
            + "  <if test=\"mode == 'LIVE'\">"
            + "    AND execution_address IS NOT NULL AND execution_address != 'paper-book'"
            + "  </if>"
            + "  <if test=\"referenceType != null\"> AND reference_type = #{referenceType} </if>"
            + "  <if test=\"referenceId != null\"> AND reference_id = #{referenceId} </if>"
            + "</where>"
            + "</script>")
    BigDecimal sumSignedAmountByMode(@Param("entryType") String entryType,
                                     @Param("mode") String mode,
                                     @Param("referenceType") String referenceType,
                                     @Param("referenceId") Long referenceId);
}
