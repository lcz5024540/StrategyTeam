package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxBalance;
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
    private volatile long buyBlockedUntilEpochMs;

    /** 启动校验：δ < 单边费率则成交必亏，拒绝启动。见 spec §1 不亏下限。 */
    @PostConstruct
    void validateConfig() {
        if (mmProps.getFeeRatePerSide().signum() < 0
                || mmProps.getFeeRatePerSide().compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException("MM fee-rate-per-side must be in [0, 1)");
        }
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
        for (HtxLockedPairEntity entity : recorder.findRecoverableCancelledMmCells()) {
            try {
                recoverCancelledBuy(entity);
            } catch (RuntimeException ex) {
                log.error("MM recover cancelled buy failed pairId={}", entity.getId(), ex);
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
        if (isBuyBalanceBackoffActive()) {
            return -1;
        }
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
                .filter(cell -> HtxStatus.MM_RESTING_BUY.equals(cell.getStatus()))
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
        if (!hasSufficientQuoteBalance(buyNotional)) {
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
            if (isInsufficientBalanceError(resp)) {
                blockNewBuysForInsufficientBalance(null, buyNotional);
            }
            log.warn("MM buy rejected symbol={} errCode={} errMsg={}", symbol,
                    resp == null ? "null" : resp.getErrCode(),
                    resp == null ? "null" : resp.getErrMsg());
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
            openSellLeg(cell, filled, o.getFilledFees(), o.getFilledCashAmount());
            return;
        }
        if (isTerminal(state)) { // 外部撤/拒
            if (filled.signum() > 0) {
                openSellLeg(cell, filled, o.getFilledFees(), o.getFilledCashAmount());
            } else {
                recorder.markMmCancelledBuy(cell.getPairId());
            }
            return;
        }
        // 仍挂着（submitted/partial-filled）：先看是否需跟价重挂，再看 TTL
        // 急停（enabled=false）时不重挂：短路，连深度都不取；买单靠 TTL/人工收敛
        if (mmProps.isEnabled() && !isBuyBalanceBackoffActive()) {
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
                openSellLeg(cell, postFilled, post.getFilledFees(), post.getFilledCashAmount()); // 撤单竞态：有成交则转挂卖
                return;
            }
            // —— taker 升级：TTL 到点仍未成交 + 价差达标 → 穿价吃 ask 立即入场（镜像卖腿 linger 退出） ——
            if (mmProps.isBuyTakerEscalationEnabled()) {
                // 安全：撤单未确认终态（仍 submitted/canceling）→ defer，杜绝旧单未死即重下造成双买/裸多；下一 tick 再评估
                if (post == null || !isTerminal(post.getState())) {
                    log.info("MM taker-buy escalate deferred: cancel not settled pairId={} buyId={} state={}",
                            cell.getPairId(), buyId, post == null ? "null" : post.getState());
                    return;
                }
                TopOfBook top = currentTopOfBook(cell.getSymbol());
                if (top != null
                        && MarketMakerMath.isTakerBuyAcceptable(top.bid(), top.ask(), mmProps.getBuyTakerMaxCrossPct())
                        && hasSufficientQuoteBalance(cell.getQty().multiply(top.ask()))) {
                    takerBuyEscalate(cell, top.ask());
                    return;
                }
            }
            recorder.markMmCancelledBuy(cell.getPairId()); // 价差太宽/关闭/余额不足 → 退回现状撤单重开
            log.info("MM buy expired & cancelled pairId={} buyId={}", cell.getPairId(), buyId);
        }
    }

    /**
     * taker 买腿升级：TTL 到点仍未成交且价差达标时，在 bestAsk 重下「可成交限价买单」立即以 taker 入场。
     * 仿 repriceBuy 结构但跳过撤单（进入前 post 已确认终态、旧单已死），下新单 + markMmReprice 保活 cell。
     * 成交后由下一 tick 既有成交检测（advanceRestingBuy 顶部）自动推进到 openSellLeg。
     */
    private void takerBuyEscalate(MarketMakerCell cell, BigDecimal askRaw) {
        BigDecimal newBuy = askRaw.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal newSell = MarketMakerMath.sellFromBuy(newBuy, mmProps.getSpreadPct());
        // 防御闸门（不变量 δ≥f 下恒真，仅防配置异常）：(1−f)²·sell ≥ buy 不成立则放弃穿价，不下单
        if (!MarketMakerMath.isNoLoss(newBuy, newSell, mmProps.getFeeRatePerSide())) {
            log.warn("MM taker-buy no-loss guard blocked pairId={} newBuy={} newSell={}",
                    cell.getPairId(), newBuy, newSell);
            return;
        }
        String buyClientId = cell.getClientId() + "-BUY-T" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "buy-limit",
                MarketMakerMath.normalizeQty(cell.getQty(), cell.getSymbol()).toPlainString(), newBuy.toPlainString(), buyClientId);
        Long newBuyId = parseOrderId(resp);
        if (newBuyId == null) {
            recorder.markMmCancelledBuy(cell.getPairId());
            if (isInsufficientBalanceError(resp)) {
                blockNewBuysForInsufficientBalance(null, cell.getQty().multiply(newBuy));
            }
            log.warn("MM taker-buy escalate rejected pairId={} ask={} errCode={} errMsg={}",
                    cell.getPairId(), newBuy,
                    resp == null ? "null" : resp.getErrCode(),
                    resp == null ? "null" : resp.getErrMsg());
            return;
        }
        recorder.markMmReprice(cell.getPairId(), newBuyId, newBuy, newSell);
        log.info("MM taker-buy escalate pairId={} ask={} newSell={} newBuyId={}",
                cell.getPairId(), newBuy, newSell, newBuyId);
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

    /** 盘口最优买卖价快照。 */
    private record TopOfBook(BigDecimal bid, BigDecimal ask) {}

    /** 取当前盘口最优买/卖价；任一不可用（深度异常/为空/≤0）返回 null（本 tick 不跟价）。 */
    private TopOfBook currentTopOfBook(String symbol) {
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(symbol, DEPTH_LEVELS);
        if (depthResp == null || !depthResp.ok() || depthResp.payload() == null) {
            return null;
        }
        HtxDepth depth = depthResp.payload();
        if (isEmpty(depth.getBids()) || isEmpty(depth.getAsks())) {
            return null;
        }
        BigDecimal bid = depth.getBids().get(0).getPrice();
        BigDecimal ask = depth.getAsks().get(0).getPrice();
        if (bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0) {
            return null;
        }
        return new TopOfBook(bid, ask);
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
            openSellLeg(cell, postFilled, post.getFilledFees(), post.getFilledCashAmount()); // 撤单竞态：有成交则转挂卖，不重挂
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
        if (!hasSufficientQuoteBalance(cell.getQty().multiply(newBuy))) {
            recorder.markMmCancelledBuy(cell.getPairId());
            return;
        }
        String buyClientId = cell.getClientId() + "-BUY-R" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "buy-limit",
                MarketMakerMath.normalizeQty(cell.getQty(), cell.getSymbol()).toPlainString(), newBuy.toPlainString(), buyClientId);
        Long newBuyId = parseOrderId(resp);
        if (newBuyId == null) {
            recorder.markMmCancelledBuy(cell.getPairId());
            if (isInsufficientBalanceError(resp)) {
                blockNewBuysForInsufficientBalance(null, cell.getQty().multiply(newBuy));
            }
            log.warn("MM reprice buy rejected pairId={} newBuy={} errCode={} errMsg={}",
                    cell.getPairId(), newBuy,
                    resp == null ? "null" : resp.getErrCode(),
                    resp == null ? "null" : resp.getErrMsg());
            return;
        }
        recorder.markMmReprice(cell.getPairId(), newBuyId, newBuy, newSell);
        log.info("MM buy repriced pairId={} oldBuy={} newBuy={} newSell={} newBuyId={}",
                cell.getPairId(), cell.getBuyPrice(), newBuy, newSell, newBuyId);
    }

    private void openSellLeg(MarketMakerCell cell, BigDecimal filledBase, BigDecimal buyFeeBase,
                             BigDecimal filledCashAmount) {
        BigDecimal actualBuyPrice = MarketMakerMath.actualBuyPrice(
                filledCashAmount, filledBase, cell.getBuyPrice());
        BigDecimal sellPrice;
        if (mmProps.isSellFollowEnabled()) {
            // follow：初始卖价取 max(含费保本地板, bestAsk)。盘口不可用则退回含费保本地板。
            TopOfBook top = currentTopOfBook(cell.getSymbol());
            BigDecimal ask = (top != null) ? top.ask() : actualBuyPrice;
            sellPrice = MarketMakerMath.desiredSellPrice(
                    actualBuyPrice, ask, mmProps.getFeeRatePerSide());
        } else {
            // legacy：固定高价挂到成交，保留不亏闸门。
            sellPrice = MarketMakerMath.sellFromBuy(actualBuyPrice, mmProps.getSpreadPct());
            if (!MarketMakerMath.isNoLoss(actualBuyPrice, sellPrice, mmProps.getFeeRatePerSide())) {
                log.error("MM actual-fill no-loss gate blocked pairId={} actualBuy={} sell={}",
                        cell.getPairId(), actualBuyPrice, sellPrice);
                return;
            }
        }
        recorder.markMmSellPending(
                cell.getPairId(), filledBase, nz(buyFeeBase), actualBuyPrice, sellPrice);
        submitSellLeg(cell.getPairId(), cell.getClientId(), cell.getSymbol(),
                filledBase, nz(buyFeeBase), sellPrice);
    }

    private void submitSellLeg(long pairId, String clientId, String symbol, BigDecimal filledBase,
                               BigDecimal buyFeeBase, BigDecimal sellPrice) {
        BigDecimal sellable = HtxFeeMath.sellableBase(filledBase, buyFeeBase)
                .setScale(MarketMakerMath.quantityScale(symbol), RoundingMode.DOWN);
        if (sellable.signum() <= 0) {
            log.error("MM sellable amount is zero pairId={} filled={} fee={}", pairId, filledBase, buyFeeBase);
            return;
        }
        String sellClientId = clientId + "-SELL";
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), symbol, "sell-limit",
                sellable.toPlainString(), sellPrice.toPlainString(), sellClientId);
        Long sellId = parseOrderId(resp);
        if (sellId == null) {
            log.error("MM limit sell rejected; keep pending pairId={} symbol={} sellable={} price={} errCode={} errMsg={}",
                    pairId, symbol, sellable, sellPrice,
                    resp == null ? "null" : resp.getErrCode(),
                    resp == null ? "null" : resp.getErrMsg());
            return;
        }
        recorder.markMmSellResting(pairId, sellId);
        log.info("MM sell resting pairId={} sellId={} sellable={} price={}", pairId, sellId, sellable, sellPrice);
    }

    private void advanceRestingSell(MarketMakerCell cell) {
        Long sellId = cell.getSellOrderId();
        if (sellId == null) {
            BigDecimal protectedSellPrice = MarketMakerMath.desiredSellPrice(
                    cell.getBuyPrice(), cell.getSellPrice(), mmProps.getFeeRatePerSide());
            recorder.markMmSellPending(cell.getPairId(), nz(cell.getBuyFilledAmount()),
                    nz(cell.getTotalFeeBase()), cell.getBuyPrice(), protectedSellPrice);
            submitSellLeg(cell.getPairId(), cell.getClientId(), cell.getSymbol(),
                    nz(cell.getBuyFilledAmount()), nz(cell.getTotalFeeBase()), protectedSellPrice);
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
            recorder.markMmDone(cell.getPairId(), cumulativeSellFilled(cell, filled));
            log.info("MM done pairId={} sellId={} sellFilled={}", cell.getPairId(), sellId, filled);
            return;
        }
        if (isTerminal(state)) { // 卖单被外部撤/拒，仍持 base → 记敞口，关闭 cell
            BigDecimal unsold = nz(o.getAmount()).subtract(filled);
            if (unsold.signum() > 0) {
                recorder.recordExposure(cell.getPairId(), cell.getSymbol(), "base-long", unsold,
                        "mm-sell-terminal:" + state);
            }
            recorder.markMmDone(cell.getPairId(), cumulativeSellFilled(cell, filled));
            return;
        }
        // —— 卖腿跟价（maker）——
        if (!mmProps.isSellFollowEnabled()) return;                 // 关：退回旧「挂到成交」
        TopOfBook top = currentTopOfBook(cell.getSymbol());
        if (top == null) return;                                    // 盘口不可用，本 tick 不动
        if (mmProps.isStopLossEnabled()
                && MarketMakerMath.shouldStopLoss(
                top.bid(), cell.getBuyPrice(), mmProps.getStopLossPct())) {
            stopLossFlatten(cell);                                  // bestBid 跌破止损线 → 吃单割肉
            return;
        }
        // —— 卖腿挂太久 → 保本(容忍微亏) taker 退出 ——（止损优先，已在上面拦截）
        if (mmProps.isSellLingerExitEnabled()
                && isSellLingerExpired(cell)
                && MarketMakerMath.isTakerExitAcceptable(cell.getBuyPrice(), top.bid(),
                        mmProps.getFeeRatePerSide(), mmProps.getSellLingerLossTolerancePct())) {
            lingerExitFlatten(cell);
            return;
        }
        BigDecimal desiredSell = MarketMakerMath.desiredSellPrice(
                cell.getBuyPrice(), top.ask(), mmProps.getFeeRatePerSide());
        if (MarketMakerMath.shouldRepriceSell(desiredSell, cell.getSellPrice(), mmProps.getSellRepriceThresholdPct())) {
            repriceSell(cell, desiredSell);
        }
    }

    /** 卖腿跟价重挂（maker）：撤旧卖单→复查竞态（全成交则完成；未终态则 defer 杜绝双卖）→新价重挂限价卖。 */
    private void repriceSell(MarketMakerCell cell, BigDecimal desiredSell) {
        HtxOrder post = cancelAndQuery(cell.getSellOrderId());
        BigDecimal filled = (post != null) ? nz(post.getFilledAmount()) : BigDecimal.ZERO;
        BigDecimal amount = nz(post != null ? post.getAmount() : null);
        if (post != null && filled.signum() > 0 && amount.signum() > 0 && filled.compareTo(amount) >= 0) {
            recorder.markMmDone(cell.getPairId(), cumulativeSellFilled(cell, filled));
            return;
        }
        if (post == null || !isTerminal(post.getState())) return;   // 未确认终态 → defer，杜绝双卖

        BigDecimal sellable = remainingSellable(cell, post);
        if (sellable.signum() <= 0) return;

        String clientId = cell.getClientId() + "-SELL-R" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-limit",
                sellable.toPlainString(), desiredSell.toPlainString(), clientId);
        if (resp == null || !resp.ok()) {
            log.warn("repriceSell place failed pairId={} keep RESTING_SELL resp={}", cell.getPairId(), resp);
            return;                                                 // 旧单已撤、库存在，下 tick 兜底
        }
        Long newSellId = parseOrderId(resp);
        if (newSellId == null) return;
        recorder.markMmSellReprice(cell.getPairId(), newSellId, desiredSell,
                cumulativeSellFilled(cell, filled));
    }

    /** 止损割肉（taker）：bestBid ≤ 止损线 → taker 吃单清库存，resultCode=MM_STOP_LOSS。 */
    private void stopLossFlatten(MarketMakerCell cell) {
        takerFlatten(cell, "MM_STOP_LOSS", "stop-loss market flatten");
    }

    /** 卖单挂太久（linger 超时）且 taker 仍容忍保本 → taker 吃单退出，resultCode=MM_LINGER_EXIT。 */
    private void lingerExitFlatten(MarketMakerCell cell) {
        takerFlatten(cell, "MM_LINGER_EXIT", "linger taker exit");
    }

    /**
     * taker 吃单平仓：撤旧卖单→复查竞态（全成交则完成；未终态则 defer 杜绝双卖）→ sell-market 吃单清库存。
     * resultCode/reason 由调用方给（止损=MM_STOP_LOSS，linger=MM_LINGER_EXIT）。
     */
    private void takerFlatten(MarketMakerCell cell, String resultCode, String reason) {
        HtxOrder post = cancelAndQuery(cell.getSellOrderId());
        BigDecimal filled = (post != null) ? nz(post.getFilledAmount()) : BigDecimal.ZERO;
        BigDecimal amount = nz(post != null ? post.getAmount() : null);
        if (post != null && filled.signum() > 0 && amount.signum() > 0 && filled.compareTo(amount) >= 0) {
            recorder.markMmDone(cell.getPairId(), cumulativeSellFilled(cell, filled));
            return;
        }
        if (post == null || !isTerminal(post.getState())) return;   // 未确认终态 → defer，杜绝双卖

        BigDecimal sellable = remainingSellable(cell, post);
        if (sellable.signum() <= 0) {
            recorder.markMmDone(cell.getPairId(), cumulativeSellFilled(cell, filled));
            return;
        }

        String clientId = cell.getClientId() + "-SELL-SL" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-market",
                sellable.toPlainString(), null, clientId);
        if (resp == null || !resp.ok()) {
            log.error("takerFlatten place failed pairId={} resultCode={} keep RESTING_SELL resp={}",
                    cell.getPairId(), resultCode, resp);
            return;                                                 // 旧单已撤、库存在，下 tick 兜底
        }
        Long marketSellId = parseOrderId(resp);
        if (marketSellId == null) return;
        recorder.markMmSellReprice(cell.getPairId(), marketSellId, cell.getSellPrice(),
                cumulativeSellFilled(cell, filled));
        recorder.updatePairStatus(cell.getPairId(), HtxStatus.MM_RESTING_SELL, resultCode,
                reason + " submitted orderId=" + marketSellId + " sellable=" + sellable.toPlainString());
    }

    private BigDecimal remainingSellable(MarketMakerCell cell, HtxOrder post) {
        BigDecimal orderAmount = nz(post != null ? post.getAmount() : null);
        BigDecimal cellSellable = HtxFeeMath.sellableBase(
                nz(cell.getBuyFilledAmount()), nz(cell.getTotalFeeBase()));
        BigDecimal totalForCurrentOrder = orderAmount.signum() > 0
                ? orderAmount.min(cellSellable)
                : cellSellable;
        return totalForCurrentOrder.subtract(nz(post != null ? post.getFilledAmount() : null))
                .max(BigDecimal.ZERO)
                .setScale(MarketMakerMath.quantityScale(cell.getSymbol()), RoundingMode.DOWN);
    }

    private BigDecimal cumulativeSellFilled(MarketMakerCell cell, BigDecimal latestFilled) {
        return nz(cell.getSellFilledAmount()).add(nz(latestFilled));
    }

    private void recoverCancelledBuy(HtxLockedPairEntity entity) {
        HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(entity.getBuyOrderId());
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return;
        }
        HtxOrder order = resp.getData();
        BigDecimal filled = nz(order.getFilledAmount());
        if (filled.signum() <= 0) {
            return;
        }
        log.warn("MM recovered filled base from cancelled cell pairId={} buyId={} filled={}",
                entity.getId(), entity.getBuyOrderId(), filled);
        openSellLeg(MarketMakerCell.fromEntity(entity), filled,
                order.getFilledFees(), order.getFilledCashAmount());
    }

    // ---- helpers ----

    private boolean isBuyExpired(MarketMakerCell cell) {
        if (mmProps.getBuyTtlMs() <= 0 || cell.getCreatedAt() == null) {
            return false;
        }
        return Duration.between(cell.getCreatedAt(), LocalDateTime.now()).toMillis() > mmProps.getBuyTtlMs();
    }

    /** 卖腿 linger 超时判定：RESTING_SELL 起（updatedAt）挂满 sellLingerTimeoutMs 即超时。timeout≤0 或 updatedAt=null 视为未超时。 */
    private boolean isSellLingerExpired(MarketMakerCell cell) {
        if (mmProps.getSellLingerTimeoutMs() <= 0 || cell.getUpdatedAt() == null) {
            return false;
        }
        return Duration.between(cell.getUpdatedAt(), LocalDateTime.now()).toMillis()
                > mmProps.getSellLingerTimeoutMs();
    }

    private boolean isBuyBalanceBackoffActive() {
        return System.currentTimeMillis() < buyBlockedUntilEpochMs;
    }

    private boolean hasSufficientQuoteBalance(BigDecimal requiredUsdt) {
        HtxRestResponse<HtxAccount> resp = htxApiService.getAccountService()
                .getAccountBalance(tradeProps.getApi().getSpotAccountId());
        if (resp == null || !resp.ok() || resp.getData() == null || resp.getData().getList() == null) {
            log.warn("MM balance preflight unavailable; fail-closed errCode={} errMsg={}",
                    resp == null ? "null" : resp.getErrCode(),
                    resp == null ? "null" : resp.getErrMsg());
            return false;
        }
        BigDecimal available = resp.getData().getList().stream()
                .filter(b -> b != null && "usdt".equalsIgnoreCase(b.getCurrency())
                        && "trade".equalsIgnoreCase(b.getType()))
                .map(HtxBalance::getBalance)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(requiredUsdt) < 0) {
            blockNewBuysForInsufficientBalance(available, requiredUsdt);
            return false;
        }
        return true;
    }

    private void blockNewBuysForInsufficientBalance(BigDecimal available, BigDecimal required) {
        long backoffMs = Math.max(0L, mmProps.getInsufficientBalanceBackoffMs());
        buyBlockedUntilEpochMs = System.currentTimeMillis() + backoffMs;
        log.warn("MM insufficient USDT: available={} required={} pauseNewBuysMs={}",
                available == null ? "unknown" : available, required, backoffMs);
    }

    private static boolean isInsufficientBalanceError(HtxRestResponse<?> resp) {
        if (resp == null) {
            return false;
        }
        String text = (String.valueOf(resp.getErrCode()) + " " + String.valueOf(resp.getErrMsg())).toLowerCase();
        return text.contains("insufficient") || text.contains("balance");
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
