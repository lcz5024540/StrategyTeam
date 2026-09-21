package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.CoinStrategyLedgerEventEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryLotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxSpotFillLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.CoinStrategyLedgerEventMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractLegMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractCycleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxDeltaHedgeLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryLotMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryRecycleSagaMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxLockedPairMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxSpotFillLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendSignalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HtxMapperIntegrationTest {

    @Autowired HtxLockedPairMapper pairMapper;
    @Autowired HtxContractCycleMapper contractCycleMapper;
    @Autowired HtxContractLegMapper contractLegMapper;
    @Autowired HtxSpotFillLedgerMapper spotFillLedgerMapper;
    @Autowired HtxInventoryLotMapper inventoryLotMapper;
    @Autowired HtxDeltaHedgeLedgerMapper deltaHedgeLedgerMapper;
    @Autowired HtxInventoryRecycleSagaMapper inventoryRecycleSagaMapper;
    @Autowired HtxTrendSignalMapper trendSignalMapper;
    @Autowired CoinStrategyLedgerEventMapper ledgerEventMapper;


    @Test void insert_and_read_strategy_ledger_event() {
        long nonce = System.nanoTime();
        CoinStrategyLedgerEventEntity event = new CoinStrategyLedgerEventEntity();
        event.setEventKey("it-ledger-" + nonce);
        event.setEventType(CoinStrategyLedgerRecorder.EVENT_OPEN_FILL);
        event.setAccountId(1L);
        event.setAccountLabel("primary");
        event.setExchange("HTX");
        event.setMarketType("USDT_SWAP");
        event.setStrategyFamily("TREND");
        event.setStrategyName("HTX_TREND_V1");
        event.setModelName("FIB_PULLBACK_CONTINUATION");
        event.setSymbol("btcusdt");
        event.setContractCode("BTC-USDT");
        event.setPositionId(nonce);
        event.setClientOrderId("it-order-" + nonce);
        event.setSide("LONG");
        event.setAmountUsdt(new BigDecimal("200.00"));
        event.setFeeUsdt(new BigDecimal("0.10"));
        event.setFundingFeeUsdt(BigDecimal.ZERO);
        event.setGrossPnlUsdt(new BigDecimal("1.20"));
        event.setNetPnlAfterFee(new BigDecimal("1.10"));
        event.setReason("Fibonacci pullback continuation");

        assertEquals(1, ledgerEventMapper.insert(event));

        CoinStrategyLedgerEventEntity stored = ledgerEventMapper.selectById(event.getId());
        assertEquals("HTX_TREND_V1", stored.getStrategyName());
        assertEquals("FIB_PULLBACK_CONTINUATION", stored.getModelName());
        assertEquals(0, new BigDecimal("1.10").compareTo(stored.getNetPnlAfterFee()));
    }

    @Test void trend_signal_snapshot_can_be_updated_by_contract_period_and_bar() {
        long barTime = 1789639200L + System.nanoTime() % 1000;
        HtxTrendSignalEntity first = trendSignal("BTC-USDT", barTime, "FLAT", "first flat");
        assertEquals(1, trendSignalMapper.insert(first));

        Long existingId = trendSignalMapper.findLatestSignalIdForBar("BTC-USDT", "15min", barTime);
        assertEquals(first.getId(), existingId);

        HtxTrendSignalEntity update = trendSignal("BTC-USDT", barTime, "LONG", "Fibonacci pullback continuation");
        update.setSpreadRate(new BigDecimal("0.0005"));
        assertEquals(1, trendSignalMapper.updateSignalSnapshotById(existingId, update));

        HtxTrendSignalEntity stored = trendSignalMapper.selectById(first.getId());
        assertEquals("LONG", stored.getSignal());
        assertEquals("Fibonacci pullback continuation", stored.getReason());
        assertEquals(0, new BigDecimal("0.0005").compareTo(stored.getSpreadRate()));
    }

    @Test void insert_and_read_pair() {
        HtxLockedPairEntity p = new HtxLockedPairEntity();
        p.setClientOrderId("it-" + System.nanoTime());
        p.setSymbol("btcusdt");
        p.setReqQuantity(new BigDecimal("0.01"));
        p.setBuyPrice(new BigDecimal("60000"));
        p.setSellPrice(new BigDecimal("60050"));
        p.setStatus(HtxStatus.NEW);
        p.setDryRun(false);
        assertEquals(1, pairMapper.insert(p));
        assertNotNull(p.getId());
        HtxLockedPairEntity got = pairMapper.selectById(p.getId());
        assertEquals("btcusdt", got.getSymbol());
        assertEquals(HtxStatus.NEW, got.getStatus());
    }

    @Test void insert_and_read_contract_cycle() {
        HtxContractCycleEntity cycle = new HtxContractCycleEntity();
        cycle.setClientOrderId(System.nanoTime());
        cycle.setContractCode("XAUT-USDT");
        cycle.setReqVolume(2L);
        cycle.setOpenPrice(new BigDecimal("3300.00"));
        cycle.setClosePrice(new BigDecimal("3310.00"));
        cycle.setLeverage(3);
        cycle.setStatus(HtxContractTradeRecorder.INTENT);

        assertEquals(1, contractCycleMapper.insert(cycle));
        HtxContractCycleEntity stored = contractCycleMapper.selectById(cycle.getId());
        assertEquals("XAUT-USDT", stored.getContractCode());
        assertEquals(3, stored.getLeverage());
    }

    @Test void insert_and_read_contract_leg() {
        HtxContractLegEntity leg = new HtxContractLegEntity();
        leg.setCycleId(System.nanoTime());
        leg.setLegType(HtxContractTradeRecorder.LONG_OPEN);
        leg.setClientOrderId(System.nanoTime());
        leg.setSide("buy");
        leg.setPositionSide("long");
        leg.setReduceOnly(false);
        leg.setReqVolume(2L);
        leg.setPrice(new BigDecimal("4333.33"));
        leg.setState(HtxContractTradeRecorder.LEG_INTENT);

        assertEquals(1, contractLegMapper.insert(leg));
        HtxContractLegEntity stored = contractLegMapper.selectById(leg.getId());
        assertEquals("long", stored.getPositionSide());
        assertFalse(stored.getReduceOnly());
    }

    @Test void insert_and_read_spot_fill_ledger_and_inventory_lot() {
        long nonce = System.nanoTime();
        HtxSpotFillLedgerEntity fill = new HtxSpotFillLedgerEntity();
        fill.setLedgerKey("it-fill-" + nonce);
        fill.setSymbol("xautusdt");
        fill.setPairId(nonce);
        fill.setHtxOrderId(nonce);
        fill.setClientOrderId("it-client-" + nonce);
        fill.setSide("BUY");
        fill.setOrderState("filled");
        fill.setDeltaBase(new BigDecimal("0.01"));
        fill.setDeltaQuote(new BigDecimal("-25.00"));
        fill.setFeeAmount(new BigDecimal("0.00002"));
        fill.setFeeCurrency("xaut");
        fill.setCumulativeFilledAmount(new BigDecimal("0.01"));
        fill.setCumulativeFilledCashAmount(new BigDecimal("25.00"));
        fill.setCumulativeFilledFees(new BigDecimal("0.00002"));
        fill.setSource(HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
        assertEquals(1, spotFillLedgerMapper.insert(fill));
        assertNotNull(fill.getId());

        HtxInventoryLotEntity lot = new HtxInventoryLotEntity();
        lot.setSymbol("xautusdt");
        lot.setSourcePairId(nonce);
        lot.setBuyOrderId(nonce);
        lot.setBuyFillLedgerId(fill.getId());
        lot.setAcquiredBase(new BigDecimal("0.00998"));
        lot.setRemainingBase(new BigDecimal("0.00998"));
        lot.setCostQuote(new BigDecimal("25.00"));
        lot.setFeeBase(new BigDecimal("0.00002"));
        lot.setStatus(HtxSpotLedgerRecorder.LOT_OPEN);
        assertEquals(1, inventoryLotMapper.insert(lot));

        assertEquals(0, new BigDecimal("0.00998")
                .compareTo(inventoryLotMapper.sumOpenRemainingBase("xautusdt")));
        assertEquals(fill.getId(), spotFillLedgerMapper
                .findLatestSnapshot("xautusdt", "BUY", nonce, "ignored").getId());
    }

    @Test void insert_and_read_delta_hedge_ledger_and_recycle_saga() {
        long nonce = System.nanoTime();
        HtxDeltaHedgeLedgerEntity hedge = new HtxDeltaHedgeLedgerEntity();
        hedge.setHedgeKey("it-hedge-" + nonce);
        hedge.setSymbol("xautusdt");
        hedge.setContractCode("XAUT-USDT");
        hedge.setEpoch(7L);
        hedge.setAction("OPEN_SHORT");
        hedge.setTargetShortBase(new BigDecimal("0.500"));
        hedge.setContractVolume(500L);
        hedge.setSide("sell");
        hedge.setPositionSide("short");
        hedge.setReduceOnly(false);
        hedge.setClientOrderId("CS-MM-HEDGE-XAUTUSDT-OPEN-" + nonce);
        hedge.setPlannerState("RUNNING");
        hedge.setStatus(HtxHedgeRecycleRecorder.STATUS_INTENT);
        assertEquals(1, deltaHedgeLedgerMapper.insert(hedge));
        assertEquals(hedge.getId(), deltaHedgeLedgerMapper
                .findByHedgeKey("it-hedge-" + nonce).getId());
        assertEquals(hedge.getId(), deltaHedgeLedgerMapper
                .findByStatus(HtxHedgeRecycleRecorder.STATUS_INTENT, 1).get(0).getId());
        assertEquals(1, deltaHedgeLedgerMapper.updateStatusIfCurrent(hedge.getId(),
                HtxHedgeRecycleRecorder.STATUS_INTENT, HtxHedgeRecycleRecorder.STATUS_SENDING,
                "claim"));
        assertEquals(0, deltaHedgeLedgerMapper.updateStatusIfCurrent(hedge.getId(),
                HtxHedgeRecycleRecorder.STATUS_INTENT, HtxHedgeRecycleRecorder.STATUS_SENT,
                "stale"));
        assertEquals(HtxHedgeRecycleRecorder.STATUS_SENDING,
                deltaHedgeLedgerMapper.findById(hedge.getId()).getStatus());
        assertEquals(1, deltaHedgeLedgerMapper.updateAcceptedIfCurrent(hedge.getId(),
                HtxHedgeRecycleRecorder.STATUS_SENDING, HtxHedgeRecycleRecorder.STATUS_SENT,
                900001L, "900002", "accepted"));
        assertEquals(1, deltaHedgeLedgerMapper.updateReconciliationIfCurrent(hedge.getId(),
                HtxHedgeRecycleRecorder.STATUS_SENT, HtxHedgeRecycleRecorder.STATUS_FILLED,
                900001L, "900002", "filled", new BigDecimal("500"),
                new BigDecimal("4311.25"), new BigDecimal("0.01"), "USDT",
                new BigDecimal("0.02"), "filled"));
        HtxDeltaHedgeLedgerEntity filledHedge = deltaHedgeLedgerMapper.findById(hedge.getId());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_FILLED, filledHedge.getStatus());
        assertEquals(900001L, filledHedge.getExchangeOrderId());
        assertEquals(0, new BigDecimal("500").compareTo(filledHedge.getFilledContracts()));
        assertEquals(1, deltaHedgeLedgerMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_FILLED));

        HtxInventoryRecycleSagaEntity saga = new HtxInventoryRecycleSagaEntity();
        saga.setSagaKey("it-recycle-" + nonce);
        saga.setSymbol("xautusdt");
        saga.setContractCode("XAUT-USDT");
        saga.setEpoch(8L);
        saga.setSpotFillLedgerId(nonce);
        saga.setSpotSoldBase(new BigDecimal("0.015"));
        saga.setAvailableShortBase(new BigDecimal("0.020"));
        saga.setCloseContracts(15L);
        saga.setCloseClientOrderId("CS-MM-RECYCLE-XAUTUSDT-CLOSE-" + nonce);
        saga.setPlannerState("RUNNING");
        saga.setStatus(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING);
        assertEquals(1, inventoryRecycleSagaMapper.insert(saga));
        assertEquals(saga.getId(), inventoryRecycleSagaMapper
                .findBySagaKey("it-recycle-" + nonce).getId());
        assertEquals(saga.getId(), inventoryRecycleSagaMapper
                .findByStatus(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING, 1).get(0).getId());
        assertEquals(1, inventoryRecycleSagaMapper.updateStatusIfCurrent(saga.getId(),
                HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING,
                HtxHedgeRecycleRecorder.STATUS_SENDING, "claim"));
        assertEquals(0, inventoryRecycleSagaMapper.updateStatusIfCurrent(saga.getId(),
                HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING,
                HtxHedgeRecycleRecorder.STATUS_SENT, "stale"));
        assertEquals(HtxHedgeRecycleRecorder.STATUS_SENDING,
                inventoryRecycleSagaMapper.findById(saga.getId()).getStatus());
        assertEquals(1, inventoryRecycleSagaMapper.updateAcceptedIfCurrent(saga.getId(),
                HtxHedgeRecycleRecorder.STATUS_SENDING, HtxHedgeRecycleRecorder.STATUS_SENT,
                900003L, "900004", "accepted"));
        assertEquals(1, inventoryRecycleSagaMapper.updateReconciliationIfCurrent(saga.getId(),
                HtxHedgeRecycleRecorder.STATUS_SENT, HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED,
                900003L, "900004", "partially_canceled", new BigDecimal("3"),
                new BigDecimal("4309.50"), new BigDecimal("0.01"), "USDT",
                BigDecimal.ZERO, "partial"));
        HtxInventoryRecycleSagaEntity degradedSaga = inventoryRecycleSagaMapper.findById(saga.getId());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED, degradedSaga.getStatus());
        assertEquals(900003L, degradedSaga.getCloseExchangeOrderId());
        assertEquals(0, new BigDecimal("3").compareTo(degradedSaga.getCloseFilledContracts()));
        assertEquals(1, inventoryRecycleSagaMapper.countByStatus(
                HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED));
    }

    @Test void sumMmRealizedNetQuoteSince_sums_done_cells_profit_minus_loss() {
        java.time.LocalDateTime start = java.time.LocalDate.now().atStartOfDay();
        BigDecimal before = pairMapper.sumMmRealizedNetQuoteSince(start);
        // 盈利 cell：买 0.001@100=0.1，卖 0.001@101=0.101 → net +0.001
        insertMmDone("mm-p-" + System.nanoTime(), "0.001", "100", "0.001", "101");
        // 亏损 cell：买 0.001@100=0.1，卖 0.001@98=0.098 → net -0.002
        insertMmDone("mm-l-" + System.nanoTime(), "0.001", "100", "0.001", "98");
        BigDecimal after = pairMapper.sumMmRealizedNetQuoteSince(start);
        // 增量 = +0.001 + (-0.002) = -0.001
        assertEquals(0, new BigDecimal("-0.001").compareTo(after.subtract(before)));
    }

    @Test void sumMmRealizedNetQuoteSince_excludes_dryrun_and_non_done() {
        java.time.LocalDateTime start = java.time.LocalDate.now().atStartOfDay();
        BigDecimal before = pairMapper.sumMmRealizedNetQuoteSince(start);
        // dry_run cell 不计入
        HtxLockedPairEntity dry = mmCell("mm-dry-" + System.nanoTime(),
                "0.001", "100", "0.001", "50");
        dry.setStatus(HtxStatus.MM_DONE);
        dry.setDryRun(true);
        pairMapper.insert(dry);
        // 未完成 cell（RESTING_SELL）不计入
        HtxLockedPairEntity resting = mmCell("mm-rest-" + System.nanoTime(),
                "0.001", "100", "0.001", "50");
        resting.setStatus(HtxStatus.MM_RESTING_SELL);
        resting.setDryRun(false);
        resting.setNetQuoteCost(new BigDecimal("-0.1"));
        pairMapper.insert(resting);
        // 卖单零成交取消后仍负担库存责任，不得把买入现金流当成已实现亏损。
        HtxLockedPairEntity retryable = mmCell("mm-retry-" + System.nanoTime(),
                "0.001", "100", "0", "101");
        retryable.setStatus(HtxStatus.MM_SELL_RETRYABLE);
        retryable.setDryRun(false);
        retryable.setNetQuoteCost(new BigDecimal("-0.1"));
        pairMapper.insert(retryable);
        BigDecimal after = pairMapper.sumMmRealizedNetQuoteSince(start);
        assertEquals(0, before.compareTo(after));
    }

    @Test void sumMmRiskQuoteNotionalSince_excludes_cancelled_buy_churn_but_counts_risk_states() {
        java.time.LocalDateTime start = java.time.LocalDate.now().atStartOfDay();
        BigDecimal before = pairMapper.sumMmRiskQuoteNotionalSince(start);

        insertMmRiskCell("mm-risk-buy-" + System.nanoTime(),
                HtxStatus.MM_RESTING_BUY, "0.010", null, "1000");
        insertMmRiskCell("mm-risk-sell-" + System.nanoTime(),
                HtxStatus.MM_RESTING_SELL, "0.010", "0.008", "1000");
        insertMmRiskCell("mm-risk-retry-" + System.nanoTime(),
                HtxStatus.MM_SELL_RETRYABLE, "0.010", "0.007", "1000");
        insertMmRiskCell("mm-risk-done-" + System.nanoTime(),
                HtxStatus.MM_DONE, "0.010", "0.006", "1000");
        insertMmRiskCell("mm-risk-reconcile-" + System.nanoTime(),
                HtxStatus.MM_RECONCILE_REQUIRED, "0.010", "0.005", "1000");
        insertMmRiskCell("mm-risk-dust-" + System.nanoTime(),
                HtxStatus.MM_DUST, "0.010", "0.004", "1000");

        // 下移/重挂时产生的大量零成交撤单不应继续消耗 daily-cap。
        insertMmRiskCell("mm-risk-cancelled-" + System.nanoTime(),
                HtxStatus.MM_CANCELLED_BUY, "99.000", null, "1000");

        HtxLockedPairEntity dry = mmCell("mm-risk-dry-" + System.nanoTime(),
                "88.000", "1000", "0", "1001");
        dry.setStatus(HtxStatus.MM_RESTING_BUY);
        dry.setDryRun(true);
        pairMapper.insert(dry);

        BigDecimal after = pairMapper.sumMmRiskQuoteNotionalSince(start);
        assertEquals(0, new BigDecimal("40.000").compareTo(after.subtract(before)));
    }

    @Test void countRecentZeroFillCancelledMmBuy_matches_nearby_price_and_excludes_filled_cancel() {
        java.time.LocalDateTime since = java.time.LocalDate.now().atStartOfDay();
        Long before = pairMapper.countRecentZeroFillCancelledMmBuy(
                "xautusdt", new BigDecimal("1000.5000"), new BigDecimal("0.001"), since);
        insertMmRiskCell("mm-cooldown-zero-" + System.nanoTime(),
                HtxStatus.MM_CANCELLED_BUY, "0.010", "0", "1000.0000");
        insertMmRiskCell("mm-cooldown-filled-" + System.nanoTime(),
                HtxStatus.MM_CANCELLED_BUY, "0.010", "0.001", "1000.0000");
        insertMmRiskCell("mm-cooldown-far-" + System.nanoTime(),
                HtxStatus.MM_CANCELLED_BUY, "0.010", "0", "1100.0000");

        Long matched = pairMapper.countRecentZeroFillCancelledMmBuy(
                "xautusdt", new BigDecimal("1000.5000"), new BigDecimal("0.001"), since);
        Long missed = pairMapper.countRecentZeroFillCancelledMmBuy(
                "xautusdt", new BigDecimal("998.0000"), new BigDecimal("0.001"), since);

        assertEquals(1L, matched - before);
        assertEquals(0L, missed);
    }

    private void insertMmDone(String cid, String buyQty, String buyPrice,
                              String sellQty, String sellPrice) {
        HtxLockedPairEntity e = mmCell(cid, buyQty, buyPrice, sellQty, sellPrice);
        e.setStatus(HtxStatus.MM_DONE);
        e.setDryRun(false);
        e.setNetQuoteCost(new BigDecimal(sellQty).multiply(new BigDecimal(sellPrice))
                .subtract(new BigDecimal(buyQty).multiply(new BigDecimal(buyPrice))));
        pairMapper.insert(e);
    }

    private HtxLockedPairEntity mmCell(String cid, String buyQty, String buyPrice,
                                       String sellQty, String sellPrice) {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setClientOrderId(cid);
        e.setSymbol("xautusdt");
        e.setReqQuantity(new BigDecimal(buyQty));
        e.setBuyFilledAmount(new BigDecimal(buyQty));
        e.setBuyPrice(new BigDecimal(buyPrice));
        e.setSellFilledAmount(new BigDecimal(sellQty));
        e.setSellPrice(new BigDecimal(sellPrice));
        e.setStrategy(HtxStatus.STRATEGY_MARKET_MAKER);
        return e;
    }

    private void insertMmRiskCell(String cid, String status, String reqQty,
                                  String buyFilledQty, String buyPrice) {
        HtxLockedPairEntity e = mmCell(cid, reqQty, buyPrice, "0", "1001");
        e.setStatus(status);
        e.setDryRun(false);
        e.setBuyFilledAmount(buyFilledQty == null ? null : new BigDecimal(buyFilledQty));
        pairMapper.insert(e);
    }

    private static HtxTrendSignalEntity trendSignal(String contractCode, long barTime, String signalValue, String reason) {
        HtxTrendSignalEntity signal = new HtxTrendSignalEntity();
        signal.setContractCode(contractCode);
        signal.setPeriod("15min");
        signal.setBarTime(barTime);
        signal.setSignal(signalValue);
        signal.setClosePrice(new BigDecimal("60000"));
        signal.setEmaFast(new BigDecimal("60100"));
        signal.setEmaSlow(new BigDecimal("59900"));
        signal.setBreakoutHigh(new BigDecimal("60200"));
        signal.setBreakoutLow(new BigDecimal("59000"));
        signal.setAtr(new BigDecimal("100"));
        signal.setAtrPct(new BigDecimal("0.0017"));
        signal.setAdx(new BigDecimal("30"));
        signal.setFundingRate(new BigDecimal("0.0001"));
        signal.setReason(reason);
        return signal;
    }
}
