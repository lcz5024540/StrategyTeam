package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PendingOrderTimeoutTrackerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * F-P1-3: LIVE GTC 全局超时取消跟踪表 Mapper。
 *
 * <p>使用 MyBatis-Plus 默认 CRUD：</p>
 * <ul>
 *   <li>{@code selectList(QueryWrapper.lambda().eq(PendingOrderTimeoutTrackerEntity::getCancelStatus, "PENDING"))}
 *       — 启动期回放扫所有未处理的 tracker；</li>
 *   <li>{@code updateById} — 撤单成功 / 失败后回写 cancelStatus + lastAttemptAt；</li>
 *   <li>{@code insert} — LIVE 网关创建 GTC 单后调用。</li>
 * </ul>
 *
 * <p>DDL: {@code sql/polymarket/2026-05-17__p1_tracker_persistence_draft.sql}。</p>
 */
@Mapper
public interface PendingOrderTimeoutTrackerMapper extends BaseMapper<PendingOrderTimeoutTrackerEntity> {
}
