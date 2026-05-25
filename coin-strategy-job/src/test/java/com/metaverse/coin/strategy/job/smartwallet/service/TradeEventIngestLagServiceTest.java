package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.service.TradeEventIngestLagService.IngestLagReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TradeEventIngestLagService} 事件摄取时延监控单测（T-B7）。
 *
 * <p>验证：摄取时延 P90 越过阈值时外发 {@link AlertType#HEALTH_INGEST_LAG_HIGH}，
 * P90 在阈值内或无样本时不外发。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeEventIngestLagServiceTest {

    @Mock private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock private SmartWalletAuditAlertSink auditAlertSink;

    private SmartWalletProperties properties;
    private TradeEventIngestLagService service;

    @BeforeEach
    void setUp() {
        // 默认配置：windowHours=4, warnP90Seconds=600, maxSamples=20000
        properties = new SmartWalletProperties();
        properties.setEnabled(true);
        service = new TradeEventIngestLagService(properties, tradeEventMapper, auditAlertSink);
    }

    @Test
    void p90WithinThreshold_doesNotPublish() {
        when(tradeEventMapper.selectIngestLagSeconds(any(Date.class), anyInt()))
                .thenReturn(constantLags(120L, 50));

        IngestLagReport report = service.runScheduled();

        assertEquals(50, report.getSampleCount());
        assertEquals(120L, report.getP90Seconds());
        assertFalse(report.isExceeded());
        verify(auditAlertSink, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void p90ExceedsThreshold_publishesAlert() {
        when(tradeEventMapper.selectIngestLagSeconds(any(Date.class), anyInt()))
                .thenReturn(constantLags(900L, 50));

        IngestLagReport report = service.runScheduled();

        assertEquals(900L, report.getP90Seconds());
        assertTrue(report.isExceeded());
        verify(auditAlertSink).publish(eq(AlertType.HEALTH_INGEST_LAG_HIGH), any(), any(), any(), any());
    }

    @Test
    void noSamples_doesNotPublish() {
        when(tradeEventMapper.selectIngestLagSeconds(any(Date.class), anyInt()))
                .thenReturn(Collections.emptyList());

        IngestLagReport report = service.runScheduled();

        assertEquals(0, report.getSampleCount());
        assertFalse(report.isExceeded());
        verify(auditAlertSink, never()).publish(any(), any(), any(), any(), any());
    }

    private static List<Long> constantLags(long value, int count) {
        List<Long> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(value);
        }
        return list;
    }
}
