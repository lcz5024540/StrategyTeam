package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxBalance;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxFundingArbitrageProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxFundingArbitrageRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxFundingArbitrageCycleEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxFundingArbitrageServiceTest {

    private HtxApiService spotApi;
    private HtxSpotService spot;
    private HtxMarketDataService marketData;
    private HtxAccountService account;
    private HtxUsdtSwapService swap;
    private HtxTradeProperties tradeProperties;
    private HtxContractProperties contractProperties;
    private HtxFundingArbitrageProperties properties;
    private HtxFundingArbitrageRecorder recorder;
    private HtxFundingArbitrageService service;

    @BeforeEach
    void setUp() {
        spotApi = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        marketData = mock(HtxMarketDataService.class);
        account = mock(HtxAccountService.class);
        swap = mock(HtxUsdtSwapService.class);
        recorder = mock(HtxFundingArbitrageRecorder.class);
        tradeProperties = new HtxTradeProperties();
        tradeProperties.getTrade().setEnabled(true);
        tradeProperties.getApi().setSpotAccountId("12345");
        contractProperties = new HtxContractProperties();
        contractProperties.setEnabled(true);
        contractProperties.setDryRun(false);
        contractProperties.setLiveEnabled(true);
        contractProperties.setMaxLeverage(5);
        properties = new HtxFundingArbitrageProperties();
        properties.setEnabled(true);
        properties.setTargetQuoteNotional(new BigDecimal("500"));
        when(spotApi.getSpotService()).thenReturn(spot);
        when(spotApi.getMarketDataService()).thenReturn(marketData);
        when(spotApi.getAccountService()).thenReturn(account);
        when(recorder.findActive("xautusdt", "XAUT-USDT")).thenReturn(Optional.empty());
        service = new HtxFundingArbitrageService(spotApi, swap, tradeProperties, contractProperties,
                properties, recorder);
    }

    @Test
    void lowFundingSkipsWithoutSpotOrder() {
        givenFunding("0.00001");
        givenDepths();

        HtxFundingArbitrageService.RunResult result = service.runOnce();

        assertEquals(HtxFundingArbitrageService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("funding below"));
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void positiveFundingSendsSpotEntryBeforeShort() {
        givenFunding("0.00005");
        givenDepths();
        givenBalances();
        givenContractInfo();
        givenLeverageOk();
        givenNoOpenContractOrders();
        when(recorder.createIntent(anyString(), eq("xautusdt"), eq("XAUT-USDT"),
                eq(new BigDecimal("500")), eq(new BigDecimal("0.115000")),
                eq(new BigDecimal("0.001")), eq(115L), eq(5), eq(new BigDecimal("0.00005")),
                eq(new BigDecimal("4330.000000000000000000")),
                eq(new BigDecimal("4330.000000000000000000")), anyString())).thenReturn(77L);
        when(spot.placeOrder(eq("12345"), eq("xautusdt"), eq("buy-limit"),
                eq("0.115000"), eq("4333.17"), anyString())).thenReturn(ok("88001"));

        HtxFundingArbitrageService.RunResult result = service.runOnce();

        assertEquals(HtxFundingArbitrageService.RunCode.SPOT_ENTRY_SENT, result.code());
        verify(spot).placeOrder(eq("12345"), eq("xautusdt"), eq("buy-limit"),
                eq("0.115000"), eq("4333.17"), anyString());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void filledSpotEntrySendsShortMarketOrder() {
        HtxFundingArbitrageCycleEntity cycle = cycle(HtxFundingArbitrageRecorder.SPOT_SENT);
        when(recorder.findActive("xautusdt", "XAUT-USDT")).thenReturn(Optional.of(cycle));
        HtxOrder spotOrder = new HtxOrder();
        spotOrder.setState("filled");
        spotOrder.setFilledAmount(new BigDecimal("0.115000"));
        spotOrder.setFilledCashAmount(new BigDecimal("497.95"));
        spotOrder.setFilledFees(new BigDecimal("0.000230"));
        when(spot.queryOrder(88001L)).thenReturn(ok(spotOrder));
        givenFunding("0.00005");
        givenContractInfo();
        givenLeverageOk();
        HtxSwapOrderResult accepted = new HtxSwapOrderResult();
        accepted.setOrderId(99001L);
        accepted.setClientOrderId(123456789L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted));

        HtxFundingArbitrageService.RunResult result = service.runOnce();

        assertEquals(HtxFundingArbitrageService.RunCode.SHORT_ENTRY_SENT, result.code());
        ArgumentCaptor<HtxSwapOrderRequest> captor = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(captor.capture());
        assertEquals("sell", captor.getValue().getSide());
        assertEquals("short", captor.getValue().getPositionSide());
        assertEquals(Boolean.FALSE, captor.getValue().getReduceOnly());
        assertEquals("market", captor.getValue().getType());
        assertEquals("114", captor.getValue().getVolume());
    }

    private void givenFunding(String value) {
        HtxSwapFundingRate funding = new HtxSwapFundingRate();
        funding.setContractCode("XAUT-USDT");
        funding.setFundingRate(new BigDecimal(value));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding));
    }

    private void givenDepths() {
        when(marketData.depth("xautusdt", 10)).thenReturn(ok(depth("4329", "4331")));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth("4329", "4331")));
    }

    private void givenBalances() {
        HtxBalance usdt = new HtxBalance();
        usdt.setCurrency("usdt");
        usdt.setType("trade");
        usdt.setBalance(new BigDecimal("1000"));
        HtxAccount spotAccount = new HtxAccount();
        spotAccount.setList(List.of(usdt));
        when(account.getAccountBalance("12345")).thenReturn(ok(spotAccount));

        HtxSwapAccount swapAccount = new HtxSwapAccount();
        swapAccount.setMarginAvailable(new BigDecimal("200"));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(swapAccount)));
    }

    private void givenContractInfo() {
        HtxSwapContractInfo info = new HtxSwapContractInfo();
        info.setContractCode("XAUT-USDT");
        info.setContractSize(new BigDecimal("0.001"));
        info.setPriceTick(new BigDecimal("0.01"));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(info)));
    }

    private void givenLeverageOk() {
        HtxSwapLeverageResult result = new HtxSwapLeverageResult();
        result.setContractCode("XAUT-USDT");
        result.setLeverage(5);
        when(swap.switchLeverage("XAUT-USDT", "short", 5)).thenReturn(ok(result));
    }

    private void givenNoOpenContractOrders() {
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of());
        openOrders.setTotalSize(0);
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
    }

    private HtxFundingArbitrageCycleEntity cycle(String status) {
        HtxFundingArbitrageCycleEntity cycle = new HtxFundingArbitrageCycleEntity();
        cycle.setId(77L);
        cycle.setCycleKey("CSFA-XAUTUSDT-1");
        cycle.setSymbol("xautusdt");
        cycle.setContractCode("XAUT-USDT");
        cycle.setStatus(status);
        cycle.setContractSize(new BigDecimal("0.001"));
        cycle.setContractVolume(115L);
        cycle.setSpotEntryOrderId(88001L);
        cycle.setSpotEntryClientOrderId("CSFA-XAUTUSDT-1-BE");
        cycle.setUpdatedAt(LocalDateTime.now());
        return cycle;
    }

    private HtxDepth depth(String bid, String ask) {
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), BigDecimal.ONE)));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), BigDecimal.ONE)));
        return depth;
    }

    private <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }
}
