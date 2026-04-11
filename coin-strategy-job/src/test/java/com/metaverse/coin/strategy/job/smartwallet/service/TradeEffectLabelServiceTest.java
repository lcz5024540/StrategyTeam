package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeEffectLabelServiceTest {

    @Mock
    private SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private MarketSnapshotCollectorService marketSnapshotCollectorService;

    private TradeEffectLabelService service;

    @BeforeEach
    void setUp() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getExecutionWallet().setEstimatedFeeRate(new BigDecimal("0.0015"));
        properties.getExecutionWallet().setPriceSlippageRate(new BigDecimal("0.02"));
        properties.getExecutionWallet().setMinOrderUsdc(new BigDecimal("1"));
        properties.getExecutionWallet().setMaxOrderUsdc(new BigDecimal("30"));
        service = new TradeEffectLabelService(
                tradeEffectLabelMapper,
                tradeEventMapper,
                marketSnapshotCollectorService,
                properties
        );
        when(tradeEffectLabelMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void shouldBuildRealizedSlippageFromEntryPriceAgainstMidpoint() {
        Date eventAt = new Date(1_000_000L);
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setId(11L)
                .setWalletAddress("wallet-1")
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setOpenedAt(eventAt)
                .setPrice(new BigDecimal("0.54"))
                .setSizeUsdc(new BigDecimal("10"));
        when(tradeEventMapper.selectById(11L)).thenReturn(event);
        when(marketSnapshotCollectorService.findLatestSnapshotAtOrBefore("market-1", "YES", eventAt))
                .thenReturn(snapshot("0.50", "0.49"));
        when(marketSnapshotCollectorService.findNearestSnapshotAtOrAfter("market-1", "YES", new Date(eventAt.getTime() + 10_000L)))
                .thenReturn(snapshot("0.55", "0.55"));
        when(marketSnapshotCollectorService.findNearestSnapshotAtOrAfter("market-1", "YES", new Date(eventAt.getTime() + 30_000L)))
                .thenReturn(snapshot("0.56", "0.56"));
        when(marketSnapshotCollectorService.findNearestSnapshotAtOrAfter("market-1", "YES", new Date(eventAt.getTime() + 60_000L)))
                .thenReturn(snapshot("0.56", "0.56"));
        when(marketSnapshotCollectorService.findNearestSnapshotAtOrAfter("market-1", "YES", new Date(eventAt.getTime() + 5 * 60_000L)))
                .thenReturn(snapshot("0.57", "0.57"));
        when(marketSnapshotCollectorService.findNearestSnapshotAtOrAfter("market-1", "YES", new Date(eventAt.getTime() + 15 * 60_000L)))
                .thenReturn(snapshot("0.56", "0.56"));

        SmartWalletTradeEffectLabelEntity label = service.buildLabel(11L);

        assertEquals(new BigDecimal("800.00000000"), label.getRealizedSlippageBps());
        assertEquals(new BigDecimal("0.74074074"), label.getRealizedSlippageUsdc());
        assertEquals(new BigDecimal("-0.40037037"), label.getNetPnlRule());

        ArgumentCaptor<SmartWalletTradeEffectLabelEntity> captor = ArgumentCaptor.forClass(SmartWalletTradeEffectLabelEntity.class);
        verify(tradeEffectLabelMapper).insert(captor.capture());
        assertEquals(new BigDecimal("0.74074074"), captor.getValue().getRealizedSlippageUsdc());
    }

    private SmartWalletMarketSnapshotEntity snapshot(String midPrice, String lastTradePrice) {
        return new SmartWalletMarketSnapshotEntity()
                .setMidPrice(new BigDecimal(midPrice))
                .setLastTradePrice(new BigDecimal(lastTradePrice));
    }
}
