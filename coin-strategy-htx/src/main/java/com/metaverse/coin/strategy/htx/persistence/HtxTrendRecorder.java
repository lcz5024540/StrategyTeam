package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
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
import java.util.Optional;

@Slf4j
@Component
public class HtxTrendRecorder {

    public static final String INTENT = "INTENT";
    public static final String ENTRY_SENT = "ENTRY_SENT";
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSE_SENT = "CLOSE_SENT";
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String ORDER_INTENT = "INTENT";
    public static final String ORDER_ACCEPTED = "ACCEPTED";
    public static final String ORDER_FILLED = "FILLED";
    public static final String ORDER_TERMINAL = "TERMINAL";
    public static final String ORDER_REJECTED = "REJECTED";
    public static final String LEG_ENTRY = "ENTRY";
    public static final String LEG_CLOSE = "CLOSE";

    private final HtxTrendPositionMapper positionMapper;
    private final HtxTrendOrderMapper orderMapper;
    private final HtxTrendSignalMapper signalMapper;
    private final CoinStrategyLedgerRecorder ledgerRecorder;

    public HtxTrendRecorder(HtxTrendPositionMapper positionMapper, HtxTrendOrderMapper orderMapper,
                            HtxTrendSignalMapper signalMapper) {
        this(positionMapper, orderMapper, signalMapper, null);
    }

    @Autowired
    public HtxTrendRecorder(HtxTrendPositionMapper positionMapper, HtxTrendOrderMapper orderMapper,
                            HtxTrendSignalMapper signalMapper, CoinStrategyLedgerRecorder ledgerRecorder) {
        this.positionMapper = positionMapper;
        this.orderMapper = orderMapper;
        this.signalMapper = signalMapper;
        this.ledgerRecorder = ledgerRecorder;
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

    public long countRecentSignalsBySide(String contractCode, String signal, LocalDateTime since, int limit) {
        Long count = signalMapper.countRecentSignalsBySide(contractCode, signal, since, limit);
        return count == null ? 0L : count;
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
            positionMapper.insert(entity);
            recordLedger(() -> ledgerRecorder.recordTrendPositionIntent(entity));
            return entity.getId();
        } catch (RuntimeException ex) {
            throw new HtxPersistenceException("trend position intent insert failed", ex);
        }
    }

    public void createOrderIntent(HtxTrendOrderEntity entity) {
        try {
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

    public void markCloseAccepted(long id, String exitReason, String clientOrderId, Long orderId, String message) {
        positionMapper.markCloseAccepted(id, exitReason, clientOrderId, orderId, message);
    }

    public void markCloseFilled(long id, BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                BigDecimal realizedProfit, String message) {
        positionMapper.markCloseFilled(id, filledContracts, avgPrice, fee, realizedProfit, message);
        recordLedger(() -> ledgerRecorder.recordTrendCloseFill(positionMapper.selectById(id)));
    }

    public void markStatus(long id, String nextStatus, String message) {
        positionMapper.markStatus(id, nextStatus, message);
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
