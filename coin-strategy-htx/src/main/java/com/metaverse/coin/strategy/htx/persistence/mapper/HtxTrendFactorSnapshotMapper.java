package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendFactorSnapshotMapper extends BaseMapper<HtxTrendFactorSnapshotEntity> {

    @Select("SELECT id, snapshot_key, contract_code, period, bar_time, market_regime, factors_json, created_at "
            + "FROM htx_trend_factor_snapshot WHERE snapshot_key = #{snapshotKey} LIMIT 1")
    HtxTrendFactorSnapshotEntity findBySnapshotKey(@Param("snapshotKey") String snapshotKey);
}
