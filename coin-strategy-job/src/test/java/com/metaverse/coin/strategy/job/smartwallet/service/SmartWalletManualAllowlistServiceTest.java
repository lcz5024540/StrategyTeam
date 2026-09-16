package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualAllowlistMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletManualAllowlistServiceTest {

    @Mock
    private SmartWalletManualAllowlistMapper allowlistMapper;
    @Mock
    private SmartWalletOrderMapper orderMapper;

    private SmartWalletManualAllowlistService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletManualAllowlistService(allowlistMapper, orderMapper);
    }

    @Test
    void upsertShouldNormalizeWalletAndApplyBalancedDefaults() {
        when(allowlistMapper.selectOne(any())).thenReturn(null);
        SmartWalletManualAllowlistRequest request = new SmartWalletManualAllowlistRequest();
        request.setWalletAddress(" 0xABC ");
        request.setReason(" trusted address ");

        service.upsert(request);

        ArgumentCaptor<SmartWalletManualAllowlistEntity> captor = ArgumentCaptor.forClass(SmartWalletManualAllowlistEntity.class);
        verify(allowlistMapper).insert(captor.capture());
        SmartWalletManualAllowlistEntity inserted = captor.getValue();
        assertEquals("0xabc", inserted.getWalletAddress());
        assertEquals("POLYMARKET", inserted.getSource());
        assertEquals("ACTIVE", inserted.getStatus());
        assertEquals("PAPER", inserted.getAllowedExecutionModes());
        assertEquals(0, new BigDecimal("0.5000").compareTo(inserted.getStakeMultiplier()));
        assertEquals(5, inserted.getMaxLiveOrdersPerDay());
        assertEquals(2, inserted.getMaxLiveOrdersPerRun());
        assertEquals("trusted address", inserted.getReason());
        assertTrue(inserted.getExpiresAt().after(new Date()));
    }

    @Test
    void findActiveShouldRejectExpiredOrDisabledRows() {
        when(allowlistMapper.selectOne(any())).thenReturn(new SmartWalletManualAllowlistEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setStatus("ACTIVE")
                .setExpiresAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))));

        Optional<SmartWalletManualAllowlistEntity> expired = service.findActive("0xabc");

        assertTrue(expired.isEmpty());
        assertFalse(service.isActive(new SmartWalletManualAllowlistEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setStatus("DISABLED")));
    }

    @Test
    void allowlistShouldCapStakeMultiplierAndDetectLiveMode() {
        SmartWalletManualAllowlistEntity entity = new SmartWalletManualAllowlistEntity()
                .setAllowedExecutionModes("both")
                .setStakeMultiplier(new BigDecimal("1.25"));

        assertTrue(service.allowsLive(entity));
        assertEquals(new BigDecimal("1.0000"), service.stakeMultiplier(entity));
    }

    @Test
    void dailyLiveLimitShouldBlockWhenLimitReached() {
        SmartWalletManualAllowlistEntity entity = new SmartWalletManualAllowlistEntity()
                .setMaxLiveOrdersPerDay(5);
        when(orderMapper.selectCount(any())).thenReturn(5L);

        assertTrue(service.exceedsDailyLiveLimit("0xabc", entity));
    }
}
