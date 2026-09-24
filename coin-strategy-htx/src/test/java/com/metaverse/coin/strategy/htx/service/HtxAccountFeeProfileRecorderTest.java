package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTradeAccountFeeProfileEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountFeeProfileMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxAccountFeeProfileRecorderTest {

    @Test
    void sameScheduleRefreshesObservationWithoutCreatingNewHistoryRow() {
        HtxTradeAccountMapper accountMapper = mock(HtxTradeAccountMapper.class);
        HtxTradeAccountFeeProfileMapper profileMapper = mock(HtxTradeAccountFeeProfileMapper.class);
        when(accountMapper.findIdByAccountLabel("primary")).thenReturn(1L);
        HtxTradeAccountFeeProfileEntity current = profile(18L, false, HtxAccountFeeProfileRecorder.SOURCE_HTX_ACCOUNT,
                "0.00012", "0.0004", "0.00012", "0.0004");
        when(profileMapper.findCurrent(1L, HtxAccountFeeProfileRecorder.MARKET_USDT_SWAP, "BTC-USDT"))
                .thenReturn(current);
        HtxAccountFeeProfileRecorder recorder = new HtxAccountFeeProfileRecorder(accountMapper, profileMapper);

        Long profileId = recorder.record("primary", "btc-usdt", rates("0.00012", "0.0004", "0.00012", "0.0004", false));

        assertEquals(18L, profileId);
        verify(profileMapper).touchCurrent(eq(18L), any());
        verify(profileMapper, never()).closeCurrent(anyLong(), any());
        verify(profileMapper, never()).insert(any());
    }

    @Test
    void changedScheduleClosesOldVersionAndCreatesNewCurrentProfile() {
        HtxTradeAccountMapper accountMapper = mock(HtxTradeAccountMapper.class);
        HtxTradeAccountFeeProfileMapper profileMapper = mock(HtxTradeAccountFeeProfileMapper.class);
        when(accountMapper.findIdByAccountLabel("TradeX")).thenReturn(2L);
        when(profileMapper.findCurrent(2L, HtxAccountFeeProfileRecorder.MARKET_USDT_SWAP, "ETH-USDT"))
                .thenReturn(profile(21L, true, HtxAccountFeeProfileRecorder.SOURCE_CONFIGURED_FALLBACK,
                        "0.0005", "0.0005", "0.0005", "0.0005"));
        when(profileMapper.insert(any(HtxTradeAccountFeeProfileEntity.class))).thenAnswer(invocation -> {
            HtxTradeAccountFeeProfileEntity entity = invocation.getArgument(0);
            entity.setId(22L);
            return 1;
        });
        HtxAccountFeeProfileRecorder recorder = new HtxAccountFeeProfileRecorder(accountMapper, profileMapper);

        Long profileId = recorder.record("TradeX", "ETH-USDT", rates("0.0001", "0.0003", "0.0001", "0.0003", false));

        assertEquals(22L, profileId);
        verify(profileMapper).closeCurrent(eq(21L), any());
        ArgumentCaptor<HtxTradeAccountFeeProfileEntity> captor = ArgumentCaptor.forClass(HtxTradeAccountFeeProfileEntity.class);
        verify(profileMapper).insert(captor.capture());
        HtxTradeAccountFeeProfileEntity inserted = captor.getValue();
        assertEquals(2L, inserted.getAccountId());
        assertEquals("TradeX", inserted.getAccountLabel());
        assertEquals("ETH-USDT", inserted.getContractCode());
        assertEquals(HtxAccountFeeProfileRecorder.SOURCE_HTX_ACCOUNT, inserted.getSource());
        assertFalse(inserted.getFallback());
        assertEquals("active:2:USDT_SWAP:ETH-USDT", inserted.getCurrentProfileKey());
    }

    @Test
    void partialExchangeScheduleIsPersistedAsConfiguredFallbackInsteadOfActualAccountRate() {
        HtxTradeAccountMapper accountMapper = mock(HtxTradeAccountMapper.class);
        HtxTradeAccountFeeProfileMapper profileMapper = mock(HtxTradeAccountFeeProfileMapper.class);
        when(accountMapper.findIdByAccountLabel("primary")).thenReturn(1L);
        when(profileMapper.insert(any(HtxTradeAccountFeeProfileEntity.class))).thenAnswer(invocation -> {
            HtxTradeAccountFeeProfileEntity entity = invocation.getArgument(0);
            entity.setId(23L);
            return 1;
        });
        HtxAccountFeeProfileRecorder recorder = new HtxAccountFeeProfileRecorder(accountMapper, profileMapper);

        HtxSwapFeeRateResolver.FeeRates partial = rates("0.0005", "0.0002", "0.0005", "0.0002", true);
        Long profileId = recorder.record("primary", "XRP-USDT", partial);

        assertEquals(23L, profileId);
        ArgumentCaptor<HtxTradeAccountFeeProfileEntity> captor = ArgumentCaptor.forClass(HtxTradeAccountFeeProfileEntity.class);
        verify(profileMapper).insert(captor.capture());
        assertEquals(HtxAccountFeeProfileRecorder.SOURCE_CONFIGURED_FALLBACK, captor.getValue().getSource());
        assertTrue(captor.getValue().getFallback());
    }

    private static HtxTradeAccountFeeProfileEntity profile(long id, boolean fallback, String source,
                                                           String openMaker, String openTaker,
                                                           String closeMaker, String closeTaker) {
        HtxTradeAccountFeeProfileEntity profile = new HtxTradeAccountFeeProfileEntity();
        profile.setId(id);
        profile.setFallback(fallback);
        profile.setSource(source);
        profile.setOpenMakerRate(new BigDecimal(openMaker));
        profile.setOpenTakerRate(new BigDecimal(openTaker));
        profile.setCloseMakerRate(new BigDecimal(closeMaker));
        profile.setCloseTakerRate(new BigDecimal(closeTaker));
        profile.setEffectiveTakerRate(new BigDecimal(openTaker).max(new BigDecimal(closeTaker)));
        return profile;
    }

    private static HtxSwapFeeRateResolver.FeeRates rates(String openMaker, String openTaker,
                                                          String closeMaker, String closeTaker, boolean fallback) {
        BigDecimal openTakerRate = new BigDecimal(openTaker);
        BigDecimal closeTakerRate = new BigDecimal(closeTaker);
        return new HtxSwapFeeRateResolver.FeeRates(new BigDecimal(openMaker), openTakerRate,
                new BigDecimal(closeMaker), closeTakerRate, openTakerRate.max(closeTakerRate), fallback, null);
    }
}
