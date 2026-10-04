package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCombinationRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Records portfolio target decisions as immutable evidence. This service is deliberately an audit
 * boundary: it cannot create an exchange order or alter an existing price plan.
 */
@Service
@RequiredArgsConstructor
public class HtxTrendPortfolioAllocationAuditService {
    private final HtxTrendCombinationRecorder recorder;
    private final HtxTrendPortfolioAllocator allocator = new HtxTrendPortfolioAllocator();

    public HtxTrendPortfolioAllocator.Allocation allocateAndRecord(String scanId, List<AuditedCandidate> candidates,
                                                                     List<HtxTrendPortfolioAllocator.AccountCapacity> accounts,
                                                                     HtxTrendPortfolioAllocator.RiskBudget budget) {
        List<HtxTrendPortfolioAllocator.Candidate> inputs = (candidates == null ? List.<AuditedCandidate>of() : candidates)
                .stream().map(AuditedCandidate::candidate).toList();
        HtxTrendPortfolioAllocator.Allocation allocation = allocator.allocate(inputs, accounts, budget);
        for (HtxTrendPortfolioAllocator.Decision decision : allocation.decisions()) {
            AuditedCandidate candidate = (candidates == null ? List.<AuditedCandidate>of() : candidates).stream()
                    .filter(value -> value.candidate().runId().equals(decision.candidate().runId())).findFirst().orElse(null);
            if (candidate != null) record(scanId, candidate, decision, allocation.budget());
        }
        return allocation;
    }

    private void record(String scanId, AuditedCandidate candidate, HtxTrendPortfolioAllocator.Decision decision,
                        HtxTrendPortfolioAllocator.PortfolioRiskBudget budget) {
        if (blank(scanId) || candidate == null || candidate.candidate() == null || blank(candidate.candidate().runId())) return;
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey("PORTFOLIO:" + scanId + ":" + digest(candidate.candidate().runId()));
        snapshot.setContractCode(candidate.candidate().contractCode());
        snapshot.setPeriod(candidate.period());
        snapshot.setBarTime(candidate.barTime());
        snapshot.setMarketRegime(candidate.marketRegime());
        snapshot.setFactorsJson(JSON.toJSONString(new TargetExposureEvidence(scanId, candidate.candidate(), decision,
                budget, "trend-target-exposure-v1", "TARGET_EXPOSURE_ONLY")));
        recorder.recordSnapshot(snapshot);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 12; index++) builder.append(String.format("%02x", hash[index]));
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record AuditedCandidate(String period, long barTime, String marketRegime,
                                   HtxTrendPortfolioAllocator.Candidate candidate) {
        public AuditedCandidate {
            if (blank(period) || barTime <= 0L || blank(marketRegime) || candidate == null) {
                throw new IllegalArgumentException("complete portfolio audit candidate is required");
            }
        }
    }

    private record TargetExposureEvidence(String scanId, HtxTrendPortfolioAllocator.Candidate candidate,
                                          HtxTrendPortfolioAllocator.Decision decision,
                                          HtxTrendPortfolioAllocator.PortfolioRiskBudget budget,
                                          String schema, String executionBoundary) { }
}
