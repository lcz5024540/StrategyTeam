package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileDiagnosisSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileDiagnosisSnapshotEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletDiagnosisView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Phase 4.4：读取 {@code smart_wallet_profile_diagnosis_snapshot} 表。
 *
 * <p>由 job 端 {@code ProfileDiagnosisSnapshotJob} 默认 30min 一次写入。
 * strengths / risks 字段表里存 JSON，read 时 jackson 反序列化为 List。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletProfileDiagnosisReadService {

    private static final String SOURCE = "POLYMARKET";
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final SmartWalletProfileDiagnosisSnapshotMapper diagnosisSnapshotMapper;
    private final ObjectMapper objectMapper;

    public WalletDiagnosisView getDiagnosis(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        SmartWalletProfileDiagnosisSnapshotEntity entity = diagnosisSnapshotMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletProfileDiagnosisSnapshotEntity>()
                        .eq(SmartWalletProfileDiagnosisSnapshotEntity::getWalletAddress, walletAddress.trim())
                        .eq(SmartWalletProfileDiagnosisSnapshotEntity::getSource, SOURCE)
                        .last("limit 1"));
        if (entity == null) {
            // 返回空 view（带 walletAddress，便于前端区分"尚未生成"和"不存在"）
            return WalletDiagnosisView.builder()
                    .walletAddress(walletAddress.trim())
                    .build();
        }
        return WalletDiagnosisView.builder()
                .walletAddress(entity.getWalletAddress())
                .alias(entity.getAlias())
                .archetype(entity.getArchetype())
                .followLane(entity.getFollowLane())
                .archetypeReason(entity.getArchetypeReason())
                .archetypeConfidence(entity.getArchetypeConfidence())
                .screenerTier(entity.getScreenerTier())
                .highQualityAddress(entity.getHighQualityAddress())
                .highFrequencyTradingAddress(entity.getHighFrequencyTradingAddress())
                .winSourceType(entity.getWinSourceType())
                .qualityScore(entity.getQualityScore())
                .replicabilityScore(entity.getReplicabilityScore())
                .stabilityScore(entity.getStabilityScore())
                .settlementWinRate(entity.getSettlementWinRate())
                .latencyAlphaRate(entity.getLatencyAlphaRate())
                .swingAlphaRate(entity.getSwingAlphaRate())
                .earlySettlementRate(entity.getEarlySettlementRate())
                .dedupOrderCount7d(entity.getDedupOrderCount7d())
                .dedupOrderCount30d(entity.getDedupOrderCount30d())
                .medianHoldMinutes(entity.getMedianHoldMinutes())
                .dataQuality(entity.getDataQuality())
                .recommendedExitLane(entity.getRecommendedExitLane())
                .summary(entity.getSummary())
                .strengths(parseJsonList(entity.getStrengthsJson()))
                .risks(parseJsonList(entity.getRisksJson()))
                .build();
    }

    private List<String> parseJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, STRING_LIST_TYPE);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("[SmartWalletProfileDiagnosisReadService] parse json list failed: {}", e.getMessage());
            return List.of();
        }
    }
}
