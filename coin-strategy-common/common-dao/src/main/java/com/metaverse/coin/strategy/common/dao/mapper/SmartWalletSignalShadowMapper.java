package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * W1-T4 被拒信号影子记账 mapper.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md.
 */
@Mapper
public interface SmartWalletSignalShadowMapper extends BaseMapper<SmartWalletSignalShadowEntity> {
}
