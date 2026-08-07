package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 卖腿 linger 超时 → 保本(容忍微亏) taker 退出（MM_LINGER_EXIT）分支单测。
 * 覆盖：达标退出、未超时不退、超时不达标兜底、止损优先、开关关闭、撤单竞态全成交、updatedAt=null。
 * 装配风格对齐 {@code MarketMakerServiceRunTest}，入口走 public {@code advanceCell(cell)}。
 */
class MarketMakerServiceLingerExitTest {

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
        mmProps = new HtxMarketMakerProperties();
        mmProps.setSymbols(List.of("btcusdt"));
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellLingerExitEnabled(true);
        mmProps.setSellLingerTimeoutMs(120_000L);
        mmProps.setSellLingerLossTolerancePct(new BigDecimal("0.002"));
        // stopLossPct 默认 0.005 → 止损线 60000·0.995 = 59700
        recorder = mock(HtxTradeRecorder.class);
        // sell-market taker 退出下单默认成功（price 参数为 null）
        when(spot.placeOrder(any(), any(), eq("sell-market"), any(), any(), any()))
                .thenReturn(placeOk("999"));
        svc = new MarketMakerService(api, depthGuard, tradeProps, mmProps, recorder);
    }

    // ---- helpers ----

    /** bestBid 变化，bestAsk 固定 60000（=buyPrice/sellPrice，desiredSell 不触发跟价重挂）。 */
    private HtxRestResponse<HtxDepth> depth(String bid) {
        HtxDepth d = new HtxDepth();
        d.setSymbol("btcusdt");
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal("60000"), new BigDecimal("10"))));
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    private HtxRestResponse<HtxOrder> order(String state, String filled, String amount) {
        HtxOrder o = new HtxOrder();
        o.setState(state);
        o.setFilledAmount(new BigDecimal(filled));
        o.setAmount(new BigDecimal(amount));
        HtxRestResponse<HtxOrder> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(o);
        return r;
    }

    private HtxRestResponse<String> placeOk(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(id);
        return r;
    }

    private MarketMakerCell restingSellCell(LocalDateTime updatedAt) {
        return MarketMakerCell.builder()
                .pairId(200L)
                .clientId("mm-btcusdt-linger")
                .symbol("btcusdt")
                .buyPrice(new BigDecimal("60000.00"))
                .sellPrice(new BigDecimal("60000.00"))
                .qty(new BigDecimal("0.0003"))
                .state(HtxStatus.MM_RESTING_SELL)
                .sellOrderId(777L)
                .buyFilledAmount(new BigDecimal("0.0003"))
                .totalFeeBase(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .updatedAt(updatedAt)
                .build();
    }

    // ---- scenarios ----

    @Test void lingerExpired_and_takerAcceptable_flattensWithLingerExitCode() {
        // 超时(5min) + bestBid=60200 达标((1-0.002)²·60200≈59979 ≥ 59880) + 撤单复查为终态未成交
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("60200"));
        when(spot.queryOrder(777L))
                .thenReturn(order("submitted", "0", "0.0003"))   // advanceRestingSell 首查
                .thenReturn(order("canceled", "0", "0.0003"));   // takerFlatten cancelAndQuery 复查

        svc.advanceCell(restingSellCell(LocalDateTime.now().minusMinutes(5)));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-market"), anyString(), any(), anyString());
        verify(recorder).updatePairStatus(eq(200L), eq(HtxStatus.MM_DONE), eq("MM_LINGER_EXIT"), anyString());
    }

    @Test void lingerNotExpired_doesNotTakerExit() {
        // 30 秒前 < 2min 超时线 → 不退出
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("60200"));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0", "0.0003"));

        svc.advanceCell(restingSellCell(LocalDateTime.now().minusSeconds(30)));

        verify(spot, never()).placeOrder(any(), any(), eq("sell-market"), any(), any(), any());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_LINGER_EXIT"), any());
    }

    @Test void lingerExpired_butTakerNotAcceptable_fallsThroughNoTaker() {
        // 超时但 bestBid=59900 不达标((1-0.002)²·59900≈59661 < 59880) 且未破止损线(59700) → 不退出
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("59900"));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0", "0.0003"));

        svc.advanceCell(restingSellCell(LocalDateTime.now().minusMinutes(5)));

        verify(spot, never()).placeOrder(any(), any(), eq("sell-market"), any(), any(), any());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_LINGER_EXIT"), any());
    }

    @Test void stopLossTakesPriorityOverLinger() {
        // 超时 + bestBid=59600 < 止损线 59700 → 走止损 MM_STOP_LOSS，绝不写 MM_LINGER_EXIT
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("59600"));
        when(spot.queryOrder(777L))
                .thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));

        svc.advanceCell(restingSellCell(LocalDateTime.now().minusMinutes(5)));

        verify(recorder).updatePairStatus(eq(200L), eq(HtxStatus.MM_DONE), eq("MM_STOP_LOSS"), anyString());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_LINGER_EXIT"), any());
    }

    @Test void disabledFlag_neverLingerExits() {
        mmProps.setSellLingerExitEnabled(false);
        // 超时且达标，但功能关闭 → 不退出
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("60200"));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0", "0.0003"));

        svc.advanceCell(restingSellCell(LocalDateTime.now().minusMinutes(5)));

        verify(spot, never()).placeOrder(any(), any(), eq("sell-market"), any(), any(), any());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_LINGER_EXIT"), any());
    }

    @Test void cancelRace_fullyFilled_marksDoneNoTaker() {
        // 超时达标但撤单复查发现已全成交 → markMmDone，不下 taker
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("60200"));
        when(spot.queryOrder(777L))
                .thenReturn(order("submitted", "0", "0.0003"))       // 首查
                .thenReturn(order("canceled", "0.0003", "0.0003"));  // 复查已全成交(filled≥amount)

        svc.advanceCell(restingSellCell(LocalDateTime.now().minusMinutes(5)));

        verify(recorder).markMmDone(eq(200L), any());
        verify(spot, never()).placeOrder(any(), any(), eq("sell-market"), any(), any(), any());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_LINGER_EXIT"), any());
    }

    @Test void updatedAtNull_treatedAsNotExpired() {
        // updatedAt=null → isSellLingerExpired=false → 不退出
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depth("60200"));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0", "0.0003"));

        svc.advanceCell(restingSellCell(null));

        verify(spot, never()).placeOrder(any(), any(), eq("sell-market"), any(), any(), any());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_LINGER_EXIT"), any());
    }
}
