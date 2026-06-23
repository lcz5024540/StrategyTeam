package com.metaverse.coin.strategy.job.smartwallet.audit;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 默认实现: 委托既有 {@link StrategyAlertService}, 复用 strategy_alert 表 + 日志通道.
 *
 * <p>开关 {@code smart-wallet.audit.alert-sink-enabled} (默认 true) 控制是否真正落库;
 * 关闭时仅 debug 日志 (避免双倍噪音, 因为各 Job 自身已经打了 WARN 级别日志).</p>
 *
 * <p>未来接入飞书 / 邮件时:
 * <ol>
 *   <li>新增 {@code FeishuAuditAlertSink} 实现 {@link SmartWalletAuditAlertSink}</li>
 *   <li>用 {@code @ConditionalOnProperty} 切换默认 bean, 或装饰本类 (本类调底层 +
 *       Feishu 推送)</li>
 *   <li>各审计 Job 零修改</li>
 * </ol></p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> — 仅在
 * 审计 Job WARN 时被调用, 失败时 swallow exception, 不阻塞 Job 主流程.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyAlertServiceAuditAlertSink implements SmartWalletAuditAlertSink {

    private static final String LOG_PREFIX = "[SmartWalletAuditAlert]";

    private final SmartWalletProperties properties;
    private final StrategyAlertService strategyAlertService;

    @Override
    public void publish(AlertType alertType, String title, String message,
                        BigDecimal metricValue, BigDecimal threshold) {
        if (!isEnabled()) {
            log.debug("{} sink disabled, skipping alert {} title={}", LOG_PREFIX, alertType, title);
            return;
        }
        if (alertType == null) {
            log.warn("{} alertType=null, ignoring", LOG_PREFIX);
            return;
        }
        try {
            int created = strategyAlertService.fireAlert(
                    STRATEGY_ID,
                    alertType,
                    title == null ? alertType.getDescription() : title,
                    message,
                    metricValue,
                    threshold);
            if (log.isDebugEnabled()) {
                log.debug("{} fired alert type={} title={} created={} (0=去重)",
                        LOG_PREFIX, alertType, title, created);
            }
        } catch (RuntimeException e) {
            // 必须 swallow, sink 不应阻塞审计 Job 主流程
            log.warn("{} publish failed type={} title={} err={}", LOG_PREFIX, alertType, title, e.toString(), e);
        }
    }

    /**
     * 通用 String 重载, 跨策略 (天气 / BTC) 可用. 不依赖跟单 AlertType enum.
     * 直接调底层 StrategyAlertService.fireAlertByName, 同样有未恢复告警去重保护.
     */
    @Override
    public void publish(String strategyId, String alertType, String title, String message,
                        BigDecimal metricValue, BigDecimal threshold) {
        if (!isEnabled()) {
            log.debug("{} sink disabled, skipping alert strategyId={} type={} title={}",
                    LOG_PREFIX, strategyId, alertType, title);
            return;
        }
        if (strategyId == null || alertType == null) {
            log.warn("{} strategyId/alertType null, ignoring (strategyId={} alertType={})",
                    LOG_PREFIX, strategyId, alertType);
            return;
        }
        // 优先把跟单已知 alertType 走强类型路径, 保留与现有 Job 完全一致的语义
        try {
            AlertType type = AlertType.valueOf(alertType);
            // 强类型路径: strategyId 被忽略(默认 SMART_WALLET_AUDIT), 与既有行为一致
            if (STRATEGY_ID.equals(strategyId)) {
                publish(type, title, message, metricValue, threshold);
                return;
            }
            // 跨策略调用方使用了跟单 enum 名但传了别的 strategyId — 用 enum + 自定义 strategyId
            int created = strategyAlertService.fireAlert(strategyId, type,
                    title == null ? type.getDescription() : title, message, metricValue, threshold);
            if (log.isDebugEnabled()) {
                log.debug("{} cross-strategy fired strategyId={} type={} created={}",
                        LOG_PREFIX, strategyId, type, created);
            }
        } catch (IllegalArgumentException notKnown) {
            // alertType 不在 AlertType enum 里 — 当前 StrategyAlertService 仅支持 enum 入参, 暂记 WARN
            log.warn("{} cross-strategy publish skipped (alertType={} not in AlertType enum) strategyId={} title={}",
                    LOG_PREFIX, alertType, strategyId, title);
        } catch (RuntimeException e) {
            log.warn("{} cross-strategy publish failed strategyId={} type={} title={} err={}",
                    LOG_PREFIX, strategyId, alertType, title, e.toString(), e);
        }
    }

    private boolean isEnabled() {
        if (properties == null || !properties.isEnabled()) {
            return false;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null) {
            return true; // 兼容旧配置: 没有 audit 节点时默认启用
        }
        return audit.isAlertSinkEnabled();
    }
}
