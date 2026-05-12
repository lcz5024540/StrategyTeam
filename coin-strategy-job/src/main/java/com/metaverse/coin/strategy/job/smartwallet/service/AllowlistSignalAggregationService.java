package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 白名单小额高频信号聚合缓冲器。
 *
 * 解决的核心问题：
 * 白名单地址 TVL 远高于我们（如 50万 vs 5000），等比缩放后(0.03×walletScore)
 * 单笔跟单金额可能仅 $0.5-2，低于 Polymarket 最低下单额 $1-5。
 *
 * 方案：
 * 1. 当缩放后的 stake < minOrderUsdc 时，不立即拒绝
 * 2. 而是将该信号的金额累积到"聚合缓冲区"
 * 3. 当同一 市场+方向 的累积金额 ≥ minOrderUsdc 时，触发一次真实下单
 * 4. 聚合窗口有时间上限，超时则放弃（市场环境可能已变化）
 *
 * 同时提供白名单专用最低下单策略：
 * - 对白名单地址，用 fixedMinStake（如 $5）代替等比缩放
 * - 确保每次跟单都能实际下单
 *
 * 线程安全：使用 ConcurrentHashMap，支持并发写入。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AllowlistSignalAggregationService {

    private final SmartWalletProperties properties;

    /**
     * 聚合缓冲区：key = marketId + "|" + outcome + "|" + walletAddress
     */
    private final Map<String, AggregationBuffer> buffers = new ConcurrentHashMap<>();

    /**
     * 尝试聚合一个小额信号。
     *
     * @param marketId       Polymarket 市场 ID
     * @param outcome        YES / NO
     * @param walletAddress  被跟单的白名单地址
     * @param scaledStake    等比缩放后的建议仓位（可能低于 minOrder）
     * @param triggerSize    触发信号的原始开仓金额（白名单地址的实际下单额）
     * @param price          当前市场价格
     * @return 聚合判定结果
     */
    public AggregationResult aggregate(String marketId, String outcome, String walletAddress,
                                        BigDecimal scaledStake, BigDecimal triggerSize,
                                        BigDecimal price) {
        SmartWalletProperties.AllowlistAggregationConfig config = getAllowlistAggregationConfig();
        BigDecimal minOrderUsdc = getMinOrderUsdc();

        // ── 策略 1: 固定最低下单保底 ──
        // 如果开启了 fixedMinStake 模式，直接使用固定值下单
        if (config.isUseFixedMinStake()) {
            BigDecimal fixedStake = config.getFixedMinStakeUsdc();
            if (fixedStake.compareTo(minOrderUsdc) >= 0) {
                return AggregationResult.builder()
                        .action(AggregationAction.EXECUTE_NOW)
                        .executeStake(fixedStake)
                        .reason("ALLOWLIST_FIXED_MIN_STAKE")
                        .build();
            }
        }

        // ── 策略 2: 时间窗口信号聚合 ──
        String bufferKey = marketId + "|" + outcome + "|" + walletAddress;
        AggregationBuffer buffer = buffers.computeIfAbsent(bufferKey,
                k -> new AggregationBuffer(marketId, outcome, walletAddress));

        // 清理过期缓冲（窗口超时）
        if (buffer.isExpired(config.getAggregationWindowMs())) {
            BigDecimal expiredAmount = buffer.getAccumulatedStake();
            buffer.reset();
            if (expiredAmount.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[AllowlistAggregation] buffer expired. key={} expiredAmount={}",
                        bufferKey, expiredAmount.setScale(2, RoundingMode.HALF_UP));
            }
        }

        // 累加本次信号的仓位
        buffer.accumulate(scaledStake, triggerSize, price);

        BigDecimal accumulated = buffer.getAccumulatedStake();
        int signalCount = buffer.getSignalCount();

        // 检查是否达到下单阈值
        if (accumulated.compareTo(minOrderUsdc) >= 0) {
            // 达到最低下单额，执行下单
            BigDecimal executeStake = accumulated.min(config.getMaxAggregatedStakeUsdc())
                    .setScale(2, RoundingMode.HALF_UP);
            buffer.reset(); // 清空缓冲
            log.info("[AllowlistAggregation] threshold reached. key={} accumulated={} signals={} executeStake={}",
                    bufferKey, accumulated.setScale(2, RoundingMode.HALF_UP), signalCount, executeStake);
            return AggregationResult.builder()
                    .action(AggregationAction.EXECUTE_NOW)
                    .executeStake(executeStake)
                    .reason("AGGREGATION_THRESHOLD_REACHED(signals=" + signalCount + ")")
                    .build();
        }

        // 还未达到阈值，继续累积
        log.debug("[AllowlistAggregation] buffering. key={} accumulated={} signals={} minOrder={}",
                bufferKey, accumulated.setScale(2, RoundingMode.HALF_UP), signalCount,
                minOrderUsdc.setScale(2, RoundingMode.HALF_UP));
        return AggregationResult.builder()
                .action(AggregationAction.BUFFERED)
                .accumulatedSoFar(accumulated)
                .signalCount(signalCount)
                .reason("BUFFERED(accumulated=" + accumulated.setScale(2, RoundingMode.HALF_UP)
                        + ",need=" + minOrderUsdc.setScale(2, RoundingMode.HALF_UP) + ")")
                .build();
    }

    /**
     * 检查缓冲区状态（不修改）。
     */
    public Optional<AggregationBuffer> peekBuffer(String marketId, String outcome, String walletAddress) {
        String key = marketId + "|" + outcome + "|" + walletAddress;
        AggregationBuffer buffer = buffers.get(key);
        return Optional.ofNullable(buffer);
    }

    /**
     * 手动清除所有过期缓冲（可由定时任务调用）。
     */
    public int purgeExpired() {
        SmartWalletProperties.AllowlistAggregationConfig config = getAllowlistAggregationConfig();
        int purged = 0;
        Iterator<Map.Entry<String, AggregationBuffer>> it = buffers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AggregationBuffer> entry = it.next();
            if (entry.getValue().isExpired(config.getAggregationWindowMs())) {
                it.remove();
                purged++;
            }
        }
        if (purged > 0) {
            log.info("[AllowlistAggregation] purged {} expired buffers", purged);
        }
        return purged;
    }

    /**
     * 获取所有活跃缓冲区的快照（监控用）。
     */
    public Map<String, AggregationBuffer> getActiveBuffers() {
        return Collections.unmodifiableMap(buffers);
    }

    // ════════════════════════════════════════════════════════════════════
    // Config helpers
    // ════════════════════════════════════════════════════════════════════

    private SmartWalletProperties.AllowlistAggregationConfig getAllowlistAggregationConfig() {
        SmartWalletProperties.AllowlistAggregationConfig config = properties.getAllowlistAggregation();
        return config != null ? config : new SmartWalletProperties.AllowlistAggregationConfig();
    }

    private BigDecimal getMinOrderUsdc() {
        SmartWalletProperties.ExecutionWallet ew = properties.getExecutionWallet();
        return ew != null && ew.getMinOrderUsdc() != null
                ? ew.getMinOrderUsdc()
                : new BigDecimal("5");
    }

    // ════════════════════════════════════════════════════════════════════
    // Models
    // ════════════════════════════════════════════════════════════════════

    public enum AggregationAction {
        /** 立即下单（固定保底或聚合达到阈值） */
        EXECUTE_NOW,
        /** 已缓冲，等待更多信号累积 */
        BUFFERED,
        /** 被暴露上限拒绝 */
        REJECTED_EXPOSURE
    }

    @Data
    @Builder
    public static class AggregationResult {
        private AggregationAction action;
        /** 如果 action=EXECUTE_NOW，使用此金额下单 */
        private BigDecimal executeStake;
        /** 如果 action=BUFFERED，当前已累积的金额 */
        private BigDecimal accumulatedSoFar;
        private int signalCount;
        private String reason;
    }

    /**
     * 聚合缓冲区：累积同一 market+outcome+wallet 的多笔小额信号。
     */
    @Data
    public static class AggregationBuffer {
        private final String marketId;
        private final String outcome;
        private final String walletAddress;
        private BigDecimal accumulatedStake = BigDecimal.ZERO;
        private BigDecimal accumulatedTriggerSize = BigDecimal.ZERO;
        private BigDecimal lastPrice = BigDecimal.ZERO;
        private int signalCount = 0;
        private Instant firstSignalAt;
        private Instant lastSignalAt;

        public AggregationBuffer(String marketId, String outcome, String walletAddress) {
            this.marketId = marketId;
            this.outcome = outcome;
            this.walletAddress = walletAddress;
        }

        public void accumulate(BigDecimal stake, BigDecimal triggerSize, BigDecimal price) {
            Instant now = Instant.now();
            if (firstSignalAt == null) firstSignalAt = now;
            lastSignalAt = now;
            accumulatedStake = accumulatedStake.add(stake);
            accumulatedTriggerSize = accumulatedTriggerSize.add(triggerSize);
            lastPrice = price;
            signalCount++;
        }

        public boolean isExpired(long windowMs) {
            if (firstSignalAt == null) return false;
            return Instant.now().toEpochMilli() - firstSignalAt.toEpochMilli() > windowMs;
        }

        public void reset() {
            accumulatedStake = BigDecimal.ZERO;
            accumulatedTriggerSize = BigDecimal.ZERO;
            signalCount = 0;
            firstSignalAt = null;
            lastSignalAt = null;
        }
    }
}
