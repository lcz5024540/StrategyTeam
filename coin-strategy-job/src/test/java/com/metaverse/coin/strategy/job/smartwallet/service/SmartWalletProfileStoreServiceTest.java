package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletProfileStoreServiceTest {

    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;

    @Test
    void shouldKeepLeaderboardBiasFallbackWhenUsingRecentWinRateScoring() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("1.0"))
                .setRecentWinRate30d(new BigDecimal("0.95"))
                .setRealizedPnlUsdc(new BigDecimal("1200"))
                .setTotalTrades(20)
                .setAverageRoiRate(new BigDecimal("0.30"))
                .setMaxDrawdownRate(new BigDecimal("0.10"));
        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));
        when(followTaskMapper.selectList(any())).thenReturn(List.of());

        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                positionMapper
        );

        service.refreshInternalWinRate();

        ArgumentCaptor<SmartWalletProfileEntity> captor = ArgumentCaptor.forClass(SmartWalletProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("0.65"), captor.getValue().getWinRate());
        assertTrue(captor.getValue().getWalletScore().compareTo(BigDecimal.ONE) < 0);
    }
}
