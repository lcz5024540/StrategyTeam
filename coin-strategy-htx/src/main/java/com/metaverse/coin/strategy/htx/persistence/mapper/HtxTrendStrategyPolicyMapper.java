package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStrategyPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HtxTrendStrategyPolicyMapper extends BaseMapper<HtxTrendStrategyPolicyEntity> {

    @Select("SELECT id, policy_code, policy_version, primary_model, confirmation_models, enabled, "
            + "base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars, updated_by, change_note, "
            + "created_at, updated_at FROM htx_trend_strategy_policy WHERE enabled = 1 ORDER BY updated_at DESC, id DESC")
    List<HtxTrendStrategyPolicyEntity> findEnabled();
}
