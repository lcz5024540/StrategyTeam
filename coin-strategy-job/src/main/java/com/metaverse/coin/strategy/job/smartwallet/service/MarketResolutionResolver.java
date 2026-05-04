package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.Builder;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MarketResolutionResolver {

    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_WAIT_FINAL_PRICE = "WAIT_FINAL_PRICE";
    public static final String STATUS_NEED_MANUAL_CHECK = "NEED_MANUAL_CHECK";
    public static final String STATUS_UNRESOLVED = "UNRESOLVED";

    public Resolution resolve(Request request) {
        if (request == null) {
            return unresolved("metadata_null");
        }
        Map<String, String> outcomeTokenIds = request.getOutcomeTokenIds() == null
                ? Map.of()
                : request.getOutcomeTokenIds();

        Resolution tokenWinner = resolveFromTokenWinners(request.getTokenWinners(), outcomeTokenIds);
        if (tokenWinner != null) {
            return tokenWinner;
        }

        String explicitWinningTokenId = trimToNull(request.getExplicitWinningTokenId());
        if (StringUtils.hasText(explicitWinningTokenId)) {
            return Resolution.builder()
                    .resolutionStatus(STATUS_RESOLVED)
                    .winningTokenId(explicitWinningTokenId)
                    .winningOutcome(outcomeByToken(outcomeTokenIds, explicitWinningTokenId))
                    .resolutionSource("WINNING_TOKEN_ID")
                    .build();
        }

        String explicitWinnerOutcome = trimToNull(request.getExplicitWinnerOutcome());
        if (StringUtils.hasText(explicitWinnerOutcome)) {
            String normalizedOutcome = normalizeOutcome(explicitWinnerOutcome);
            return Resolution.builder()
                    .resolutionStatus(STATUS_RESOLVED)
                    .winningOutcome(normalizedOutcome)
                    .winningTokenId(outcomeTokenIds.get(normalizedOutcome))
                    .resolutionSource("EXPLICIT_WINNER")
                    .build();
        }

        boolean terminal = isTerminalMarketSignal(
                request.isClosed(),
                request.isAcceptingOrdersPresent(),
                request.isAcceptingOrders(),
                request.getUmaResolutionStatus());
        if (terminal) {
            FinalPriceOutcome finalPriceOutcome = resolveOutcomeFromFinalPrices(
                    request.getOutcomes(),
                    request.getOutcomePrices());
            if (finalPriceOutcome.isResolved()) {
                String winningTokenId = outcomeTokenIds.get(finalPriceOutcome.winningOutcome());
                if (!StringUtils.hasText(winningTokenId)) {
                    return manual("final_price_winning_token_unmapped");
                }
                return Resolution.builder()
                        .resolutionStatus(STATUS_RESOLVED)
                        .winningOutcome(finalPriceOutcome.winningOutcome())
                        .winningTokenId(winningTokenId)
                        .resolutionSource("FINAL_PRICE_INFERENCE")
                        .build();
            }
            return terminalUnresolved(request, finalPriceOutcome.reason());
        }

        return unresolved("market_not_terminal");
    }

    private Resolution resolveFromTokenWinners(List<TokenInfo> tokenWinners, Map<String, String> outcomeTokenIds) {
        if (tokenWinners == null || tokenWinners.isEmpty()) {
            return null;
        }
        TokenInfo winner = null;
        for (TokenInfo token : tokenWinners) {
            if (token == null || !token.isWinner()) {
                continue;
            }
            if (winner != null) {
                return manual("multiple_token_winners");
            }
            winner = token;
        }
        if (winner == null) {
            return null;
        }
        String outcome = normalizeOutcome(winner.getOutcome());
        String tokenId = trimToNull(winner.getTokenId());
        if (!StringUtils.hasText(tokenId) && StringUtils.hasText(outcome)) {
            tokenId = outcomeTokenIds.get(outcome);
        }
        if (!StringUtils.hasText(outcome) && StringUtils.hasText(tokenId)) {
            outcome = outcomeByToken(outcomeTokenIds, tokenId);
        }
        if (!StringUtils.hasText(outcome) && !StringUtils.hasText(tokenId)) {
            return manual("token_winner_missing_outcome_and_token");
        }
        return Resolution.builder()
                .resolutionStatus(STATUS_RESOLVED)
                .winningOutcome(outcome)
                .winningTokenId(tokenId)
                .resolutionSource("TOKEN_WINNER")
                .build();
    }

    private Resolution terminalUnresolved(Request request, String reason) {
        String normalizedReason = StringUtils.hasText(reason) ? reason : "terminal_without_unique_final_price";
        if (request.isClosed() || isUmaResolved(request.getUmaResolutionStatus())) {
            return manual(normalizedReason);
        }
        return Resolution.builder()
                .resolutionStatus(STATUS_WAIT_FINAL_PRICE)
                .manualCheckReason(normalizedReason)
                .build();
    }

    private Resolution unresolved(String reason) {
        return Resolution.builder()
                .resolutionStatus(STATUS_UNRESOLVED)
                .manualCheckReason(reason)
                .build();
    }

    private Resolution manual(String reason) {
        return Resolution.builder()
                .resolutionStatus(STATUS_NEED_MANUAL_CHECK)
                .manualCheckReason(reason)
                .build();
    }

    private FinalPriceOutcome resolveOutcomeFromFinalPrices(List<String> outcomes, List<String> outcomePrices) {
        if (outcomes == null || outcomePrices == null || outcomes.isEmpty() || outcomePrices.isEmpty()) {
            return FinalPriceOutcome.unresolved("missing_outcomes_or_prices");
        }
        int size = Math.min(outcomes.size(), outcomePrices.size());
        int winningIndex = -1;
        for (int i = 0; i < size; i++) {
            BigDecimal price = parsePrice(outcomePrices.get(i));
            if (price == null) {
                return FinalPriceOutcome.unresolved("invalid_outcome_price");
            }
            if (price.compareTo(new BigDecimal("0.999")) >= 0) {
                if (winningIndex >= 0) {
                    return FinalPriceOutcome.unresolved("multiple_final_price_winners");
                }
                winningIndex = i;
            } else if (price.compareTo(new BigDecimal("0.001")) > 0) {
                return FinalPriceOutcome.unresolved("non_binary_final_price");
            }
        }
        if (winningIndex < 0) {
            return FinalPriceOutcome.unresolved("no_final_price_winner");
        }
        String outcome = normalizeOutcome(outcomes.get(winningIndex));
        if (!StringUtils.hasText(outcome)) {
            return FinalPriceOutcome.unresolved("winning_outcome_blank");
        }
        return FinalPriceOutcome.resolved(outcome);
    }

    private boolean isTerminalMarketSignal(boolean closed,
                                           boolean acceptingOrdersPresent,
                                           boolean acceptingOrders,
                                           String umaResolutionStatus) {
        if (closed || (acceptingOrdersPresent && !acceptingOrders)) {
            return true;
        }
        return isUmaResolved(umaResolutionStatus);
    }

    private boolean isUmaResolved(String umaResolutionStatus) {
        if (!StringUtils.hasText(umaResolutionStatus)) {
            return false;
        }
        String normalized = umaResolutionStatus.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("resolved")
                || normalized.contains("settled")
                || normalized.contains("final");
    }

    private BigDecimal parsePrice(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String outcomeByToken(Map<String, String> outcomeTokenIds, String tokenId) {
        if (!StringUtils.hasText(tokenId) || outcomeTokenIds == null || outcomeTokenIds.isEmpty()) {
            return null;
        }
        String normalizedTokenId = tokenId.trim();
        for (Map.Entry<String, String> entry : outcomeTokenIds.entrySet()) {
            if (StringUtils.hasText(entry.getValue()) && normalizedTokenId.equals(entry.getValue().trim())) {
                return normalizeOutcome(entry.getKey());
            }
        }
        return null;
    }

    public String normalizeOutcome(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record FinalPriceOutcome(boolean isResolved, String winningOutcome, String reason) {
        private static FinalPriceOutcome resolved(String winningOutcome) {
            return new FinalPriceOutcome(true, winningOutcome, null);
        }

        private static FinalPriceOutcome unresolved(String reason) {
            return new FinalPriceOutcome(false, null, reason);
        }
    }

    @Data
    @Builder
    public static class Request {
        private boolean closed;
        private boolean acceptingOrders;
        private boolean acceptingOrdersPresent;
        private String umaResolutionStatus;
        private String explicitWinningTokenId;
        private String explicitWinnerOutcome;
        @Builder.Default
        private List<String> outcomes = List.of();
        @Builder.Default
        private List<String> outcomePrices = List.of();
        @Builder.Default
        private Map<String, String> outcomeTokenIds = new LinkedHashMap<>();
        @Builder.Default
        private List<TokenInfo> tokenWinners = List.of();
    }

    @Data
    @Builder
    public static class TokenInfo {
        private String outcome;
        private String tokenId;
        private boolean winner;
    }

    @Data
    @Builder
    public static class Resolution {
        private String resolutionStatus;
        private String winningOutcome;
        private String winningTokenId;
        private String resolutionSource;
        private String manualCheckReason;
    }
}
