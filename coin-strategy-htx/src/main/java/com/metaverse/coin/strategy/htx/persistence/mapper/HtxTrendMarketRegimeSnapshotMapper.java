package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendMarketRegimeSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendMarketRegimeSnapshotMapper extends BaseMapper<HtxTrendMarketRegimeSnapshotEntity> {

    @Select("SELECT id, snapshot_key, dataset_key, scope, contract_code, bar_time, state_code, daily_direction, "
            + "four_hour_direction, one_hour_direction, breadth_ratio, volatility_score, crowding_score, evidence_json, "
            + "data_integrity, created_at FROM htx_trend_market_regime_snapshot WHERE snapshot_key = #{snapshotKey}")
    HtxTrendMarketRegimeSnapshotEntity findBySnapshotKey(@Param("snapshotKey") String snapshotKey);
}
