package com.metaverse.coin.strategy.job.smartwallet.trace;

import com.metaverse.coin.strategy.common.service.trace.TraceIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdsTest {

    @Test
    void fromTradeEventId_zeroPaddedToFixedWidth() {
        assertEquals("tev_000000000000", TraceIds.fromTradeEventId(0L));
        assertEquals("tev_000000000001", TraceIds.fromTradeEventId(1L));
        assertEquals("tev_000000123456", TraceIds.fromTradeEventId(123_456L));
        assertEquals("tev_999999999999", TraceIds.fromTradeEventId(999_999_999_999L));
    }

    @Test
    void fromTradeEventId_rejectsNullOrNegative() {
        assertThrows(IllegalArgumentException.class, () -> TraceIds.fromTradeEventId(null));
        assertThrows(IllegalArgumentException.class, () -> TraceIds.fromTradeEventId(-1L));
    }

    @Test
    void extractTradeEventId_roundTrips() {
        for (long id : new long[] {0L, 1L, 42L, 999_999L, 123_456_789_012L}) {
            String trace = TraceIds.fromTradeEventId(id);
            assertEquals(id, TraceIds.extractTradeEventId(trace));
            assertTrue(TraceIds.isValid(trace));
        }
    }

    @Test
    void extractTradeEventId_rejectsMalformed() {
        assertNull(TraceIds.extractTradeEventId(null));
        assertNull(TraceIds.extractTradeEventId(""));
        assertNull(TraceIds.extractTradeEventId("tev_"));              // 太短
        assertNull(TraceIds.extractTradeEventId("tev_abc"));            // 字母
        assertNull(TraceIds.extractTradeEventId("wth_000000000001"));   // 前缀不符
        assertNull(TraceIds.extractTradeEventId("tev_00000000000")); // 长度差一位
        assertFalse(TraceIds.isValid("not-a-trace-id"));
    }

    @Test
    void fixedLengthIs16() {
        assertEquals(16, TraceIds.TRADE_EVENT_TRACE_LENGTH);
        assertEquals(16, TraceIds.fromTradeEventId(7L).length());
    }
}
