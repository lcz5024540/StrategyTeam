package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LiveReplayDiffHistoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 下一轮 #7 Live↔Replay diff 历史 mapper.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.
 */
@Mapper
public interface LiveReplayDiffHistoryMapper extends BaseMapper<LiveReplayDiffHistoryEntity> {
}
