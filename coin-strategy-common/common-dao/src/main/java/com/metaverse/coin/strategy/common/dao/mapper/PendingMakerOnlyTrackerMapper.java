package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PendingMakerOnlyTrackerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * F-P1-4: MAKER_ONLY / MAKER_FIRST 超时跟踪表 Mapper。
 *
 * <p>由 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper} 提供 CRUD：</p>
 * <ul>
 *   <li>{@code selectList(QueryWrapper.lambda().eq(PendingMakerOnlyTrackerEntity::getStatus, "PENDING"))}
 *       — 启动期 {@code @PostConstruct} 回放扫表重建 in-memory tracker；</li>
 *   <li>{@code insert} — LIVE 网关在成功下 MAKER_ONLY / MAKER_FIRST 单后调用；</li>
 *   <li>{@code updateById} — scan 撤单成功 / 失败后回写 status。</li>
 * </ul>
 *
 * <p>DDL: {@code sql/polymarket/2026-05-17__p1_tracker_persistence_draft.sql}。</p>
 */
@Mapper
public interface PendingMakerOnlyTrackerMapper extends BaseMapper<PendingMakerOnlyTrackerEntity> {
}
