package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Executes the "locked-pair" leg sequence when live trading is enabled.
 * <p>
 * v1.0.1 hotfixes applied (see review of v1 code):
 * <ul>
 *   <li><b>#1 partial-filled recovery</b> — on buy timeout, re-query one last time and read
 *       {@code filledAmount}. If &gt; 0, cancel the remainder and place the sell leg for the
 *       filled quantity instead of returning a bare TIMEOUT that would leave a naked buy.</li>
 *   <li><b>#2 sell-leg fill confirmation</b> — after placing sell-limit, poll until terminal.
 *       Only return {@code OK} when both legs are actually filled. If sell does not terminate
 *       within the timeout, return {@code SELL_PENDING} with the sellOrderId so the caller can
 *       track / cancel / hedge externally.</li>
 *   <li><b>#4 place-order returns String</b> — HTX's {@code data} is a JSON string; parseLong here.</li>
 *   <li><b>#7 clientOrderId idempotency</b> — derive {@code $key-BUY} / {@code $key-SELL}
 *       from the caller's clientOrderId so a network-triggered retry hits HTX's dedup rather
 *       than double-buying.</li>
 * </ul>
 * Contract with {@link HtxTradingService}: caller has already verified {@code buyPrice <= sellPrice}
 * and depth. This class only runs the imperative side.
 */
@Slf4j
@RequiredArgsConstructor
public class HtxLockedPairExecutor {

    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;

    public HtxLockedPairResult execute(HtxLockedPairRequest req) {
        String symbol = req.getSymbol();
        String qtyStr = req.getQuantity().toPlainString();
        String buyPriceStr = req.getBuyPrice().toPlainString();
        String sellPriceStr = req.getSellPrice().toPlainString();
        String accountId = props.getApi().getSpotAccountId();
        long timeoutMs = req.getWaitFillTimeoutMs() != null ? req.getWaitFillTimeoutMs() : props.getTrade().getFillTimeoutMs();
        String buyClientId = derive(req.getClientOrderId(), "BUY");
        String sellClientId = derive(req.getClientOrderId(), "SELL");

        // ---- Leg 1: BUY ----
        HtxRestResponse<String> buyResp = htxApiService.getSpotService()
                .placeOrder(accountId, symbol, "buy-limit", qtyStr, buyPriceStr, buyClientId);
        Long buyId = parseOrderId(buyResp);
        if (buyId == null) {
            log.warn("HTX BUY rejected for {}: {}", symbol, buyResp.getErrMsg());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.BUY_ORDER_REJECTED)
                    .message(buyResp.getErrCode() + ":" + buyResp.getErrMsg())
                    .build();
        }

        // ---- Wait for BUY terminal (filled or partial-canceled-with-fills) ----
        FillOutcome buyOutcome = waitForFill(buyId, timeoutMs);
        if (buyOutcome.infraFailure) {
            log.error("HTX BUY {} queryOrder infra failure ({} consecutive failures); refusing to cancel",
                    buyId, buyOutcome.consecutiveFailures);
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.QUERY_INFRA_ERROR)
                    .buyOrderId(buyId)
                    .message("query-infra-fail:" + buyOutcome.consecutiveFailures)
                    .build();
        }
        if (buyOutcome.terminalRejected) {
            log.warn("HTX BUY {} terminal without fill: state={}", buyId, buyOutcome.finalState);
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.BUY_ORDER_REJECTED)
                    .buyOrderId(buyId)
                    .message("terminal-state:" + buyOutcome.finalState)
                    .build();
        }
        if (!buyOutcome.filled && (buyOutcome.filledAmount == null || buyOutcome.filledAmount.signum() == 0)) {
            log.warn("HTX BUY {} not filled within {}ms; cancelling (0 fills)", buyId, timeoutMs);
            htxApiService.getSpotService().cancelOrder(buyId);
            // v1.0.2 hotfix #6: re-query after cancel. Race: order may have filled between our
            // last poll and our cancel. Trust the post-cancel state over "we asked to cancel".
            HtxRestResponse<HtxOrder> postCancel = htxApiService.getSpotService().queryOrder(buyId);
            if (postCancel.ok() && postCancel.getData() != null) {
                HtxOrder o = postCancel.getData();
                BigDecimal actuallyFilled = o.getFilledAmount();
                if (actuallyFilled != null && actuallyFilled.signum() > 0) {
                    log.warn("HTX BUY {} cancel race: state={} filled={} — recovering with SELL", buyId, o.getState(), actuallyFilled);
                    // Always treat cancel-race recovery as partial: use the actually-filled amount,
                    // never the requested qty. Even if state=filled, HTX may have reduced the executed
                    // amount post-cancel; downstream must hedge only what settled.
                    buyOutcome.filledAmount = actuallyFilled;
                    buyOutcome.filled = false;
                    // fall through to partial-fill recovery
                } else {
                    return HtxLockedPairResult.builder()
                            .code(HtxLockedPairResult.Code.BUY_WAIT_TIMEOUT)
                            .buyOrderId(buyId)
                            .buyFilledAmount(BigDecimal.ZERO)
                            .message("timeout")
                            .build();
                }
            } else {
                return HtxLockedPairResult.builder()
                        .code(HtxLockedPairResult.Code.BUY_WAIT_TIMEOUT)
                        .buyOrderId(buyId)
                        .buyFilledAmount(BigDecimal.ZERO)
                        .message("timeout")
                        .build();
            }
        }

        // BUY produced some fills: either fully filled, or partial + timeout.
        BigDecimal filledForSell = buyOutcome.filled ? req.getQuantity() : buyOutcome.filledAmount;
        boolean partial = !buyOutcome.filled;
        if (partial) {
            log.warn("HTX BUY {} partial-filled at timeout: filled={} of {}; cancelling remainder and hedging with SELL",
                    buyId, filledForSell, qtyStr);
            htxApiService.getSpotService().cancelOrder(buyId);
        }

        // ---- Leg 2: SELL (for the actually-filled base amount) ----
        HtxRestResponse<String> sellResp = htxApiService.getSpotService()
                .placeOrder(accountId, symbol, "sell-limit", filledForSell.toPlainString(), sellPriceStr, sellClientId);
        Long sellId = parseOrderId(sellResp);
        if (sellId == null) {
            log.error("HTX SELL rejected AFTER buy filled={} — single leg exposed. buyId={} err={}",
                    filledForSell, buyId, sellResp.getErrMsg());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId)
                    .buyFilledAmount(filledForSell)
                    .message("sell-rejected:" + sellResp.getErrMsg())
                    .build();
        }

        // ---- Wait for SELL terminal ----
        FillOutcome sellOutcome = waitForFill(sellId, timeoutMs);
        if (sellOutcome.infraFailure) {
            log.error("HTX SELL {} queryOrder infra failure ({} consecutive failures); buy already filled={}",
                    sellId, sellOutcome.consecutiveFailures, filledForSell);
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.QUERY_INFRA_ERROR)
                    .buyOrderId(buyId)
                    .sellOrderId(sellId)
                    .buyFilledAmount(filledForSell)
                    .message("sell-query-infra-fail:" + sellOutcome.consecutiveFailures)
                    .build();
        }
        if (sellOutcome.terminalRejected) {
            log.error("HTX SELL {} rejected after placement (buyId={} filled={}) — single leg exposed.",
                    sellId, buyId, filledForSell);
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId)
                    .sellOrderId(sellId)
                    .buyFilledAmount(filledForSell)
                    .sellFilledAmount(sellOutcome.filledAmount)
                    .message("sell-terminal:" + sellOutcome.finalState)
                    .build();
        }
        if (!sellOutcome.filled) {
            log.warn("HTX SELL {} not fully filled within {}ms (filledSoFar={}); returning SELL_PENDING",
                    sellId, timeoutMs, sellOutcome.filledAmount);
            HtxLockedPairResult.Code code = partial
                    ? HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED
                    : HtxLockedPairResult.Code.SELL_PENDING;
            return HtxLockedPairResult.builder()
                    .code(code)
                    .buyOrderId(buyId)
                    .sellOrderId(sellId)
                    .buyFilledAmount(filledForSell)
                    .sellFilledAmount(sellOutcome.filledAmount == null ? BigDecimal.ZERO : sellOutcome.filledAmount)
                    .message(partial ? "buy-partial-sell-still-open" : "sell-still-open")
                    .build();
        }

        // Happy path: both legs terminal-filled.
        HtxLockedPairResult.Code okCode = partial
                ? HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED
                : HtxLockedPairResult.Code.OK;
        log.info("HTX locked-pair {}: symbol={} buyId={} sellId={} filled={} buy={} sell={}",
                okCode, symbol, buyId, sellId, filledForSell, buyPriceStr, sellPriceStr);
        return HtxLockedPairResult.builder()
                .code(okCode)
                .buyOrderId(buyId)
                .sellOrderId(sellId)
                .buyFilledAmount(filledForSell)
                .sellFilledAmount(filledForSell)
                .message(partial ? "buy-partial-sell-full" : "ok")
                .build();
    }

    /**
     * Poll queryOrder until the order reaches a fully-filled state, a terminal rejected state,
     * the timeout elapses, or the circuit breaker (v1.0.2 hotfix #5) trips on consecutive
     * infrastructure failures. Returns whatever fills accumulated.
     */
    private FillOutcome waitForFill(long orderId, long timeoutMs) {
        long start = System.currentTimeMillis();
        FillOutcome out = new FillOutcome();
        int threshold = props.getTrade().getQueryFailureThreshold();
        while (System.currentTimeMillis() - start < timeoutMs) {
            HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(orderId);
            if (resp.ok() && resp.getData() != null) {
                out.consecutiveFailures = 0;
                HtxOrder o = resp.getData();
                out.finalState = o.getState();
                out.filledAmount = o.getFilledAmount();
                if ("filled".equalsIgnoreCase(out.finalState)) {
                    out.filled = true;
                    return out;
                }
                if ("canceled".equalsIgnoreCase(out.finalState)
                        || "partial-canceled".equalsIgnoreCase(out.finalState)
                        || "rejected".equalsIgnoreCase(out.finalState)) {
                    boolean anyFill = out.filledAmount != null && out.filledAmount.signum() > 0;
                    if (!anyFill) {
                        out.terminalRejected = true;
                    }
                    return out;
                }
            } else {
                out.consecutiveFailures++;
                if (out.consecutiveFailures >= threshold) {
                    out.infraFailure = true;
                    return out;
                }
            }
            sleep(props.getTrade().getFillPollIntervalMs());
        }
        return out;
    }

    private static Long parseOrderId(HtxRestResponse<String> resp) {
        if (!resp.ok() || resp.getData() == null || resp.getData().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(resp.getData().trim());
        } catch (NumberFormatException e) {
            log.warn("HTX returned non-numeric order id: {}", resp.getData());
            return null;
        }
    }

    private static String derive(String base, String suffix) {
        if (base == null || base.isEmpty()) {
            return null;
        }
        // HTX caps client-order-id at 64 chars; truncate defensively.
        String combined = base + "-" + suffix;
        return combined.length() > 64 ? combined.substring(0, 64) : combined;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class FillOutcome {
        boolean filled;
        boolean terminalRejected;
        boolean infraFailure;
        int consecutiveFailures;
        BigDecimal filledAmount;
        String finalState;
    }
}
