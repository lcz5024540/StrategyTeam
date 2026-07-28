package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Public entry point for HTX spot execution.
 * <p>
 * Only present when {@link HtxApiService} is wired (see {@code HtxConfig}, which is itself
 * {@code @ConditionalOnExpression} on the AK/SK being set). If both keys are missing,
 * this bean does not exist and callers see a clean {@code NoSuchBeanDefinitionException}
 * instead of a runtime NPE.
 * <p>
 * See {@code HTX_SPOT_EXECUTION_SDK_V1.md} §3–§4 for architecture rationale.
 */
@Slf4j
@Service
@ConditionalOnBean(HtxApiService.class)
@RequiredArgsConstructor
public class HtxTradingService {

    private final HtxApiService htxApiService;
    private final HtxDepthGuard depthGuard;
    private final HtxLockedPairExecutor executor;
    private final HtxTradeProperties props;

    /**
     * Submit a locked-pair (buy + sell) intent. See design doc §4 for the state machine.
     * <p>
     * Pre-check ordering matters: cheapest checks first, then a network call for depth.
     * <ol>
     *   <li>Validate inputs (non-null, positive qty, buyPrice ≤ sellPrice).</li>
     *   <li>Verify min notional.</li>
     *   <li>Fetch depth once; verify both sides of the book.</li>
     *   <li>If {@code htx.trade.enabled=false} → log intended orders, return DRY_RUN_OK.</li>
     *   <li>Otherwise delegate to {@link HtxLockedPairExecutor}.</li>
     * </ol>
     */
    public HtxLockedPairResult submitLockedPair(HtxLockedPairRequest req) {
        HtxLockedPairResult invalid = validate(req);
        if (invalid != null) {
            return invalid;
        }
        BigDecimal minNotional = props.getDepthGuard().getMinQuoteNotional();
        BigDecimal buyNotional = req.getQuantity().multiply(req.getBuyPrice());
        BigDecimal sellNotional = req.getQuantity().multiply(req.getSellPrice());
        // v1.0.2 hotfix #3: check both legs against the floor. Previously only the sell side was
        // gated, so a wide spread (e.g. buyPrice=15000, sellPrice=25000) could pass here yet
        // fail HTX's server-side min-notional filter on the actual BUY submission.
        BigDecimal weakerLeg = buyNotional.min(sellNotional);
        if (weakerLeg.compareTo(minNotional) < 0) {
            log.warn("HTX pair rejected — weaker-leg notional {} (buy={} sell={}) below floor {}",
                    weakerLeg, buyNotional, sellNotional, minNotional);
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.REJECT_MIN_NOTIONAL)
                    .message("notional-below-floor:weaker=" + weakerLeg)
                    .build();
        }
        int topN = props.getDepthGuard().getTopN();
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(req.getSymbol(), topN);
        if (!depthResp.ok() || depthResp.payload() == null) {
            log.warn("HTX depth fetch failed for {}: {}", req.getSymbol(), depthResp.getErrMsg());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.DEPTH_FETCH_FAILED)
                    .message(depthResp.getErrMsg())
                    .build();
        }
        BigDecimal safety = req.getSafetyRatio() != null ? req.getSafetyRatio() : props.getDepthGuard().getSafetyRatio();
        HtxDepthGuard.Verdict v = depthGuard.check(depthResp.payload(), req.getQuantity(), safety, topN);
        if (!v.allOk()) {
            HtxLockedPairResult.Code code;
            if (!v.isBuyOk() && !v.isSellOk()) {
                code = HtxLockedPairResult.Code.REJECT_DEPTH_BOTH;
            } else if (!v.isBuyOk()) {
                code = HtxLockedPairResult.Code.REJECT_DEPTH_BUY;
            } else {
                code = HtxLockedPairResult.Code.REJECT_DEPTH_SELL;
            }
            return HtxLockedPairResult.builder()
                    .code(code)
                    .message(String.format("required=%s askSum=%s bidSum=%s",
                            v.getRequiredAmount(), v.getCumulativeAskAmount(), v.getCumulativeBidAmount()))
                    .build();
        }

        if (!props.getTrade().isEnabled()) {
            log.info("[HTX-DRY-RUN] would place BUY-LIMIT symbol={} qty={} price={} then SELL-LIMIT price={}",
                    req.getSymbol(), req.getQuantity(), req.getBuyPrice(), req.getSellPrice());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.DRY_RUN_OK)
                    .message("dry-run")
                    .build();
        }
        return executor.execute(req);
    }

    private HtxLockedPairResult validate(HtxLockedPairRequest req) {
        if (req == null || req.getSymbol() == null || req.getSymbol().isEmpty()
                || req.getQuantity() == null || req.getQuantity().signum() <= 0
                || req.getBuyPrice() == null || req.getSellPrice() == null) {
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.REJECT_INVALID_INPUT)
                    .message("null-or-non-positive-inputs")
                    .build();
        }
        if (req.getBuyPrice().compareTo(req.getSellPrice()) > 0) {
            log.warn("HTX pair rejected — buyPrice {} > sellPrice {}", req.getBuyPrice(), req.getSellPrice());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.REJECT_PRICE_INVERSION)
                    .message("buy>sell")
                    .build();
        }
        return null;
    }
}
