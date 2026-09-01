package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxDeltaHedgeLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryRecycleSagaMapper;
import com.metaverse.coin.strategy.htx.strategy.DeltaHedgeManager;
import com.metaverse.coin.strategy.htx.strategy.InventoryRecyclePlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Durable audit boundary for moving-grid hedge/recycle planner decisions.
 * This recorder only persists intents; it never sends contract or spot orders.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxHedgeRecycleRecorder {

    public static final String STATUS_INTENT = "INTENT";
    public static final String STATUS_CLOSE_SHORT_PENDING = "CLOSE_SHORT_PENDING";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_INVALID_INTENT = "INVALID_INTENT";
    public static final String STATUS_SKIPPED_DRY_RUN = "SKIPPED_DRY_RUN";
    public static final String STATUS_SKIPPED_LIVE_GATE = "SKIPPED_LIVE_GATE";

    private final HtxDeltaHedgeLedgerMapper hedgeMapper;
    private final HtxInventoryRecycleSagaMapper recycleMapper;

    @Transactional
    public RecordResult recordHedgeIntent(String symbol, long epoch,
                                          DeltaHedgeManager.HedgeDecision decision) {
        if (decision == null || decision.orderRequest() == null
                || DeltaHedgeManager.Action.NONE.equals(decision.action())) {
            return RecordResult.skipped("no hedge order intent");
        }
        HtxSwapOrderRequest order = decision.orderRequest();
        HtxDeltaHedgeLedgerEntity entity = new HtxDeltaHedgeLedgerEntity();
        entity.setHedgeKey(hedgeKey(symbol, epoch, order));
        entity.setSymbol(symbol);
        entity.setContractCode(order.getContractCode());
        entity.setEpoch(epoch);
        entity.setAction(decision.action().name());
        entity.setTargetShortBase(nz(decision.targetShortBase()));
        entity.setContractVolume(parseVolume(order.getVolume()));
        entity.setSide(order.getSide());
        entity.setPositionSide(order.getPositionSide());
        entity.setReduceOnly(Boolean.TRUE.equals(order.getReduceOnly()));
        entity.setClientOrderId(order.getClientOrderId());
        entity.setPlannerState(decision.state());
        entity.setStatus(STATUS_INTENT);
        entity.setReason(decision.reason());
        entity.setUpdatedAt(LocalDateTime.now());
        return insertHedge(entity);
    }

    @Transactional
    public RecordResult recordRecycleIntent(Long spotFillLedgerId,
                                            InventoryRecyclePlanner.RecycleInput input,
                                            InventoryRecyclePlanner.RecycleDecision decision) {
        if (spotFillLedgerId == null || input == null || decision == null
                || decision.closeShortRequest() == null || decision.closeContracts() <= 0) {
            return RecordResult.skipped("no recycle close-short intent");
        }
        HtxSwapOrderRequest close = decision.closeShortRequest();
        if (!Boolean.TRUE.equals(close.getReduceOnly())) {
            return RecordResult.skipped("recycle close-short must be reduce-only");
        }
        HtxInventoryRecycleSagaEntity entity = new HtxInventoryRecycleSagaEntity();
        entity.setSagaKey(recycleKey(spotFillLedgerId, close.getClientOrderId()));
        entity.setSymbol(input.symbol());
        entity.setContractCode(input.contractCode());
        entity.setEpoch(input.epoch());
        entity.setSpotFillLedgerId(spotFillLedgerId);
        entity.setSpotSoldBase(nz(input.spotSoldBase()));
        entity.setAvailableShortBase(nz(input.availableShortBase()));
        entity.setCloseContracts(decision.closeContracts());
        entity.setCloseClientOrderId(close.getClientOrderId());
        entity.setPlannerState(decision.state());
        entity.setStatus(STATUS_CLOSE_SHORT_PENDING);
        entity.setReason(decision.reason());
        entity.setUpdatedAt(LocalDateTime.now());
        return insertRecycle(entity);
    }

    private RecordResult insertHedge(HtxDeltaHedgeLedgerEntity entity) {
        try {
            hedgeMapper.insert(entity);
            return RecordResult.appended(entity.getId());
        } catch (DuplicateKeyException duplicate) {
            return RecordResult.duplicated();
        } catch (Exception ex) {
            log.error("hedge intent record failed key={}", entity.getHedgeKey(), ex);
            throw new HtxPersistenceException("hedge intent record failed", ex);
        }
    }

    private RecordResult insertRecycle(HtxInventoryRecycleSagaEntity entity) {
        try {
            recycleMapper.insert(entity);
            return RecordResult.appended(entity.getId());
        } catch (DuplicateKeyException duplicate) {
            return RecordResult.duplicated();
        } catch (Exception ex) {
            log.error("recycle saga record failed key={}", entity.getSagaKey(), ex);
            throw new HtxPersistenceException("recycle saga record failed", ex);
        }
    }

    private static String hedgeKey(String symbol, long epoch, HtxSwapOrderRequest order) {
        return "hedge:" + symbol + ":" + epoch + ":" + order.getClientOrderId();
    }

    private static String recycleKey(Long spotFillLedgerId, String closeClientOrderId) {
        return "recycle:" + spotFillLedgerId + ":" + closeClientOrderId;
    }

    private static Long parseVolume(String volume) {
        if (volume == null || volume.isBlank()) {
            return 0L;
        }
        return Long.parseLong(volume);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record RecordResult(boolean appended, boolean duplicate, String skippedReason, Long id) {
        static RecordResult appended(Long id) {
            return new RecordResult(true, false, null, id);
        }

        static RecordResult duplicated() {
            return new RecordResult(false, true, null, null);
        }

        static RecordResult skipped(String reason) {
            return new RecordResult(false, false, reason, null);
        }
    }
}
