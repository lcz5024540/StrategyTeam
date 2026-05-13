package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileDiagnosisSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileDiagnosisSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletDiagnosisView;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileStoreReadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 4.4 引入：定期把 executable+warm watchlist 的 diagnosis 快照写到
 * {@code smart_wallet_profile_diagnosis_snapshot} 表。
 *
 * <p>调用 job 端 {@code WalletProfileAnalysisService.diagnose(walletAddress, false)}
 * 复用现算逻辑，再把 List 字段（strengths/risks）jackson 序列化后写库。</p>
 *
 * <p>默认间隔 30min（{@code smart-wallet.diagnosis-snapshot-delay-ms} 可覆盖）；
 * diagnosis 比 order-stats 重，间隔放长。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProfileDiagnosisSnapshotJob {

    private static final String SOURCE = "POLYMARKET";

    private final WalletProfileAnalysisService walletProfileAnalysisService;
    private final SmartWalletProfileDiagnosisSnapshotMapper diagnosisSnapshotMapper;
    private final SmartWalletProfileStoreReadService profileStoreReadService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${smart-wallet.diagnosis-snapshot-delay-ms:1800000}")
    public void snapshot() {
        try {
            List<SmartWalletProfileEntity> targets = collectTargets();
            if (targets.isEmpty()) {
                return;
            }
            int ok = 0;
            int failed = 0;
            for (SmartWalletProfileEntity profile : targets) {
                try {
                    WalletDiagnosisView view = walletProfileAnalysisService.diagnose(profile.getWalletAddress(), false);
                    if (view == null) {
                        continue;
                    }
                    upsert(profile.getWalletAddress(), view);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[ProfileDiagnosisSnapshotJob] failed for wallet={} reason={}",
                            profile.getWalletAddress(), e.getMessage());
                }
            }
            log.info("[ProfileDiagnosisSnapshotJob] snapshot done targets={} ok={} failed={}",
                    targets.size(), ok, failed);
        } catch (Exception e) {
            log.error("[ProfileDiagnosisSnapshotJob] unexpected failure: {}", e.getMessage(), e);
        }
    }

    private List<SmartWalletProfileEntity> collectTargets() {
        Set<String> seen = new LinkedHashSet<>();
        List<SmartWalletProfileEntity> targets = new ArrayList<>();
        for (SmartWalletProfileEntity profile : profileStoreReadService.listExecutableWatchlist()) {
            if (profile != null && profile.getWalletAddress() != null
                    && seen.add(profile.getWalletAddress().toLowerCase())) {
                targets.add(profile);
            }
        }
        for (SmartWalletProfileEntity profile : profileStoreReadService.listWarmWatchlist()) {
            if (profile != null && profile.getWalletAddress() != null
                    && seen.add(profile.getWalletAddress().toLowerCase())) {
                targets.add(profile);
            }
        }
        return targets;
    }

    private void upsert(String walletAddress, WalletDiagnosisView view) {
        SmartWalletProfileDiagnosisSnapshotEntity existing = diagnosisSnapshotMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletProfileDiagnosisSnapshotEntity>()
                        .eq(SmartWalletProfileDiagnosisSnapshotEntity::getWalletAddress, walletAddress)
                        .eq(SmartWalletProfileDiagnosisSnapshotEntity::getSource, SOURCE)
                        .last("limit 1"));
        Date now = new Date();
        SmartWalletProfileDiagnosisSnapshotEntity entity = existing == null
                ? new SmartWalletProfileDiagnosisSnapshotEntity().setCreatedAt(now)
                : existing;
        entity.setWalletAddress(walletAddress)
                .setSource(SOURCE)
                .setAlias(view.alias())
                .setArchetype(view.archetype())
                .setFollowLane(view.followLane())
                .setArchetypeReason(view.archetypeReason())
                .setArchetypeConfidence(view.archetypeConfidence())
                .setScreenerTier(view.screenerTier())
                .setHighQualityAddress(view.highQualityAddress())
                .setHighFrequencyTradingAddress(view.highFrequencyTradingAddress())
                .setWinSourceType(view.winSourceType())
                .setQualityScore(view.qualityScore())
                .setReplicabilityScore(view.replicabilityScore())
                .setStabilityScore(view.stabilityScore())
                .setSettlementWinRate(view.settlementWinRate())
                .setLatencyAlphaRate(view.latencyAlphaRate())
                .setSwingAlphaRate(view.swingAlphaRate())
                .setEarlySettlementRate(view.earlySettlementRate())
                .setDedupOrderCount7d(view.dedupOrderCount7d())
                .setDedupOrderCount30d(view.dedupOrderCount30d())
                .setMedianHoldMinutes(view.medianHoldMinutes())
                .setDataQuality(view.dataQuality())
                .setRecommendedExitLane(view.recommendedExitLane())
                .setSummary(view.summary())
                .setStrengthsJson(toJsonSafe(view.strengths()))
                .setRisksJson(toJsonSafe(view.risks()))
                .setGeneratedAt(now)
                .setUpdatedAt(now);
        if (existing == null) {
            diagnosisSnapshotMapper.insert(entity);
        } else {
            diagnosisSnapshotMapper.updateById(entity);
        }
    }

    private String toJsonSafe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("[ProfileDiagnosisSnapshotJob] toJsonSafe failed: {}", e.getMessage());
            return "[]";
        }
    }
}
