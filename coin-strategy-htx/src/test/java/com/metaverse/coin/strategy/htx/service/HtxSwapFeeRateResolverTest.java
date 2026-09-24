package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFeeRate;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxSwapFeeRateResolverTest {

    @Test
    void actualAccountScheduleGetsStableProfileReference() {
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxAccountFeeProfileRecorder recorder = mock(HtxAccountFeeProfileRecorder.class);
        when(swap.tradingFee("BTC-USDT")).thenReturn(ok(List.of(feeRate("BTC-USDT", "0.00012", "0.0004", "0.00012", "0.0004"))));
        when(recorder.record(eq("primary"), eq("BTC-USDT"), any())).thenReturn(41L);
        HtxSwapFeeRateResolver resolver = new HtxSwapFeeRateResolver(swap, () -> new BigDecimal("0.0005"),
                () -> 60_000L, "primary", recorder);

        HtxSwapFeeRateResolver.FeeRates rates = resolver.rates("btc-usdt");

        assertEquals(new BigDecimal("0.0004"), rates.taker());
        assertFalse(rates.fallback());
        assertEquals(41L, rates.profileId());
        verify(recorder).record(eq("primary"), eq("BTC-USDT"), any());
    }

    @Test
    void unavailableExchangeWritesFallbackProfileInsteadOfClaimingAccountFee() {
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxAccountFeeProfileRecorder recorder = mock(HtxAccountFeeProfileRecorder.class);
        when(swap.tradingFee("ETH-USDT")).thenReturn(HtxRestResponse.<List<HtxSwapFeeRate>>builder()
                .status("error").errMsg("temporary unavailable").build());
        when(recorder.record(eq("TradeX"), eq("ETH-USDT"), any())).thenReturn(42L);
        HtxSwapFeeRateResolver resolver = new HtxSwapFeeRateResolver(swap, () -> new BigDecimal("0.0002"),
                () -> 60_000L, "TradeX", recorder);

        HtxSwapFeeRateResolver.FeeRates rates = resolver.rates("ETH-USDT");

        assertEquals(new BigDecimal("0.0002"), rates.taker());
        assertTrue(rates.fallback());
        assertEquals(42L, rates.profileId());
        verify(recorder).record(eq("TradeX"), eq("ETH-USDT"), any());
    }

    private static HtxSwapFeeRate feeRate(String contractCode, String openMaker, String openTaker,
                                          String closeMaker, String closeTaker) {
        HtxSwapFeeRate rate = new HtxSwapFeeRate();
        rate.setContractCode(contractCode);
        rate.setOpenMakerFee(new BigDecimal(openMaker));
        rate.setOpenTakerFee(new BigDecimal(openTaker));
        rate.setCloseMakerFee(new BigDecimal(closeMaker));
        rate.setCloseTakerFee(new BigDecimal(closeTaker));
        return rate;
    }

    private static <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }
}
