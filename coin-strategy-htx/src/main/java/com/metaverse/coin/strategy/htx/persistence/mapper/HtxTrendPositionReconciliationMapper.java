package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionReconciliationEntity;
import org.apache.ibatis.annotations.Mapper;

/** Append-only reconciliation evidence; duplicate keys make a restart harmless. */
@Mapper
public interface HtxTrendPositionReconciliationMapper extends BaseMapper<HtxTrendPositionReconciliationEntity> {
}
