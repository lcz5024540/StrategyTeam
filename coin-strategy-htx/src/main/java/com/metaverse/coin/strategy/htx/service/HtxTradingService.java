package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
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
    private final HtxTradeRecorder recorder;

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

        boolean dryRun = !props.getTrade().isEnabled();
        if (dryRun) {
            log.info("[HTX-DRY-RUN] would place BUY-LIMIT symbol={} qty={} price={} then SELL-LIMIT price={}",
                    req.getSymbol(), req.getQuantity(), req.getBuyPrice(), req.getSellPrice());
            // dry-run 也留审计轨迹；best-effort，绝不因写库失败中断
            try {
                recorder.createPair(req, true);
            } catch (RuntimeException e) {
                log.warn("dry-run pair persist failed (ignored): {}", e.getMessage());
            }
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.DRY_RUN_OK)
                    .message("dry-run")
                    .build();
        }

        // ── 实盘预检：enabled=true 时 spot-account-id 必须非空 ──
        String acct = props.getApi().getSpotAccountId();
        if (acct == null || acct.trim().isEmpty()) {
            log.error("HTX preflight failed: trade.enabled=true but api.spot-account-id is empty; refuse to trade");
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.REJECT_PREFLIGHT)
                    .message("preflight: spot-account-id empty while enabled")
                    .build();
        }

        // ── 风控：单笔交易额上限（quote 名义）。取买/卖两腿较大者，确保任一腿都不超限 ──
        BigDecimal maxSingle = props.getRisk().getMaxSingleQuoteNotional();
        BigDecimal strongerLeg = buyNotional.max(sellNotional);
        if (maxSingle != null && maxSingle.signum() > 0 && strongerLeg.compareTo(maxSingle) > 0) {
            log.warn("HTX pair rejected — single-trade notional {} (buy={} sell={}) exceeds cap {}",
                    strongerLeg, buyNotional, sellNotional, maxSingle);
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.REJECT_MAX_SINGLE_NOTIONAL)
                    .message("single-notional-above-cap:stronger=" + strongerLeg + " cap=" + maxSingle)
                    .build();
        }

        // ── 风控：当日累计交易额上限（quote 名义）。已用 + 本单买腿名义 > 上限则拒单 ──
        // 读取失败 fail-closed（无法确认未超限，宁拒单不冒险）。累计与本单口径均用买腿名义（=投入资金）。
        BigDecimal maxDaily = props.getRisk().getMaxDailyQuoteNotional();
        if (maxDaily != null && maxDaily.signum() > 0) {
            BigDecimal usedToday;
            try {
                usedToday = recorder.sumTodayLiveQuoteNotional();
            } catch (HtxPersistenceException e) {
                log.error("HTX fail-closed: cannot read today's cumulative notional, refuse to trade: {}",
                        e.getMessage());
                return HtxLockedPairResult.builder()
                        .code(HtxLockedPairResult.Code.REJECT_MAX_DAILY_NOTIONAL)
                        .message("daily-notional-read-failed (fail-closed): " + e.getMessage())
                        .build();
            }
            BigDecimal projected = usedToday.add(buyNotional);
            if (projected.compareTo(maxDaily) > 0) {
                log.warn("HTX pair rejected — daily cumulative notional {} (used={} + this={}) exceeds cap {}",
                        projected, usedToday, buyNotional, maxDaily);
                return HtxLockedPairResult.builder()
                        .code(HtxLockedPairResult.Code.REJECT_MAX_DAILY_NOTIONAL)
                        .message("daily-notional-above-cap:projected=" + projected + " cap=" + maxDaily)
                        .build();
            }
        }

        // ── 实盘 write-ahead fail-closed：写不进库就拒单，不发任何真实订单 ──
        long pairId;
        try {
            pairId = recorder.createPair(req, false);
        } catch (HtxPersistenceException e) {
            log.error("HTX fail-closed: cannot persist pair intent, refuse to trade: {}", e.getMessage());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.PERSIST_WRITE_FAILED)
                    .message("db write-ahead failed: " + e.getMessage())
                    .build();
        }
        // ── 买腿 write-ahead 同样 fail-closed：executor 落库失败在发 REST 前抛出，映射为 PERSIST_WRITE_FAILED ──
        try {
            return executor.execute(req, pairId);
        } catch (HtxPersistenceException e) {
            log.error("HTX fail-closed: cannot persist buy-leg intent, refuse to trade: {}", e.getMessage());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.PERSIST_WRITE_FAILED)
                    .message("db write-ahead failed: " + e.getMessage())
                    .build();
        }
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
