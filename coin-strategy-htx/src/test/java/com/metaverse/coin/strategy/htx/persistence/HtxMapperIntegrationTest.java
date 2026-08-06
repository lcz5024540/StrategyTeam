package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
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
}
