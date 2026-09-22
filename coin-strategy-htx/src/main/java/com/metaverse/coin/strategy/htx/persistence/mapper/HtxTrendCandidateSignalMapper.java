package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendCandidateSignalMapper extends BaseMapper<HtxTrendCandidateSignalEntity> {

    String COLUMNS = "id, contract_code, period, bar_time, direction, regime, signal_score, long_score, "
            + "short_score, score_gap, close_price, ema_fast, ema_medium, ema_slow, breakout_high, breakout_low, "
            + "atr, atr_pct, adx, momentum_pct, volume_ratio, entry_reason, invalidation_reason, shadow, "
            + "model_version, primary_model, confirmation_models, policy_version, factor_snapshot_id, gate_result, "
            + "gate_reason, invalidation_price, entry_hypothesis, sizing_multiplier, planned_notional_usdt, "
            + "target_zone_low, target_zone_high, box_measured_move_target, fib_extension_1272, fib_extension_1618, "
            + "expected_r_multiple, momentum_quality_score, created_at";

    @Select("SELECT " + COLUMNS + " FROM htx_trend_candidate_signal "
            + "WHERE contract_code = #{contractCode} AND period = #{period} AND model_version = #{modelVersion} "
            + "ORDER BY id DESC LIMIT 1")
    HtxTrendCandidateSignalEntity findLatest(@Param("contractCode") String contractCode,
                                             @Param("period") String period,
                                             @Param("modelVersion") String modelVersion);
}
