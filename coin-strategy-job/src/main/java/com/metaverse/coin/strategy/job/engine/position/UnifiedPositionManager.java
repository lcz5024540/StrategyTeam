package com.metaverse.coin.strategy.job.engine.position;

import java.util.List;

/**
 * 跨策略统一仓位管理接口。
 *
 * <p>底层仍然存储在各策略自己的表中（如 smart_wallet_position, weather_paper_position），
 * 但查询时通过 adapter 汇聚为统一视图。</p>
 *
 * <p><b>Phase 0</b>：定义接口。<br>
 * <b>Phase 1</b>：各策略实现 adapter 注册到此管理器。</p>
 */
public interface UnifiedPositionManager {

    /** 按策略查询持仓 */
    List<UnifiedPosition> getPositions(String strategyId);

    /** 全局持仓汇总 */
    List<UnifiedPosition> getAllPositions();

    /** 检查某个 market 上是否有跨策略的持仓（用于冲突检测） */
    CrossStrategyExposure checkExposure(String marketId);
}
