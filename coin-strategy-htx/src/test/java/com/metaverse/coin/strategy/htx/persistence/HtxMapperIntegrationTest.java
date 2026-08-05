package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxLockedPairMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HtxMapperIntegrationTest {

    @Autowired HtxLockedPairMapper pairMapper;

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
}
