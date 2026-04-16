package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SingleExchangeArbitrageRebalanceServiceTest {

    @Mock
    private SingleExchangeRiskController riskController;

    @Mock
    private BinanceApiService binanceApiService;

    @Spy
    private SingleExchangeSpotFuturesProperties properties = new SingleExchangeSpotFuturesProperties();

    @InjectMocks
    private SingleExchangeArbitrageRebalanceService rebalanceService;

    @Test
    void testExecuteSpotToFuturesTransferWhenMarginRatioIsLow() {
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(true)
                .enableInternalTransfer(true)
                .build());
        when(binanceApiService.getFuturesAccount()).thenReturn(BinanceFuturesAccount.builder()
                .totalMarginBalance(new BigDecimal("120"))
                .totalMaintMargin(new BigDecimal("100"))
                .build());
        when(binanceApiService.getAccountInfo()).thenReturn(BinanceAccountInfo.builder()
                .balances(List.of(BinanceAccountInfo.BinanceBalance.builder()
                        .asset("USDT")
                        .free(new BigDecimal("500"))
                        .locked(BigDecimal.ZERO)
                        .build()))
                .build());
        when(binanceApiService.universalTransfer("MAIN_UMFUTURE", "USDT", "180"))
                .thenReturn(BinanceUniversalTransferResult.builder().tranId(12345L).build());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService).universalTransfer("MAIN_UMFUTURE", "USDT", "180");
    }

    @Test
    void testSkipTransferWhenApiPermissionIsMissing() {
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.20"));
        when(binanceApiService.getApiKeyPermission()).thenReturn(BinanceApiKeyPermission.builder()
                .permitsUniversalTransfer(false)
                .enableInternalTransfer(false)
                .build());

        rebalanceService.checkAndRebalance();

        verify(binanceApiService, never()).getFuturesAccount();
        verify(binanceApiService, never()).universalTransfer(anyString(), anyString(), anyString());
    }
}
