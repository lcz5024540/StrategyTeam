package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Last known valid public contract metadata, isolated by exchange. */
public class PerpetualContractCatalog {
    private final List<PerpetualExchangeGateway> gateways;
    private final ExchangeHealthRegistry health;
    private volatile Map<Exchange, Map<String, PerpetualExchangeGateway.PerpetualContract>> contracts = Map.of();
    private volatile boolean initialized;

    public PerpetualContractCatalog(List<PerpetualExchangeGateway> gateways,
            ExchangeHealthRegistry health) {
        this.gateways = List.copyOf(gateways);
        this.health = health;
    }

    public void refresh(Instant now) {
        Map<Exchange, Map<String, PerpetualExchangeGateway.PerpetualContract>> refreshed =
                new EnumMap<>(Exchange.class);
        for (PerpetualExchangeGateway gateway : gateways) {
            try {
                Map<String, PerpetualExchangeGateway.PerpetualContract> exchangeContracts =
                        gateway.listContracts().stream().collect(Collectors.toUnmodifiableMap(
                                PerpetualExchangeGateway.PerpetualContract::canonicalSymbol,
                                Function.identity(), (left, right) -> left));
                refreshed.put(gateway.exchange(), exchangeContracts);
                health.success(gateway.exchange(), now);
            } catch (RuntimeException exception) {
                health.failure(gateway.exchange(), now, exception);
                Map<String, PerpetualExchangeGateway.PerpetualContract> previous =
                        contracts.get(gateway.exchange());
                if (previous != null) {
                    refreshed.put(gateway.exchange(), previous);
                }
            }
        }
        contracts = Map.copyOf(refreshed);
        initialized = !contracts.isEmpty();
    }

    public void ensureInitialized(Instant now) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    refresh(now);
                }
            }
        }
    }

    public Optional<PerpetualExchangeGateway.PerpetualContract> find(Exchange exchange,
            String canonicalSymbol) {
        return Optional.ofNullable(contracts.getOrDefault(exchange, Map.of()).get(canonicalSymbol));
    }

    public Map<Exchange, Map<String, PerpetualExchangeGateway.PerpetualContract>> snapshot() {
        return contracts;
    }
}
