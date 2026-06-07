package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalHealthSummaryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StrategyHealthMonitorJob} 「条件恢复 → 自动 resolved」闭环单测（T-C2）。
 *
 * <p>验证修复前「告警只增不减、无任何把 strategy_alert.resolved 置 1 的逻辑」的问题已闭环：
 * 触发条件恢复时调用 {@link StrategyAlertService#autoResolveByStrategy}，触发条件仍满足时不恢复。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyHealthMonitorJobTest {

    private static final String STRATEGY_ID = SmartWalletAuditAlertSink.STRATEGY_ID;

    @Mock private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock private SmartWalletFollowTaskService followTaskService;
    @Mock private SmartWalletAccountingService accountingService;
    @Mock private SmartWalletAuditAlertSink alertSink;
    @Mock private StrategyAlertService strategyAlertService;
    @Mock private MarketSnapshotCollectorService marketSnapshotCollectorService;

    private SmartWalletProperties properties;
    private StrategyHealthMonitorJob job;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.setEnabled(true);
        properties.getSignalRisk().setMaxTotalOpenCostUsdc(new BigDecimal("500"));
        job = new StrategyHealthMonitorJob(properties, followTaskMapper, followTaskService,
                accountingService, alertSink, strategyAlertService, marketSnapshotCollectorService);
    }

    @Test
    void healthyState_autoResolvesAllHealthAlerts() {
        // 资金充足（available 200 / budget 500 → 剩余 40%），且各时间窗均有开仓
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("200"));
        when(followTaskMapper.selectCount(any())).thenReturn(5L);

        job.checkPositionHealth();

        verify(strategyAlertService).autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_CAPITAL_DEPLETED);
        verify(strategyAlertService).autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_CAPITAL_LOW);
        verify(strategyAlertService).autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_NO_OPEN_4H);
        verify(strategyAlertService).autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_NO_OPEN_24H);
        // 健康状态不应外发任何告警
        verify(alertSink, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void capitalDepleted_publishesAlertAndDoesNotResolveDepleted() {
        // 可用资金耗尽：触发条件仍满足，不应自动恢复 DEPLETED
        when(accountingService.availableCapitalUsdc()).thenReturn(BigDecimal.ZERO);
        when(followTaskMapper.selectCount(any())).thenReturn(5L);

        job.checkPositionHealth();

        verify(alertSink).publish(eq(AlertType.HEALTH_CAPITAL_DEPLETED), any(), any(), any(), any());
        verify(strategyAlertService, never())
                .autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_CAPITAL_DEPLETED);
    }

    @Test
    void zeroOpen24h_publishesAlertAndKeeps24hUnresolved() {
        // 24h 零开仓：触发条件仍满足，不应自动恢复 NO_OPEN_24H
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("200"));
        when(followTaskMapper.selectCount(any())).thenReturn(0L);
        when(followTaskService.getSignalHealthSummary(anyInt()))
                .thenReturn(mock(SignalHealthSummaryView.class));

        job.checkPositionHealth();

        verify(alertSink).publish(eq(AlertType.HEALTH_NO_OPEN_24H), any(), any(), any(), any());
        verify(strategyAlertService, never())
                .autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_NO_OPEN_24H);
        // 资金侧仍正常 → DEPLETED 应被自动恢复
        verify(strategyAlertService).autoResolveByStrategy(STRATEGY_ID, AlertType.HEALTH_CAPITAL_DEPLETED);
    }
}
