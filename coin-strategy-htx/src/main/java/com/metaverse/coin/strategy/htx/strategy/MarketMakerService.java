package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxFeeMath;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 做市服务：开仓决策 + 买→挂卖→完成状态机 + 重启恢复。见 spec 2026-08-06-htx-market-maker-design.md §3/§4/§6。
 * 非阻塞：runOnce 每 tick 先推进 open cell，再按白名单各尝试开一个新 cell。
 * 买腿写前 fail-closed（createMmCell 抛异常即未发单）；买腿成交后卖腿失败 → 记 exposure 交对账器平，关闭 cell 防重复挂卖。
 */
@Slf4j
@Service
@ConditionalOnBean(HtxApiService.class)
@RequiredArgsConstructor
public class MarketMakerService {

    private final HtxApiService htxApiService;
    private final HtxDepthGuard depthGuard;
    private final HtxTradeProperties tradeProps;
    private final HtxMarketMakerProperties mmProps;
    private final HtxTradeRecorder recorder;

    private static final int DEPTH_LEVELS = 5;

    /** 启动校验：δ < 单边费率则成交必亏，拒绝启动。见 spec §1 不亏下限。 */
    @PostConstruct
    void validateConfig() {
        if (mmProps.getSpreadPct().compareTo(mmProps.getFeeRatePerSide()) < 0) {
            throw new IllegalStateException("MM spread-pct (" + mmProps.getSpreadPct()
                    + ") must be >= fee-rate-per-side (" + mmProps.getFeeRatePerSide()
                    + "): filling would lose money");
        }
    }

    // ---- 编排（D3） ----

    /** 主循环一次：先推进所有 open cell，再（若未急停）按白名单各尝试开一个新 cell。 */
    public void runOnce() {
        for (MarketMakerCell cell : loadOpenCells()) {
            try {
                advanceCell(cell);
            } catch (RuntimeException ex) {
                log.error("MM advanceCell failed pairId={}", cell.getPairId(), ex);
            }
        }
        if (!mmProps.isEnabled()) {
            return; // 急停：只停开新，不停推进
        }
        for (String symbol : mmProps.getSymbols()) {
            try {
                openNewCellIfEligible(symbol);
            } catch (RuntimeException ex) {
                log.error("MM openNewCell failed symbol={}", symbol, ex);
            }
        }
    }

    /** 重启恢复：库里所有 open cell 拉回并对齐一次（幂等）。 */
    public void recoverOpenCells() {
        for (MarketMakerCell cell : loadOpenCells()) {
            try {
                advanceCell(cell);
            } catch (RuntimeException ex) {
                log.error("MM recover advanceCell failed pairId={}", cell.getPairId(), ex);
            }
        }
    }

    private List<MarketMakerCell> loadOpenCells() {
        return recorder.findAllOpenMmCells().stream()
                .map(MarketMakerCell::fromEntity)
                .collect(Collectors.toList());
    }

    // ---- 开仓（D1） ----

    /** 通过所有闸门则开一个新 cell 并发买单，返回 pairId；任一闸门不过返回 -1。 */
    public long openNewCellIfEligible(String symbol) {
        if (!mmProps.getSymbols().contains(symbol)) {
            return -1;
        }
        if (recorder.countOpenMmCells(symbol) >= mmProps.getMaxConcurrentCellsPerSymbol()) {
            return -1;
        }
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(symbol, DEPTH_LEVELS);
        if (depthResp == null || !depthResp.ok() || depthResp.payload() == null) {
            return -1;
        }
        HtxDepth depth = depthResp.payload();
        if (isEmpty(depth.getBids()) || isEmpty(depth.getAsks())) {
            return -1;
        }
        BigDecimal bestBid = depth.getBids().get(0).getPrice();
        BigDecimal bestAsk = depth.getAsks().get(0).getPrice();
        if (bestBid == null || bestAsk == null || bestBid.signum() <= 0 || bestAsk.signum() <= 0) {
            return -1;
        }
        BigDecimal buyPrice = bestBid.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal sellPrice = MarketMakerMath.sellFromBuy(buyPrice, mmProps.getSpreadPct());
        BigDecimal qty = MarketMakerMath.qtyFor(mmProps.getPerCellQuoteNotional(), buyPrice);
        if (qty.signum() <= 0) {
            return -1;
        }
        if (!MarketMakerMath.isNoLoss(buyPrice, sellPrice, mmProps.getFeeRatePerSide())) {
            log.warn("MM no-loss gate blocked symbol={} buy={} sell={}", symbol, buyPrice, sellPrice);
            return -1;
        }
        List<BigDecimal> existingBuys = recorder.findOpenMmCells(symbol).stream()
                .map(HtxLockedPairEntity::getBuyPrice)
                .collect(Collectors.toList());
        if (!MarketMakerMath.farEnough(buyPrice, existingBuys, mmProps.getMinGapPct())) {
            return -1;
        }
        BigDecimal buyNotional = qty.multiply(buyPrice);
        BigDecimal sellNotional = qty.multiply(sellPrice);
        BigDecimal maxSingle = tradeProps.getRisk().getMaxSingleQuoteNotional();
        if (maxSingle != null && maxSingle.signum() > 0
                && buyNotional.max(sellNotional).compareTo(maxSingle) > 0) {
            log.warn("MM single-notional gate blocked symbol={} notional={}", symbol, buyNotional.max(sellNotional));
            return -1;
        }
        BigDecimal maxDaily = tradeProps.getRisk().getMaxDailyQuoteNotional();
        if (maxDaily != null && maxDaily.signum() > 0) {
            BigDecimal usedToday;
            try {
                usedToday = recorder.sumTodayLiveQuoteNotional();
            } catch (RuntimeException ex) {
                log.error("MM daily-cap read failed; fail-closed skip open symbol={}", symbol, ex);
                return -1;
            }
            if (usedToday.add(buyNotional).compareTo(maxDaily) > 0) {
                log.warn("MM daily-cap gate blocked symbol={} used={} add={} max={}", symbol, usedToday, buyNotional, maxDaily);
                return -1;
            }
        }
        if (!depthGuard.check(depth, qty, tradeProps.getDepthGuard().getSafetyRatio(),
                tradeProps.getDepthGuard().getTopN()).allOk()) {
            return -1;
        }

        String base = "mm-" + symbol + "-" + System.currentTimeMillis();
        long pairId = recorder.createMmCell(symbol, qty, buyPrice, sellPrice, base); // fail-closed
        String buyClientId = base + "-BUY";
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), symbol, "buy-limit",
                qty.toPlainString(), buyPrice.toPlainString(), buyClientId);
        Long buyId = parseOrderId(resp);
        if (buyId == null) {
            recorder.markMmCancelledBuy(pairId);
            log.warn("MM buy rejected symbol={} err={}", symbol, resp == null ? "null" : resp.getErrMsg());
            return -1;
        }
        recorder.markMmBuySent(pairId, buyId);
        log.info("MM opened pairId={} symbol={} buyId={} buy={} sell={} qty={}",
                pairId, symbol, buyId, buyPrice, sellPrice, qty);
        return pairId;
    }

    // ---- 状态机（D2） ----

    /** 按 cell 当前状态推进一步。 */
    public void advanceCell(MarketMakerCell cell) {
        if (HtxStatus.MM_RESTING_BUY.equals(cell.getState())) {
            advanceRestingBuy(cell);
        } else if (HtxStatus.MM_RESTING_SELL.equals(cell.getState())) {
            advanceRestingSell(cell);
        }
    }

    private void advanceRestingBuy(MarketMakerCell cell) {
        Long buyId = cell.getBuyOrderId();
        if (buyId == null) {
            return; // 崩溃在 createMmCell 与 markMmBuySent 之间；留待人工/后续对齐
        }
        HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(buyId);
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return; // 瞬时故障，下 tick 重试
        }
        HtxOrder o = resp.getData();
        String state = o.getState();
        BigDecimal filled = nz(o.getFilledAmount());
        if ("filled".equalsIgnoreCase(state)) {
            openSellLeg(cell, filled, o.getFilledFees());
            return;
        }
        if (isTerminal(state)) { // 外部撤/拒
            if (filled.signum() > 0) {
                openSellLeg(cell, filled, o.getFilledFees());
            } else {
                recorder.markMmCancelledBuy(cell.getPairId());
            }
            return;
        }
        // 仍挂着（submitted/partial-filled）：先看是否需跟价重挂，再看 TTL
        // 急停（enabled=false）时不重挂：短路，连深度都不取；买单靠 TTL/人工收敛
        if (mmProps.isEnabled()) {
            BigDecimal bestBid = currentBestBid(cell.getSymbol());
            if (bestBid != null
                    && MarketMakerMath.shouldReprice(bestBid, cell.getBuyPrice(), mmProps.getRepriceThresholdPct())) {
                repriceBuy(cell, bestBid);
                return;
            }
        }
        // 仍挂着（submitted/partial-filled）→ TTL 撤单
        if (isBuyExpired(cell)) {
            HtxOrder post = cancelAndQuery(buyId);
            BigDecimal postFilled = post == null ? BigDecimal.ZERO : nz(post.getFilledAmount());
            if (postFilled.signum() > 0) {
                openSellLeg(cell, postFilled, post.getFilledFees()); // 撤单竞态：有成交则转挂卖
            } else {
                recorder.markMmCancelledBuy(cell.getPairId());
                log.info("MM buy expired & cancelled pairId={} buyId={}", cell.getPairId(), buyId);
            }
        }
    }

    /** 撤旧买单并复查；返回复查到的订单（可能因竞态已成交），查询失败返回 null。 */
    private HtxOrder cancelAndQuery(Long buyId) {
        htxApiService.getSpotService().cancelOrder(buyId);
        HtxRestResponse<HtxOrder> post = htxApiService.getSpotService().queryOrder(buyId);
        return (post != null && post.ok()) ? post.getData() : null;
    }

    /** 取当前最优买价；深度不可用/异常返回 null（本 tick 不重挂）。 */
    private BigDecimal currentBestBid(String symbol) {
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(symbol, DEPTH_LEVELS);
        if (depthResp == null || !depthResp.ok() || depthResp.payload() == null) {
            return null;
        }
        if (isEmpty(depthResp.payload().getBids())) {
            return null;
        }
        BigDecimal bid = depthResp.payload().getBids().get(0).getPrice();
        return (bid == null || bid.signum() <= 0) ? null : bid;
    }

    /**
     * 跟价重挂：撤旧买单→复查竞态（撤单期间成交则转卖腿）→在新 bestBid 重下买单→原地更新 cell。
     * 重挂失败（新单被拒）关闭 cell（此时无库存、无敞口）。
     */
    private void repriceBuy(MarketMakerCell cell, BigDecimal newBidRaw) {
        Long buyId = cell.getBuyOrderId();
        HtxOrder post = cancelAndQuery(buyId);
        BigDecimal postFilled = post == null ? BigDecimal.ZERO : nz(post.getFilledAmount());
        if (postFilled.signum() > 0) {
            openSellLeg(cell, postFilled, post.getFilledFees()); // 撤单竞态：有成交则转挂卖，不重挂
            return;
        }
        // 安全：撤单未确认为终态（仍 submitted/canceling）→ 本 tick 放弃重挂，避免旧单未死即重下造成双买/裸多；下一 tick 再评估
        if (post == null || !isTerminal(post.getState())) {
            log.info("MM reprice deferred: cancel not settled pairId={} buyId={} state={}",
                    cell.getPairId(), buyId, post == null ? "null" : post.getState());
            return;
        }
        BigDecimal newBuy = newBidRaw.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal newSell = MarketMakerMath.sellFromBuy(newBuy, mmProps.getSpreadPct());
        if (!MarketMakerMath.isNoLoss(newBuy, newSell, mmProps.getFeeRatePerSide())) {
            log.warn("MM reprice no-loss guard blocked pairId={} newBuy={} newSell={}",
                    cell.getPairId(), newBuy, newSell);
            return;
        }
        String buyClientId = cell.getClientId() + "-BUY-R" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "buy-limit",
                cell.getQty().toPlainString(), newBuy.toPlainString(), buyClientId);
        Long newBuyId = parseOrderId(resp);
        if (newBuyId == null) {
            recorder.markMmCancelledBuy(cell.getPairId());
            log.warn("MM reprice buy rejected pairId={} newBuy={} err={}",
                    cell.getPairId(), newBuy, resp == null ? "null" : resp.getErrMsg());
            return;
        }
        recorder.markMmReprice(cell.getPairId(), newBuyId, newBuy, newSell);
        log.info("MM buy repriced pairId={} oldBuy={} newBuy={} newSell={} newBuyId={}",
                cell.getPairId(), cell.getBuyPrice(), newBuy, newSell, newBuyId);
    }

    private void openSellLeg(MarketMakerCell cell, BigDecimal filledBase, BigDecimal buyFeeBase) {
        BigDecimal sellable = HtxFeeMath.sellableBase(filledBase, buyFeeBase)
                .setScale(MarketMakerMath.QTY_SCALE, RoundingMode.HALF_DOWN);
        if (sellable.signum() <= 0) {
            recorder.markMmCancelledBuy(cell.getPairId());
            return;
        }
        String sellClientId = cell.getClientId() + "-SELL";
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-limit",
                sellable.toPlainString(), cell.getSellPrice().toPlainString(), sellClientId);
        Long sellId = parseOrderId(resp);
        if (sellId == null) {
            recorder.recordExposure(cell.getPairId(), cell.getSymbol(), "base-long", sellable,
                    "mm-sell-rejected:" + (resp == null ? "null" : resp.getErrMsg()));
            recorder.markMmCancelledBuy(cell.getPairId());
            log.error("MM sell rejected after buy fill pairId={} sellable={}", cell.getPairId(), sellable);
            return;
        }
        recorder.markMmSellResting(cell.getPairId(), sellId, filledBase);
        log.info("MM sell resting pairId={} sellId={} sellable={}", cell.getPairId(), sellId, sellable);
    }

    private void advanceRestingSell(MarketMakerCell cell) {
        Long sellId = cell.getSellOrderId();
        if (sellId == null) {
            return;
        }
        HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(sellId);
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return;
        }
        HtxOrder o = resp.getData();
        String state = o.getState();
        BigDecimal filled = nz(o.getFilledAmount());
        if ("filled".equalsIgnoreCase(state)) {
            recorder.markMmDone(cell.getPairId(), filled);
            log.info("MM done pairId={} sellId={} sellFilled={}", cell.getPairId(), sellId, filled);
            return;
        }
        if (isTerminal(state)) { // 卖单被外部撤/拒，仍持 base → 记敞口，关闭 cell
            BigDecimal unsold = nz(o.getAmount()).subtract(filled);
            if (unsold.signum() > 0) {
                recorder.recordExposure(cell.getPairId(), cell.getSymbol(), "base-long", unsold,
                        "mm-sell-terminal:" + state);
            }
            recorder.markMmDone(cell.getPairId(), filled);
        }
        // 仍挂着：卖单 TTL 默认 0 = 不撤，挂到成交
    }

    // ---- helpers ----

    private boolean isBuyExpired(MarketMakerCell cell) {
        if (mmProps.getBuyTtlMs() <= 0 || cell.getCreatedAt() == null) {
            return false;
        }
        return Duration.between(cell.getCreatedAt(), LocalDateTime.now()).toMillis() > mmProps.getBuyTtlMs();
    }

    private static boolean isTerminal(String state) {
        return "canceled".equalsIgnoreCase(state)
                || "partial-canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state);
    }

    private static Long parseOrderId(HtxRestResponse<String> resp) {
        if (resp == null || !resp.ok() || resp.getData() == null || resp.getData().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(resp.getData().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }
}
