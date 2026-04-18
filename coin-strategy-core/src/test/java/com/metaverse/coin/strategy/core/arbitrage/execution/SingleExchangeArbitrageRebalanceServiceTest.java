package com.metaverse.coin.strategy.core.arbitrage.execution;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SingleExchangeArbitrageRebalanceServiceTest {

    @Mock
    private SingleExchangeRiskController riskController;

    @Mock
    private BinanceApiService binanceApiService;

    @Mock
    private SingleExchangeArbitrageRebalanceRecordMapper rebalanceRecordMapper;

    @Mock
    private SingleExchangeArbitrageRiskSnapshotMapper riskSnapshotMapper;

    @Mock
    private SingleExchangeArbitragePositionMapper positionMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private SingleExchangeSpotFuturesProperties properties = new SingleExchangeSpotFuturesProperties();

    @InjectMocks
    private SingleExchangeArbitrageRebalanceService rebalanceService;

    @Test
    void testDoNotPersistWhenMarginRatioIsHealthy() {
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("2.50"));

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).getApiKeyPermission();
        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
        verify(riskSnapshotMapper, never()).insert(any(SingleExchangeArbitrageRiskSnapshot.class));
        verify(rebalanceRecordMapper, never()).insert(any(SingleExchangeArbitrageRebalanceRecord.class));
    }

    @Test
    void testExecuteSpotToFuturesTransferWhenMarginRatioIsLow() {
        stubTransactionExecution();
        stubRiskSnapshotId(100L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("120"))
                .totalMaintMargin(new BigDecimal("100"))
                .assets(Collections.singletonList(new BinanceFuturesAccount.BinanceFuturesAsset(
                        "USDT", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("80"), true, 0L
                )))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(Collections.singletonList(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(new BigDecimal("500"))
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new SingleExchangeArbitragePosition()
                .setId(9L)
                .setPositionCode("SEA-BTCUSDT-001")));
        when(binanceApiService.universalTransfer("MAIN_UMFUTURE", "USDT", "180"))
                .thenReturn(BinanceUniversalTransferResult.builder().tranId(12345L).build());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService).universalTransfer("MAIN_UMFUTURE", "USDT", "180");
        verify(riskSnapshotMapper).insert(argThat(snapshot ->
                "REBALANCE".equals(snapshot.getCheckStage())
                        && Long.valueOf(9L).equals(snapshot.getPositionId())
                        && "SEA-BTCUSDT-001".equals(snapshot.getPositionCode())
                        && new BigDecimal("500").compareTo(snapshot.getSpotUsdtBalance()) == 0
                        && new BigDecimal("80").compareTo(snapshot.getFuturesAvailableBalance()) == 0
                        && new BigDecimal("100").compareTo(snapshot.getRequiredMargin()) == 0
                        && new BigDecimal("300.0").compareTo(snapshot.getBufferedRequiredMargin()) == 0
                        && Boolean.FALSE.equals(snapshot.getRiskPassed())));
        verify(rebalanceRecordMapper).insert(argThat(record ->
                Long.valueOf(100L).equals(record.getRiskSnapshotId())
                        && "EXECUTED".equals(record.getStatus())
                        && "EXECUTED".equals(record.getReasonCode())
                        && new BigDecimal("180").compareTo(record.getActualTransferAmount()) == 0
                        && Long.valueOf(12345L).equals(record.getTransferId())));
    }

    @Test
    void testSkipTransferWhenApiPermissionIsMissing() {
        stubTransactionExecution();
        stubRiskSnapshotId(200L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(false)
                .enableInternalTransfer(false)
                .build());
        when(positionMapper.selectList(any())).thenReturn(Collections.emptyList());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).getFuturesAccount();
        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
        verify(riskSnapshotMapper).insert(argThat(snapshot ->
                snapshot.getPositionId() == null
                        && snapshot.getPositionCode() == null
                        && snapshot.getRejectionReason().contains("PERMISSION_MISSING")));
        verify(rebalanceRecordMapper).insert(argThat(record ->
                Long.valueOf(200L).equals(record.getRiskSnapshotId())
                        && "SKIPPED".equals(record.getStatus())
                        && "PERMISSION_MISSING".equals(record.getReasonCode())));
    }

    @Test
    void testSkipTransferWhenNoMaintenanceMargin() {
        stubTransactionExecution();
        stubRiskSnapshotId(300L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("10"))
                .totalMaintMargin(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("5"))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(Collections.singletonList(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(new BigDecimal("500"))
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(positionMapper.selectList(any())).thenReturn(Collections.emptyList());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
        verify(rebalanceRecordMapper).insert(argThat(record ->
                "SKIPPED".equals(record.getStatus())
                        && "NO_MAINT_MARGIN".equals(record.getReasonCode())));
    }

    @Test
    void testSkipTransferWhenNoSpotBalance() {
        stubTransactionExecution();
        stubRiskSnapshotId(400L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("120"))
                .totalMaintMargin(new BigDecimal("100"))
                .availableBalance(new BigDecimal("80"))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(Collections.singletonList(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(BigDecimal.ZERO)
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(positionMapper.selectList(any())).thenReturn(Collections.emptyList());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
        verify(rebalanceRecordMapper).insert(argThat(record ->
                "SKIPPED".equals(record.getStatus())
                        && "NO_SPOT_BALANCE".equals(record.getReasonCode())));
    }

    @Test
    void testSkipTransferWhenTargetAlreadyMet() {
        stubTransactionExecution();
        stubRiskSnapshotId(450L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("320"))
                .totalMaintMargin(new BigDecimal("100"))
                .availableBalance(new BigDecimal("80"))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(Collections.singletonList(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(new BigDecimal("500"))
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(positionMapper.selectList(any())).thenReturn(Collections.emptyList());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
        verify(rebalanceRecordMapper).insert(argThat(record ->
                Long.valueOf(450L).equals(record.getRiskSnapshotId())
                        && "SKIPPED".equals(record.getStatus())
                        && "TARGET_ALREADY_MET".equals(record.getReasonCode())));
    }

    @Test
    void testPersistFailedOutcomeWhenTransferThrows() {
        stubTransactionExecution();
        stubRiskSnapshotId(500L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("120"))
                .totalMaintMargin(new BigDecimal("100"))
                .availableBalance(new BigDecimal("80"))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(Collections.singletonList(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(new BigDecimal("500"))
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(positionMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(binanceApiService.universalTransfer("MAIN_UMFUTURE", "USDT", "180"))
                .thenThrow(new RuntimeException("transfer failed"));

        rebalanceService.checkAndRebalance();

        verify(riskSnapshotMapper).insert(argThat(snapshot ->
                snapshot.getRejectionReason().contains("REBALANCE_ERROR")));
        verify(rebalanceRecordMapper).insert(argThat(record ->
                Long.valueOf(500L).equals(record.getRiskSnapshotId())
                        && "FAILED".equals(record.getStatus())
                        && "REBALANCE_ERROR".equals(record.getReasonCode())
                        && "transfer failed".equals(record.getErrorMessage())));
    }

    @Test
    void testSkipTransferWhenNormalizedTransferAmountIsNonPositive() {
        stubTransactionExecution();
        stubRiskSnapshotId(600L);
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("2.9999999995"))
                .totalMaintMargin(new BigDecimal("1"))
                .availableBalance(new BigDecimal("100"))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(Collections.singletonList(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(new BigDecimal("100"))
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(positionMapper.selectList(any())).thenReturn(Collections.emptyList());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
        verify(rebalanceRecordMapper).insert(argThat(record ->
                "SKIPPED".equals(record.getStatus())
                        && "NON_POSITIVE_TRANSFER_AMOUNT".equals(record.getReasonCode())));
    }

    private void stubTransactionExecution() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private void stubRiskSnapshotId(long initialId) {
        AtomicLong sequence = new AtomicLong(initialId);
        doAnswer(invocation -> {
            SingleExchangeArbitrageRiskSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(sequence.getAndIncrement());
            return 1;
        }).when(riskSnapshotMapper).insert(any(SingleExchangeArbitrageRiskSnapshot.class));
    }
}
