package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeStrategyLeaseMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Database-backed short lease preventing duplicate Paper opens across scheduler instances. */
@Service
public class CrossExchangeStrategyLeaseService {
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private final CrossExchangeStrategyLeaseMapper mapper;
    private final String ownerId = UUID.randomUUID().toString();

    public CrossExchangeStrategyLeaseService(CrossExchangeStrategyLeaseMapper mapper) {
        this.mapper = mapper;
    }

    public boolean acquireSymbol(String symbol, Instant now) {
        String leaseKey = "cross-exchange-open:" + symbol;
        Date observedAt = Date.from(now);
        return mapper.acquire(leaseKey, ownerId, Date.from(now.plus(LEASE_TTL)), observedAt) > 0;
    }

    public void releaseSymbol(String symbol) {
        mapper.release("cross-exchange-open:" + symbol, ownerId);
    }
}
