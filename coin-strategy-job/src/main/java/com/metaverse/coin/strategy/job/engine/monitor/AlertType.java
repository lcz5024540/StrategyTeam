package com.metaverse.coin.strategy.job.engine.monitor;

/**
 * 告警类型枚举。
 */
public enum AlertType {

    /** 策略健康检查失败 */
    HEALTH_CHECK_FAIL(AlertLevel.CRITICAL, "策略健康检查失败"),

    /** 全局曝险超限 */
    EXPOSURE_LIMIT(AlertLevel.CRITICAL, "全局曝险超限"),

    /** 单策略曝险超限 */
    STRATEGY_EXPOSURE_LIMIT(AlertLevel.WARN, "单策略曝险超限"),

    /** 每日亏损超限 */
    DAILY_LOSS_LIMIT(AlertLevel.CRITICAL, "每日亏损超限"),

    /** 策略连续错误 */
    CONSECUTIVE_ERRORS(AlertLevel.WARN, "策略连续错误"),

    /** 策略心跳丢失（长时间未运行） */
    HEARTBEAT_MISS(AlertLevel.CRITICAL, "策略心跳丢失"),

    /** 紧急熔断触发 */
    KILL_SWITCH(AlertLevel.FATAL, "紧急熔断已触发"),

    /** 策略模式变更 */
    MODE_CHANGED(AlertLevel.INFO, "策略模式变更"),

    /** 策略启停 */
    STRATEGY_TOGGLED(AlertLevel.INFO, "策略启停操作"),

    // ── P1-4 跟单策略健康监控告警（StrategyHealthMonitorJob 外发） ──

    /** 跟单策略 24h 零开仓（结构性卡死风险） */
    HEALTH_NO_OPEN_24H(AlertLevel.CRITICAL, "跟单策略 24h 零开仓"),

    /** 跟单策略 4h 零开仓 */
    HEALTH_NO_OPEN_4H(AlertLevel.WARN, "跟单策略 4h 零开仓"),

    /** 跟单策略可用资金耗尽，新信号全部被阻断 */
    HEALTH_CAPITAL_DEPLETED(AlertLevel.CRITICAL, "跟单策略可用资金耗尽"),

    /** 跟单策略可用资金偏低，可能很快归零 */
    HEALTH_CAPITAL_LOW(AlertLevel.WARN, "跟单策略可用资金偏低"),

    /** T-B7: 事件摄取延迟 P90 超阈值（smart_wallet_trade_event 的 opened_at→created_at） */
    HEALTH_INGEST_LAG_HIGH(AlertLevel.WARN, "事件摄取延迟 P90 超阈值"),

    // ── W1-T1.5 / W1-T4.5 智能钱包审计告警（统一告警通道接入） ──

    /** W1-T1: Live↔Replay 一致性差异超阈值 */
    AUDIT_LIVE_REPLAY_DIFF(AlertLevel.WARN, "Live↔Replay 一致性差异超阈值"),

    /** W1-T4: 影子记账 vs 实盘 PnL 差异超阈值 */
    AUDIT_SHADOW_VS_LIVE(AlertLevel.WARN, "影子记账 vs 实盘 PnL 差异超阈值"),

    /** W1-T2: 结算源对齐审计异常（解析失败 / 结算延迟 / claim 失败累积） */
    AUDIT_SETTLEMENT_SOURCE(AlertLevel.WARN, "结算源对齐审计异常"),

    /** W2-T4.5: 滑点事后统计 P95 超阈值 */
    AUDIT_SLIPPAGE_POSTFACT(AlertLevel.WARN, "滑点事后统计 P95 超阈值"),

    /** W2-T6: Leader 行为漂移触发 (DRIFTED / DRIFTED_SEVERE) */
    AUDIT_LEADER_DRIFT(AlertLevel.WARN, "Leader 行为漂移触发"),

    /** W1-T2.2 + 第十一批 (2026-05-16): Polymarket Gamma / 我方 / UMA Oracle 三方 resolution diff */
    AUDIT_RESOLUTION_TRIDIFF(AlertLevel.WARN, "结算三方 diff 不一致"),

    /** W2-T2 灰度自动化 (第十八批): leader 滑动窗口胜率灰度健康检查异常 (coolingRatio 过高 / overallWinRate 过低). */
    AUDIT_LEADER_ROLLING_HEALTH(AlertLevel.WARN, "Leader 滑动窗口胜率灰度健康异常"),

    /** PositionPriceMonitorService: 持仓 token 的 best bid 完全为空, 等同于无人接货, 极有可能本仓位最终判负, 运维需关注是否手动减仓。 */
    POSITION_EMPTY_BID_BOOK(AlertLevel.WARN, "持仓 best bid 为空,无人接货,可能判负"),

    /** R3(2026-06-03): 盘口快照管道停滞/为空——smart_wallet_market_snapshot 长时间无新数据，开单/估值将退化为现价。 */
    HEALTH_SNAPSHOT_STALE(AlertLevel.CRITICAL, "盘口快照管道停滞或为空"),

    /** R3(2026-06-03): 结算积压——OPEN 持仓所在市场已 resolved 却未入账(卡 OPEN)。 */
    HEALTH_SETTLEMENT_BACKLOG(AlertLevel.WARN, "结算积压(已结算市场仓位卡 OPEN)"),

    /** R19(2026-06-03): 本地账与链上余额对账漂移——本地可用资金 vs 执行钱包链上 USDC 偏差超阈值。 */
    AUDIT_RECONCILE_DRIFT(AlertLevel.WARN, "本地账与链上余额对账漂移");

    private final AlertLevel defaultLevel;
    private final String description;

    AlertType(AlertLevel defaultLevel, String description) {
        this.defaultLevel = defaultLevel;
        this.description = description;
    }

    public AlertLevel getDefaultLevel() {
        return defaultLevel;
    }

    public String getDescription() {
        return description;
    }
}
