package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.*;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 网格化刷量（grid volume churn）服务层测试。见 spec 2026-08-18-htx-spot-grid-volume-churn-design.md §6。
 * 锁定：等比全梯补齐（D1/D2）、总敞口+地板双闸（D3）、当日净亏损/连亏熔断（D4）。
 * 复用 MarketMakerServiceOpenTest 的 Mockito 装置风格；legacy 卖价路径（sellFollow=false）。
 */
class MarketMakerServiceGridTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxMarketDataService md;
    HtxAccountService account;
    HtxDepthGuard depthGuard;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        md = mock(HtxMarketDataService.class);
        account = mock(HtxAccountService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(md);
        when(api.getAccountService()).thenReturn(account);
        depthGuard = mock(HtxDepthGuard.class);
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        tradeProps.getTrade().setEnabled(true);
        mmProps = new HtxMarketMakerProperties();
        mmProps.setSymbols(List.of("btcusdt", "xautusdt"));
        mmProps.setEnabled(true);
        mmProps.setSellFollowEnabled(false);
        mmProps.setGridModeEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.003"));
        mmProps.setGridLevels(5);
        mmProps.setMaxConcurrentCellsPerSymbol(3);
        mmProps.setPerCellQuoteNotional(new BigDecimal("20"));
        mmProps.setMaxTotalExposureQuoteNotional(new BigDecimal("100"));
        mmProps.setDailyNetLossBudget(new BigDecimal("5"));
        mmProps.setMaxConsecutiveLossCycles(3);
        recorder = mock(HtxTradeRecorder.class);
        svc = new MarketMakerService(api, depthGuard, tradeProps, mmProps, recorder);
        // 默认：K 未满、无已挂 cell、当日名义 0、当日净额 0、余额充足、深度闸放行
        when(recorder.countOpenMmCells(anyString())).thenReturn(0);
        when(recorder.findOpenMmCells(anyString())).thenReturn(List.of());
        when(recorder.sumTodayLiveQuoteNotional()).thenReturn(BigDecimal.ZERO);
        when(recorder.sumTodayMmRealizedNetQuote()).thenReturn(BigDecimal.ZERO);
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("100000"));
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true, new BigDecimal("999"), new BigDecimal("999"), BigDecimal.ONE));
        when(recorder.createMmCell(anyString(), any(), any(), any(), anyString())).thenReturn(100L);
        when(spot.placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("555"));
    }

    private HtxDepth depth(String bid, String ask) {
        HtxDepth d = new HtxDepth();
        d.setSymbol("btcusdt");
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), new BigDecimal("10"))));
        return d;
    }

    private HtxRestResponse<HtxDepth> depthOk(HtxDepth d) {
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    private HtxRestResponse<String> placeOk(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(id);
        return r;
    }

    private HtxRestResponse<HtxAccount> balanceOk(String availableUsdt) {
        HtxBalance balance = new HtxBalance();
        balance.setCurrency("usdt");
        balance.setType("trade");
        balance.setBalance(new BigDecimal(availableUsdt));
        HtxAccount data = new HtxAccount();
        data.setList(List.of(balance));
        HtxRestResponse<HtxAccount> response = new HtxRestResponse<>();
        response.setStatus("ok");
        response.setData(data);
        return response;
    }

    private HtxLockedPairEntity restingBuy(String buyPrice, String qty) {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setBuyPrice(new BigDecimal(buyPrice));
        e.setReqQuantity(new BigDecimal(qty));
        e.setStatus(HtxStatus.MM_RESTING_BUY);
        return e;
    }

    @Test void empty_inventory_fills_ladder_up_to_concurrency_budget() {
        // bestBid=100, step=0.003, levels=5 → [100.00, 99.70, 99.40, 99.10, 98.81]
        // maxConcurrent=3, open=0 → budget=3 → 只补最上三档
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.70"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.40"), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), eq("98.81"), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), eq("98.51"), anyString());
        verify(spot, times(3)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
    }

    @Test void partial_ladder_only_opens_missing_levels() {
        // 已挂两档（100.00 / 99.70），open=2 → budget=1 → 仅补下一缺口 99.40
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.countOpenMmCells("btcusdt")).thenReturn(2);
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(
                restingBuy("100.00", "0.2000"), restingBuy("99.70", "0.2006")));

        svc.fillGridLadder("btcusdt");

        // budget=1：只开一档，且是首个与已挂档拉开 min-gap 的价位（99.40）
        verify(spot, times(1)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.40"), anyString());
    }

    @Test void circuit_breaker_daily_net_loss_blocks_all_opens() {
        // 当日净亏损 -10 > 预算 5 → 熔断，全 tick 零开
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.sumTodayMmRealizedNetQuote()).thenReturn(new BigDecimal("-10"));

        svc.fillGridLadder("btcusdt");

        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void circuit_breaker_daily_net_loss_read_failure_is_fail_closed() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.sumTodayMmRealizedNetQuote()).thenThrow(new HtxPersistenceException("db down", null));

        svc.fillGridLadder("btcusdt");

        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void total_exposure_cap_stops_deeper_levels() {
        // maxTotalExposure=45, perCell=20：第1格(0→20)、第2格(20→40) 过；第3格(40→60>45) 停
        mmProps.setMaxTotalExposureQuoteNotional(new BigDecimal("45"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        verify(spot, times(2)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.70"), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), eq("99.40"), anyString());
    }

    @Test void absolute_price_floor_skips_below_floor_levels() {
        // 地板 99.5：ladder [100.00,99.70,99.40,...] 中 <99.5 的全部不开，前两档合格
        mmProps.setGridFloorPrice(new BigDecimal("99.5"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        verify(spot, times(2)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.70"), anyString());
    }
}
