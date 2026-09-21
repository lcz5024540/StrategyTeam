package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStrategyPolicyEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendStrategyPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Reads the Dashboard-managed policy set; an unavailable policy store fails closed for new combinations. */
@Service
@RequiredArgsConstructor
public class HtxTrendStrategyPolicyService {

    private final HtxTrendStrategyPolicyMapper mapper;

    public List<HtxTrendStrategyPolicyEntity> enabledPolicies() {
        try {
            List<HtxTrendStrategyPolicyEntity> rows = mapper.findEnabled();
            return rows == null ? List.of() : rows;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
