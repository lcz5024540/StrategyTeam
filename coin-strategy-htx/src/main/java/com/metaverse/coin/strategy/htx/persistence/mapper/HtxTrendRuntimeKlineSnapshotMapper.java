package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendRuntimeKlineSnapshotMapper extends BaseMapper<HtxTrendRuntimeKlineSnapshotEntity> {

    @Select("SELECT id, snapshot_key, run_id, factor_snapshot_id, position_id, capture_stage, contract_code, period, "
            + "target_bar_time, window_start_bar_time, window_end_bar_time, expected_window_size, actual_window_size, "
            + "complete_window, window_sha256, source_kind, source_reference, data_integrity, captured_at "
            + "FROM htx_trend_runtime_kline_snapshot WHERE snapshot_key = #{snapshotKey} LIMIT 1")
    HtxTrendRuntimeKlineSnapshotEntity findBySnapshotKey(@Param("snapshotKey") String snapshotKey);
}
