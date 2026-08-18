package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractLegMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractCycleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxLockedPairMapper;
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
        pairMapper.insert(resting);
        BigDecimal after = pairMapper.sumMmRealizedNetQuoteSince(start);
        assertEquals(0, before.compareTo(after));
    }

    private void insertMmDone(String cid, String buyQty, String buyPrice,
                              String sellQty, String sellPrice) {
        HtxLockedPairEntity e = mmCell(cid, buyQty, buyPrice, sellQty, sellPrice);
        e.setStatus(HtxStatus.MM_DONE);
        e.setDryRun(false);
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
