package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEventEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionEventMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HtxTrendPaperExecutionLifecycleTest {
    @Test
    void recordsPartialFillAndCancellationAsAppendOnlyStateTransitions() {
        HtxTrendPaperExecutionEventMapper events = mock(HtxTrendPaperExecutionEventMapper.class);
        Map<String, HtxTrendPaperExecutionEventEntity> rows = new LinkedHashMap<>();
        when(events.findByEventKey(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(events.findLatestByExecutionKey(anyString())).thenAnswer(call -> rows.values().stream()
                .filter(row -> call.getArgument(0).equals(row.getExecutionKey()))
                .max(Comparator.comparing(HtxTrendPaperExecutionEventEntity::getId)).orElse(null));
        when(events.insert(any())).thenAnswer(call -> {
            HtxTrendPaperExecutionEventEntity row = call.getArgument(0);
            row.setId((long) rows.size() + 1L);
            rows.put(row.getEventKey(), row);
            return 1;
        });
        HtxTrendPaperExecutionService service = new HtxTrendPaperExecutionService(mock(HtxTrendCombinationService.class),
                mock(HtxTrendPaperExecutionMapper.class), events);

        assertTrue(service.reportEvent(event("submit", HtxTrendOrderStateMachine.Event.SUBMIT, 0L, null)).accepted());
        assertTrue(service.reportEvent(event("accept", HtxTrendOrderStateMachine.Event.ACCEPT, 0L, null)).accepted());
        assertTrue(service.reportEvent(event("partial", HtxTrendOrderStateMachine.Event.FILL_UPDATE, 4L, "100")).accepted());
        assertTrue(service.reportEvent(event("cancel", HtxTrendOrderStateMachine.Event.REQUEST_CANCEL, 4L, null)).accepted());
        var cancelled = service.reportEvent(event("cancelled", HtxTrendOrderStateMachine.Event.CANCEL_CONFIRMED, 4L, null));
        assertTrue(cancelled.accepted());
        assertEquals(HtxTrendOrderStateMachine.State.CANCELLED, cancelled.state());
        assertFalse(service.reportEvent(event("late-fill", HtxTrendOrderStateMachine.Event.FILL_UPDATE, 10L, "101")).accepted());
        assertEquals(5, rows.size());
    }

    private static HtxTrendPaperExecutionService.LifecycleEvent event(String suffix, HtxTrendOrderStateMachine.Event type,
                                                                        long filled, String price) {
        return new HtxTrendPaperExecutionService.LifecycleEvent("event:" + suffix, "execution:1", "run:1", type,
                10L, filled, price == null ? null : new BigDecimal(price), "test", null);
    }
}
