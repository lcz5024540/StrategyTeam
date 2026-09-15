package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendSignalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class HtxTrendRecorder {

    public static final String INTENT = "INTENT";
    public static final String ENTRY_SENT = "ENTRY_SENT";
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSE_SENT = "CLOSE_SENT";
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";

    private final HtxTrendPositionMapper positionMapper;
    private final HtxTrendSignalMapper signalMapper;

    public Optional<HtxTrendPositionEntity> findActive(String contractCode) {
        return Optional.ofNullable(positionMapper.findActive(contractCode));
    }

    public Optional<LocalDateTime> latestDoneAt(String contractCode) {
        return Optional.ofNullable(positionMapper.latestDoneAt(contractCode));
    }

    public void recordSignal(HtxTrendSignalEntity signal) {
        try {
            signalMapper.insert(signal);
        } catch (RuntimeException ex) {
            log.warn("trend signal insert failed contract={} signal={}",
                    signal == null ? null : signal.getContractCode(), signal == null ? null : signal.getSignal(), ex);
        }
    }

    public long createIntent(HtxTrendPositionEntity entity) {
        try {
            positionMapper.insert(entity);
            return entity.getId();
        } catch (RuntimeException ex) {
            throw new HtxPersistenceException("trend position intent insert failed", ex);
        }
    }

    public void markEntryAccepted(long id, Long orderId, String message) {
        positionMapper.markEntryAccepted(id, INTENT, ENTRY_SENT, orderId, message);
    }

    public void markEntryFilled(long id, BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                BigDecimal highestPrice, BigDecimal lowestPrice, String message) {
        positionMapper.markEntryFilled(id, filledContracts, avgPrice, fee, highestPrice, lowestPrice, message);
    }

    public void updateRiskLevels(long id, BigDecimal stopPrice, BigDecimal trailingStopPrice,
                                 BigDecimal takeProfitPrice, BigDecimal highestPrice, BigDecimal lowestPrice,
                                 String message) {
        positionMapper.updateRiskLevels(id, stopPrice, trailingStopPrice, takeProfitPrice,
                highestPrice, lowestPrice, message);
    }

    public void markCloseAccepted(long id, String exitReason, String clientOrderId, Long orderId, String message) {
        positionMapper.markCloseAccepted(id, exitReason, clientOrderId, orderId, message);
    }

    public void markCloseFilled(long id, BigDecimal filledContracts, BigDecimal avgPrice, BigDecimal fee,
                                BigDecimal realizedProfit, String message) {
        positionMapper.markCloseFilled(id, filledContracts, avgPrice, fee, realizedProfit, message);
    }

    public void markStatus(long id, String nextStatus, String message) {
        positionMapper.markStatus(id, nextStatus, message);
    }
}
