package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendExitDecisionEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendExitDecisionMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendOrderMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendSignalMapper;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendBreadthStats;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendPerformanceStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class HtxTrendRecorder {

    public static final String INTENT = "INTENT";
    public static final String ENTRY_SENT = "ENTRY_SENT";
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSE_SENT = "CLOSE_SENT";
    public static final String PARTIAL_CLOSE_SENT = "PARTIAL_CLOSE_SENT";
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String ORDER_INTENT = "INTENT";
    public static final String ORDER_ACCEPTED = "ACCEPTED";
    public static final String ORDER_FILLED = "FILLED";
    public static final String ORDER_PARTIALLY_FILLED = "PARTIALLY_FILLED";
    public static final String ORDER_TERMINAL = "TERMINAL";
    public static final String ORDER_REJECTED = "REJECTED";
    public static final String LEG_ENTRY = "ENTRY";
    public static final String LEG_CLOSE = "CLOSE";
    public static final String LEG_PARTIAL_CLOSE = "PARTIAL_CLOSE";

    private final HtxTrendPositionMapper positionMapper;
    private final HtxTrendOrderMapper orderMapper;
    private final HtxTrendSignalMapper signalMapper;
    private final CoinStrategyLedgerRecorder ledgerRecorder;
    private final HtxTrendCombinationRecorder combinationRecorder;
    @Autowired(required = false)
    private HtxTrendExitDecisionMapper exitDecisionMapper;

    public HtxTrendRecorder(HtxTrendPositionMapper positionMapper, HtxTrendOrderMapper orderMapper,
                            HtxTrendSignalMapper signalMapper) {
        this(positionMapper, orderMapper, signalMapper, null, null);
    }

    public HtxTrendRecorder(HtxTrendPositionMapper positionMapper, HtxTrendOrderMapper orderMapper,
                            HtxTrendSignalMapper signalMapper, CoinStrategyLedgerRecorder ledgerRecorder) {
        this(positionMapper, orderMapper, signalMapper, ledgerRecorder, null);
    }

    @Autowired
    public HtxTrendRecorder(HtxTrendPositionMapper positionMapper, HtxTrendOrderMapper orderMapper,
                            HtxTrendSignalMapper signalMapper, CoinStrategyLedgerRecorder ledgerRecorder,
                            HtxTrendCombinationRecorder combinationRecorder) {
        this.positionMapper = positionMapper;
        this.orderMapper = orderMapper;
        this.signalMapper = signalMapper;
        this.ledgerRecorder = ledgerRecorder;
        this.combinationRecorder = combinationRecorder;
    }

    public Optional<HtxTrendPositionEntity> findActive(String contractCode) {
        return Optional.ofNullable(positionMapper.findActive(contractCode));
    }

    public long countOpenLike() {
        Long count = positionMapper.countOpenLike();
        return count == null ? 0L : count;
    }

    public Optional<LocalDateTime> latestDoneAt(String contractCode) {
        return Optional.ofNullable(positionMapper.latestDoneAt(contractCode));
    }

    public Optional<HtxTrendPositionEntity> findLastDone(String contractCode) {
        return Optional.ofNullable(positionMapper.findLastDone(contractCode));
    }

    public Optional<HtxTrendOrderEntity> findAcceptedCloseOrder(long positionId) {
        return Optional.ofNullable(orderMapper.findLatestAcceptedCloseOrder(positionId));
    }

    public Optional<HtxTrendOrderEntity> findAcceptedPartialCloseOrder(long positionId) {
        return Optional.ofNullable(orderMapper.findLatestAcceptedPartialCloseOrder(positionId));
    }

    public long countRecentSignalsBySide(String contractCode, String signal, LocalDateTime since, int limit) {
        Long count = signalMapper.countRecentSignalsBySide(contractCode, signal, since, limit);
        return count == null ? 0L : count;
    }

    /** Counts the newest uninterrupted run of one signal side for the configured entry timeframe. */
    public long countConsecutiveSignalsBySide(String contractCode, String period, String signal,
                                              LocalDateTime since, int limit) {
        if (limit <= 0) {
            return 0L;
        }
        List<String> recentSides = signalMapper.recentSignalSides(contractCode, period, since, limit);
        if (recentSides == null || recentSides.isEmpty()) {
            return 0L;
        }
        long consecutive = 0L;
        for (String recentSide : recentSides) {
            if (!signal.equals(recentSide)) {
                break;
            }
            consecutive++;
        }
        return consecutive;
    }

    public HtxTrendBreadthStats latestBreadthStats(LocalDateTime since) {
        try {
            HtxTrendBreadthStats stats = signalMapper.latestBreadthStats(since);
            return stats == null ? HtxTrendBreadthStats.empty() : stats;
        } catch (RuntimeException ex) {
            log.warn("trend breadth stats query failed since={}", since, ex);
            return HtxTrendBreadthStats.empty();
        }
    }

    public HtxTrendPerformanceStats trendPerformanceSince(LocalDateTime since, int limit) {
        try {
            HtxTrendPerformanceStats stats = positionMapper.trendPerformanceSince(since, limit);
            return stats == null ? HtxTrendPerformanceStats.empty() : stats;
        } catch (RuntimeException ex) {
            log.warn("trend performance stats query failed since={} limit={}", since, limit, ex);
            return HtxTrendPerformanceStats.empty();
        }
    }

    /** Read-only quality slice for a prospective entry; it never feeds the current live gate or sizing path. */
    public HtxTrendPerformanceStats trendPerformanceForBucket(LocalDateTime since, int limit, String side,
                                                               String primaryModel, String marketRegime,
                                                               String accountLabel) {
        try {
            HtxTrendPerformanceStats stats = positionMapper.trendPerformanceForBucket(since, limit, side,
                    primaryModel, marketRegime, accountLabel);
            return stats == null ? HtxTrendPerformanceStats.empty() : stats;
        } catch (RuntimeException ex) {
            log.warn("trend bucket performance query failed since={} limit={} side={} model={} regime={} account={}",
                    since, limit, side, primaryModel, marketRegime, accountLabel, ex);
            return HtxTrendPerformanceStats.empty();
        }
    }

    public void recordSignal(HtxTrendSignalEntity signal) {
        if (signal == null) {
            return;
        }
        try {
            Long existingId = signalMapper.findLatestSignalIdForBar(signal.getContractCode(),
                    signal.getPeriod(), signal.getBarTime());
            if (existingId != null) {
                signalMapper.updateSignalSnapshotById(existingId, signal);
                return;
            }
            signalMapper.insert(signal);
        } catch (RuntimeException ex) {
            log.warn("trend signal upsert failed contract={} period={} barTime={} signal={}",
                    signal.getContractCode(), signal.getPeriod(), signal.getBarTime(), signal.getSignal(), ex);
        }
    }

    public long createIntent(HtxTrendPositionEntity entity) {
        try {
            preparePositionIdentity(entity);
            positionMapper.insert(entity);
            recordLedger(() -> ledgerRecorder.recordTrendPositionIntent(entity));
            return entity.getId();
        } catch (RuntimeException ex) {
            throw new HtxPersistenceException("trend position intent insert failed", ex);
        }
    }

    public void createOrderIntent(HtxTrendOrderEntity entity) {
        try {
            prepareOrderIdentity(entity);
            orderMapper.insert(entity);
            recordLedger(() -> ledgerRecorder.recordTrendOrderIntent(entity));
        } catch (RuntimeException ex) {
            throw new HtxPersistenceException("trend order intent insert failed", ex);
        }
    }

    public void markOrderAccepted(long positionId, String leg, String clientOrderId, Long orderId,
                                  Long exchangeClientOrderId, String message) {
        orderMapper.markAccepted(positionId, leg, clientOrderId, ORDER_ACCEPTED, orderId,
                exchangeClientOrderId, clip(message));
    }

    public void markOrderRejected(long positionId, String leg, String clientOrderId, String message) {
        orderMapper.markStatus(positionId, leg, clientOrderId, ORDER_REJECTED, clip(message));
    }

    public void markOrderReconciled(long positionId, String leg, String clientOrderId, String status,
                                    Long exchangeOrderId, Long exchangeClientOrderId, String exchangeState,
                                    BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                    String feeCurrency, BigDecimal realizedProfit, String message) {
        orderMapper.markReconciled(positionId, leg, clientOrderId, status, exchangeOrderId, exchangeClientOrderId,
                exchangeState, nz(filledContracts), avgPrice, nz(fee), feeCurrency, realizedProfit, clip(message));
    }

    public void markEntryAccepted(long id, Long orderId, String message) {
        positionMapper.markEntryAccepted(id, INTENT, ENTRY_SENT, orderId, message);
    }

    public void markEntryFilled(long id, BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                BigDecimal highestPrice, BigDecimal lowestPrice, String message) {
        positionMapper.markEntryFilled(id, filledContracts, avgPrice, fee, highestPrice, lowestPrice, message);
        recordLedger(() -> ledgerRecorder.recordTrendEntryFill(positionMapper.selectById(id)));
    }

    public void updateRiskLevels(long id, BigDecimal stopPrice, BigDecimal trailingStopPrice,
                                 BigDecimal takeProfitPrice, BigDecimal highestPrice, BigDecimal lowestPrice,
                                 String message) {
        updateRiskLevels(id, stopPrice, trailingStopPrice, takeProfitPrice, highestPrice, lowestPrice, message,
                null, null, null, null, null, null, null);
    }

    public void updateRiskLevels(long id, BigDecimal stopPrice, BigDecimal trailingStopPrice,
                                 BigDecimal takeProfitPrice, BigDecimal highestPrice, BigDecimal lowestPrice,
                                 String message, String exitSnapshotState, BigDecimal exitSnapshotNet,
                                 BigDecimal exitSnapshotMfe, BigDecimal exitSnapshotMae,
                                 BigDecimal exitSnapshotGiveback, Long exitSnapshotBars,
                                 String exitSnapshotCandidates) {
        positionMapper.updateRiskLevels(id, stopPrice, trailingStopPrice, takeProfitPrice,
                highestPrice, lowestPrice, message, exitSnapshotState, exitSnapshotNet, exitSnapshotMfe,
                exitSnapshotMae, exitSnapshotGiveback, exitSnapshotBars, clip(exitSnapshotCandidates, 1024));
    }

    /** Writes state needed after process restart; failures must not interrupt existing protective exits. */
    public void updateExitEngineState(long id, String exitPhase, LocalDateTime trailingArmedAt,
                                      String trailingArmReason, BigDecimal maxFavorableR,
                                      BigDecimal profitLockPrice, String exitEngineVersion) {
        try {
            positionMapper.updateExitEngineState(id, exitPhase, trailingArmedAt, clip(trailingArmReason),
                    maxFavorableR, profitLockPrice, exitEngineVersion);
        } catch (RuntimeException ex) {
            log.warn("trend exit-engine state persistence failed positionId={}", id, ex);
        }
    }

    /** Appends an explainable decision without allowing diagnostics to block the legacy exit path. */
    public void recordExitDecision(HtxTrendExitDecisionEntity decision) {
        if (decision == null || exitDecisionMapper == null) {
            return;
        }
        try {
            decision.setEvidenceJson(clip(decision.getEvidenceJson(), 12_000));
            decision.setExecutionBlockReason(clip(decision.getExecutionBlockReason()));
            exitDecisionMapper.insert(decision);
        } catch (RuntimeException ex) {
            log.warn("trend exit-engine decision persistence failed positionId={} key={}",
                    decision.getPositionId(), decision.getDecisionKey(), ex);
        }
    }

    public void markCloseAccepted(long id, String exitReason, String clientOrderId, Long orderId, String message) {
        positionMapper.markCloseAccepted(id, clip(exitReason), clientOrderId, orderId, clip(message));
    }

    public void markPartialCloseAccepted(long id, String clientOrderId, Long orderId, String message) {
        positionMapper.markPartialCloseAccepted(id, clientOrderId, orderId, clip(message));
    }

    public void markPartialCloseFilled(long id, int partialExitStage, String clientOrderId,
                                       BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                       BigDecimal realizedProfit, String message) {
        positionMapper.markPartialCloseFilled(id, partialExitStage, filledContracts, avgPrice, fee, realizedProfit,
                clip(message));
        recordLedger(() -> ledgerRecorder.recordTrendCloseFill(positionMapper.selectById(id), clientOrderId,
                filledContracts, avgPrice, fee, realizedProfit));
    }

    /**
     * A full-close intent can be cancelled after an exchange-side partial fill. Keep the position active
     * with the remaining contracts instead of treating the unfilled balance as closed or unknown.
     */
    public void markClosePartiallyFilled(long id, String clientOrderId, BigDecimal filledContracts,
                                         BigDecimal avgPrice, BigDecimal fee, BigDecimal realizedProfit,
                                         String message) {
        positionMapper.markClosePartiallyFilled(id, filledContracts, avgPrice, fee, realizedProfit, clip(message));
        recordLedger(() -> ledgerRecorder.recordTrendCloseFill(positionMapper.selectById(id), clientOrderId,
                filledContracts, avgPrice, fee, realizedProfit));
    }

    public void markCloseFilled(long id, BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                BigDecimal realizedProfit, String message) {
        positionMapper.markCloseFilled(id, filledContracts, avgPrice, fee, realizedProfit, message);
        HtxTrendPositionEntity closedPosition = positionMapper.selectById(id);
        recordExitAttribution(closedPosition, avgPrice);
        recordLedger(() -> {
            if (closedPosition == null || closedPosition.getPartialExitStage() == null || closedPosition.getPartialExitStage() <= 0) {
                ledgerRecorder.recordTrendCloseFill(closedPosition);
                return;
            }
            ledgerRecorder.recordTrendCloseFill(closedPosition, closedPosition.getCloseClientOrderId(), filledContracts,
                    avgPrice, fee, realizedProfit);
        });
    }

    /** Writes immutable post-fill evidence. Early/late exit classification remains pending until a forward window matures. */
    private void recordExitAttribution(HtxTrendPositionEntity position, BigDecimal closePrice) {
        if (position == null || combinationRecorder == null || position.getId() == null) {
            return;
        }
        try {
            BigDecimal realized = nz(position.getRealizedProfit());
            BigDecimal entryFee = nz(position.getEntryFee());
            BigDecimal closeFee = nz(position.getCloseFee());
            BigDecimal net = realized.subtract(entryFee).subtract(closeFee);
            BigDecimal mfe = position.getExitSnapshotMfe();
            BigDecimal mae = position.getExitSnapshotMae();
            BigDecimal giveback = position.getExitSnapshotGiveback();
            String attribution = exitAttribution(position.getExitReason(), net, mfe, giveback, entryFee.add(closeFee));
            HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
            snapshot.setSnapshotKey("EXIT:" + position.getId() + ":" + position.getUpdatedAt() + ":trend-exit-attribution-v1");
            snapshot.setContractCode(position.getContractCode());
            snapshot.setPeriod(position.getTimeframeProfile() == null ? "15min" : position.getTimeframeProfile());
            snapshot.setBarTime(null);
            snapshot.setMarketRegime(position.getMarketRegime());
            snapshot.setFactorsJson(exitAttributionJson(position, closePrice, net, mfe, mae, giveback, attribution));
            Long snapshotId = combinationRecorder.recordSnapshot(snapshot);
            if (snapshotId != null) {
                positionMapper.markExitAttributionSnapshotId(position.getId(), snapshotId);
            }
        } catch (RuntimeException ex) {
            log.warn("trend exit attribution persistence failed positionId={}", position.getId(), ex);
        }
    }

    private static String exitAttribution(String exitReason, BigDecimal net, BigDecimal mfe, BigDecimal giveback,
                                          BigDecimal totalFees) {
        String reason = exitReason == null ? "" : exitReason.toUpperCase(java.util.Locale.ROOT);
        if (net.signum() <= 0 && totalFees.signum() > 0 && net.add(totalFees).signum() > 0) return "COST_EROSION";
        if (reason.contains("MODEL_INVALIDATION") || reason.contains("INITIAL_STOP")) return "DIRECTION_OR_MODEL_FAILURE";
        if (net.signum() < 0 && (mfe == null || mfe.signum() <= 0)) return "ENTRY_QUALITY_FAILURE";
        if (net.signum() <= 0 && giveback != null && giveback.compareTo(new BigDecimal("0.50")) >= 0) return "LATE_EXIT_GIVEBACK";
        if (net.signum() > 0) return "PROFIT_PROTECTION";
        return "FORWARD_WINDOW_REQUIRED";
    }

    private static String exitAttributionJson(HtxTrendPositionEntity position, BigDecimal closePrice, BigDecimal net,
                                              BigDecimal mfe, BigDecimal mae, BigDecimal giveback, String attribution) {
        return "{\"schema\":\"trend-exit-attribution-v1\",\"runId\":" + json(position.getRunId())
                + ",\"positionId\":" + position.getId() + ",\"model\":" + json(position.getPrimaryModel())
                + ",\"side\":" + json(position.getSide()) + ",\"timeframeProfile\":" + json(position.getTimeframeProfile())
                + ",\"exitReason\":" + json(position.getExitReason()) + ",\"closePrice\":" + number(closePrice)
                + ",\"realizedProfit\":" + number(position.getRealizedProfit()) + ",\"entryFee\":" + number(position.getEntryFee())
                + ",\"closeFee\":" + number(position.getCloseFee()) + ",\"netAfterFees\":" + number(net)
                + ",\"mfe\":" + number(mfe) + ",\"mae\":" + number(mae) + ",\"giveback\":" + number(giveback)
                + ",\"barsHeld\":" + number(position.getExitSnapshotBars()) + ",\"attribution\":" + json(attribution)
                + ",\"earlyLateStatus\":\"FORWARD_WINDOW_REQUIRED\"}";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String number(Object value) { return value == null ? "null" : value.toString(); }

    public void markStatus(long id, String nextStatus, String message) {
        positionMapper.markStatus(id, nextStatus, message);
    }

    private void preparePositionIdentity(HtxTrendPositionEntity entity) {
        if (ledgerRecorder != null) {
            ledgerRecorder.enrichTrendPositionIdentity(entity);
        }
    }

    private void prepareOrderIdentity(HtxTrendOrderEntity entity) {
        if (ledgerRecorder != null) {
            ledgerRecorder.enrichTrendOrderIdentity(entity);
        }
    }

    private void recordLedger(Runnable writer) {
        if (ledgerRecorder == null || writer == null) {
            return;
        }
        try {
            writer.run();
        } catch (RuntimeException ex) {
            log.warn("trend ledger projection failed: {}", ex.getClass().getSimpleName());
        }
    }

    private static String clip(String value) {
        return clip(value, 512);
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
