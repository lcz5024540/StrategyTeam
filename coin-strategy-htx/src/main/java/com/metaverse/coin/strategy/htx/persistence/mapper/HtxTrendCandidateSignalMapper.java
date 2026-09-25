package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface HtxTrendCandidateSignalMapper extends BaseMapper<HtxTrendCandidateSignalEntity> {

    String COLUMNS = "id, contract_code, period, bar_time, direction, regime, signal_score, long_score, "
            + "short_score, score_gap, close_price, ema_fast, ema_medium, ema_slow, breakout_high, breakout_low, "
            + "atr, atr_pct, adx, momentum_pct, volume_ratio, entry_reason, invalidation_reason, shadow, "
            + "model_version, primary_model, confirmation_models, policy_version, run_id, factor_snapshot_id, gate_result, "
            + "gate_reason, execution_gate_result, execution_gate_reason, execution_evaluated_at, invalidation_price, entry_hypothesis, sizing_multiplier, planned_notional_usdt, "
            + "target_zone_low, target_zone_high, box_measured_move_target, fib_extension_1272, fib_extension_1618, "
            + "expected_r_multiple, momentum_quality_score, created_at";

    @Select("SELECT " + COLUMNS + " FROM htx_trend_candidate_signal "
            + "WHERE contract_code = #{contractCode} AND period = #{period} AND model_version = #{modelVersion} "
            + "ORDER BY id DESC LIMIT 1")
    HtxTrendCandidateSignalEntity findLatest(@Param("contractCode") String contractCode,
                                             @Param("period") String period,
                                             @Param("modelVersion") String modelVersion);

    /**
     * Returns only candidates that entered the real execution path and have a durable strategy position.
     * A replay compares exit policies from actual entry prices and fees; broad model observations belong
     * to the candidate analytics ledger and must not trigger market-data freezing here.
     */
    @Select("SELECT c.id, c.contract_code, c.period, c.bar_time, c.direction, c.regime, c.signal_score, c.long_score, "
            + "c.short_score, c.score_gap, c.close_price, c.ema_fast, c.ema_medium, c.ema_slow, c.breakout_high, c.breakout_low, "
            + "c.atr, c.atr_pct, c.adx, c.momentum_pct, c.volume_ratio, c.entry_reason, c.invalidation_reason, c.shadow, "
            + "c.model_version, c.primary_model, c.confirmation_models, c.policy_version, c.run_id, c.factor_snapshot_id, c.gate_result, "
            + "c.gate_reason, c.execution_gate_result, c.execution_gate_reason, c.execution_evaluated_at, c.invalidation_price, c.entry_hypothesis, "
            + "c.sizing_multiplier, c.planned_notional_usdt, c.target_zone_low, c.target_zone_high, c.box_measured_move_target, "
            + "c.fib_extension_1272, c.fib_extension_1618, c.expected_r_multiple, c.momentum_quality_score, c.created_at "
            + "FROM htx_trend_candidate_signal c INNER JOIN htx_trend_position p ON p.run_id = c.run_id "
            + "WHERE c.bar_time >= #{startBarTime} AND c.bar_time <= #{endBarTime} "
            + "AND c.execution_gate_result IN ('PASSED', 'ORDER_SUBMITTED') ORDER BY c.bar_time, c.id")
    List<HtxTrendCandidateSignalEntity> findReplayExecutedCandidatesBetween(@Param("startBarTime") long startBarTime,
                                                                             @Param("endBarTime") long endBarTime);

    @Update("UPDATE htx_trend_candidate_signal SET execution_gate_result = #{result}, "
            + "execution_gate_reason = #{reason}, execution_evaluated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE run_id = #{runId} AND contract_code = #{contractCode} AND gate_result = 'PASSED'")
    int markExecutionGate(@Param("runId") String runId, @Param("contractCode") String contractCode,
                          @Param("result") String result, @Param("reason") String reason);
}
