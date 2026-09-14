package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxFundingArbitrageCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxFundingArbitrageCycleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/** Write-ahead persistence for HTX funding arbitrage cycles. */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxFundingArbitrageRecorder {

    public static final String INTENT = "INTENT";
    public static final String SPOT_SENT = "SPOT_SENT";
    public static final String SPOT_FILLED = "SPOT_FILLED";
    public static final String SHORT_SENT = "SHORT_SENT";
    public static final String SHORT_DEGRADED = "SHORT_DEGRADED";
    public static final String ACTIVE = "ACTIVE";
    public static final String SHORT_CLOSED = "SHORT_CLOSED";
    public static final String EXITING_SHORT_SENT = "EXITING_SHORT_SENT";
    public static final String EXITING_SPOT_SENT = "EXITING_SPOT_SENT";
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";

    private final HtxFundingArbitrageCycleMapper mapper;

    public Optional<HtxFundingArbitrageCycleEntity> findActive(String symbol, String contractCode) {
        try {
            return Optional.ofNullable(mapper.findActive(symbol, contractCode));
        } catch (Exception ex) {
            throw new HtxPersistenceException("funding-arbitrage active-cycle query failed", ex);
        }
    }

    public long createIntent(String cycleKey, String symbol, String contractCode,
                             BigDecimal targetQuoteNotional, BigDecimal targetBaseAmount,
                             BigDecimal contractSize, long contractVolume, int leverage,
                             BigDecimal entryFundingRate, BigDecimal entrySpotPrice,
                             BigDecimal entrySwapPrice, String spotClientOrderId) {
        try {
            HtxFundingArbitrageCycleEntity entity = new HtxFundingArbitrageCycleEntity();
            entity.setCycleKey(cycleKey);
            entity.setSymbol(symbol);
            entity.setContractCode(contractCode);
            entity.setStatus(INTENT);
            entity.setTargetQuoteNotional(targetQuoteNotional);
            entity.setTargetBaseAmount(targetBaseAmount);
            entity.setContractSize(contractSize);
            entity.setContractVolume(contractVolume);
            entity.setLeverage(leverage);
            entity.setEntryFundingRate(entryFundingRate);
            entity.setLatestFundingRate(entryFundingRate);
            entity.setEntrySpotPrice(entrySpotPrice);
            entity.setEntrySwapPrice(entrySwapPrice);
            entity.setSpotEntryClientOrderId(spotClientOrderId);
            entity.setSpotEntryFilledBase(BigDecimal.ZERO);
            entity.setSpotEntryFilledQuote(BigDecimal.ZERO);
            entity.setSpotEntryFeeBase(BigDecimal.ZERO);
            entity.setShortEntryFilledContracts(BigDecimal.ZERO);
            entity.setShortEntryFee(BigDecimal.ZERO);
            entity.setShortCloseFilledContracts(BigDecimal.ZERO);
            entity.setShortCloseFee(BigDecimal.ZERO);
            entity.setSpotExitFilledBase(BigDecimal.ZERO);
            entity.setSpotExitFilledQuote(BigDecimal.ZERO);
            entity.setSpotExitFeeQuote(BigDecimal.ZERO);
            entity.setMessage("entry intent created");
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.insert(entity);
            return entity.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("funding-arbitrage intent insert failed", ex);
        }
    }

    public void markStatus(long id, String expectedStatus, String nextStatus, String message) {
        try {
            mapper.updateStatusIfCurrent(id, expectedStatus, nextStatus, clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage status update failed id={} status={}", id, nextStatus, ex);
        }
    }

    public void markSpotEntryAccepted(long id, String expectedStatus, Long orderId, String message) {
        try {
            mapper.updateSpotEntryAcceptedIfCurrent(id, expectedStatus, SPOT_SENT, orderId, clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage spot entry accepted update failed id={}", id, ex);
        }
    }

    public void markSpotEntryFill(long id, String nextStatus, BigDecimal latestFundingRate,
                                  BigDecimal filledBase, BigDecimal filledQuote,
                                  BigDecimal feeBase, String message) {
        try {
            mapper.updateSpotEntryFill(id, nextStatus, latestFundingRate, nz(filledBase), nz(filledQuote),
                    nz(feeBase), clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage spot entry fill update failed id={}", id, ex);
        }
    }

    public void markShortEntryAccepted(long id, long contractVolume, String clientOrderId,
                                       Long orderId, String message) {
        try {
            mapper.updateShortEntryAccepted(id, SHORT_SENT, contractVolume, clientOrderId, orderId,
                    clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage short entry accepted update failed id={}", id, ex);
        }
    }

    public void markShortEntryFill(long id, String nextStatus, BigDecimal filledContracts,
                                   BigDecimal avgPrice, BigDecimal fee,
                                   BigDecimal latestFundingRate, String message) {
        try {
            mapper.updateShortEntryFill(id, nextStatus, nz(filledContracts), avgPrice, nz(fee),
                    latestFundingRate, clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage short entry fill update failed id={}", id, ex);
        }
    }

    public void markShortCloseAccepted(long id, String exitReason, String clientOrderId,
                                       Long orderId, String message) {
        try {
            mapper.updateShortCloseAccepted(id, EXITING_SHORT_SENT, clip(exitReason), clientOrderId,
                    orderId, clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage short close accepted update failed id={}", id, ex);
        }
    }

    public void markShortCloseFill(long id, String nextStatus, BigDecimal filledContracts,
                                   BigDecimal avgPrice, BigDecimal fee, String message) {
        try {
            mapper.updateShortCloseFill(id, nextStatus, nz(filledContracts), avgPrice, nz(fee), clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage short close fill update failed id={}", id, ex);
        }
    }

    public void markSpotExitAccepted(long id, String clientOrderId, Long orderId, String message) {
        try {
            mapper.updateSpotExitAccepted(id, EXITING_SPOT_SENT, clientOrderId, orderId, clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage spot exit accepted update failed id={}", id, ex);
        }
    }

    public void markSpotExitFill(long id, String nextStatus, BigDecimal filledBase,
                                 BigDecimal filledQuote, BigDecimal feeQuote, String message) {
        try {
            mapper.updateSpotExitFill(id, nextStatus, nz(filledBase), nz(filledQuote), nz(feeQuote), clip(message));
        } catch (Exception ex) {
            log.error("funding-arbitrage spot exit fill update failed id={}", id, ex);
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String clip(String value) {
        if (value == null || value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512);
    }
}
