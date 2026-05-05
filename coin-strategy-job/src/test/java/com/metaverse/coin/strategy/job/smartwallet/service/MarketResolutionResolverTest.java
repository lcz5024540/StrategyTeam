package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarketResolutionResolverTest {

    private final MarketResolutionResolver resolver = new MarketResolutionResolver();

    @Test
    void shouldResolveFromTokenWinnerFirst() {
        MarketResolutionResolver.Resolution resolution = resolver.resolve(baseRequest()
                .tokenWinners(List.of(
                        MarketResolutionResolver.TokenInfo.builder().outcome("Yes").tokenId("yes-token").winner(true).build(),
                        MarketResolutionResolver.TokenInfo.builder().outcome("No").tokenId("no-token").winner(false).build()))
                .explicitWinningTokenId("no-token")
                .outcomePrices(List.of("0", "1"))
                .build());

        assertEquals(MarketResolutionResolver.STATUS_RESOLVED, resolution.getResolutionStatus());
        assertEquals("YES", resolution.getWinningOutcome());
        assertEquals("yes-token", resolution.getWinningTokenId());
        assertEquals("TOKEN_WINNER", resolution.getResolutionSource());
    }

    @Test
    void shouldResolveTopLevelWinningTokenIdToOutcome() {
        MarketResolutionResolver.Resolution resolution = resolver.resolve(baseRequest()
                .explicitWinningTokenId("no-token")
                .build());

        assertEquals(MarketResolutionResolver.STATUS_RESOLVED, resolution.getResolutionStatus());
        assertEquals("NO", resolution.getWinningOutcome());
        assertEquals("no-token", resolution.getWinningTokenId());
        assertEquals("WINNING_TOKEN_ID", resolution.getResolutionSource());
    }

    @Test
    void shouldInferYesNoAndOverUnderFromFinalPrices() {
        MarketResolutionResolver.Resolution yesNo = resolver.resolve(baseRequest()
                .outcomePrices(List.of("1", "0"))
                .build());
        MarketResolutionResolver.Resolution overUnder = resolver.resolve(MarketResolutionResolver.Request.builder()
                .closed(true)
                .acceptingOrders(false)
                .acceptingOrdersPresent(true)
                .outcomes(List.of("Over", "Under"))
                .outcomePrices(List.of("0", "1"))
                .outcomeTokenIds(Map.of("OVER", "over-token", "UNDER", "under-token"))
                .build());

        assertEquals("YES", yesNo.getWinningOutcome());
        assertEquals("yes-token", yesNo.getWinningTokenId());
        assertEquals("FINAL_PRICE_INFERENCE", yesNo.getResolutionSource());
        assertEquals("UNDER", overUnder.getWinningOutcome());
        assertEquals("under-token", overUnder.getWinningTokenId());
    }

    @Test
    void shouldRequireManualCheckForTerminalNonBinaryOrUnmappedFinalPrices() {
        MarketResolutionResolver.Resolution nonBinary = resolver.resolve(baseRequest()
                .outcomePrices(List.of("0.5", "0.5"))
                .build());
        MarketResolutionResolver.Resolution unmapped = resolver.resolve(MarketResolutionResolver.Request.builder()
                .closed(true)
                .acceptingOrders(false)
                .acceptingOrdersPresent(true)
                .outcomes(List.of("Yes", "No"))
                .outcomePrices(List.of("1", "0"))
                .outcomeTokenIds(Map.of())
                .build());

        assertEquals(MarketResolutionResolver.STATUS_NEED_MANUAL_CHECK, nonBinary.getResolutionStatus());
        assertEquals("non_binary_final_price", nonBinary.getManualCheckReason());
        assertNull(nonBinary.getWinningTokenId());
        assertEquals(MarketResolutionResolver.STATUS_NEED_MANUAL_CHECK, unmapped.getResolutionStatus());
        assertEquals("final_price_winning_token_unmapped", unmapped.getManualCheckReason());
    }

    @Test
    void shouldWaitForFinalPriceWhenOnlyAcceptingOrdersStopped() {
        MarketResolutionResolver.Resolution resolution = resolver.resolve(baseRequest()
                .closed(false)
                .acceptingOrders(false)
                .acceptingOrdersPresent(true)
                .outcomePrices(List.of("0.5", "0.5"))
                .build());

        assertEquals(MarketResolutionResolver.STATUS_WAIT_FINAL_PRICE, resolution.getResolutionStatus());
    }

    private MarketResolutionResolver.Request.RequestBuilder baseRequest() {
        return MarketResolutionResolver.Request.builder()
                .closed(true)
                .acceptingOrders(false)
                .acceptingOrdersPresent(true)
                .outcomes(List.of("Yes", "No"))
                .outcomePrices(List.of("0.5", "0.5"))
                .outcomeTokenIds(Map.of("YES", "yes-token", "NO", "no-token"));
    }
}
