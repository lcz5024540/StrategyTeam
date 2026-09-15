package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendCandidateSignalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Records trend ensemble shadow candidates without blocking the live trend runner. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtxTrendCandidateSignalRecorder {

    private final HtxTrendCandidateSignalMapper mapper;

    public boolean record(HtxTrendCandidateSignalEntity entity) {
        try {
            mapper.insert(entity);
            return true;
        } catch (RuntimeException ex) {
            log.warn("trend candidate insert failed contract={} period={} direction={}",
                    entity == null ? null : entity.getContractCode(),
                    entity == null ? null : entity.getPeriod(),
                    entity == null ? null : entity.getDirection(), ex);
            return false;
        }
    }
}
