package com.metaverse.coin.strategy.job.engine.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 监控模块配置。
 *
 * <pre>
 * strategy-monitor:
 *   enabled: true
 *   snapshot-interval-ms: 60000
 *   alert-check-interval-ms: 30000
 *   max-consecutive-errors: 5
 *   heartbeat-timeout-seconds: 300
 *   strategy-exposure-warn-usdc: 500
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "strategy-monitor")
public class MonitorProperties {

    /** 是否启用监控 */
    private boolean enabled = true;

    /** 健康快照采集间隔（毫秒），默认1分钟 */
    private long snapshotIntervalMs = 60_000;

    /** 告警检查间隔（毫秒），默认30秒 */
    private long alertCheckIntervalMs = 30_000;

    /** 连续错误阈值（超过即告警） */
    private int maxConsecutiveErrors = 5;

    /** 心跳超时时间（秒），策略超过此时间未运行则告警 */
    private int heartbeatTimeoutSeconds = 300;

    /** 单策略曝险警告阈值（USDC） */
    private BigDecimal strategyExposureWarnUsdc = new BigDecimal("500");

    /** 快照保留天数（自动清理更早的数据） */
    private int snapshotRetentionDays = 30;

    /** 告警保留天数 */
    private int alertRetentionDays = 90;
}
