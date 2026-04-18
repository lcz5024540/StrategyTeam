package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageRebalanceRecordMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageRiskSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageRebalanceRecord;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageRiskSnapshot;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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
    private static final String CHECK_STAGE_REBALANCE = "REBALANCE";
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
    private static final String REBALANCE_TRIGGER = "MARGIN_RATIO_BELOW_THRESHOLD";
    private static final List<String> ACTIVE_POSITION_STATUSES = Arrays.asList("OPENING", "OPENED", "CLOSING");
    private static final String SPOT_TO_UM_FUTURES_TRANSFER_TYPE = "MAIN_UMFUTURE";

    private final SingleExchangeRiskController riskController;
    private final BinanceApiService binanceApiService;
    private final SingleExchangeArbitrageRebalanceRecordMapper rebalanceRecordMapper;
    private final SingleExchangeArbitrageRiskSnapshotMapper riskSnapshotMapper;
    private final SingleExchangeArbitragePositionMapper positionMapper;
    private final SingleExchangeSpotFuturesProperties properties;
    private final TransactionTemplate transactionTemplate;

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
        RebalancePersistenceContext context = basePersistenceContext(currentRatio);
        if (!hasUniversalTransferPermission()) {
            log.warn("Skip rebalance because API key does not have universal transfer permission");
            finalizeAndPersist(context, STATUS_SKIPPED, REASON_PERMISSION_MISSING, null);
            return;
        }

        try {
            BinanceFuturesAccount futuresAccount = binanceApiService.getFuturesAccount();
            BigDecimal currentMarginBalance = defaultValue(futuresAccount.getTotalMarginBalance());
            BigDecimal maintenanceMargin = defaultValue(futuresAccount.getTotalMaintMargin());
            BigDecimal targetMarginBalance = maintenanceMargin.multiply(properties.getRebalanceTargetMarginRatio());
            BigDecimal requiredTransferAmount = targetMarginBalance.subtract(currentMarginBalance);
            BigDecimal availableSpotBalance = getSpotFreeBalance(properties.getRebalanceAsset());
            BigDecimal futuresAvailableBalance = getFuturesAvailableBalance(futuresAccount, properties.getRebalanceAsset());

            populateBalances(context, currentMarginBalance, maintenanceMargin, targetMarginBalance,
                    requiredTransferAmount, availableSpotBalance, futuresAvailableBalance);

            if (maintenanceMargin.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Skip rebalance because there is no active futures maintenance margin");
                finalizeAndPersist(context, STATUS_SKIPPED, REASON_NO_MAINT_MARGIN, null);
                return;
            }

            if (requiredTransferAmount.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Skip rebalance because current futures margin balance already meets target ratio {}", properties.getRebalanceTargetMarginRatio());
                finalizeAndPersist(context, STATUS_SKIPPED, REASON_TARGET_ALREADY_MET, null);
                return;
            }

            if (availableSpotBalance.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Skip rebalance because no free {} is available in spot account", properties.getRebalanceAsset());
                finalizeAndPersist(context, STATUS_SKIPPED, REASON_NO_SPOT_BALANCE, null);
                return;
            }

            BigDecimal transferAmount = requiredTransferAmount.min(availableSpotBalance)
                    .setScale(8, RoundingMode.DOWN)
                    .stripTrailingZeros();
            context.record.setActualTransferAmount(transferAmount);
            if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Skip rebalance because calculated transfer amount is not positive. required={}, available={}",
                        requiredTransferAmount, availableSpotBalance);
                finalizeAndPersist(context, STATUS_SKIPPED, REASON_NON_POSITIVE_TRANSFER_AMOUNT, null);
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
            context.record.setTransferId(transferResult.getTranId());
            finalizeAndPersist(context, STATUS_EXECUTED, partialTransfer ? REASON_PARTIAL_TRANSFER : REASON_EXECUTED, null);
            log.warn("Executed spot-to-futures rebalance. currentRatio={}, targetRatio={}, asset={}, amount={}, tranId={}",
                    currentRatio, properties.getRebalanceTargetMarginRatio(), properties.getRebalanceAsset(), transferAmount.toPlainString(), transferResult.getTranId());
        } catch (Exception e) {
            finalizeAndPersist(context, STATUS_FAILED, REASON_REBALANCE_ERROR, trimMessage(e.getMessage()));
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

    private BigDecimal getFuturesAvailableBalance(BinanceFuturesAccount futuresAccount, String asset) {
        return Optional.ofNullable(futuresAccount.getAssets())
                .orElse(Collections.emptyList())
                .stream()
                .filter(item -> asset.equalsIgnoreCase(item.getAsset()))
                .map(BinanceFuturesAccount.BinanceFuturesAsset::getAvailableBalance)
                .findFirst()
                .orElse(defaultValue(futuresAccount.getAvailableBalance()));
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private RebalancePersistenceContext basePersistenceContext(BigDecimal currentRatio) {
        SingleExchangeArbitragePosition position = findLatestActivePosition();
        SingleExchangeArbitrageRiskSnapshot snapshot = new SingleExchangeArbitrageRiskSnapshot()
                .setPositionId(position != null ? position.getId() : null)
                .setPositionCode(position != null ? position.getPositionCode() : null)
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(properties.getSymbol())
                .setCheckStage(CHECK_STAGE_REBALANCE)
                .setMarginRatio(currentRatio)
                .setRiskPassed(Boolean.FALSE);

        SingleExchangeArbitrageRebalanceRecord record = new SingleExchangeArbitrageRebalanceRecord()
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(properties.getSymbol())
                .setAsset(properties.getRebalanceAsset())
                .setTransferType(SPOT_TO_UM_FUTURES_TRANSFER_TYPE)
                .setCurrentMarginRatio(currentRatio)
                .setThresholdMarginRatio(properties.getRebalanceThresholdMarginRatio())
                .setTargetMarginRatio(properties.getRebalanceTargetMarginRatio());
        return new RebalancePersistenceContext(snapshot, record);
    }

    private SingleExchangeArbitragePosition findLatestActivePosition() {
        LambdaQueryWrapper<SingleExchangeArbitragePosition> queryWrapper = new LambdaQueryWrapper<SingleExchangeArbitragePosition>()
                .eq(SingleExchangeArbitragePosition::getSymbol, properties.getSymbol())
                .in(SingleExchangeArbitragePosition::getStatus, ACTIVE_POSITION_STATUSES)
                .orderByDesc(SingleExchangeArbitragePosition::getId)
                .last("LIMIT 1");
        List<SingleExchangeArbitragePosition> positions = positionMapper.selectList(queryWrapper);
        return positions.isEmpty() ? null : positions.get(0);
    }

    private void populateBalances(RebalancePersistenceContext context,
                                  BigDecimal currentMarginBalance,
                                  BigDecimal maintenanceMargin,
                                  BigDecimal targetMarginBalance,
                                  BigDecimal requiredTransferAmount,
                                  BigDecimal availableSpotBalance,
                                  BigDecimal futuresAvailableBalance) {
        context.record.setFuturesMarginBalance(currentMarginBalance)
                .setFuturesMaintMargin(maintenanceMargin)
                .setTargetMarginBalance(targetMarginBalance)
                .setRequiredTransferAmount(requiredTransferAmount)
                .setAvailableSpotBalance(availableSpotBalance);

        context.snapshot.setSpotUsdtBalance(availableSpotBalance)
                .setFuturesAvailableBalance(futuresAvailableBalance)
                .setRequiredMargin(maintenanceMargin)
                .setBufferedRequiredMargin(targetMarginBalance);
    }

    private void finalizeAndPersist(RebalancePersistenceContext context, String status, String reasonCode, String errorMessage) {
        context.record.setStatus(status)
                .setReasonCode(reasonCode)
                .setErrorMessage(errorMessage);
        context.snapshot.setRejectionReason(buildSnapshotReason(status, reasonCode, errorMessage));
        persistRebalanceOutcome(context);
    }

    private void persistRebalanceOutcome(RebalancePersistenceContext context) {
        Date now = new Date();
        transactionTemplate.executeWithoutResult(ignored -> {
            riskSnapshotMapper.insert(context.snapshot.setCreatedAt(now));
            if (context.snapshot.getId() == null) {
                throw new IllegalStateException("Failed to persist rebalance risk snapshot");
            }
            rebalanceRecordMapper.insert(context.record
                    .setRiskSnapshotId(context.snapshot.getId())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        });
    }

    private String buildSnapshotReason(String status, String reasonCode, String errorMessage) {
        StringBuilder builder = new StringBuilder()
                .append("TRIGGER=").append(REBALANCE_TRIGGER)
                .append(",status=").append(status)
                .append(",reasonCode=").append(reasonCode);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            builder.append(",detail=").append(errorMessage);
        }
        return trimSnapshotReason(builder.toString());
    }

    private String trimSnapshotReason(String reason) {
        if (reason == null || reason.length() <= 255) {
            return reason;
        }
        return reason.substring(0, 255);
    }

    private String trimMessage(String message) {
        if (message == null || message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }

    private static final class RebalancePersistenceContext {
        private final SingleExchangeArbitrageRiskSnapshot snapshot;
        private final SingleExchangeArbitrageRebalanceRecord record;

        private RebalancePersistenceContext(SingleExchangeArbitrageRiskSnapshot snapshot,
                                            SingleExchangeArbitrageRebalanceRecord record) {
            this.snapshot = snapshot;
            this.record = record;
        }
    }
}
