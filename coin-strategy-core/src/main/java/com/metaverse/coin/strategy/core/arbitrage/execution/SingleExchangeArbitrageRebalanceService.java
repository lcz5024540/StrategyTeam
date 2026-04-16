package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageRebalanceRecordMapper;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageRebalanceRecord;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiKeyPermission;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceUniversalTransferResult;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;

/**
 * Rebalance Service
 * Monitors margin ratio and performs transfers between Spot and Futures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeArbitrageRebalanceService {

    private static final String EXCHANGE_NAME = "BINANCE";
    private static final String STATUS_EXECUTED = "EXECUTED";
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String REASON_PERMISSION_MISSING = "PERMISSION_MISSING";
    private static final String REASON_NO_MAINT_MARGIN = "NO_MAINT_MARGIN";
    private static final String REASON_TARGET_ALREADY_MET = "TARGET_ALREADY_MET";
    private static final String REASON_NO_SPOT_BALANCE = "NO_SPOT_BALANCE";
    private static final String REASON_NON_POSITIVE_TRANSFER_AMOUNT = "NON_POSITIVE_TRANSFER_AMOUNT";
    private static final String REASON_PARTIAL_TRANSFER = "PARTIAL_TRANSFER";
    private static final String REASON_EXECUTED = "EXECUTED";
    private static final String REASON_REBALANCE_ERROR = "REBALANCE_ERROR";

    private final SingleExchangeRiskController riskController;
    private final BinanceApiService binanceApiService;
    private final SingleExchangeArbitrageRebalanceRecordMapper rebalanceRecordMapper;
    private final SingleExchangeSpotFuturesProperties properties;

    private static final String SPOT_TO_UM_FUTURES_TRANSFER_TYPE = "MAIN_UMFUTURE";
    
    // Check every 5 minutes
    @Scheduled(fixedRate = 300000)
    public void checkAndRebalance() {
        if (!Boolean.TRUE.equals(properties.getRebalanceEnabled())) {
            log.debug("Skip rebalance because it is disabled by configuration");
            return;
        }

        log.info("Checking for Margin Rebalancing...");
        
        try {
            BigDecimal marginRatio = riskController.getMarginRatio();
            if (marginRatio == null) {
                log.warn("Skip rebalance because margin ratio is unavailable");
                return;
            }

            log.info("Current margin ratio: {}", marginRatio);
            if (marginRatio.compareTo(properties.getRebalanceThresholdMarginRatio()) < 0) {
                log.warn("Account health is poor. Triggering spot-to-futures rebalance");
                performRebalance(marginRatio);
            }
            
        } catch (Exception e) {
            log.error("Error during rebalance check", e);
        }
    }

    private void performRebalance(BigDecimal currentRatio) {
        SingleExchangeArbitrageRebalanceRecord record = baseRecord(currentRatio);
        if (!hasUniversalTransferPermission()) {
            log.warn("Skip rebalance because API key does not have universal transfer permission");
            persistRebalanceRecord(record
                    .setStatus(STATUS_SKIPPED)
                    .setReasonCode(REASON_PERMISSION_MISSING));
            return;
        }

        try {
            BinanceFuturesAccount futuresAccount = binanceApiService.getFuturesAccount();
            BigDecimal currentMarginBalance = defaultValue(futuresAccount.getTotalMarginBalance());
            BigDecimal maintenanceMargin = defaultValue(futuresAccount.getTotalMaintMargin());
            BigDecimal targetMarginBalance = maintenanceMargin.multiply(properties.getRebalanceTargetMarginRatio());
            BigDecimal requiredTransferAmount = targetMarginBalance.subtract(currentMarginBalance);
            BigDecimal availableSpotBalance = getSpotFreeBalance(properties.getRebalanceAsset());
            record.setFuturesMarginBalance(currentMarginBalance)
                    .setFuturesMaintMargin(maintenanceMargin)
                    .setTargetMarginBalance(targetMarginBalance)
                    .setRequiredTransferAmount(requiredTransferAmount)
                    .setAvailableSpotBalance(availableSpotBalance);

            if (maintenanceMargin.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Skip rebalance because there is no active futures maintenance margin");
                persistRebalanceRecord(record
                        .setStatus(STATUS_SKIPPED)
                        .setReasonCode(REASON_NO_MAINT_MARGIN));
                return;
            }

            if (requiredTransferAmount.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Skip rebalance because current futures margin balance already meets target ratio {}", properties.getRebalanceTargetMarginRatio());
                persistRebalanceRecord(record
                        .setStatus(STATUS_SKIPPED)
                        .setReasonCode(REASON_TARGET_ALREADY_MET));
                return;
            }

            if (availableSpotBalance.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Skip rebalance because no free {} is available in spot account", properties.getRebalanceAsset());
                persistRebalanceRecord(record
                        .setStatus(STATUS_SKIPPED)
                        .setReasonCode(REASON_NO_SPOT_BALANCE));
                return;
            }

            BigDecimal transferAmount = requiredTransferAmount.min(availableSpotBalance)
                    .setScale(8, RoundingMode.DOWN)
                    .stripTrailingZeros();
            record.setActualTransferAmount(transferAmount);
            if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Skip rebalance because calculated transfer amount is not positive. required={}, available={}",
                        requiredTransferAmount, availableSpotBalance);
                persistRebalanceRecord(record
                        .setStatus(STATUS_SKIPPED)
                        .setReasonCode(REASON_NON_POSITIVE_TRANSFER_AMOUNT));
                return;
            }

            boolean partialTransfer = availableSpotBalance.compareTo(requiredTransferAmount) < 0;
            if (partialTransfer) {
                log.warn("Spot balance is insufficient for full rebalance. required={}, available={}, transferring partial amount={}",
                        requiredTransferAmount.toPlainString(), availableSpotBalance.toPlainString(), transferAmount.toPlainString());
            }

            BinanceUniversalTransferResult transferResult = binanceApiService.universalTransfer(
                    SPOT_TO_UM_FUTURES_TRANSFER_TYPE,
                    properties.getRebalanceAsset(),
                    transferAmount.toPlainString()
            );
            record.setTransferId(transferResult.getTranId());
            persistRebalanceRecord(record
                    .setStatus(STATUS_EXECUTED)
                    .setReasonCode(partialTransfer ? REASON_PARTIAL_TRANSFER : REASON_EXECUTED));
            log.warn("Executed spot-to-futures rebalance. currentRatio={}, targetRatio={}, asset={}, amount={}, tranId={}",
                    currentRatio, properties.getRebalanceTargetMarginRatio(), properties.getRebalanceAsset(), transferAmount.toPlainString(), transferResult.getTranId());
        } catch (Exception e) {
            persistRebalanceRecord(record
                    .setStatus(STATUS_FAILED)
                    .setReasonCode(REASON_REBALANCE_ERROR)
                    .setErrorMessage(trimMessage(e.getMessage())));
            throw e;
        }
    }

    private boolean hasUniversalTransferPermission() {
        try {
            BinanceApiKeyPermission permission = binanceApiService.getApiKeyPermission();
            return Boolean.TRUE.equals(permission.getPermitsUniversalTransfer())
                    || Boolean.TRUE.equals(permission.getEnableInternalTransfer());
        } catch (Exception e) {
            log.error("Failed to query Binance API permissions before rebalance", e);
            return false;
        }
    }

    private BigDecimal getSpotFreeBalance(String asset) {
        BinanceAccountInfo accountInfo = binanceApiService.getAccountInfo();
        Optional<BinanceAccountInfo.BinanceBalance> balance = Optional.ofNullable(accountInfo.getBalances())
                .orElse(Collections.emptyList())
                .stream()
                .filter(item -> asset.equalsIgnoreCase(item.getAsset()))
                .findFirst();
        return balance.map(BinanceAccountInfo.BinanceBalance::getFree).orElse(BigDecimal.ZERO);
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private SingleExchangeArbitrageRebalanceRecord baseRecord(BigDecimal currentRatio) {
        return new SingleExchangeArbitrageRebalanceRecord()
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(properties.getSymbol())
                .setAsset(properties.getRebalanceAsset())
                .setTransferType(SPOT_TO_UM_FUTURES_TRANSFER_TYPE)
                .setCurrentMarginRatio(currentRatio)
                .setThresholdMarginRatio(properties.getRebalanceThresholdMarginRatio())
                .setTargetMarginRatio(properties.getRebalanceTargetMarginRatio());
    }

    private void persistRebalanceRecord(SingleExchangeArbitrageRebalanceRecord record) {
        Date now = new Date();
        rebalanceRecordMapper.insert(record
                .setCreatedAt(now)
                .setUpdatedAt(now));
    }

    private String trimMessage(String message) {
        if (message == null || message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }
}
