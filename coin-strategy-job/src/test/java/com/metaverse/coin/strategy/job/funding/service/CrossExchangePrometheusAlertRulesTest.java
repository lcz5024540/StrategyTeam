package com.metaverse.coin.strategy.job.funding.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossExchangePrometheusAlertRulesTest {
    @SuppressWarnings("unchecked")
    @Test
    void definesAlertsForEveryCriticalFundingMetric() {
        InputStream resource = getClass().getClassLoader().getResourceAsStream(
                "prometheus/cross-exchange-funding-alerts.yml");
        assertNotNull(resource);
        Map<String, Object> document = new Yaml().load(resource);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) document.get("groups");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.get(0).get("rules");
        Set<String> alertNames = rules.stream().map(rule -> String.valueOf(rule.get("alert")))
                .collect(Collectors.toSet());

        assertEquals(Set.of("FundingArbExchangeUnhealthy", "FundingArbRateLimitSpike",
                "FundingArbUnhedgedExposure", "FundingArbManualIntervention",
                "FundingArbCircuitOpen"), alertNames);
        String expressions = rules.stream().map(rule -> String.valueOf(rule.get("expr")))
                .collect(Collectors.joining(" "));
        assertTrue(expressions.contains("funding_arb_exchange_healthy"));
        assertTrue(expressions.contains("funding_arb_exchange_rate_limits_total"));
        assertTrue(expressions.contains("funding_arb_unhedged_notional"));
        assertTrue(expressions.contains("funding_arb_manual_interventions_total"));
        assertTrue(expressions.contains("funding_arb_circuit_open"));
    }
}
