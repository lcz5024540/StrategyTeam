package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendCandidateSignalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Records trend ensemble shadow candidates without blocking the live trend runner. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtxTrendCandidateSignalRecorder {

    private final HtxTrendCandidateSignalMapper mapper;

    public RecordResult record(HtxTrendCandidateSignalEntity entity) {
        try {
            HtxTrendCandidateSignalEntity latest = mapper.findLatest(entity.getContractCode(), entity.getPeriod(),
                    entity.getModelVersion());
            if (sameBar(latest, entity)) {
                return RecordResult.SKIPPED_DUPLICATE_BAR;
            }
            mapper.insert(entity);
            return RecordResult.STORED;
        } catch (RuntimeException ex) {
            log.warn("trend candidate insert failed contract={} period={} direction={}",
                    entity == null ? null : entity.getContractCode(),
                    entity == null ? null : entity.getPeriod(),
                    entity == null ? null : entity.getDirection(), ex);
            return RecordResult.FAILED;
        }
    }

    /** Final live-preflight outcome is mutable operational evidence; structural candidate fields remain immutable. */
    public void markExecutionGate(String runId, String contractCode, String result, String reason) {
        if (runId == null || runId.isBlank() || contractCode == null || contractCode.isBlank()) {
            return;
        }
        try {
            mapper.markExecutionGate(runId, contractCode, result, reason == null ? null
                    : reason.substring(0, Math.min(reason.length(), 1024)));
        } catch (RuntimeException ex) {
            log.warn("trend execution gate update failed runId={} contract={}", runId, contractCode, ex);
        }
    }

    private static boolean sameBar(HtxTrendCandidateSignalEntity latest, HtxTrendCandidateSignalEntity current) {
        return latest != null
                && current != null
                && current.getBarTime() != null
                && Objects.equals(latest.getBarTime(), current.getBarTime());
    }

    public enum RecordResult {
        STORED,
        SKIPPED_DUPLICATE_BAR,
        FAILED
    }
}
