package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmResolutionAuditEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * W1-T2.2 结算审计持久化 mapper.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.
 */
@Mapper
public interface PmResolutionAuditMapper extends BaseMapper<PmResolutionAuditEntity> {
}
