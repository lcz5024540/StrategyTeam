package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** Accepts paper-only fills after validating the immutable outbox and price-plan boundaries. */
@Service
@RequiredArgsConstructor
public class HtxTrendPaperExecutionService {
    private final HtxTrendCombinationService combinationService;
    private final HtxTrendPaperExecutionMapper mapper;

    public Result report(Report request) {
        HtxTrendFactorSnapshotEntity signal = combinationService.latestExternalSignal(request.contractCode(), request.period(),
                request.barTime(), request.policyVersion(), request.runId());
        if (signal == null) return Result.rejected("ENTRY_READY outbox event not found");
        if (!"FILLED".equals(request.outcome()) && !"PARTIALLY_FILLED".equals(request.outcome())
                && !"REJECTED".equals(request.outcome()) && !"EXPIRED".equals(request.outcome())) {
            return Result.rejected("unsupported paper outcome");
        }
        HtxTrendPaperExecutionEntity entity = new HtxTrendPaperExecutionEntity();
        entity.setExecutionKey(request.executionKey()); entity.setRunId(request.runId()); entity.setContractCode(request.contractCode());
        entity.setOutcome(request.outcome()); entity.setFillPrice(request.fillPrice()); entity.setFilledContracts(request.filledContracts());
        entity.setReason(request.reason());
        try { mapper.insert(entity); return Result.accepted(entity.getId()); }
        catch (RuntimeException ex) { return Result.rejected("duplicate or persistence failure"); }
    }

    public record Report(String executionKey, String runId, String contractCode, String period, Long barTime,
                         String policyVersion, String outcome, BigDecimal fillPrice, Long filledContracts, String reason) { }
    public record Result(boolean accepted, Long id, String reason) {
        static Result accepted(Long id) { return new Result(true, id, null); }
        static Result rejected(String reason) { return new Result(false, null, reason); }
    }
}
