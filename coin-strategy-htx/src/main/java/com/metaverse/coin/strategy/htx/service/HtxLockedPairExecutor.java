package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Locked-pair 执行。所有真实下单前先 write-ahead 落库（见 spec §6）：
 * 买腿写库失败 → 抛 HtxPersistenceException（fail-closed，未发 REST）；
 * 卖腿写库/发单失败发生在买腿已成交之后，无法回滚，改为记 exposure 交由对账器市价平掉。
 * 卖量用 {@link HtxFeeMath#sellableBase} 扣掉买腿 base 手续费，消除超卖。
 */
@Slf4j
@RequiredArgsConstructor
public class HtxLockedPairExecutor {

    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;
    private final HtxTradeRecorder recorder;

    public HtxLockedPairResult execute(HtxLockedPairRequest req, long pairId) {
        String symbol = req.getSymbol();
        String qtyStr = req.getQuantity().toPlainString();
        String buyPriceStr = req.getBuyPrice().toPlainString();
        String sellPriceStr = req.getSellPrice().toPlainString();
        String accountId = props.getApi().getSpotAccountId();
        long timeoutMs = req.getWaitFillTimeoutMs() != null ? req.getWaitFillTimeoutMs() : props.getTrade().getFillTimeoutMs();
        String buyClientId = derive(req.getClientOrderId(), "BUY");
        String sellClientId = derive(req.getClientOrderId(), "SELL");

        // ---- Leg 1: BUY write-ahead（失败抛异常 → TradingService 映射 PERSIST_WRITE_FAILED） ----
        long buyRow = recorder.insertOrderIntent(pairId, buyClientId, "buy", "buy-limit", req.getBuyPrice(), req.getQuantity());

        HtxRestResponse<String> buyResp = htxApiService.getSpotService()
                .placeOrder(accountId, symbol, "buy-limit", qtyStr, buyPriceStr, buyClientId);
        Long buyId = parseOrderId(buyResp);
        if (buyId == null) {
            recorder.markOrderSendFailed(buyRow, buyResp.getErrMsg());
            recorder.updatePairStatus(pairId, HtxStatus.BUY_REJECTED, "BUY_ORDER_REJECTED", buyResp.getErrMsg());
            log.warn("HTX BUY rejected for {}: {}", symbol, buyResp.getErrMsg());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.BUY_ORDER_REJECTED)
                    .message(buyResp.getErrCode() + ":" + buyResp.getErrMsg()).build();
        }
        recorder.markOrderSent(buyRow, buyId);
        recorder.updatePairStatus(pairId, HtxStatus.BUY_SENT, null, null);

        // ---- Wait BUY terminal ----
        FillOutcome buyOutcome = waitForFill(buyId, timeoutMs);
        if (buyOutcome.order != null) {
            recorder.updateOrderFill(buyRow, symbol, "buy", buyOutcome.order, JSON.toJSONString(buyOutcome.order));
        }
        if (buyOutcome.infraFailure) {
            recorder.updatePairStatus(pairId, HtxStatus.BUY_SENT, "QUERY_INFRA_ERROR", "buy-query-infra");
            log.error("HTX BUY {} query infra failure ({}); refusing to cancel", buyId, buyOutcome.consecutiveFailures);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.QUERY_INFRA_ERROR)
                    .buyOrderId(buyId).message("query-infra-fail:" + buyOutcome.consecutiveFailures).build();
        }
        if (buyOutcome.terminalRejected) {
            recorder.updatePairStatus(pairId, HtxStatus.BUY_REJECTED, "BUY_ORDER_REJECTED", "terminal:" + buyOutcome.finalState);
            log.warn("HTX BUY {} terminal without fill: {}", buyId, buyOutcome.finalState);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.BUY_ORDER_REJECTED)
                    .buyOrderId(buyId).message("terminal-state:" + buyOutcome.finalState).build();
        }
        if (!buyOutcome.filled && (buyOutcome.filledAmount == null || buyOutcome.filledAmount.signum() == 0)) {
            log.warn("HTX BUY {} not filled within {}ms; cancelling", buyId, timeoutMs);
            HtxRestResponse<String> cancelResp = htxApiService.getSpotService().cancelOrder(buyId);
            HtxRestResponse<HtxOrder> postCancel = htxApiService.getSpotService().queryOrder(buyId);
            BigDecimal postFilled = BigDecimal.ZERO;
            String postState = null;
            if (postCancel.ok() && postCancel.getData() != null) {
                postState = postCancel.getData().getState();
                postFilled = postCancel.getData().getFilledAmount() == null ? BigDecimal.ZERO : postCancel.getData().getFilledAmount();
            }
            recorder.recordCancel(buyRow, buyId, "buy-timeout", cancelResp.ok(), postState, postFilled);
            if (postFilled.signum() > 0) {
                log.warn("HTX BUY {} cancel race: filled={} — recovering with SELL", buyId, postFilled);
                buyOutcome.filledAmount = postFilled;
                buyOutcome.filled = false;
                if (postCancel.getData() != null) {
                    buyOutcome.order = postCancel.getData();
                    recorder.updateOrderFill(buyRow, symbol, "buy", buyOutcome.order, JSON.toJSONString(buyOutcome.order));
                }
                // fall through to partial recovery
            } else {
                recorder.updatePairStatus(pairId, HtxStatus.BUY_TIMEOUT, "BUY_WAIT_TIMEOUT", "timeout");
                return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.BUY_WAIT_TIMEOUT)
                        .buyOrderId(buyId).buyFilledAmount(BigDecimal.ZERO).message("timeout").build();
            }
        }

        // BUY produced fills.
        BigDecimal filledBase = buyOutcome.filled ? req.getQuantity() : buyOutcome.filledAmount;
        BigDecimal buyFeeBase = buyOutcome.order != null && buyOutcome.order.getFilledFees() != null
                ? buyOutcome.order.getFilledFees() : BigDecimal.ZERO;
        BigDecimal buyCash = buyOutcome.order != null && buyOutcome.order.getFilledCashAmount() != null
                ? buyOutcome.order.getFilledCashAmount() : BigDecimal.ZERO;
        boolean partial = !buyOutcome.filled;
        recorder.updatePairBuy(pairId, buyId, filledBase);
        if (partial) {
            HtxRestResponse<String> c = htxApiService.getSpotService().cancelOrder(buyId);
            recorder.recordCancel(buyRow, buyId, "partial-recover", c.ok(), null, filledBase);
            log.warn("HTX BUY {} partial-filled {}; cancelled remainder", buyId, filledBase);
        }

        // 费用感知卖量：扣掉买腿 base 手续费，消除超卖。
        BigDecimal sellable = HtxFeeMath.sellableBase(filledBase, buyFeeBase);

        // ---- Leg 2: SELL write-ahead（买腿已成交，写库失败→记 exposure，不抛） ----
        long sellRow;
        try {
            sellRow = recorder.insertOrderIntent(pairId, sellClientId, "sell", "sell-limit", req.getSellPrice(), sellable);
        } catch (RuntimeException persistEx) {
            recorder.recordExposure(pairId, symbol, HtxStatus.EXPOSED_SIDE_BASE_LONG, sellable, "sell-write-ahead-failed:" + persistEx.getMessage());
            recorder.updatePairStatus(pairId, HtxStatus.EXPOSED, "FAIL_AFTER_BUY_FILL", "sell-persist-failed");
            log.error("HTX SELL write-ahead failed after buy filled={}; exposure recorded. buyId={}", sellable, buyId, persistEx);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId).buyFilledAmount(filledBase).message("sell-persist-failed").build();
        }

        HtxRestResponse<String> sellResp = htxApiService.getSpotService()
                .placeOrder(accountId, symbol, "sell-limit", sellable.toPlainString(), sellPriceStr, sellClientId);
        Long sellId = parseOrderId(sellResp);
        if (sellId == null) {
            recorder.markOrderSendFailed(sellRow, sellResp.getErrMsg());
            recorder.recordExposure(pairId, symbol, HtxStatus.EXPOSED_SIDE_BASE_LONG, sellable, "sell-rejected:" + sellResp.getErrMsg());
            recorder.updatePairStatus(pairId, HtxStatus.EXPOSED, "FAIL_AFTER_BUY_FILL", "sell-rejected");
            log.error("HTX SELL rejected after buy filled={} — exposure recorded. buyId={} err={}", sellable, buyId, sellResp.getErrMsg());
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId).buyFilledAmount(filledBase).message("sell-rejected:" + sellResp.getErrMsg()).build();
        }
        recorder.markOrderSent(sellRow, sellId);
        recorder.updatePairSell(pairId, sellId, BigDecimal.ZERO);

        // ---- Wait SELL terminal ----
        FillOutcome sellOutcome = waitForFill(sellId, timeoutMs);
        if (sellOutcome.order != null) {
            recorder.updateOrderFill(sellRow, symbol, "sell", sellOutcome.order, JSON.toJSONString(sellOutcome.order));
        }
        BigDecimal sellFilled = sellOutcome.filledAmount == null ? BigDecimal.ZERO : sellOutcome.filledAmount;
        BigDecimal sellFeeQuote = sellOutcome.order != null && sellOutcome.order.getFilledFees() != null
                ? sellOutcome.order.getFilledFees() : BigDecimal.ZERO;
        BigDecimal sellCash = sellOutcome.order != null && sellOutcome.order.getFilledCashAmount() != null
                ? sellOutcome.order.getFilledCashAmount() : BigDecimal.ZERO;

        if (sellOutcome.infraFailure) {
            recorder.updatePairStatus(pairId, HtxStatus.SELL_SENT, "QUERY_INFRA_ERROR", "sell-query-infra");
            log.error("HTX SELL {} query infra failure; buy filled={}", sellId, filledBase);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.QUERY_INFRA_ERROR)
                    .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase)
                    .message("sell-query-infra-fail:" + sellOutcome.consecutiveFailures).build();
        }
        if (sellOutcome.terminalRejected) {
            recorder.recordExposure(pairId, symbol, HtxStatus.EXPOSED_SIDE_BASE_LONG, sellable.subtract(sellFilled), "sell-terminal:" + sellOutcome.finalState);
            recorder.updatePairStatus(pairId, HtxStatus.EXPOSED, "FAIL_AFTER_BUY_FILL", "sell-terminal:" + sellOutcome.finalState);
            log.error("HTX SELL {} rejected after placement — exposure recorded. buyId={}", sellId, buyId);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase).sellFilledAmount(sellFilled)
                    .message("sell-terminal:" + sellOutcome.finalState).build();
        }
        if (!sellOutcome.filled) {
            BigDecimal unsold = sellable.subtract(sellFilled);
            if (unsold.signum() > 0) {
                recorder.recordExposure(pairId, symbol, HtxStatus.EXPOSED_SIDE_BASE_LONG, unsold, "sell-still-open");
            }
            recorder.updatePairStatus(pairId, HtxStatus.SELL_PENDING,
                    partial ? "BUY_PARTIAL_FILL_RECOVERED" : "SELL_PENDING", partial ? "buy-partial-sell-open" : "sell-open");
            HtxLockedPairResult.Code code = partial
                    ? HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED : HtxLockedPairResult.Code.SELL_PENDING;
            log.warn("HTX SELL {} not fully filled (filled={}); {}", sellId, sellFilled, code);
            return HtxLockedPairResult.builder().code(code)
                    .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase).sellFilledAmount(sellFilled)
                    .message(partial ? "buy-partial-sell-still-open" : "sell-still-open").build();
        }

        // Happy path: both filled → finalize with net accounting.
        BigDecimal netBaseDelta = sellable.subtract(sellFilled);
        BigDecimal netQuoteCost = sellCash.subtract(sellFeeQuote).subtract(buyCash);
        HtxLockedPairResult.Code okCode = partial
                ? HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED : HtxLockedPairResult.Code.OK;
        recorder.finalizePair(pairId, netBaseDelta, buyFeeBase, sellFeeQuote, netQuoteCost, okCode.name(),
                partial ? "buy-partial-sell-full" : "ok");
        log.info("HTX locked-pair {}: symbol={} buyId={} sellId={} sellable={} netBase={} netQuote={} feeBase={} feeQuote={}",
                okCode, symbol, buyId, sellId, sellable, netBaseDelta, netQuoteCost, buyFeeBase, sellFeeQuote);
        return HtxLockedPairResult.builder().code(okCode)
                .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase).sellFilledAmount(sellFilled)
                .message(partial ? "buy-partial-sell-full" : "ok").build();
    }

    private FillOutcome waitForFill(long orderId, long timeoutMs) {
        long start = System.currentTimeMillis();
        FillOutcome out = new FillOutcome();
        int threshold = props.getTrade().getQueryFailureThreshold();
        while (System.currentTimeMillis() - start < timeoutMs) {
            HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(orderId);
            if (resp.ok() && resp.getData() != null) {
                out.consecutiveFailures = 0;
                HtxOrder o = resp.getData();
                out.order = o;
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
        HtxOrder order;
    }
}
