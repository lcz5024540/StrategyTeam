package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持仓集中度持续监控 Job
 *
 * 职责：对策略当前所有 OPEN 持仓对应的市场，定期检测持仓集中度变化。
 * 若某市场在开仓后集中度急剧上升（CR_3 上涨超过告警阈值），说明巨鲸可能在我们开仓后
 * 继续大量买入，存在被"接盘"的风险，触发告警让运维人员决策是否提前退出。
 *
 * 告警条件（任一满足即告警）：
 *   - 市场当前集中度已进入"高度集中"状态（isHighlyConcentrated = true）
 *   - 当前 CR_3 与首次观测值相比上升超过 concentrationSurgePct（默认 0.20 = 20pp）
 *
 * 设计说明：
 *   - 首次扫描时记录各市场的基线 CR_3，后续对比用
 *   - 仅打印告警日志，不自动平仓（平仓策略由运营人员判断）
 *   - 扫描间隔由 polymarket.smart-wallet.concentration-monitor-delay-ms 控制（默认 5 分钟）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketConcentrationMonitorJob {

    private final SmartWalletProperties properties;
    private final SmartWalletPositionMapper positionMapper;
    private final MarketConcentrationService concentrationService;
    private final SmartWalletMarketInfoService marketInfoService;

    /**
     * 各市场首次观测的 CR_3 基线（key = conditionId:outcome）
     * 仅在 JVM 生命周期内有效，重启后重新采集
     */
    private final Map<String, BigDecimal> baselineCr3 = new ConcurrentHashMap<>();

    /** CR_3 上涨超过此值时触发告警（默认 20 个百分点） */
    private static final BigDecimal DEFAULT_SURGE_THRESHOLD = new BigDecimal("0.20");

    @Scheduled(fixedDelayString = "${polymarket.smart-wallet.concentration-monitor-delay-ms:300000}")
    public void scanOpenPositions() {
        if (!properties.getSignalRisk().isEnableConcentrationGate()) {
            // 集中度门控未开启时，监控 Job 也静默运行（仍采集基线数据）
            return;
        }
        try {
            doScan();
        } catch (Exception e) {
            log.error("[ConcentrationMonitor] Unexpected error: {}", e.getMessage(), e);
        }
    }

    private void doScan() {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));

        if (openPositions.isEmpty()) {
            return;
        }

        // Populate all positions with market info to enable delegate getters
        marketInfoService.populatePositions(openPositions);

        int alertCount = 0;
        for (SmartWalletPositionEntity position : openPositions) {
            try {
                if (checkPosition(position)) {
                    alertCount++;
                }
            } catch (Exception e) {
                log.debug("[ConcentrationMonitor] Error checking positionId={}: {}", position.getId(), e.getMessage());
            }
        }

        if (alertCount > 0) {
            log.warn("[ConcentrationMonitor] {} open position(s) are in concentrated markets. Review recommended.",
                    alertCount);
        }
    }

    /**
     * 检查单个持仓的市场集中度，返回 true 表示有告警
     */
    private boolean checkPosition(SmartWalletPositionEntity position) {
        if (position.getMarketId() == null) return false;

        ConcentrationSnapshot snap =
                concentrationService.analyze(position.getMarketId(), position.getOutcome());

        if (!snap.isDataAvailable()) {
            return false;
        }

        String baselineKey = position.getMarketId() + ":" + (position.getOutcome() != null ? position.getOutcome() : "ALL");
        boolean alerted = false;

        // Gate 1：当前已进入极端/高度集中状态
        if (snap.isExtremelyConcentrated()) {
            log.warn("[ConcentrationMonitor] EXTREME ALERT: positionId={} market={} outcome={} is now EXTREMELY CONCENTRATED. " +
                            "CR1={} CR3={} CR5={} HHI={} holders={}. Review exit urgency immediately.",
                    position.getId(), position.getMarketId(), position.getOutcome(),
                    snap.top1Pct().setScale(3, RoundingMode.HALF_UP),
                    snap.top3Pct().setScale(3, RoundingMode.HALF_UP),
                    snap.top5Pct().setScale(3, RoundingMode.HALF_UP),
                    snap.hhi(), snap.observedHolders());
            alerted = true;
        } else if (snap.isHighlyConcentrated()) {
            log.warn("[ConcentrationMonitor] ALERT: positionId={} market={} outcome={} is now HIGHLY CONCENTRATED. " +
                            "CR1={} CR3={} HHI={} holders={}. Consider reviewing this position.",
                    position.getId(), position.getMarketId(), position.getOutcome(),
                    snap.top1Pct().setScale(3, RoundingMode.HALF_UP),
                    snap.top3Pct().setScale(3, RoundingMode.HALF_UP),
                    snap.hhi(), snap.observedHolders());
            alerted = true;
        }

        // Gate 2：CR_3 相对基线急剧上升
        BigDecimal baseline = baselineCr3.get(baselineKey);
        if (baseline != null) {
            BigDecimal surge = snap.top3Pct().subtract(baseline);
            if (surge.compareTo(DEFAULT_SURGE_THRESHOLD) > 0) {
                log.warn("[ConcentrationMonitor] SURGE ALERT: positionId={} market={} outcome={} " +
                                "CR3 surged from {} to {} (+{}pp since first observation). " +
                                "Possible post-entry whale accumulation.",
                        position.getId(), position.getMarketId(), position.getOutcome(),
                        baseline.setScale(3, RoundingMode.HALF_UP),
                        snap.top3Pct().setScale(3, RoundingMode.HALF_UP),
                        surge.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP));
                alerted = true;
            }
        } else {
            // 首次观测：记录基线
            baselineCr3.put(baselineKey, snap.top3Pct());
            log.debug("[ConcentrationMonitor] Baseline recorded for key={} CR3={}",
                    baselineKey, snap.top3Pct().setScale(3, RoundingMode.HALF_UP));
        }

        return alerted;
    }
}
