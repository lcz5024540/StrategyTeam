package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MarketMakerCellTest {

    @Test void fromEntity_maps_all_fields() {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setId(11L);
        e.setClientOrderId("mm-btcusdt-123");
        e.setSymbol("btcusdt");
        e.setBuyPrice(new BigDecimal("59700.00"));
        e.setSellPrice(new BigDecimal("60300.00"));
        e.setReqQuantity(new BigDecimal("0.0003"));
        e.setStatus(HtxStatus.MM_RESTING_BUY);
        e.setBuyOrderId(555L);
        e.setSellOrderId(null);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);

        MarketMakerCell c = MarketMakerCell.fromEntity(e);
        assertEquals(11L, c.getPairId());
        assertEquals("mm-btcusdt-123", c.getClientId());
        assertEquals("btcusdt", c.getSymbol());
        assertEquals(0, new BigDecimal("59700.00").compareTo(c.getBuyPrice()));
        assertEquals(0, new BigDecimal("60300.00").compareTo(c.getSellPrice()));
        assertEquals(0, new BigDecimal("0.0003").compareTo(c.getQty()));
        assertEquals(HtxStatus.MM_RESTING_BUY, c.getState());
        assertEquals(555L, c.getBuyOrderId());
        assertNull(c.getSellOrderId());
        assertEquals(now, c.getCreatedAt());
    }
}
