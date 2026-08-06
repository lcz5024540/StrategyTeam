package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtxOrderParseTest {

    @Test
    void parsesHtxFieldAmountWireNames() {
        HtxOrder order = JSON.parseObject("""
                {
                  "id": 1661737376941281,
                  "client-order-id": "mm-btcusdt-1-BUY",
                  "account-id": 73741966,
                  "symbol": "btcusdt",
                  "state": "filled",
                  "field-amount": "0.0003",
                  "field-cash-amount": "19.264815",
                  "field-fees": "0.0000006",
                  "created-at": 1786022624708
                }
                """, HtxOrder.class);

        assertEquals(1661737376941281L, order.getOrderId());
        assertEquals("mm-btcusdt-1-BUY", order.getClientOrderId());
        assertEquals("73741966", order.getAccountId());
        assertEquals(new BigDecimal("0.0003"), order.getFilledAmount());
        assertEquals(new BigDecimal("19.264815"), order.getFilledCashAmount());
        assertEquals(new BigDecimal("0.0000006"), order.getFilledFees());
        assertEquals(1786022624708L, order.getCreatedAt());
    }

    @Test
    void keepsCompatibilityWithFilledAmountAliases() {
        HtxOrder order = JSON.parseObject("""
                {
                  "filled-amount": "0.0105",
                  "filled-cash-amount": "19.908945",
                  "filled-fees": "0.000021"
                }
                """, HtxOrder.class);

        assertEquals(new BigDecimal("0.0105"), order.getFilledAmount());
        assertEquals(new BigDecimal("19.908945"), order.getFilledCashAmount());
        assertEquals(new BigDecimal("0.000021"), order.getFilledFees());
    }
}
