package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchCandleEntity;

import java.time.LocalDateTime;
import java.util.List;

/** Immutable local input supplied by the offline dataset collector to the research ledger. */
public record HtxTrendResearchDatasetFreezeRequest(
        String datasetKey,
        String sourceKind,
        String sourceReference,
        LocalDateTime capturedAt,
        LocalDateTime inputStartAt,
        LocalDateTime inputEndAt,
        String contractsJson,
        String periodsJson,
        String inputChecksum,
        String inputIntegrity,
        String purpose,
        String provenanceJson,
        List<HtxTrendResearchCandleEntity> candles
) { }
