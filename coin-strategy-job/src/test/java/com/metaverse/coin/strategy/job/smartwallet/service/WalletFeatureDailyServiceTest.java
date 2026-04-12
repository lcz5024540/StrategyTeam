package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletFeatureDailyServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    @Mock
    private SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;

    private WalletFeatureDailyService service;

    @BeforeEach
    void setUp() {
        service = new WalletFeatureDailyService(
                walletFeatureDailyMapper,
                tradeEventMapper,
                tradeEffectLabelMapper,
                profileMapper
        );
        when(walletFeatureDailyMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void shouldBuildWalletFeatureDailyFromEventsAndLabels() {
        LocalDate featureDate = LocalDate.of(2026, 4, 10);
        Date eventDate1 = asDate(LocalDate.of(2026, 4, 9));
        Date eventDate2 = asDate(LocalDate.of(2026, 4, 5));
        when(tradeEventMapper.selectList(any())).thenReturn(
                List.of(
                        event("wallet-1", "market-a", "10", eventDate1),
                        event("wallet-1", "market-b", "20", eventDate2)
                ),
                List.of(event("wallet-1", "market-a", "10", eventDate1))
        );
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of(
                label("12.50", true, "40", "10"),
                label("-2.50", false, "120", "20")
        ));
        when(profileMapper.selectOne(any())).thenReturn(new SmartWalletProfileEntity()
                .setMaxDrawdownRate(new BigDecimal("0.15")));

        SmartWalletWalletFeatureDailyEntity feature = service.buildFeature("wallet-1", featureDate);

        assertEquals(2, feature.getTrades7d());
        assertEquals(2, feature.getTrades30d());
        assertEquals(2, feature.getMarkets30d());
        assertEquals(new BigDecimal("10.00000000"), feature.getPnl30d());
        assertEquals(new BigDecimal("0.5000"), feature.getWinRate30d());
        assertEquals(new BigDecimal("15.00000000"), feature.getAvgSize30d());
        assertEquals(new BigDecimal("0.1500"), feature.getDrawdown30d());
        assertEquals(new BigDecimal("0.5000"), feature.getConcentrationMarket30d());
        assertEquals(new BigDecimal("0.0000"), feature.getLowLiquidityRatio30d());
        assertEquals(new BigDecimal("0.0000"), feature.getLateMarketRatio30d());
        assertEquals(new BigDecimal("0.5000"), feature.getFollowableRate30d());

        ArgumentCaptor<SmartWalletWalletFeatureDailyEntity> captor = ArgumentCaptor.forClass(SmartWalletWalletFeatureDailyEntity.class);
        verify(walletFeatureDailyMapper).insert(captor.capture());
        assertEquals("wallet-1", captor.getValue().getWalletAddress());
    }

    private SmartWalletTradeEventEntity event(String walletAddress, String marketId, String sizeUsdc, Date openedAt) {
        return new SmartWalletTradeEventEntity()
                .setWalletAddress(walletAddress)
                .setMarketId(marketId)
                .setSizeUsdc(new BigDecimal(sizeUsdc))
                .setOpenedAt(openedAt);
    }

    private SmartWalletTradeEffectLabelEntity label(String netPnlRule,
                                                    boolean positive,
                                                    String realizedSlippageBps,
                                                    String stakeUsdc) {
        return new SmartWalletTradeEffectLabelEntity()
                .setNetPnlRule(new BigDecimal(netPnlRule))
                .setIsPositiveEvRule(positive)
                .setRealizedSlippageBps(new BigDecimal(realizedSlippageBps))
                .setPaperStakeUsdc(new BigDecimal(stakeUsdc));
    }

    private Date asDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZONE_ID).toInstant());
    }
}
