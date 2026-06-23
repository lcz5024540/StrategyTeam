package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.LeaderRollingAggregationDailyMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LeaderRollingAggregationDailyEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2-T2 灰度自动化 (第十九批 · 自动化追加):
 * {@link LeaderRollingMonitorService} 健康状态机 + upsert + 告警闭环单测.
 *
 * <p>覆盖 4 个 health 状态机分支:
 * <ul>
 *   <li>HEALTH_OK — coolingRatio < 0.40 && overallWin >= 0.48 (含样本不足的兜底 OK)</li>
 *   <li>HEALTH_WARN_HIGH_COOLING — coolingRatio >= 0.40 && overallWin >= 0.48</li>
 *   <li>HEALTH_WARN_LOW_WIN_RATE — coolingRatio < 0.40 && overallWin < 0.48</li>
 *   <li>HEALTH_WARN_BOTH — coolingRatio >= 0.40 && overallWin < 0.48</li>
 * </ul>
 * 以及 upsert 幂等 (selectOne→update vs insert)、告警 sink 默认开启时只在 != OK 触发.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderRollingMonitorServiceTest {

    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private LeaderRollingAggregationDailyMapper aggregationDailyMapper;
    @Mock
    private SmartWalletAuditAlertSink auditAlertSink;

    private SmartWalletProperties properties;
    private LeaderRollingMonitorService service;

    private final AtomicLong nextPositionId = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setLeaderRollingWindowSize(20);
        risk.setLeaderRollingMinSamples(8);
        risk.setLeaderRollingMinWinRate(new BigDecimal("0.45"));
        properties.setSignalRisk(risk);

        SmartWalletProperties.Audit audit = new SmartWalletProperties.Audit();
        audit.setAlertSinkEnabled(true);
        audit.setLeaderRollingAggregationEnabled(true);
        audit.setLeaderRollingAggregationCron("0 30 9 * * *");
        audit.setLeaderRollingAggregationZone("Asia/Shanghai");
        audit.setLeaderRollingAggregationLookbackDays(Collections.singletonList(7));
        audit.setLeaderRollingAggregationWarnCoolingRatio(new BigDecimal("0.40"));
        audit.setLeaderRollingAggregationWarnMinOverallWinRate(new BigDecimal("0.48"));
        audit.setLeaderRollingAggregationTopNToCapture(5);
        audit.setLeaderRollingAggregationMinEvaluatedCount(10);
        properties.setAudit(audit);

        service = new LeaderRollingMonitorService(
                properties, orderMapper, positionMapper, aggregationDailyMapper, auditAlertSink);
    }

    // ───────────────────────── HEALTH_OK ─────────────────────────

    /**
     * 样本不足 (evaluated < minEvaluatedForHealth=10) 时统一 OK + 不告警.
     * 设置 5 个 leader, 每个 8 笔全胜 (满足 minSamples=8 但 evaluated=5 < 10).
     */
    @Test
    void runScheduled_lowSamples_resultsInOkAndNoAlert() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String leader = "0xLeader" + i;
            for (int j = 0; j < 8; j++) {
                long pid = nextPositionId.getAndIncrement();
                orders.add(buildOrder(leader, pid));
                positions.add(buildPosition(pid, new BigDecimal("3.50"))); // 全胜
            }
        }
        stubMappers(orders, positions);
        when(aggregationDailyMapper.selectOne(any())).thenReturn(null);

        List<LeaderRollingMonitorService.LeaderRollingAggregationSnapshot> snaps = service.runScheduled();

        assertEquals(1, snaps.size());
        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = snaps.get(0);
        assertEquals(LeaderRollingMonitorService.HEALTH_OK, snap.getHealthStatus());
        // 5 个 leader 均 active (samples=8 >= minSamples, winRate=1.0 >= 0.45)
        assertEquals(5, snap.getActiveCount());
        assertEquals(0, snap.getCoolingCount());
        assertEquals(0, snap.getColdCount());
        verify(auditAlertSink, never()).publish(any(AlertType.class), any(), any(), any(), any());
        verify(aggregationDailyMapper, times(1)).insert(any());
    }

    /**
     * evaluated 充足 (=12), cooling 占比 25% (<40%), overallWin=0.55 (>=0.48) → OK.
     */
    @Test
    void runScheduled_healthyDistribution_resultsInOkAndNoAlert() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        // 9 active leaders: 8 笔, 5 胜 3 负 → winRate=0.625 >= 0.45 → ACTIVE
        for (int i = 0; i < 9; i++) {
            String leader = "0xActive" + i;
            buildSeries(leader, orders, positions, 8, 5);
        }
        // 3 cooling leaders: 8 笔, 3 胜 5 负 → winRate=0.375 < 0.45 → COOLING
        for (int i = 0; i < 3; i++) {
            String leader = "0xCooling" + i;
            buildSeries(leader, orders, positions, 8, 3);
        }
        stubMappers(orders, positions);
        when(aggregationDailyMapper.selectOne(any())).thenReturn(null);

        List<LeaderRollingMonitorService.LeaderRollingAggregationSnapshot> snaps = service.runScheduled();

        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = snaps.get(0);
        assertEquals(9, snap.getActiveCount());
        assertEquals(3, snap.getCoolingCount());
        assertEquals(12, snap.getEvaluatedCount());
        // coolingRatio = 3/12 = 0.25; overallWin = (9*5 + 3*3) / (12*8) = 54/96 = 0.5625
        assertEquals(0, new BigDecimal("0.2500").compareTo(snap.getCoolingRatio()));
        assertEquals(0, new BigDecimal("0.5625").compareTo(snap.getOverallWinRate()));
        assertEquals(LeaderRollingMonitorService.HEALTH_OK, snap.getHealthStatus());
        verify(auditAlertSink, never()).publish(any(AlertType.class), any(), any(), any(), any());
    }

    // ───────────────────────── HEALTH_WARN_HIGH_COOLING ─────────────────────────

    /**
     * cooling 占比 50% (>=40%), overallWin=0.50 (>=0.48) → WARN_HIGH_COOLING + 触发告警.
     */
    @Test
    void runScheduled_highCoolingButHealthyWinRate_warnHighCooling() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        // 6 active leaders: 8 笔, 6 胜 2 负 → winRate=0.75 → ACTIVE
        for (int i = 0; i < 6; i++) {
            buildSeries("0xActive" + i, orders, positions, 8, 6);
        }
        // 6 cooling leaders: 8 笔, 2 胜 6 负 → winRate=0.25 → COOLING
        for (int i = 0; i < 6; i++) {
            buildSeries("0xCooling" + i, orders, positions, 8, 2);
        }
        stubMappers(orders, positions);
        when(aggregationDailyMapper.selectOne(any())).thenReturn(null);

        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = service.runScheduled().get(0);

        assertEquals(LeaderRollingMonitorService.HEALTH_WARN_HIGH_COOLING, snap.getHealthStatus());
        // coolingRatio = 6/12 = 0.50 >= 0.40
        assertEquals(0, new BigDecimal("0.5000").compareTo(snap.getCoolingRatio()));
        // overallWin = (6*6 + 6*2) / 96 = 48/96 = 0.50 >= 0.48
        assertEquals(0, new BigDecimal("0.5000").compareTo(snap.getOverallWinRate()));

        ArgumentCaptor<AlertType> typeCap = ArgumentCaptor.forClass(AlertType.class);
        verify(auditAlertSink, times(1)).publish(typeCap.capture(), any(), any(), any(), any());
        assertEquals(AlertType.AUDIT_LEADER_ROLLING_HEALTH, typeCap.getValue());
    }

    // ───────────────────────── HEALTH_WARN_LOW_WIN_RATE ─────────────────────────

    /**
     * cooling 占比 25% (<40%), overallWin=0.41 (<0.48) → WARN_LOW_WIN_RATE + 触发告警.
     */
    @Test
    void runScheduled_lowWinRateButHealthyCooling_warnLowWinRate() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        // 9 active leaders: 8 笔, 4 胜 4 负 → winRate=0.50 >= 0.45 → ACTIVE (但全 active 不会让 cooling > 0)
        for (int i = 0; i < 9; i++) {
            buildSeries("0xActiveLow" + i, orders, positions, 8, 4);
        }
        // 3 cooling leaders: 8 笔, 1 胜 7 负 → winRate=0.125 < 0.45 → COOLING
        for (int i = 0; i < 3; i++) {
            buildSeries("0xCoolingDeep" + i, orders, positions, 8, 1);
        }
        stubMappers(orders, positions);
        when(aggregationDailyMapper.selectOne(any())).thenReturn(null);

        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = service.runScheduled().get(0);

        // cooling = 3/12 = 0.25 < 0.40
        assertEquals(0, new BigDecimal("0.2500").compareTo(snap.getCoolingRatio()));
        // overallWin = (9*4 + 3*1) / 96 = 39/96 = 0.40625
        assertTrue(snap.getOverallWinRate().compareTo(new BigDecimal("0.48")) < 0,
                "overallWinRate=" + snap.getOverallWinRate());
        assertEquals(LeaderRollingMonitorService.HEALTH_WARN_LOW_WIN_RATE, snap.getHealthStatus());
        verify(auditAlertSink, times(1)).publish(eq(AlertType.AUDIT_LEADER_ROLLING_HEALTH),
                any(), any(), any(), any());
    }

    // ───────────────────────── HEALTH_WARN_BOTH ─────────────────────────

    /**
     * cooling 占比 58% (>=40%), overallWin=0.29 (<0.48) → WARN_BOTH + 触发告警.
     */
    @Test
    void runScheduled_highCoolingAndLowWinRate_warnBoth() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        // 5 active leaders: 8 笔, 5 胜 3 负 → winRate=0.625 >= 0.45 → ACTIVE
        for (int i = 0; i < 5; i++) {
            buildSeries("0xActiveMix" + i, orders, positions, 8, 5);
        }
        // 7 cooling leaders: 8 笔, 1 胜 7 负 → winRate=0.125 < 0.45 → COOLING
        for (int i = 0; i < 7; i++) {
            buildSeries("0xCoolingBad" + i, orders, positions, 8, 1);
        }
        stubMappers(orders, positions);
        when(aggregationDailyMapper.selectOne(any())).thenReturn(null);

        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = service.runScheduled().get(0);

        // cooling = 7/12 = 0.5833...
        assertTrue(snap.getCoolingRatio().compareTo(new BigDecimal("0.40")) >= 0,
                "coolingRatio=" + snap.getCoolingRatio());
        // overallWin = (5*5 + 7*1) / 96 = 32/96 = 0.333...
        assertTrue(snap.getOverallWinRate().compareTo(new BigDecimal("0.48")) < 0,
                "overallWinRate=" + snap.getOverallWinRate());
        assertEquals(LeaderRollingMonitorService.HEALTH_WARN_BOTH, snap.getHealthStatus());

        verify(auditAlertSink, times(1)).publish(eq(AlertType.AUDIT_LEADER_ROLLING_HEALTH),
                any(), any(), any(), any());
    }

    // ───────────────────────── upsert 幂等 ─────────────────────────

    /**
     * 已存在同 (asOfDate, lookbackDays) 行时, 走 updateById 而非 insert.
     */
    @Test
    void runScheduled_existingRow_invokesUpdateNotInsert() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        for (int i = 0; i < 9; i++) buildSeries("0xUp" + i, orders, positions, 8, 5);
        for (int i = 0; i < 3; i++) buildSeries("0xCool" + i, orders, positions, 8, 2);
        stubMappers(orders, positions);

        LeaderRollingAggregationDailyEntity existing = new LeaderRollingAggregationDailyEntity()
                .setAsOfDate(java.time.LocalDate.now())
                .setLookbackDays(7);
        when(aggregationDailyMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        service.runScheduled();

        verify(aggregationDailyMapper, times(1)).updateById(any(LeaderRollingAggregationDailyEntity.class));
        verify(aggregationDailyMapper, never()).insert(any(LeaderRollingAggregationDailyEntity.class));
    }

    // ───────────────────────── dryRun (公共调试入口) ─────────────────────────

    /**
     * dryRun 跑通整套算法但不 upsert / 不告警, 用于运维 ad-hoc 触发与单测断言场景.
     */
    @Test
    void dryRun_doesNotUpsertOrAlert() {
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        for (int i = 0; i < 6; i++) buildSeries("0xDry" + i, orders, positions, 8, 6);
        for (int i = 0; i < 6; i++) buildSeries("0xDryC" + i, orders, positions, 8, 2);
        stubMappers(orders, positions);

        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = service.dryRun(7);

        assertNotNull(snap);
        assertEquals(LeaderRollingMonitorService.HEALTH_WARN_HIGH_COOLING, snap.getHealthStatus());
        verify(aggregationDailyMapper, never()).insert(any(LeaderRollingAggregationDailyEntity.class));
        verify(aggregationDailyMapper, never()).updateById(any(LeaderRollingAggregationDailyEntity.class));
        verify(auditAlertSink, never()).publish(any(AlertType.class), any(), any(), any(), any());
    }

    // ───────────────────────── sink 关闭路径 ─────────────────────────

    /**
     * audit.alertSinkEnabled=false 时即便 health != OK 也不 publish.
     */
    @Test
    void runScheduled_sinkDisabled_doesNotPublishEvenOnWarn() {
        properties.getAudit().setAlertSinkEnabled(false);
        List<SmartWalletOrderEntity> orders = new ArrayList<>();
        List<SmartWalletPositionEntity> positions = new ArrayList<>();
        for (int i = 0; i < 6; i++) buildSeries("0xS" + i, orders, positions, 8, 6);
        for (int i = 0; i < 6; i++) buildSeries("0xSC" + i, orders, positions, 8, 1);
        stubMappers(orders, positions);
        when(aggregationDailyMapper.selectOne(any())).thenReturn(null);

        LeaderRollingMonitorService.LeaderRollingAggregationSnapshot snap = service.runScheduled().get(0);

        assertEquals(LeaderRollingMonitorService.HEALTH_WARN_BOTH, snap.getHealthStatus());
        verify(auditAlertSink, never()).publish(any(AlertType.class), any(), any(), any(), any());
        verify(aggregationDailyMapper, times(1)).insert(any(LeaderRollingAggregationDailyEntity.class));
    }

    // ───────────────────────── 工具方法 ─────────────────────────

    /** mock orderMapper / positionMapper. */
    private void stubMappers(List<SmartWalletOrderEntity> orders, List<SmartWalletPositionEntity> positions) {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(orders);
        when(positionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(positions);
    }

    /** 为某 leader 生成 totalCount 笔 BUY+ENTRY 订单 + 对应 SETTLED positions, 其中 winCount 笔正 PnL. */
    private void buildSeries(String leader, List<SmartWalletOrderEntity> orders,
                              List<SmartWalletPositionEntity> positions,
                              int totalCount, int winCount) {
        for (int i = 0; i < totalCount; i++) {
            long pid = nextPositionId.getAndIncrement();
            orders.add(buildOrder(leader, pid));
            BigDecimal pnl = (i < winCount) ? new BigDecimal("2.50") : new BigDecimal("-3.00");
            positions.add(buildPosition(pid, pnl));
        }
    }

    private SmartWalletOrderEntity buildOrder(String leader, long positionId) {
        SmartWalletOrderEntity o = new SmartWalletOrderEntity();
        o.setWalletAddress(leader);
        o.setPositionId(positionId);
        o.setSide("BUY");
        o.setOrderType("ENTRY");
        o.setFilledAt(new Date(System.currentTimeMillis() - 3_600_000L));
        return o;
    }

    private SmartWalletPositionEntity buildPosition(long id, BigDecimal pnl) {
        SmartWalletPositionEntity p = new SmartWalletPositionEntity();
        p.setId(id);
        p.setStatus("CLOSED");
        p.setRealizedPnlUsdc(pnl);
        return p;
    }
}
