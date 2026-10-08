package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionMarkSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendPositionMarkSnapshotMapper extends BaseMapper<HtxTrendPositionMarkSnapshotEntity> {

    @Select("SELECT id, snapshot_key, position_id, run_id, account_id, account_label, contract_code, side, "
            + "primary_model, timeframe_profile, bar_time, reference_price, unrealized_net_pnl, mfe_net_pnl, "
            + "mae_net_pnl, giveback_rate, protection_price, trend_state, exit_phase, suggested_action, "
            + "execution_mode, evidence_json, created_at FROM htx_trend_position_mark_snapshot "
            + "WHERE snapshot_key = #{snapshotKey} LIMIT 1")
    HtxTrendPositionMarkSnapshotEntity findBySnapshotKey(@Param("snapshotKey") String snapshotKey);
}
