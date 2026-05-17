package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSignalShadowMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * W1-T4.3 被拒/降级 影子记录的结算回填服务.
 *
 * <p>职责: 把 status ∈ {PENDING_SETTLEMENT, DRY_FALLBACK} 的影子记录在 market 解析后
 * 回填出场价 / 影子 PnL / settled_at, 长期未结算的标记为 EXPIRED.</p>
 *
 * <p>回填逻辑 (二元市场, share 价格 ∈ [0,1]):
 *   - 加载 market_info (依次按 marketInfoId / market_id+outcome 兜底).
 *   - 若 marketLifecycle == RESOLVED 且 resolvedOutcome 非空:
 *       won = resolvedOutcome.equalsIgnoreCase(marketInfo.outcome)
 *       BUY:  exitPrice = won ? 1.0 : 0.0
 *             proceeds  = entrySize × exitPrice
 *             pnl       = proceeds - entryCost
 *       SELL: exitPrice = won ? 1.0 : 0.0     (合约 share 的最终价)
 *             proceeds  = entryPrice × entrySize - exitPrice × entrySize  (合成空头)
 *             pnl       = (entryPrice - exitPrice) × entrySize
 *     标记 status=SETTLED.
 *   - 否则:
 *       若记录 age > shadowMaxAgeDays → 标记 EXPIRED.
 *       否则保持 PENDING_SETTLEMENT (等待下次调度).</p>
 *
 * <p>失败容错: 单条异常只 warn, 不中断整个批次.</p>
 *
 * <p>纯计算与读库, 不调用执行链路; 因此对开单效率 / 资金使用率 / 实际盈亏 / 胜率 <b>无任何直接影响</b>,
 * 仅产出 PnL 数据供 W1-T4.4 周报和门控调参回环使用.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T4).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowSettlementBackfillService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MONEY_SCALE = 6;

    private final SmartWalletSignalShadowMapper shadowMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletProperties properties;

    /**
     * 执行一次回填.
     *
     * @return 本次执行的统计结果
     */
    public BackfillSummary runOnce() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        int batchSize = safePositive(audit.getShadowBackfillBatchSize(), 200);
        int maxAgeDays = safePositive(audit.getShadowMaxAgeDays(), 60);
        long runTimeoutMs = audit.getShadowBackfillRunTimeoutMs() == null ? 300000L
                : audit.getShadowBackfillRunTimeoutMs();
        Instant startedAt = Instant.now();

        BackfillSummary summary = new BackfillSummary();
        List<SmartWalletSignalShadowEntity> candidates = loadCandidates(batchSize);
        summary.setScanned(candidates.size());

        for (SmartWalletSignalShadowEntity shadow : candidates) {
            if (Duration.between(startedAt, Instant.now()).toMillis() > runTimeoutMs) {
                log.warn("[ShadowSettlementBackfill] timeout reached after {}ms, processed={}, remaining={}",
                        runTimeoutMs, summary.getProcessed(), candidates.size() - summary.getProcessed());
                summary.setTimedOut(true);
                break;
            }
            summary.setProcessed(summary.getProcessed() + 1);
            try {
                BackfillOutcome outcome = processOne(shadow, maxAgeDays);
                switch (outcome) {
                    case SETTLED -> summary.setSettled(summary.getSettled() + 1);
                    case EXPIRED -> summary.setExpired(summary.getExpired() + 1);
                    case SKIPPED_PENDING -> summary.setSkipped(summary.getSkipped() + 1);
                    case SKIPPED_MISSING_DATA -> summary.setMissingData(summary.getMissingData() + 1);
                }
            } catch (RuntimeException e) {
                summary.setFailed(summary.getFailed() + 1);
                log.warn("[ShadowSettlementBackfill] failed shadowId={} traceId={} reason={}",
                        shadow.getId(), shadow.getTraceId(), e.toString());
            }
        }

        log.info("[ShadowSettlementBackfill] done scanned={} settled={} expired={} skipped={} missingData={} failed={} timedOut={} elapsedMs={}",
                summary.getScanned(), summary.getSettled(), summary.getExpired(), summary.getSkipped(),
                summary.getMissingData(), summary.getFailed(), summary.isTimedOut(),
                Duration.between(startedAt, Instant.now()).toMillis());
        return summary;
    }

    private List<SmartWalletSignalShadowEntity> loadCandidates(int batchSize) {
        return shadowMapper.selectList(new LambdaQueryWrapper<SmartWalletSignalShadowEntity>()
                .in(SmartWalletSignalShadowEntity::getStatus,
                        List.of("PENDING_SETTLEMENT", "DRY_FALLBACK"))
                .orderByAsc(SmartWalletSignalShadowEntity::getCreatedAt)
                .last("limit " + batchSize));
    }

    private BackfillOutcome processOne(SmartWalletSignalShadowEntity shadow, int maxAgeDays) {
        MarketInfoLookup lookup = lookupMarketInfo(shadow);
        if (lookup == null) {
            return tryExpireOrPending(shadow, maxAgeDays, "MISSING_MARKET_INFO");
        }
        String lifecycle = lookup.marketInfo.getMarketLifecycle();
        String resolvedOutcome = trimToNull(lookup.marketInfo.getResolvedOutcome());
        if (!MarketStateSnapshot.LIFECYCLE_RESOLVED.equalsIgnoreCase(lifecycle)
                || resolvedOutcome == null) {
            return tryExpireOrPending(shadow, maxAgeDays, "NOT_RESOLVED");
        }
        String shadowOutcome = trimToNull(lookup.marketInfo.getOutcome());
        if (shadowOutcome == null) {
            shadowOutcome = trimToNull(lookup.eventOutcome);
        }
        if (shadowOutcome == null) {
            // 无法判定胜负的特殊情况, 维持 PENDING 但下次仍会复算 (除非超期)
            return tryExpireOrPending(shadow, maxAgeDays, "OUTCOME_UNKNOWN");
        }

        boolean won = resolvedOutcome.equalsIgnoreCase(shadowOutcome);
        BigDecimal entrySize = safe(shadow.getShadowEntrySize());
        BigDecimal entryCost = safe(shadow.getShadowEntryCostUsdc());
        BigDecimal entryPrice = safe(shadow.getShadowEntryPrice());

        if (entrySize.signum() <= 0) {
            return tryExpireOrPending(shadow, maxAgeDays, "NO_ENTRY_SIZE");
        }

        BigDecimal exitPrice = won ? ONE : ZERO;
        BigDecimal proceeds;
        BigDecimal pnl;
        String side = shadow.getSide() == null ? "BUY" : shadow.getSide().toUpperCase();
        if ("SELL".equals(side)) {
            // 合成空头视角: 卖出 entryPrice, 结算时 share 价 exitPrice
            proceeds = entryPrice.multiply(entrySize).subtract(exitPrice.multiply(entrySize));
            pnl = entryPrice.subtract(exitPrice).multiply(entrySize);
        } else {
            // BUY 与 unknown 一律按多头处理
            proceeds = entrySize.multiply(exitPrice);
            pnl = proceeds.subtract(entryCost);
        }

        Date now = new Date();
        shadow.setShadowExitPrice(scale(exitPrice))
                .setShadowExitProceedsUsdc(scale(proceeds))
                .setShadowPnlUsdc(scale(pnl))
                .setShadowSettledAt(now)
                .setStatus("SETTLED")
                .setUpdatedAt(now);
        // 若之前没存 marketInfoId 而走兜底解析到了, 顺便补一下
        if (shadow.getMarketInfoId() == null && lookup.marketInfo.getId() != null) {
            shadow.setMarketInfoId(lookup.marketInfo.getId());
        }
        shadowMapper.updateById(shadow);
        if (log.isDebugEnabled()) {
            log.debug("[ShadowSettlementBackfill] settled shadowId={} traceId={} side={} won={} entryPrice={} exitPrice={} entrySize={} pnl={}",
                    shadow.getId(), shadow.getTraceId(), side, won, entryPrice, exitPrice, entrySize, pnl);
        }
        return BackfillOutcome.SETTLED;
    }

    private BackfillOutcome tryExpireOrPending(SmartWalletSignalShadowEntity shadow,
                                                int maxAgeDays,
                                                String reasonHint) {
        Date createdAt = shadow.getCreatedAt();
        if (createdAt != null) {
            long ageMs = System.currentTimeMillis() - createdAt.getTime();
            if (ageMs >= Duration.ofDays(maxAgeDays).toMillis()) {
                Date now = new Date();
                shadow.setStatus("EXPIRED").setUpdatedAt(now);
                shadowMapper.updateById(shadow);
                log.info("[ShadowSettlementBackfill] expired shadowId={} traceId={} hint={} ageDays={}",
                        shadow.getId(), shadow.getTraceId(), reasonHint, ageMs / 86_400_000L);
                return BackfillOutcome.EXPIRED;
            }
        }
        return "MISSING_MARKET_INFO".equals(reasonHint)
                ? BackfillOutcome.SKIPPED_MISSING_DATA
                : BackfillOutcome.SKIPPED_PENDING;
    }

    private MarketInfoLookup lookupMarketInfo(SmartWalletSignalShadowEntity shadow) {
        SmartWalletMarketInfoEntity info = null;
        if (shadow.getMarketInfoId() != null) {
            info = marketInfoService.getById(shadow.getMarketInfoId());
        }
        String eventOutcome = null;
        if (info == null && shadow.getSourceEventId() != null) {
            SmartWalletTradeEventEntity event = tradeEventMapper.selectById(shadow.getSourceEventId());
            if (event != null) {
                eventOutcome = event.getOutcome();
                if (StringUtils.hasText(event.getMarketId()) && StringUtils.hasText(event.getOutcome())) {
                    info = marketInfoService.getByMarketIdAndOutcome(event.getMarketId(), event.getOutcome());
                }
            }
        }
        if (info == null) {
            return null;
        }
        return new MarketInfoLookup(info, eventOutcome);
    }

    // ── 辅助 ──

    private BigDecimal safe(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private int safePositive(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }

    private String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private record MarketInfoLookup(SmartWalletMarketInfoEntity marketInfo, String eventOutcome) { }

    /** 单笔回填的终态. */
    private enum BackfillOutcome {
        SETTLED,                 // 已写入 exit / pnl
        EXPIRED,                 // 超期标记 EXPIRED
        SKIPPED_PENDING,         // 市场未结算, 保持 PENDING 等下一次
        SKIPPED_MISSING_DATA     // 缺 market_info / trade_event 等关键依赖
    }

    /** 单次 Job 调用的统计输出. */
    @Data
    @Accessors(chain = true)
    public static class BackfillSummary {
        private int scanned;
        private int processed;
        private int settled;
        private int expired;
        private int skipped;
        private int missingData;
        private int failed;
        private boolean timedOut;
    }
}
