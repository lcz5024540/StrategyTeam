package com.metaverse.coin.strategy.job.engine.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 策略单轮仓位退出扫描的运行结果。
 */
@Data
@Builder
public class StrategyExitResult {

    /** 策略标识 */
    private String strategyId;

    /** 运行时间 */
    private Instant scannedAt;

    /** 检查的仓位数 */
    private int positionsScanned;

    /** 触发退出的仓位数 */
    private int exitsTriggered;

    /** 退出详情 */
    private List<ExitDetail> exitDetails;

    @Data
    @Builder
    public static class ExitDetail {
        private String marketId;
        private String outcome;
        private String exitReason; // STOP_LOSS / TAKE_PROFIT / SETTLEMENT / MANUAL
        private String orderId;    // 退出订单 ID（如果是 LIVE 模式）
    }
}
