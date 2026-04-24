package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
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
    private SmartWalletOrderMapper orderMapper;
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

        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                properties,
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper
        );

        service.refreshInternalWinRate();

        ArgumentCaptor<SmartWalletProfileEntity> captor = ArgumentCaptor.forClass(SmartWalletProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("0.65"), captor.getValue().getWinRate());
        assertTrue(captor.getValue().getWalletScore().compareTo(BigDecimal.ONE) < 0);
    }

    @Test
    void shouldUseFollowTaskPositionLinksAndClosedStatusesForInternalWinRateRefresh() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xee613b")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("1.0"))
                .setRecentWinRate30d(new BigDecimal("1.0"))
                .setRealizedPnlUsdc(new BigDecimal("1200"))
                .setTotalTrades(50)
                .setAverageRoiRate(new BigDecimal("0.12"))
                .setMaxDrawdownRate(new BigDecimal("0.10"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
        SmartWalletFollowTaskEntity task1 = new SmartWalletFollowTaskEntity().setId(101L).setWalletAddress("0xee613b").setStatus("FOLLOWED");
        SmartWalletFollowTaskEntity task2 = new SmartWalletFollowTaskEntity().setId(102L).setWalletAddress("0xee613b").setStatus("FOLLOWED");
        SmartWalletOrderEntity order1 = new SmartWalletOrderEntity().setFollowTaskId(101L).setPositionId(1001L);
        SmartWalletOrderEntity order2 = new SmartWalletOrderEntity().setFollowTaskId(102L).setPositionId(1002L);
        SmartWalletPositionEntity closedLoss = new SmartWalletPositionEntity().setId(1001L).setStatus("CLOSED").setRealizedPnlUsdc(new BigDecimal("-50"));
        SmartWalletPositionEntity claimedWin = new SmartWalletPositionEntity().setId(1002L).setStatus("CLAIMED").setRealizedPnlUsdc(new BigDecimal("20"));

        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task1, task2));
        when(orderMapper.selectList(any())).thenReturn(List.of(order1, order2));
        when(positionMapper.selectBatchIds(any())).thenReturn(List.of(closedLoss, claimedWin));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.80"));
        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                properties,
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper
        );

        service.refreshInternalWinRate();

        ArgumentCaptor<SmartWalletProfileEntity> captor = ArgumentCaptor.forClass(SmartWalletProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("0.7000"), captor.getValue().getWinRate());
        assertEquals(Boolean.FALSE, captor.getValue().getQualified());
        assertEquals(Boolean.FALSE, captor.getValue().getWatchEnabled());
    }
}
