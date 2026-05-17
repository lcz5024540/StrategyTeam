package com.metaverse.coin.strategy.job.smartwallet.trace;

import com.metaverse.coin.strategy.common.service.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void scope_putsAndRestoresMdc() {
        assertNull(MDC.get(TraceContext.MDC_KEY));
        try (TraceContext.Scope ignored = TraceContext.scope("tev_000000000042")) {
            assertEquals("tev_000000000042", MDC.get(TraceContext.MDC_KEY));
            assertEquals("tev_000000000042", TraceContext.current());
        }
        // 出 scope 后 MDC 清空
        assertNull(MDC.get(TraceContext.MDC_KEY));
        assertNull(TraceContext.current());
    }

    @Test
    void scope_nullOrBlank_isNoop() {
        assertNull(MDC.get(TraceContext.MDC_KEY));
        try (TraceContext.Scope ignored = TraceContext.scope(null)) {
            assertNull(MDC.get(TraceContext.MDC_KEY));
        }
        try (TraceContext.Scope ignored = TraceContext.scope("   ")) {
            assertNull(MDC.get(TraceContext.MDC_KEY));
        }
        assertNull(MDC.get(TraceContext.MDC_KEY));
    }

    @Test
    void scope_nestingRestoresOuterValueOnInnerClose() {
        try (TraceContext.Scope outer = TraceContext.scope("tev_aaa")) {
            assertEquals("tev_aaa", MDC.get(TraceContext.MDC_KEY));
            try (TraceContext.Scope inner = TraceContext.scope("tev_bbb")) {
                assertEquals("tev_bbb", MDC.get(TraceContext.MDC_KEY));
            }
            // 内层关闭后, MDC 恢复成外层值
            assertEquals("tev_aaa", MDC.get(TraceContext.MDC_KEY));
        }
        assertNull(MDC.get(TraceContext.MDC_KEY));
    }
}
