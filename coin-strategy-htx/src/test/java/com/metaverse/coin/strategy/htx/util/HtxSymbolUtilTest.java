package com.metaverse.coin.strategy.htx.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HtxSymbolUtilTest {
    @Test void splits_usdt_pair() {
        assertEquals("btc", HtxSymbolUtil.base("btcusdt"));
        assertEquals("usdt", HtxSymbolUtil.quote("btcusdt"));
    }
    @Test void splits_btc_quoted_pair() {
        assertEquals("eth", HtxSymbolUtil.base("ethbtc"));
        assertEquals("btc", HtxSymbolUtil.quote("ethbtc"));
    }
    @Test void unknown_quote_returns_symbol_as_base_and_empty_quote() {
        assertEquals("weirdcoin", HtxSymbolUtil.base("weirdcoin"));
        assertEquals("", HtxSymbolUtil.quote("weirdcoin"));
    }
    @Test void null_safe() {
        assertEquals("", HtxSymbolUtil.base(null));
        assertEquals("", HtxSymbolUtil.quote(null));
    }
}
