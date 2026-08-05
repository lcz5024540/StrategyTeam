package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class HtxFeeMathTest {
    @Test void subtracts_base_fee_from_filled() {
        // 买到 1.0 BTC，手续费 0.002 BTC → 可卖 0.998
        assertEquals(0, new BigDecimal("0.998")
                .compareTo(HtxFeeMath.sellableBase(new BigDecimal("1.0"), new BigDecimal("0.002"))));
    }
    @Test void null_fee_treated_as_zero() {
        assertEquals(0, new BigDecimal("1.0")
                .compareTo(HtxFeeMath.sellableBase(new BigDecimal("1.0"), null)));
    }
    @Test void never_returns_negative() {
        // 手续费异常大于成交（防御）→ 归零，不产生负卖量
        assertEquals(0, BigDecimal.ZERO
                .compareTo(HtxFeeMath.sellableBase(new BigDecimal("0.001"), new BigDecimal("0.005"))));
    }
    @Test void null_filled_is_zero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(HtxFeeMath.sellableBase(null, new BigDecimal("0.1"))));
    }
}
