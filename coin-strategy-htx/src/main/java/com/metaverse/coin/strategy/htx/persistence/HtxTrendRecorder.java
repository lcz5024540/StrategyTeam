package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendExitDecisionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendAssessmentEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendAssessmentMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendExitDecisionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendProfitProtectionEventMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendProfitProtectionEventEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionReconciliationEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendOrderMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionReconciliationMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendSignalMapper;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendBreadthStats;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendPerformanceStats;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendOrderLedgerPnl;
import com.metaverse.coin.strategy.htx.service.HtxTrendStructureLifecycleService;
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
    @Autowired(required = false)
    private HtxTrendAssessmentMapper assessmentMapper;
    @Autowired(required = false)
    private HtxTrendProfitProtectionEventMapper profitProtectionEventMapper;
    @Autowired(required = false)
    private HtxTrendPositionReconciliationMapper positionReconciliationMapper;
    @Autowired(required = false)
    private HtxTrendStructureLifecycleService structureLifecycleService;

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

    /**
     * Each follower account owns an independent position row.  Orders must never be attached to the
     * primary position merely because the primary signal originated the mirror execution.
     */
    public HtxTrendPositionEntity ensureMirrorPosition(HtxTrendPositionEntity primary, Long accountId,
                                                        String accountLabel) {
        if (primary == null || primary.getId() == null || accountLabel == null || accountLabel.isBlank()) {
            return primary;
        }
        HtxTrendPositionEntity existing = positionMapper.findMirrorPosition(primary.getId(), accountLabel.trim());
        if (existing != null) {
            return existing;
        }
        HtxTrendPositionEntity mirror = new HtxTrendPositionEntity();
        mirror.setSourcePositionId(primary.getId());
        mirror.setPositionKey(mirrorPositionKey(primary.getPositionKey(), accountLabel));
        mirror.setRunId(primary.getRunId());
        mirror.setContractCode(primary.getContractCode());
        mirror.setStatus(INTENT);
        mirror.setSide(primary.getSide());
        mirror.setContractSize(nz(primary.getContractSize()));
        mirror.setContractVolume(primary.getContractVolume() == null ? 0L : primary.getContractVolume());
        mirror.setRemainingContractVolume(0L);
        mirror.setPartialExitStage(0);
        mirror.setLeverage(primary.getLeverage() == null ? 1 : primary.getLeverage());
        mirror.setEntryFilledContracts(BigDecimal.ZERO);
        mirror.setEntryFee(BigDecimal.ZERO);
        mirror.setCloseFilledContracts(BigDecimal.ZERO);
        mirror.setCloseFee(BigDecimal.ZERO);
        mirror.setEntrySignal(clip(primary.getEntrySignal()));
        mirror.setAccountId(accountId);
        mirror.setAccountLabel(accountLabel.trim());
        mirror.setStrategyFamily(primary.getStrategyFamily() == null ? "TREND" : primary.getStrategyFamily());
        mirror.setStrategyName(primary.getStrategyName() == null ? "HTX_TREND_V1" : primary.getStrategyName());
        mirror.setModelName(primary.getModelName() == null ? "LEGACY_TREND_SIGNAL" : primary.getModelName());
        mirror.setEntryFeeRate(primary.getEntryFeeRate());
        mirror.setEntryFeeProfileId(primary.getEntryFeeProfileId());
        mirror.setEstimatedEntryFee(primary.getEstimatedEntryFee());
        mirror.setEstimatedCloseFee(primary.getEstimatedCloseFee());
        mirror.setExpectedNetAfterFees(primary.getExpectedNetAfterFees());
        mirror.setSizingMultiplier(primary.getSizingMultiplier());
        mirror.setSizingReason(clip(primary.getSizingReason()));
        mirror.setEntryGateReason(clip(primary.getEntryGateReason(), 1024));
        mirror.setPrimaryModel(primary.getPrimaryModel());
        mirror.setConfirmationModels(primary.getConfirmationModels());
        mirror.setPolicyVersion(primary.getPolicyVersion());
        mirror.setTimeframeProfile(primary.getTimeframeProfile() == null ? "INTRADAY_15M" : primary.getTimeframeProfile());
        mirror.setMarketRegime(primary.getMarketRegime());
        mirror.setFactorSnapshotId(primary.getFactorSnapshotId());
        mirror.setEntryHypothesis(clip(primary.getEntryHypothesis(), 1024));
        mirror.setInvalidationPrice(primary.getInvalidationPrice());
        mirror.setTargetZoneLow(primary.getTargetZoneLow());
        mirror.setTargetZoneHigh(primary.getTargetZoneHigh());
        mirror.setExpectedRMultiple(primary.getExpectedRMultiple());
        mirror.setMomentumQualityScore(primary.getMomentumQualityScore());
        createIntent(mirror);
        return mirror;
    }

    public Optional<HtxTrendPositionEntity> findMirrorPosition(long sourcePositionId, String accountLabel) {
        return Optional.ofNullable(positionMapper.findMirrorPosition(sourcePositionId, accountLabel));
    }

    public Optional<HtxTrendOrderEntity> findAcceptedCloseOrder(long positionId, String accountLabel) {
        return Optional.ofNullable(orderMapper.findLatestAcceptedCloseOrder(positionId, accountLabel));
    }

    public Optional<HtxTrendOrderEntity> findLatestCloseOrder(long positionId, String accountLabel) {
        return Optional.ofNullable(orderMapper.findLatestCloseOrder(positionId, accountLabel));
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

    /**
     * Counts the newest uninterrupted completed entry-timeframe closes beyond a profit-protection price.
     * A retry of the same K line is deliberately not another confirmation bar.
     */
    public long countConsecutiveProtectionBreaches(String contractCode, String period, String side,
                                                   BigDecimal protectionPrice, LocalDateTime since, int limit) {
        if (limit <= 0 || protectionPrice == null || protectionPrice.signum() <= 0) {
            return 0L;
        }
        List<HtxTrendSignalEntity> snapshots = signalMapper.recentSignalSnapshots(contractCode, period, since, limit);
        if (snapshots == null || snapshots.isEmpty()) {
            return 0L;
        }
        long consecutive = 0L;
        for (HtxTrendSignalEntity snapshot : snapshots) {
            BigDecimal close = snapshot.getClosePrice();
            boolean crossed = close != null && ("LONG".equals(side)
                    ? close.compareTo(protectionPrice) <= 0 : close.compareTo(protectionPrice) >= 0);
            if (!crossed) {
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
        if (structureLifecycleService != null) {
            structureLifecycleService.markEntryActive(id);
        }
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

    /** Assessment history is diagnostic only: it may never block a live risk or execution path. */
    public void recordAssessment(HtxTrendAssessmentEntity assessment) {
        if (assessment == null || assessmentMapper == null || assessment.getAssessmentKey() == null) return;
        try {
            // Retry of the same completed bar is expected after a process restart.  It is not a new assessment.
            if (assessmentMapper.countByAssessmentKey(assessment.getAssessmentKey()) > 0L) return;
            assessment.setEvidenceJson(clip(assessment.getEvidenceJson(), 12_000));
            assessment.setTransitionReason(clip(assessment.getTransitionReason()));
            assessmentMapper.insert(assessment);
        } catch (RuntimeException ex) {
            log.warn("[HTX-TREND-ASSESSMENT] persistence failed stage={} positionId={} key={} reason={}",
                    assessment.getAssessmentStage(), assessment.getPositionId(), assessment.getAssessmentKey(),
                    ex.getMessage());
        }
    }

    public boolean assessmentExists(String assessmentKey) {
        if (assessmentMapper == null || assessmentKey == null) return false;
        try { return assessmentMapper.countByAssessmentKey(assessmentKey) > 0L; }
        catch (RuntimeException ex) { return false; }
    }

    public String latestHoldingAssessmentState(long positionId) {
        if (assessmentMapper == null) return null;
        try { return assessmentMapper.latestHoldingState(positionId); }
        catch (RuntimeException ex) { return null; }
    }

    public String latestHoldingAssessmentStateBefore(long positionId, long barTime) {
        if (assessmentMapper == null) return null;
        try { return assessmentMapper.latestHoldingStateBefore(positionId, barTime); }
        catch (RuntimeException ex) { return null; }
    }

    /** Persists the restart-safe MFE protection input used by the v4 exit engine; this method never places an order. */
    public void updateProfitProtectionState(long id, BigDecimal mfePrice, BigDecimal mfeNetPnl,
                                            BigDecimal protectionPrice, BigDecimal projectedLockedNetPnl,
                                            BigDecimal mfeCaptureRatio, String protectionMode) {
        try {
            positionMapper.updateProfitProtectionState(id, mfePrice, mfeNetPnl, protectionPrice,
                    projectedLockedNetPnl, mfeCaptureRatio, protectionMode);
        } catch (RuntimeException ex) {
            log.warn("trend profit-protection state persistence failed positionId={}", id, ex);
        }
    }

    /** Appends a protection lifecycle event; diagnostics must never block the existing risk path. */
    public void recordProfitProtectionEvent(HtxTrendProfitProtectionEventEntity event) {
        if (event == null || profitProtectionEventMapper == null) {
            return;
        }
        try {
            event.setReason(clip(event.getReason()));
            event.setEvidenceJson(clip(event.getEvidenceJson(), 12_000));
            profitProtectionEventMapper.insert(event);
        } catch (RuntimeException ex) {
            log.warn("trend profit-protection event persistence failed positionId={} key={}",
                    event.getPositionId(), event.getEventKey(), ex);
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
        refreshFinalOrderLedgerPnl(id);
        HtxTrendPositionEntity closedPosition = positionMapper.selectById(id);
        recordExitAttribution(closedPosition, avgPrice);
        markStructureTerminal(closedPosition);
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
            BigDecimal realized = position.getFinalGrossProfit() == null ? nz(position.getRealizedProfit())
                    : position.getFinalGrossProfit();
            BigDecimal totalFee = position.getFinalTotalFee() == null ? nz(position.getEntryFee()).add(nz(position.getCloseFee()))
                    : position.getFinalTotalFee();
            BigDecimal entryFee = nz(position.getEntryFee());
            BigDecimal closeFee = nz(position.getCloseFee());
            BigDecimal net = position.getFinalNetProfit() == null ? realized.subtract(totalFee) : position.getFinalNetProfit();
            BigDecimal mfe = position.getExitSnapshotMfe();
            BigDecimal mae = position.getExitSnapshotMae();
            BigDecimal giveback = position.getExitSnapshotGiveback();
            String attribution = exitAttribution(position.getExitReason(), net, mfe, giveback, totalFee);
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
                + ",\"closeFee\":" + number(position.getCloseFee()) + ",\"finalGrossProfit\":" + number(position.getFinalGrossProfit())
                + ",\"finalTotalFee\":" + number(position.getFinalTotalFee()) + ",\"finalNetProfit\":" + number(position.getFinalNetProfit())
                + ",\"finalPnlSource\":" + json(position.getFinalPnlSource()) + ",\"netAfterFees\":" + number(net)
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
        if (REJECTED.equals(nextStatus) && structureLifecycleService != null) {
            structureLifecycleService.markEntryAbandoned(id, message);
        }
    }

    /** Records why an exchange-absent position was resolved without inventing an execution price or PnL. */
    public void markReconciledClosed(HtxTrendPositionEntity position, HtxTrendOrderEntity closeOrder,
                                     Long exchangeCloseOrderId, String resolution, String reason) {
        if (position == null || position.getId() == null) {
            return;
        }
        String safeReason = clip(reason);
        positionMapper.markReconciledClosed(position.getId(), "EXCHANGE_POSITION_ABSENT_" + resolution, safeReason);
        markStructureTerminal(positionMapper.selectById(position.getId()));
        if (positionReconciliationMapper == null) {
            return;
        }
        try {
            HtxTrendPositionReconciliationEntity event = new HtxTrendPositionReconciliationEntity();
            event.setReconciliationKey("trend-position-reconcile:" + position.getId() + ":" + resolution
                    + ":" + (exchangeCloseOrderId == null ? "none" : exchangeCloseOrderId));
            event.setPositionId(position.getId());
            event.setAccountLabel(position.getAccountLabel() == null ? "primary" : position.getAccountLabel());
            event.setContractCode(position.getContractCode());
            event.setPriorStatus(position.getStatus());
            event.setExchangePositionState("ABSENT_CONFIRMED");
            event.setCloseOrderId(closeOrder == null ? null : closeOrder.getId());
            event.setCloseClientOrderId(closeOrder == null ? null : closeOrder.getClientOrderId());
            event.setExchangeCloseOrderId(exchangeCloseOrderId);
            event.setResolution(resolution);
            event.setReason(safeReason);
            positionReconciliationMapper.insert(event);
        } catch (RuntimeException ex) {
            log.warn("trend position reconciliation audit insert failed positionId={}", position.getId(), ex);
        }
    }

    private void markStructureTerminal(HtxTrendPositionEntity position) {
        if (structureLifecycleService == null || position == null || position.getId() == null) {
            return;
        }
        structureLifecycleService.markTerminal(position.getId(), position.getExitReason());
    }

    private void refreshFinalOrderLedgerPnl(long positionId) {
        try {
            HtxTrendOrderLedgerPnl pnl = positionMapper.aggregateFilledOrderLedger(positionId);
            if (pnl == null || pnl.getFilledOrderCount() == null || pnl.getFilledOrderCount() <= 0) {
                log.warn("trend final order-ledger PnL unavailable positionId={}", positionId);
                return;
            }
            positionMapper.updateFinalOrderLedgerPnl(positionId, nz(pnl.getGrossProfit()), nz(pnl.getTotalFee()),
                    nz(pnl.getNetProfit()));
        } catch (RuntimeException ex) {
            log.error("trend final order-ledger PnL refresh failed positionId={}", positionId, ex);
        }
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

    private static String mirrorPositionKey(String primaryKey, String accountLabel) {
        String prefix = "mirror:" + accountLabel.trim() + ":" + (primaryKey == null ? "unknown" : primaryKey);
        return prefix.length() <= 128 ? prefix : prefix.substring(0, 128);
    }
}
