package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
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
import java.util.Optional;

/**
 * Rebalance Service
 * Monitors margin ratio and performs transfers between Spot and Futures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeArbitrageRebalanceService {

    private final SingleExchangeRiskController riskController;
    private final BinanceApiService binanceApiService;
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
        if (!hasUniversalTransferPermission()) {
            log.warn("Skip rebalance because API key does not have universal transfer permission");
            return;
        }

        BinanceFuturesAccount futuresAccount = binanceApiService.getFuturesAccount();
        BigDecimal currentMarginBalance = defaultValue(futuresAccount.getTotalMarginBalance());
        BigDecimal maintenanceMargin = defaultValue(futuresAccount.getTotalMaintMargin());

        if (maintenanceMargin.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Skip rebalance because there is no active futures maintenance margin");
            return;
        }

        BigDecimal targetMarginBalance = maintenanceMargin.multiply(properties.getRebalanceTargetMarginRatio());
        BigDecimal requiredTransferAmount = targetMarginBalance.subtract(currentMarginBalance);
        if (requiredTransferAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Skip rebalance because current futures margin balance already meets target ratio {}", properties.getRebalanceTargetMarginRatio());
            return;
        }

        BigDecimal availableSpotBalance = getSpotFreeBalance(properties.getRebalanceAsset());
        if (availableSpotBalance.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Skip rebalance because no free {} is available in spot account", properties.getRebalanceAsset());
            return;
        }

        BigDecimal transferAmount = requiredTransferAmount.min(availableSpotBalance)
                .setScale(8, RoundingMode.DOWN)
                .stripTrailingZeros();
        if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Skip rebalance because calculated transfer amount is not positive. required={}, available={}",
                    requiredTransferAmount, availableSpotBalance);
            return;
        }

        if (availableSpotBalance.compareTo(requiredTransferAmount) < 0) {
            log.warn("Spot balance is insufficient for full rebalance. required={}, available={}, transferring partial amount={}",
                    requiredTransferAmount.toPlainString(), availableSpotBalance.toPlainString(), transferAmount.toPlainString());
        }

        BinanceUniversalTransferResult transferResult = binanceApiService.universalTransfer(
                SPOT_TO_UM_FUTURES_TRANSFER_TYPE,
                properties.getRebalanceAsset(),
                transferAmount.toPlainString()
        );
        log.warn("Executed spot-to-futures rebalance. currentRatio={}, targetRatio={}, asset={}, amount={}, tranId={}",
                currentRatio, properties.getRebalanceTargetMarginRatio(), properties.getRebalanceAsset(), transferAmount.toPlainString(), transferResult.getTranId());
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
}
