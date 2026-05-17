package com.metaverse.coin.strategy.job.smartwallet.audit;

import com.metaverse.coin.strategy.common.service.alert.StrategyAuditAlertSink;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;

import java.math.BigDecimal;

/**
 * W1-T1.5 / W1-T4.5 跟单策略审计告警 Sink.
 *
 * <p>把分散在 5 个审计 Job 里的 WARN 日志收口到一处:
 * <ul>
 *   <li>{@link com.metaverse.coin.strategy.job.smartwallet.service.LiveReplayDiffReportService}
 *       - {@link AlertType#AUDIT_LIVE_REPLAY_DIFF}</li>
 *   <li>{@link com.metaverse.coin.strategy.job.smartwallet.service.ShadowVsLiveWeeklyReportService}
 *       - {@link AlertType#AUDIT_SHADOW_VS_LIVE}</li>
 *   <li>{@link com.metaverse.coin.strategy.job.smartwallet.service.SettlementSourceAuditService}
 *       - {@link AlertType#AUDIT_SETTLEMENT_SOURCE}</li>
 *   <li>{@link com.metaverse.coin.strategy.job.smartwallet.service.SlippagePostFactReportService}
 *       - {@link AlertType#AUDIT_SLIPPAGE_POSTFACT}</li>
 *   <li>{@link com.metaverse.coin.strategy.job.smartwallet.service.LeaderBehaviorDriftService}
 *       - {@link AlertType#AUDIT_LEADER_DRIFT} (按 leader 分维度)</li>
 * </ul>
 *
 * <p>实现侧默认走 {@code StrategyAlertService}, 落 strategy_alert 表 + 日志输出, 同时为
 * 后续飞书 / 邮件接入预留唯一注入点 — 把当前默认 bean 替换或装饰即可, 无需改动各 Job.</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> — Sink 仅
 * 在审计 Job 检测到 WARN 状况时被调用, 不参与 signal/order 决策路径; 失败也不应阻塞 Job
 * 主流程 (实现需 swallow exception). 间接价值: 让 ops 在统一告警面板里看到所有跟单策略
 * 审计的 WARN 状态, 不再需要逐 Job grep 日志.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T1.5 / W1-T4.5).</p>
 */
public interface SmartWalletAuditAlertSink extends StrategyAuditAlertSink {

    /** 智能钱包审计相关告警归属的策略 ID. 与 {@code coin-strategy-job} engine 注册策略空间隔离. */
    String STRATEGY_ID = "SMART_WALLET_AUDIT";

    /**
     * 发布一条审计告警 (跟单专用强类型重载).
     *
     * <p>实现必须保证: 1) 抛出的任何异常都被 swallow + log; 2) 当 sink 全局关闭时直接 no-op;
     * 3) 同 strategyId+alertType 已存在未恢复告警时去重 (复用 StrategyAlertService 默认行为).</p>
     *
     * @param alertType    告警类型 (例如 {@link AlertType#AUDIT_LIVE_REPLAY_DIFF})
     * @param title        告警标题 (短, 便于面板展示)
     * @param message      告警详情 (含关键指标, 便于运维定位)
     * @param metricValue  指标实际值 (可选, null 表示无量化指标)
     * @param threshold    指标阈值 (可选, null 表示无阈值)
     */
    void publish(AlertType alertType, String title, String message,
                 BigDecimal metricValue, BigDecimal threshold);

    /** 不带指标值的简化重载. */
    default void publish(AlertType alertType, String title, String message) {
        publish(alertType, title, message, null, null);
    }

    /**
     * 通用 String 类型重载, 委托到 {@link AlertType}.valueOf 后调用强类型版本.
     * 跨策略代码(天气 / BTC) 不应依赖跟单的 AlertType 枚举, 应只用 {@link StrategyAuditAlertSink}.
     */
    @Override
    default void publish(String strategyId, String alertType, String title, String message,
                         BigDecimal metricValue, BigDecimal threshold) {
        AlertType type;
        try {
            type = AlertType.valueOf(alertType);
        } catch (IllegalArgumentException ex) {
            // 跨策略调用方传入了跟单不识别的 alertType — 此时不应 fall through 到 publish(AlertType,...),
            // 因为它会再 valueOf 一次. 实现侧应直接走 String 路径; 这里 default 行为是退化为不发告警.
            return;
        }
        publish(type, title, message, metricValue, threshold);
    }
}
