package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.DirectionalBasisAdmissionGate;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Restores the bounded in-memory directional-basis window from persisted observations. */
@Component
public class DirectionalBasisHistoryLoader {
    private static final Logger LOG = LoggerFactory.getLogger(DirectionalBasisHistoryLoader.class);
    private static final int RESTORE_LIMIT = 20_000;

    private final CrossExchangeArbOpportunityMapper opportunities;
    private final DirectionalBasisAdmissionGate gate;

    public DirectionalBasisHistoryLoader(CrossExchangeArbOpportunityMapper opportunities,
            DirectionalBasisAdmissionGate gate) {
        this.opportunities = opportunities;
        this.gate = gate;
    }

    @PostConstruct
    public void restore() {
        List<CrossExchangeArbOpportunityEntity> rows = new ArrayList<>(opportunities.selectList(
                new LambdaQueryWrapper<CrossExchangeArbOpportunityEntity>()
                        .isNotNull(CrossExchangeArbOpportunityEntity::getDirectionalBasis)
                        .orderByDesc(CrossExchangeArbOpportunityEntity::getDiscoveredAt)
                        .last("LIMIT " + RESTORE_LIMIT)));
        Collections.reverse(rows);
        int restored = 0;
        for (CrossExchangeArbOpportunityEntity row : rows) {
            try {
                gate.seed(row.getSymbol(), Exchange.valueOf(row.getLongExchange()),
                        Exchange.valueOf(row.getShortExchange()), row.getDirectionalBasis());
                restored++;
            } catch (IllegalArgumentException exception) {
                LOG.warn("Skipping directional-basis row with unknown exchange: id={}", row.getId());
            }
        }
        LOG.info("Restored {} persisted directional-basis samples", restored);
    }
}
