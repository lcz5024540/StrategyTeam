package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendFactorSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Persists immutable combination evidence before it can be referenced by a live position. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtxTrendCombinationRecorder {

    private final HtxTrendFactorSnapshotMapper factorSnapshotMapper;

    public Long recordSnapshot(HtxTrendFactorSnapshotEntity snapshot) {
        if (snapshot == null || snapshot.getSnapshotKey() == null || snapshot.getSnapshotKey().isBlank()) {
            return null;
        }
        try {
            HtxTrendFactorSnapshotEntity existing = factorSnapshotMapper.findBySnapshotKey(snapshot.getSnapshotKey());
            if (existing != null) {
                return existing.getId();
            }
            factorSnapshotMapper.insert(snapshot);
            return snapshot.getId();
        } catch (RuntimeException ex) {
            log.warn("trend factor snapshot persistence failed contract={} barTime={}",
                    snapshot.getContractCode(), snapshot.getBarTime(), ex);
            return null;
        }
    }
}
