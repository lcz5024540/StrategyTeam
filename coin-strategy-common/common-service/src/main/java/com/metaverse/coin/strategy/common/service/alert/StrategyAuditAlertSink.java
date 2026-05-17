package com.metaverse.coin.strategy.common.service.alert;

import java.math.BigDecimal;

/**
 * 通用策略审计告警 Sink (跨策略).
 *
 * <p>设计目的: 让跟单 / 天气 / BTC 5min 等任意策略的审计告警都能通过同一个接口收口,
 * 实现侧由 {@code StrategyAlertService} 落 {@code strategy_alert} 表, 后续可装饰为
 * 飞书 / 邮件 / PagerDuty 等多通道分发.</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> —
 * Sink 仅在审计 Job 检测到 WARN 状况时被调用, 不参与下单决策路径; 失败必须 swallow.</p>
 *
 * <p><b>与 {@code SmartWalletAuditAlertSink} 的关系</b>: 后者是本接口的子接口,
 * 保留 strongly-typed {@code AlertType} enum 入参 (跟单专用), 但底层最终委托到本接口的
 * {@code publish(String, ...)} String 类型版本; 跨策略 / 通用代码应只依赖本接口.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier A.</p>
 */
public interface StrategyAuditAlertSink {

    /**
     * 发布一条审计告警 (通用 String 类型, 跨策略可用).
     *
     * <p>实现必须保证: 1) 抛出的任何异常都被 swallow + log; 2) 当 sink 全局关闭时直接 no-op;
     * 3) 同 strategyId+alertType 已存在未恢复告警时去重.</p>
     *
     * @param strategyId   策略 ID (例如 {@code SMART_WALLET_AUDIT} / 未来 {@code WEATHER_AUDIT})
     * @param alertType    告警类型字符串 (例如 {@code AUDIT_LIVE_REPLAY_DIFF})
     * @param title        告警标题 (短)
     * @param message      告警详情
     * @param metricValue  指标实际值 (可选, null=无)
     * @param threshold    指标阈值 (可选, null=无)
     */
    void publish(String strategyId, String alertType, String title, String message,
                 BigDecimal metricValue, BigDecimal threshold);

    /** 不带指标值的简化重载. */
    default void publish(String strategyId, String alertType, String title, String message) {
        publish(strategyId, alertType, title, message, null, null);
    }
}
