package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryLotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxSpotFillLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractLegMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractCycleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxDeltaHedgeLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryLotMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryRecycleSagaMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxLockedPairMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxSpotFillLedgerMapper;
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
}
