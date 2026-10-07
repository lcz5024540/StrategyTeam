package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendOrderLedgerPnl;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendPerformanceStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface HtxTrendPositionMapper extends BaseMapper<HtxTrendPositionEntity> {

    String COLUMNS = "id, source_position_id, position_key, run_id, contract_code, status, side, contract_size, contract_volume, remaining_contract_volume, partial_exit_stage, partial_close_client_order_id, partial_close_order_id, leverage, "
            + "entry_client_order_id, entry_order_id, entry_filled_contracts, entry_avg_price, entry_fee, "
            + "close_client_order_id, close_order_id, close_filled_contracts, close_avg_price, close_fee, "
            + "realized_profit, final_gross_profit, final_total_fee, final_net_profit, final_pnl_source, final_pnl_calculated_at, "
            + "stop_price, trailing_stop_price, take_profit_price, highest_price, lowest_price, "
            + "entry_signal, exit_reason, message, account_id, account_label, strategy_family, strategy_name, model_name, entry_fee_rate, entry_fee_profile_id, estimated_entry_fee, "
            + "estimated_close_fee, expected_net_after_fees, sizing_multiplier, sizing_reason, entry_gate_reason, "
            + "primary_model, confirmation_models, policy_version, timeframe_profile, market_regime, factor_snapshot_id, "
            + "entry_hypothesis, invalidation_price, target_zone_low, target_zone_high, expected_r_multiple, momentum_quality_score, "
            + "exit_snapshot_state, exit_snapshot_net, exit_snapshot_mfe, exit_snapshot_mae, exit_snapshot_giveback, "
            + "exit_snapshot_bars, exit_snapshot_candidates, exit_snapshot_updated_at, exit_phase, trailing_armed_at, "
            + "trailing_arm_reason, max_favorable_r, profit_lock_price, profit_protection_mfe_price, "
            + "profit_protection_mfe_net_pnl, profit_protection_price, profit_protection_projected_net_pnl, "
            + "profit_protection_capture_ratio, profit_protection_mode, profit_protection_updated_at, exit_engine_version, "
            + "exit_attribution_snapshot_id, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_trend_position WHERE contract_code = #{contractCode} "
            + "AND source_position_id IS NULL AND account_label = 'primary' "
            + "AND status IN ('INTENT', 'ENTRY_SENT', 'ACTIVE', 'PARTIAL_CLOSE_SENT', 'CLOSE_SENT', 'UNKNOWN') "
            + "ORDER BY id LIMIT 1")
    HtxTrendPositionEntity findActive(@Param("contractCode") String contractCode);

    @Select("SELECT " + COLUMNS + " FROM htx_trend_position WHERE run_id = #{runId} "
            + "AND source_position_id IS NULL AND account_label = 'primary' ORDER BY id DESC LIMIT 1")
    HtxTrendPositionEntity findByRunId(@Param("runId") String runId);

    @Select("SELECT COUNT(*) FROM htx_trend_position "
            + "WHERE source_position_id IS NULL AND account_label = 'primary' "
            + "AND status IN ('INTENT', 'ENTRY_SENT', 'ACTIVE', 'PARTIAL_CLOSE_SENT', 'CLOSE_SENT', 'UNKNOWN')")
    Long countOpenLike();

    @Select("SELECT " + COLUMNS + " FROM htx_trend_position WHERE contract_code = #{contractCode} "
            + "AND source_position_id IS NULL AND account_label = 'primary' AND status = 'DONE' ORDER BY updated_at DESC LIMIT 1")
    HtxTrendPositionEntity findLastDone(@Param("contractCode") String contractCode);

    @Select("SELECT " + COLUMNS + " FROM htx_trend_position WHERE source_position_id = #{sourcePositionId} "
            + "AND account_label = #{accountLabel} ORDER BY id DESC LIMIT 1")
    HtxTrendPositionEntity findMirrorPosition(@Param("sourcePositionId") long sourcePositionId,
                                               @Param("accountLabel") String accountLabel);

    @Update("UPDATE htx_trend_position SET status = #{nextStatus}, entry_order_id = #{orderId}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int markEntryAccepted(@Param("id") long id,
                          @Param("expectedStatus") String expectedStatus,
                          @Param("nextStatus") String nextStatus,
                          @Param("orderId") Long orderId,
                          @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'ACTIVE', entry_filled_contracts = #{filledContracts}, "
            + "remaining_contract_volume = FLOOR(#{filledContracts}), partial_exit_stage = 0, "
            + "entry_avg_price = #{avgPrice}, entry_fee = #{fee}, highest_price = #{highestPrice}, "
            + "lowest_price = #{lowestPrice}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id}")
    int markEntryFilled(@Param("id") long id,
                        @Param("filledContracts") BigDecimal filledContracts,
                        @Param("avgPrice") BigDecimal avgPrice,
                        @Param("fee") BigDecimal fee,
                        @Param("highestPrice") BigDecimal highestPrice,
                        @Param("lowestPrice") BigDecimal lowestPrice,
                        @Param("message") String message);

    @Update("UPDATE htx_trend_position SET stop_price = #{stopPrice}, trailing_stop_price = #{trailingStopPrice}, "
            + "take_profit_price = #{takeProfitPrice}, highest_price = #{highestPrice}, lowest_price = #{lowestPrice}, "
            + "exit_snapshot_state = #{exitSnapshotState}, exit_snapshot_net = #{exitSnapshotNet}, "
            + "exit_snapshot_mfe = #{exitSnapshotMfe}, exit_snapshot_mae = #{exitSnapshotMae}, "
            + "exit_snapshot_giveback = #{exitSnapshotGiveback}, exit_snapshot_bars = #{exitSnapshotBars}, "
            + "exit_snapshot_candidates = #{exitSnapshotCandidates}, exit_snapshot_updated_at = CURRENT_TIMESTAMP(3), "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateRiskLevels(@Param("id") long id,
                         @Param("stopPrice") BigDecimal stopPrice,
                         @Param("trailingStopPrice") BigDecimal trailingStopPrice,
                         @Param("takeProfitPrice") BigDecimal takeProfitPrice,
                         @Param("highestPrice") BigDecimal highestPrice,
                         @Param("lowestPrice") BigDecimal lowestPrice,
                         @Param("message") String message,
                         @Param("exitSnapshotState") String exitSnapshotState,
                         @Param("exitSnapshotNet") BigDecimal exitSnapshotNet,
                         @Param("exitSnapshotMfe") BigDecimal exitSnapshotMfe,
                         @Param("exitSnapshotMae") BigDecimal exitSnapshotMae,
                         @Param("exitSnapshotGiveback") BigDecimal exitSnapshotGiveback,
                         @Param("exitSnapshotBars") Long exitSnapshotBars,
                         @Param("exitSnapshotCandidates") String exitSnapshotCandidates);

    /** Persist restart-safe exit state without changing the initial stop or any order status. */
    @Update("UPDATE htx_trend_position SET exit_phase = #{exitPhase}, "
            + "trailing_armed_at = COALESCE(trailing_armed_at, #{trailingArmedAt}), "
            + "trailing_arm_reason = COALESCE(trailing_arm_reason, #{trailingArmReason}), "
            + "max_favorable_r = CASE WHEN max_favorable_r IS NULL OR max_favorable_r < #{maxFavorableR} "
            + "THEN #{maxFavorableR} ELSE max_favorable_r END, "
            + "profit_lock_price = CASE WHEN #{profitLockPrice} IS NULL THEN profit_lock_price "
            + "WHEN profit_lock_price IS NULL THEN #{profitLockPrice} "
            + "WHEN side = 'LONG' THEN GREATEST(profit_lock_price, #{profitLockPrice}) "
            + "WHEN side = 'SHORT' THEN LEAST(profit_lock_price, #{profitLockPrice}) "
            + "ELSE profit_lock_price END, exit_engine_version = #{exitEngineVersion}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateExitEngineState(@Param("id") long id,
                              @Param("exitPhase") String exitPhase,
                              @Param("trailingArmedAt") LocalDateTime trailingArmedAt,
                              @Param("trailingArmReason") String trailingArmReason,
                              @Param("maxFavorableR") BigDecimal maxFavorableR,
                              @Param("profitLockPrice") BigDecimal profitLockPrice,
                              @Param("exitEngineVersion") String exitEngineVersion);

    /** P0 only persists a monotonic MFE protection recommendation; it never creates an exchange order. */
    @Update("UPDATE htx_trend_position SET "
            + "profit_protection_mfe_price = CASE WHEN profit_protection_mfe_price IS NULL THEN #{mfePrice} "
            + "WHEN side = 'LONG' THEN GREATEST(profit_protection_mfe_price, #{mfePrice}) "
            + "WHEN side = 'SHORT' THEN LEAST(profit_protection_mfe_price, #{mfePrice}) ELSE profit_protection_mfe_price END, "
            + "profit_protection_mfe_net_pnl = CASE WHEN profit_protection_mfe_net_pnl IS NULL "
            + "OR profit_protection_mfe_net_pnl < #{mfeNetPnl} THEN #{mfeNetPnl} ELSE profit_protection_mfe_net_pnl END, "
            + "profit_protection_price = CASE WHEN profit_protection_price IS NULL THEN #{protectionPrice} "
            + "WHEN side = 'LONG' THEN GREATEST(profit_protection_price, #{protectionPrice}) "
            + "WHEN side = 'SHORT' THEN LEAST(profit_protection_price, #{protectionPrice}) ELSE profit_protection_price END, "
            + "profit_protection_projected_net_pnl = #{projectedLockedNetPnl}, "
            + "profit_protection_capture_ratio = #{mfeCaptureRatio}, profit_protection_mode = #{protectionMode}, "
            + "profit_protection_updated_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateProfitProtectionState(@Param("id") long id,
                                    @Param("mfePrice") BigDecimal mfePrice,
                                    @Param("mfeNetPnl") BigDecimal mfeNetPnl,
                                    @Param("protectionPrice") BigDecimal protectionPrice,
                                    @Param("projectedLockedNetPnl") BigDecimal projectedLockedNetPnl,
                                    @Param("mfeCaptureRatio") BigDecimal mfeCaptureRatio,
                                    @Param("protectionMode") String protectionMode);

    @Update("UPDATE htx_trend_position SET status = 'CLOSE_SENT', exit_reason = #{exitReason}, "
            + "close_client_order_id = #{clientOrderId}, close_order_id = #{orderId}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markCloseAccepted(@Param("id") long id,
                          @Param("exitReason") String exitReason,
                          @Param("clientOrderId") String clientOrderId,
                          @Param("orderId") Long orderId,
                          @Param("message") String message);


    @Update("UPDATE htx_trend_position SET status = 'PARTIAL_CLOSE_SENT', exit_reason = #{message}, "
            + "partial_close_client_order_id = #{clientOrderId}, partial_close_order_id = #{orderId}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markPartialCloseAccepted(@Param("id") long id,
                                 @Param("clientOrderId") String clientOrderId,
                                 @Param("orderId") Long orderId,
                                 @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'ACTIVE', partial_exit_stage = #{partialExitStage}, "
            + "remaining_contract_volume = GREATEST(0, COALESCE(remaining_contract_volume, contract_volume) - FLOOR(#{filledContracts})), "
            + "close_avg_price = CASE WHEN COALESCE(close_filled_contracts, 0) + #{filledContracts} > 0 "
            + "THEN ((COALESCE(close_avg_price, 0) * COALESCE(close_filled_contracts, 0)) + (#{avgPrice} * #{filledContracts})) "
            + "/ (COALESCE(close_filled_contracts, 0) + #{filledContracts}) ELSE #{avgPrice} END, "
            + "close_filled_contracts = COALESCE(close_filled_contracts, 0) + #{filledContracts}, "
            + "close_fee = COALESCE(close_fee, 0) + #{fee}, "
            + "realized_profit = COALESCE(realized_profit, 0) + COALESCE(#{realizedProfit}, 0), "
            + "partial_close_client_order_id = NULL, partial_close_order_id = NULL, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markPartialCloseFilled(@Param("id") long id,
                               @Param("partialExitStage") int partialExitStage,
                               @Param("filledContracts") BigDecimal filledContracts,
                               @Param("avgPrice") BigDecimal avgPrice,
                               @Param("fee") BigDecimal fee,
                               @Param("realizedProfit") BigDecimal realizedProfit,
                               @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'ACTIVE', "
            + "remaining_contract_volume = GREATEST(0, COALESCE(remaining_contract_volume, contract_volume) - FLOOR(#{filledContracts})), "
            + "close_avg_price = CASE WHEN COALESCE(close_filled_contracts, 0) + #{filledContracts} > 0 "
            + "THEN ((COALESCE(close_avg_price, 0) * COALESCE(close_filled_contracts, 0)) + (#{avgPrice} * #{filledContracts})) "
            + "/ (COALESCE(close_filled_contracts, 0) + #{filledContracts}) ELSE #{avgPrice} END, "
            + "close_filled_contracts = COALESCE(close_filled_contracts, 0) + #{filledContracts}, "
            + "close_fee = COALESCE(close_fee, 0) + #{fee}, "
            + "realized_profit = COALESCE(realized_profit, 0) + COALESCE(#{realizedProfit}, 0), "
            + "close_client_order_id = NULL, close_order_id = NULL, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markClosePartiallyFilled(@Param("id") long id,
                                 @Param("filledContracts") BigDecimal filledContracts,
                                 @Param("avgPrice") BigDecimal avgPrice,
                                 @Param("fee") BigDecimal fee,
                                 @Param("realizedProfit") BigDecimal realizedProfit,
                                 @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'DONE', remaining_contract_volume = 0, "
            + "close_avg_price = CASE WHEN COALESCE(close_filled_contracts, 0) + #{filledContracts} > 0 "
            + "THEN ((COALESCE(close_avg_price, 0) * COALESCE(close_filled_contracts, 0)) + (#{avgPrice} * #{filledContracts})) "
            + "/ (COALESCE(close_filled_contracts, 0) + #{filledContracts}) ELSE #{avgPrice} END, "
            + "close_filled_contracts = COALESCE(close_filled_contracts, 0) + #{filledContracts}, "
            + "close_fee = COALESCE(close_fee, 0) + #{fee}, "
            + "realized_profit = COALESCE(realized_profit, 0) + COALESCE(#{realizedProfit}, 0), "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markCloseFilled(@Param("id") long id,
                        @Param("filledContracts") BigDecimal filledContracts,
                        @Param("avgPrice") BigDecimal avgPrice,
                        @Param("fee") BigDecimal fee,
                        @Param("realizedProfit") BigDecimal realizedProfit,
                        @Param("message") String message);

    /**
     * Final PnL is an order-ledger aggregate. In particular, a partial close followed by a runner close
     * must include every filled leg and must never reuse the last exit-engine snapshot.
     */
    @Select("SELECT COUNT(*) AS filled_order_count, "
            + "COALESCE(SUM(COALESCE(realized_profit, 0)), 0) AS gross_profit, "
            + "COALESCE(SUM(ABS(COALESCE(fee, 0))), 0) AS total_fee, "
            + "COALESCE(SUM(COALESCE(realized_profit, 0)), 0) - COALESCE(SUM(ABS(COALESCE(fee, 0))), 0) AS net_profit "
            + "FROM htx_trend_order WHERE position_id = #{positionId} "
            + "AND status IN ('FILLED', 'PARTIALLY_FILLED')")
    HtxTrendOrderLedgerPnl aggregateFilledOrderLedger(@Param("positionId") long positionId);

    @Update("UPDATE htx_trend_position SET final_gross_profit = #{grossProfit}, final_total_fee = #{totalFee}, "
            + "final_net_profit = #{netProfit}, final_pnl_source = 'ORDER_LEDGER', "
            + "final_pnl_calculated_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{positionId}")
    int updateFinalOrderLedgerPnl(@Param("positionId") long positionId,
                                  @Param("grossProfit") BigDecimal grossProfit,
                                  @Param("totalFee") BigDecimal totalFee,
                                  @Param("netProfit") BigDecimal netProfit);

    @Update("UPDATE htx_trend_position SET exit_attribution_snapshot_id = #{snapshotId}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markExitAttributionSnapshotId(@Param("id") long id, @Param("snapshotId") long snapshotId);

    @Update("UPDATE htx_trend_position SET status = #{nextStatus}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markStatus(@Param("id") long id,
                   @Param("nextStatus") String nextStatus,
                   @Param("message") String message);

    /**
     * The exchange has authoritatively reported zero volume twice, but no strategy close fill can be
     * linked by exchange/client order id.  Keep the unknown external result out of strategy PnL while
     * releasing the local capacity it would otherwise occupy forever.
     */
    @Update("UPDATE htx_trend_position SET status = 'RECONCILED_CLOSED', remaining_contract_volume = 0, "
            + "exit_reason = #{exitReason}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markReconciledClosed(@Param("id") long id,
                             @Param("exitReason") String exitReason,
                             @Param("message") String message);

    @Select("SELECT MAX(updated_at) FROM htx_trend_position WHERE contract_code = #{contractCode} AND status = 'DONE'")
    LocalDateTime latestDoneAt(@Param("contractCode") String contractCode);

    @Select("SELECT COUNT(*) AS closedCount, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit > 0 THEN 1 ELSE 0 END), 0) AS winCount, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit < 0 THEN 1 ELSE 0 END), 0) AS lossCount, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit > 0 THEN recent.net_profit ELSE 0 END), 0) AS grossProfit, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit < 0 THEN -recent.net_profit ELSE 0 END), 0) AS grossLoss, "
            + "COALESCE(SUM(recent.net_profit), 0) AS netProfit, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit > 0 THEN recent.net_profit ELSE 0 END) "
            + "/ NULLIF(SUM(CASE WHEN recent.net_profit > 0 THEN 1 ELSE 0 END), 0), 0) AS averageWin, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit < 0 THEN -recent.net_profit ELSE 0 END) "
            + "/ NULLIF(SUM(CASE WHEN recent.net_profit < 0 THEN 1 ELSE 0 END), 0), 0) AS averageLoss, "
            + "COALESCE(SUM(recent.net_profit) / NULLIF(COUNT(*), 0), 0) AS expectancy "
            + "FROM ("
            + "SELECT COALESCE(final_net_profit, COALESCE(realized_profit, 0) - COALESCE(entry_fee, 0) - COALESCE(close_fee, 0)) AS net_profit "
            + "FROM htx_trend_position "
            + "WHERE status = 'DONE' AND updated_at >= #{since} "
            + "ORDER BY updated_at DESC LIMIT #{limit}"
            + ") recent")
    HtxTrendPerformanceStats trendPerformanceSince(@Param("since") LocalDateTime since,
                                                   @Param("limit") int limit);

    /**
     * Returns closed-position performance for one direction/model/regime bucket.
     * Fees are deducted before classifying wins and losses, so this query never treats fee-only gains as profit.
     * An absent account label intentionally means all recorded accounts; callers must choose that scope explicitly.
     */
    @Select("SELECT COUNT(*) AS closedCount, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit > 0 THEN 1 ELSE 0 END), 0) AS winCount, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit < 0 THEN 1 ELSE 0 END), 0) AS lossCount, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit > 0 THEN recent.net_profit ELSE 0 END), 0) AS grossProfit, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit < 0 THEN -recent.net_profit ELSE 0 END), 0) AS grossLoss, "
            + "COALESCE(SUM(recent.net_profit), 0) AS netProfit, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit > 0 THEN recent.net_profit ELSE 0 END) "
            + "/ NULLIF(SUM(CASE WHEN recent.net_profit > 0 THEN 1 ELSE 0 END), 0), 0) AS averageWin, "
            + "COALESCE(SUM(CASE WHEN recent.net_profit < 0 THEN -recent.net_profit ELSE 0 END) "
            + "/ NULLIF(SUM(CASE WHEN recent.net_profit < 0 THEN 1 ELSE 0 END), 0), 0) AS averageLoss, "
            + "COALESCE(SUM(recent.net_profit) / NULLIF(COUNT(*), 0), 0) AS expectancy "
            + "FROM ("
            + "SELECT COALESCE(final_net_profit, COALESCE(realized_profit, 0) - COALESCE(entry_fee, 0) - COALESCE(close_fee, 0)) AS net_profit "
            + "FROM htx_trend_position "
            + "WHERE status = 'DONE' AND updated_at >= #{since} "
            + "AND side = #{side} AND primary_model = #{primaryModel} AND market_regime = #{marketRegime} "
            + "AND (#{accountLabel} IS NULL OR account_label = #{accountLabel}) "
            + "ORDER BY updated_at DESC LIMIT #{limit}"
            + ") recent")
    HtxTrendPerformanceStats trendPerformanceForBucket(@Param("since") LocalDateTime since,
                                                        @Param("limit") int limit,
                                                        @Param("side") String side,
                                                        @Param("primaryModel") String primaryModel,
                                                        @Param("marketRegime") String marketRegime,
                                                        @Param("accountLabel") String accountLabel);
}
