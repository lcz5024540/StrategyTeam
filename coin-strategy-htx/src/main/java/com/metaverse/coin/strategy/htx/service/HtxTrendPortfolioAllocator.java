package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure portfolio layer inspired by target-position execution models.
 *
 * <p>It turns already-authorized candidate intents into desired exposures. It deliberately has no
 * HTX client, mapper or order method: the existing price-plan and order layers remain responsible
 * for deciding whether a target can be executed.</p>
 */
public final class HtxTrendPortfolioAllocator {
    public Allocation allocate(List<Candidate> candidates, List<AccountCapacity> accounts, RiskBudget budget) {
        if (budget == null) throw new IllegalArgumentException("portfolio risk budget is required");
        List<Candidate> ordered = (candidates == null ? List.<Candidate>of() : candidates).stream()
                .filter(Candidate::valid).sorted(Comparator.comparing(Candidate::score).reversed()
                        .thenComparing(Candidate::runId)).toList();
        Map<Long, BigDecimal> usedByAccount = new HashMap<>();
        Map<String, Integer> clusterCounts = new HashMap<>();
        Set<String> symbols = new HashSet<>();
        BigDecimal gross = BigDecimal.ZERO, longGross = BigDecimal.ZERO, shortGross = BigDecimal.ZERO;
        List<TargetExposure> targets = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        for (Candidate candidate : ordered) {
            String key = candidate.contractCode() + ":" + candidate.direction();
            String rejection = rejection(candidate, budget, targets.size(), symbols, clusterCounts, gross, longGross, shortGross);
            if (rejection != null) { decisions.add(Decision.rejected(candidate, rejection)); continue; }
            AccountCapacity account = account(candidate, accounts, usedByAccount);
            if (account == null) { decisions.add(Decision.rejected(candidate, "no_enabled_account_with_available_risk_budget")); continue; }
            BigDecimal allocated = candidate.requestedNotional().min(account.maxNotional().subtract(usedByAccount.getOrDefault(account.accountId(), BigDecimal.ZERO)));
            if (allocated.signum() <= 0) { decisions.add(Decision.rejected(candidate, "account_capacity_exhausted")); continue; }
            TargetExposure target = new TargetExposure(candidate.runId(), candidate.contractCode(), candidate.direction(),
                    account.accountId(), account.accountLabel(), allocated, candidate.riskUsdt(), candidate.cluster(),
                    candidate.score(), "TARGET_EXPOSURE_ONLY");
            targets.add(target); decisions.add(Decision.accepted(candidate, target)); symbols.add(key);
            clusterCounts.merge(candidate.cluster(), 1, Integer::sum); usedByAccount.merge(account.accountId(), allocated, BigDecimal::add);
            gross = gross.add(allocated); if ("LONG".equals(candidate.direction())) longGross = longGross.add(allocated); else shortGross = shortGross.add(allocated);
        }
        return new Allocation(List.copyOf(targets), List.copyOf(decisions), new PortfolioRiskBudget(gross, longGross, shortGross,
                targets.size(), Map.copyOf(clusterCounts), Map.copyOf(usedByAccount)));
    }

    private static String rejection(Candidate candidate, RiskBudget budget, int active, Set<String> symbols,
                                    Map<String, Integer> clusters, BigDecimal gross, BigDecimal longGross, BigDecimal shortGross) {
        if (active >= budget.maxActivePositions()) return "max_active_positions";
        if (symbols.contains(candidate.contractCode() + ":" + candidate.direction())) return "duplicate_symbol_direction";
        if (clusters.getOrDefault(candidate.cluster(), 0) >= budget.maxPositionsPerCluster()) return "correlation_cluster_limit";
        if (gross.add(candidate.requestedNotional()).compareTo(budget.maxGrossNotional()) > 0) return "max_gross_notional";
        BigDecimal directional = "LONG".equals(candidate.direction()) ? longGross : shortGross;
        return directional.add(candidate.requestedNotional()).compareTo(budget.maxDirectionalNotional()) > 0
                ? "max_directional_notional" : null;
    }

    private static AccountCapacity account(Candidate candidate, List<AccountCapacity> accounts, Map<Long, BigDecimal> used) {
        return (accounts == null ? List.<AccountCapacity>of() : accounts).stream().filter(AccountCapacity::enabled)
                .filter(value -> value.maxNotional().subtract(used.getOrDefault(value.accountId(), BigDecimal.ZERO)).signum() > 0)
                .filter(value -> value.availableMargin().compareTo(candidate.riskUsdt()) >= 0)
                .sorted(Comparator.comparing(AccountCapacity::takerFeeRate).thenComparing(AccountCapacity::accountId)).findFirst().orElse(null);
    }

    public record Candidate(String runId, String contractCode, String direction, BigDecimal requestedNotional,
                            BigDecimal riskUsdt, BigDecimal score, String cluster) {
        boolean valid() { return nonBlank(runId) && nonBlank(contractCode) && ("LONG".equals(direction) || "SHORT".equals(direction))
                && positive(requestedNotional) && positive(riskUsdt) && score != null && nonBlank(cluster); }
    }
    public record AccountCapacity(long accountId, String accountLabel, boolean enabled, BigDecimal availableMargin,
                                  BigDecimal maxNotional, BigDecimal takerFeeRate) {
        public AccountCapacity { if (accountId <= 0 || !nonBlank(accountLabel) || !nonNegative(availableMargin) || !positive(maxNotional) || !nonNegative(takerFeeRate)) throw new IllegalArgumentException("complete account capacity is required"); }
    }
    public record RiskBudget(int maxActivePositions, int maxPositionsPerCluster, BigDecimal maxGrossNotional, BigDecimal maxDirectionalNotional) {
        public RiskBudget { if (maxActivePositions < 1 || maxPositionsPerCluster < 1 || !positive(maxGrossNotional) || !positive(maxDirectionalNotional)) throw new IllegalArgumentException("positive portfolio limits are required"); }
    }
    public record TargetExposure(String runId, String contractCode, String direction, long accountId, String accountLabel,
                                 BigDecimal targetNotional, BigDecimal riskUsdt, String cluster, BigDecimal score, String executionBoundary) { }
    public record Decision(Candidate candidate, TargetExposure target, String rejection) {
        static Decision accepted(Candidate candidate, TargetExposure target) { return new Decision(candidate, target, null); }
        static Decision rejected(Candidate candidate, String reason) { return new Decision(candidate, null, reason); }
        public boolean accepted() { return target != null; }
    }
    public record PortfolioRiskBudget(BigDecimal grossNotional, BigDecimal longNotional, BigDecimal shortNotional,
                                      int activePositions, Map<String, Integer> clusterCounts, Map<Long, BigDecimal> accountNotional) { }
    public record Allocation(List<TargetExposure> targets, List<Decision> decisions, PortfolioRiskBudget budget) { }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static boolean nonNegative(BigDecimal value) { return value != null && value.signum() >= 0; }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
