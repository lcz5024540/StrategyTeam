package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtxTrendCombinationGateReasonTest {
    @Test
    void exposesStableEnglishCodeAndChineseDescription() {
        assertEquals("MISSING_BOX_BREAKOUT", HtxTrendCombinationGateReason.MISSING_BOX_BREAKOUT.code());
        assertEquals("未形成箱体突破", HtxTrendCombinationGateReason.MISSING_BOX_BREAKOUT.zhDescription());
    }
}
