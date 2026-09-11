package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxBalance;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxSpotLedgerRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxFeeMath;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.strategy.grid.FeeAwareSpotGridStrategy;
import com.metaverse.coin.strategy.htx.strategy.grid.SpotGridStrategy;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 做市服务：开仓决策 + 买→挂卖→完成状态机 + 重启恢复。见 spec 2026-08-06-htx-market-maker-design.md §3/§4/§6。
 * 非阻塞：runOnce 每 tick 先推进 open cell，再按白名单各尝试开一个新 cell。
 * 买腿写前 fail-closed（createMmCell 抛异常即未发单）；买腿成交后卖腿失败 → 记 exposure 交对账器平，关闭 cell 防重复挂卖。
 */
@Slf4j
@Service
@ConditionalOnBean(HtxApiService.class)
public class MarketMakerService {

    private final HtxApiService htxApiService;
    private final HtxDepthGuard depthGuard;
    private final HtxTradeProperties tradeProps;
    private final HtxMarketMakerProperties mmProps;
    private final HtxTradeRecorder recorder;
    private final HtxSpotLedgerRecorder spotLedgerRecorder;
    private final HtxSpotFeeRateProvider feeRateProvider;
    private final SpotGridStrategy gridStrategy;
    private final HtxGridWindowStore gridWindowStore;
    private final MovingGridEngine movingGridEngine = new MovingGridEngine();
    private final MarketMakerRiskCoordinator riskCoordinator = new MarketMakerRiskCoordinator();

    private static final int DEPTH_LEVELS = 5;
    private volatile long buyBlockedUntilEpochMs;

    /** 网格熔断内存态：连续亏损退出的 round-trip 数（盈利退出清零）；重启清零，保守偏安全。 */
    private volatile int consecutiveLossCycles;
    /** 曾走 taker 割肉/linger 亏损退出的 cell pairId 集合，供 round-trip 完成时判定连亏。 */
    private final Set<Long> lossExitPairIds = ConcurrentHashMap.newKeySet();

    @Autowired
    public MarketMakerService(HtxApiService htxApiService,
                              HtxDepthGuard depthGuard,
                              HtxTradeProperties tradeProps,
                              HtxMarketMakerProperties mmProps,
                              HtxTradeRecorder recorder,
                              HtxSpotLedgerRecorder spotLedgerRecorder,
                              HtxSpotFeeRateProvider feeRateProvider,
                              SpotGridStrategy gridStrategy,
                              HtxGridWindowStore gridWindowStore) {
        this.htxApiService = htxApiService;
        this.depthGuard = depthGuard;
        this.tradeProps = tradeProps;
        this.mmProps = mmProps;
        this.recorder = recorder;
        this.spotLedgerRecorder = spotLedgerRecorder;
        this.feeRateProvider = feeRateProvider;
        this.gridStrategy = gridStrategy;
        this.gridWindowStore = gridWindowStore;
    }

    MarketMakerService(HtxApiService htxApiService,
                       HtxDepthGuard depthGuard,
                       HtxTradeProperties tradeProps,
                       HtxMarketMakerProperties mmProps,
                       HtxTradeRecorder recorder) {
        this(htxApiService, depthGuard, tradeProps, mmProps, recorder, new HtxGridWindowStore(null));
    }

    MarketMakerService(HtxApiService htxApiService,
                       HtxDepthGuard depthGuard,
                       HtxTradeProperties tradeProps,
                       HtxMarketMakerProperties mmProps,
                       HtxTradeRecorder recorder,
                       HtxGridWindowStore gridWindowStore) {
        this(htxApiService, depthGuard, tradeProps, mmProps, recorder,
                HtxSpotLedgerRecorder.noop(), new HtxSpotFeeRateProvider(htxApiService, mmProps),
                new FeeAwareSpotGridStrategy(), gridWindowStore);
    }

    /** 启动校验：策略会自动抬高卖价覆盖手续费，这里只校验配置定义域。 */
    @PostConstruct
    void validateConfig() {
        if (mmProps.getFeeRatePerSide() == null
                || mmProps.getFeeRatePerSide().signum() < 0
                || mmProps.getFeeRatePerSide().compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException("MM fee-rate-per-side must be in [0, 1)");
        }
        if (mmProps.getSpreadPct() == null || mmProps.getSpreadPct().signum() < 0) {
            throw new IllegalStateException("MM spread-pct must be non-negative");
        }
        if (mmProps.getGridStepPct() == null
                || mmProps.getGridStepPct().signum() <= 0
                || mmProps.getGridStepPct().compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException("MM grid-step-pct must be in (0, 1)");
        }
        if (mmProps.getGridLevels() <= 0) {
            throw new IllegalStateException("MM grid-levels must be positive");
        }
        if (mmProps.getGridMigrationHysteresisPct() == null
                || mmProps.getGridMigrationHysteresisPct().signum() < 0) {
            throw new IllegalStateException("MM grid-migration-hysteresis-pct must be non-negative");
        }
        if (mmProps.getGridMigrationConfirmations() <= 0) {
            throw new IllegalStateException("MM grid-migration-confirmations must be positive");
        }
        if (mmProps.getGridMigrationCooldownMs() < 0) {
            throw new IllegalStateException("MM grid-migration-cooldown-ms must be non-negative");
        }
        if (mmProps.getGridMaxShiftStepsPerTick() <= 0) {
            throw new IllegalStateException("MM grid-max-shift-steps-per-tick must be positive");
        }
        if (mmProps.getPerCellQuoteNotional() == null
                || mmProps.getPerCellQuoteNotional().signum() <= 0) {
            throw new IllegalStateException("MM per-cell-quote-notional must be positive");
        }
        if (mmProps.getMinSellOrderQuoteNotional() == null
                || mmProps.getMinSellOrderQuoteNotional().signum() <= 0) {
            throw new IllegalStateException("MM min-sell-order-quote-notional must be positive");
        }
        if (mmProps.getMaxConcurrentCellsPerSymbol() <= 0) {
            throw new IllegalStateException("MM max-concurrent-cells-per-symbol must be positive");
        }
        if (mmProps.getMaxTotalExposureQuoteNotional() == null
                || mmProps.getMaxTotalExposureQuoteNotional()
                .compareTo(mmProps.getPerCellQuoteNotional()) < 0) {
            throw new IllegalStateException("MM max-total-exposure-quote-notional must cover one cell");
        }
        if (mmProps.getMinQuoteReserve() == null || mmProps.getMinQuoteReserve().signum() < 0) {
            throw new IllegalStateException("MM min-quote-reserve must be non-negative");
        }
        if (mmProps.getMaxBaseInventory() == null || mmProps.getMaxBaseInventory().signum() < 0) {
            throw new IllegalStateException("MM max-base-inventory must be non-negative");
        }
        if (mmProps.getDailyNetLossBudget() == null
                || mmProps.getDailyNetLossBudget().signum() < 0) {
            throw new IllegalStateException("MM daily-net-loss-budget must be non-negative");
        }
        if (mmProps.getMaxConsecutiveLossCycles() < 0) {
            throw new IllegalStateException("MM max-consecutive-loss-cycles must be non-negative");
        }
    }

    // ---- 编排（D3） ----

    /** 主循环一次：先推进所有 open cell，再（若未急停）按白名单各尝试开一个新 cell。 */
    public void runOnce() {
        if (!isLiveTradingEnabled()) {
            log.warn("MM run skipped: both htx.trade.enabled and htx.market-maker.enabled must be true");
            return;
        }
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
                if (mmProps.isGridModeEnabled()) {
                    fillGridLadder(symbol);
                } else {
                    openNewCellIfEligible(symbol);
                }
            } catch (RuntimeException ex) {
                log.error("MM openNewCell failed symbol={}", symbol, ex);
            }
        }
    }

    /** 重启恢复：库里所有 open cell 拉回并对齐一次（幂等）。 */
    public void recoverOpenCells() {
        if (!isLiveTradingEnabled()) {
            log.warn("MM recovery skipped: both htx.trade.enabled and htx.market-maker.enabled must be true");
            return;
        }
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
        if (!isLiveTradingEnabled()) {
            return -1;
        }
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
        if (spotRiskThresholdsEnabled()) {
            MarketMakerRiskCoordinator.SpotBalanceSnapshot balances = spotBalances(symbol);
            MarketMakerRiskCoordinator.RiskDecision risk = evaluateSpotRisk(symbol, bestBid, balances);
            if (!risk.allowNewRisk()) {
                log.warn("MM spot risk blocks new cell symbol={} state={} reason={}",
                        symbol, risk.state(), risk.reason());
                return -1;
            }
        }
        return tryOpenCellAtAnchor(symbol, bestBid, depth);
    }

    /**
     * 在给定锚点价开一个 cell：feeRate→定价(quote anchor)→qty→不亏/去重/单笔/当日额/深度/余额闸→createMmCell→买单。
     * 由单格 open（anchor=bestBid）与网格全梯补齐（anchor=各价梯档）共用；anchor=bestBid 时与原单格逻辑逐字节等价。
     * 通过所有闸门返回 pairId；任一闸门不过返回 -1。
     */
    private long tryOpenCellAtAnchor(String symbol, BigDecimal anchor, HtxDepth depth) {
        HtxSpotFeeRateProvider.FeeRates feeRates = feeRateProvider.rates(symbol);
        SpotGridStrategy.GridQuote quote = gridStrategy.quote(anchor, mmProps.getSpreadPct(),
                feeRates.maker(), feeRates.maker(), MarketMakerMath.PRICE_SCALE);
        BigDecimal buyPrice = quote.buyPrice();
        BigDecimal sellPrice = quote.sellPrice();
        BigDecimal qty = MarketMakerMath.qtyFor(mmProps.getPerCellQuoteNotional(), buyPrice);
        if (qty.signum() <= 0) {
            return -1;
        }
        if (!gridStrategy.coversFees(buyPrice, sellPrice, feeRates.maker(), feeRates.maker())) {
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
        if (isBuyRetryCoolingDown(symbol, buyPrice)) {
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
                usedToday = recorder.sumTodayMmRiskQuoteNotional();
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
                tradeProps.getApi().getSpotAccountId(), symbol, "buy-limit-maker",
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

    // ---- 网格化刷量：全梯补齐 + 熔断/敞口/地板三闸（spec 2026-08-18 §3/§4） ----

    /**
     * 网格全梯补齐：一 tick 内为单标的在等比价梯上补齐缺失档位，直至并行度预算耗尽。
     * 闸门链（spec §3.2）：余额 backoff → 白名单 → 熔断（当日净亏损/连亏，fail-closed）
     * → K 并行度预算 → 深度 → 逐档【总敞口闸 → tryOpenCellAtAnchor（含地板已在价梯生成时截断 + 现有全部闸门）】。
     * gridModeEnabled=false 时本方法不被调用（runOnce 分流），行为与原单格路径逐字节等价。
     */
    public void fillGridLadder(String symbol) {
        if (!isLiveTradingEnabled()) {
            return;
        }
        if (isBuyBalanceBackoffActive()) {
            return;
        }
        if (!mmProps.getSymbols().contains(symbol)) {
            return;
        }
        if (isCircuitBreakerTripped()) {
            return; // 熔断：当日净亏损超预算或连亏达上限（读失败 fail-closed）
        }
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(symbol, DEPTH_LEVELS);
        if (depthResp == null || !depthResp.ok() || depthResp.payload() == null) {
            return;
        }
        HtxDepth depth = depthResp.payload();
        if (isEmpty(depth.getBids()) || isEmpty(depth.getAsks())) {
            return;
        }
        BigDecimal bestBid = depth.getBids().get(0).getPrice();
        BigDecimal bestAsk = depth.getAsks().get(0).getPrice();
        if (bestBid == null || bestAsk == null || bestBid.signum() <= 0 || bestAsk.signum() <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity window =
                gridWindowStore.loadOrCreate(symbol, bestBid, mmProps.getGridStepPct(),
                        mmProps.getGridLevels(), movingGridEngine, now);
        MovingGridEngine.Decision decision = movingGridEngine.observe(window, bestBid,
                mmProps.getGridMigrationHysteresisPct(), mmProps.getGridMigrationConfirmations(),
                mmProps.getGridMigrationCooldownMs(), mmProps.getGridMaxShiftStepsPerTick(), now);
        gridWindowStore.save(decision.window());

        // 始终从持久化 anchor 生成稳定价格晶格；只有确认越界才移动，不随每 tick 的 bestBid 漂移。
        List<BigDecimal> levels = MarketMakerMath.gridBuyLevels(decision.window().getAnchorPrice(),
                decision.window().getStepPct(), decision.window().getLevels(),
                mmProps.getGridFloorPrice(), MarketMakerMath.PRICE_SCALE);
        if (decision.migrated()) {
            retireBuysOutsideWindow(symbol, levels);
            log.info("MM grid migrated symbol={} state={} steps={} epoch={} anchor={} lower={} upper={}",
                    symbol, decision.window().getState(), decision.signedSteps(), decision.window().getEpoch(),
                    decision.window().getAnchorPrice(), decision.window().getLowerPrice(),
                    decision.window().getUpperPrice());
        }
        MarketMakerRiskCoordinator.SpotBalanceSnapshot balances = null;
        if (spotRiskThresholdsEnabled()) {
            balances = spotBalances(symbol);
            MarketMakerRiskCoordinator.RiskDecision firstRisk = evaluateSpotRisk(symbol, bestBid, balances);
            if (!firstRisk.allowNewRisk()) {
                persistGridRiskState(decision.window(), firstRisk);
                return;
            }
        }
        int budget = mmProps.getMaxConcurrentCellsPerSymbol() - recorder.countOpenMmBuyCells(symbol);
        if (budget <= 0) {
            return;
        }
        BigDecimal maxTotal = mmProps.getMaxTotalExposureQuoteNotional();
        BigDecimal perCell = mmProps.getPerCellQuoteNotional();
        BigDecimal running = currentOpenBuyNotional(symbol); // 已挂 open cell 买入名义之和
        BigDecimal projectedQuote = balances == null ? BigDecimal.ZERO : nz(balances.availableQuote());
        BigDecimal projectedBase = balances == null ? BigDecimal.ZERO : nz(balances.baseInventory());
        int opened = 0;
        for (BigDecimal level : levels) {
            if (opened >= budget) {
                break; // 并行度预算耗尽
            }
            if (maxTotal != null && maxTotal.signum() > 0
                    && running.add(perCell).compareTo(maxTotal) > 0) {
                break; // 总敞口闸：再开一档即超上限 → 停更深档（同 perCell，越深只会更超）
            }
            BigDecimal pendingBuyBase = MarketMakerMath.qtyFor(perCell, level);
            if (spotRiskThresholdsEnabled()) {
                MarketMakerRiskCoordinator.RiskDecision loopRisk = riskCoordinator.evaluate(
                        new MarketMakerRiskCoordinator.SpotBalanceSnapshot(true, projectedQuote, projectedBase),
                        perCell, mmProps.getMinQuoteReserve(), projectedBase, pendingBuyBase,
                        mmProps.getMaxBaseInventory());
                if (!loopRisk.allowNewRisk()) {
                    persistGridRiskState(decision.window(), loopRisk);
                    break;
                }
            }
            long pairId = tryOpenCellAtAnchor(symbol, level, depth);
            if (pairId > 0) {
                opened++;
                running = running.add(perCell);
                projectedQuote = projectedQuote.subtract(perCell).max(BigDecimal.ZERO);
                projectedBase = projectedBase.add(pendingBuyBase);
            }
        }
    }

    private boolean spotRiskThresholdsEnabled() {
        return positive(mmProps.getMinQuoteReserve()).signum() > 0
                || positive(mmProps.getMaxBaseInventory()).signum() > 0;
    }

    private MarketMakerRiskCoordinator.RiskDecision evaluateSpotRisk(
            String symbol, BigDecimal anchor, MarketMakerRiskCoordinator.SpotBalanceSnapshot balances) {
        BigDecimal pendingBuyBase = MarketMakerMath.qtyFor(mmProps.getPerCellQuoteNotional(), anchor);
        return riskCoordinator.evaluate(balances, mmProps.getPerCellQuoteNotional(), mmProps.getMinQuoteReserve(),
                balances == null ? BigDecimal.ZERO : balances.baseInventory(), pendingBuyBase,
                mmProps.getMaxBaseInventory());
    }

    private void persistGridRiskState(
            com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity window,
            MarketMakerRiskCoordinator.RiskDecision risk) {
        window.setState(risk.state());
        window.setUpdatedAt(LocalDateTime.now());
        gridWindowStore.save(window);
        log.warn("MM grid risk paused symbol={} state={} reason={}",
                window.getSymbol(), risk.state(), risk.reason());
    }

    /** 两阶段迁移的撤旧阶段：只撤窗口外未成交买单，卖单与库存义务始终保留。 */
    private void retireBuysOutsideWindow(String symbol, List<BigDecimal> desiredLevels) {
        for (HtxLockedPairEntity entity : recorder.findOpenMmCells(symbol)) {
            if (!HtxStatus.MM_RESTING_BUY.equals(entity.getStatus())
                    || !MarketMakerMath.farEnough(entity.getBuyPrice(), desiredLevels, mmProps.getMinGapPct())) {
                continue;
            }
            Long buyOrderId = entity.getBuyOrderId();
            if (buyOrderId == null) {
                continue;
            }
            HtxOrder post = cancelAndQuery(buyOrderId);
            recordSpotFillSnapshot(MarketMakerCell.fromEntity(entity), "buy", buyOrderId, post,
                    HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
            if (post == null || !isTerminal(post.getState())) {
                continue;
            }
            BigDecimal filled = nz(post.getFilledAmount());
            if (filled.signum() > 0) {
                openSellLeg(MarketMakerCell.fromEntity(entity), filled,
                        post.getFilledFees(), post.getFilledCashAmount());
            } else {
                recorder.markMmCancelledBuy(entity.getId());
            }
        }
    }

    /**
     * 熔断闸（fail-closed）：连续亏损退出 cell 数达上限，或当日已实现净亏损超预算 → 停开新单。
     * 净额读取失败即视为熔断（无法确认盈亏时宁停勿冒险）。
     */
    private boolean isCircuitBreakerTripped() {
        if (mmProps.getMaxConsecutiveLossCycles() > 0
                && consecutiveLossCycles >= mmProps.getMaxConsecutiveLossCycles()) {
            log.warn("MM circuit-breaker: consecutive loss cycles {} >= {}",
                    consecutiveLossCycles, mmProps.getMaxConsecutiveLossCycles());
            return true;
        }
        BigDecimal budget = mmProps.getDailyNetLossBudget();
        if (budget == null || budget.signum() <= 0) {
            return false; // 未设净亏损预算
        }
        BigDecimal net;
        try {
            net = recorder.sumTodayMmRealizedNetQuote();
        } catch (RuntimeException ex) {
            log.error("MM circuit-breaker net-loss read failed; fail-closed", ex);
            return true;
        }
        if (net.negate().compareTo(budget) > 0) { // net<0 为亏损；亏损额 = −net
            log.warn("MM circuit-breaker: daily net loss {} exceeds budget {}", net.negate(), budget);
            return true;
        }
        return false;
    }

    /**
     * 零成交取消说明该价位当前挂不住或被交易所立即取消；冷却期内跳过近似价位，防止撤挂风暴。
     */
    private boolean isBuyRetryCoolingDown(String symbol, BigDecimal buyPrice) {
        long cooldownMs = mmProps.getBuyCancelRetryCooldownMs();
        if (cooldownMs <= 0) {
            return false;
        }
        LocalDateTime since = LocalDateTime.now().minus(Duration.ofMillis(cooldownMs));
        try {
            boolean cooling = recorder.hasRecentZeroFillCancelledMmBuy(
                    symbol, buyPrice, mmProps.getMinGapPct(), since);
            if (cooling) {
                log.info("MM buy retry cooldown blocks symbol={} buy={} cooldownMs={}",
                        symbol, buyPrice, cooldownMs);
            }
            return cooling;
        } catch (RuntimeException ex) {
            log.error("MM buy retry cooldown read failed; fail-closed skip open symbol={} buy={}",
                    symbol, buyPrice, ex);
            return true;
        }
    }

    /** 单标的所有 open cell 的买入名义之和（Σ reqQuantity×buyPrice），用于总敞口闸初值。 */
    private BigDecimal currentOpenBuyNotional(String symbol) {
        return recorder.findOpenMmCells(symbol).stream()
                .map(cell -> nz(cell.getReqQuantity()).multiply(nz(cell.getBuyPrice())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * round-trip 完成时记连亏计数：该 cell 曾走 taker 割肉/linger 退出（在 lossExitPairIds 中）→ 连亏 +1；
     * 否则视为正常盈利退出 → 连亏清零。内存态，重启清零（保守偏安全）。
     */
    private void recordCycleOutcome(long pairId) {
        if (lossExitPairIds.remove(pairId)) {
            consecutiveLossCycles++;
        } else {
            consecutiveLossCycles = 0;
        }
    }

    // ---- 状态机（D2） ----

    /** 按 cell 当前状态推进一步。 */
    public void advanceCell(MarketMakerCell cell) {
        if (!isLiveTradingEnabled()) {
            return;
        }
        if (HtxStatus.MM_RESTING_BUY.equals(cell.getState())) {
            advanceRestingBuy(cell);
        } else if (HtxStatus.MM_RESTING_SELL.equals(cell.getState())
                || HtxStatus.MM_SELL_RETRYABLE.equals(cell.getState())) {
            advanceRestingSell(cell);
        }
    }

    private boolean isLiveTradingEnabled() {
        return tradeProps.getTrade().isEnabled() && mmProps.isEnabled();
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
        recordSpotFillSnapshot(cell, "buy", buyId, o, HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
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
        if (!mmProps.isGridModeEnabled() && mmProps.isEnabled() && !isBuyBalanceBackoffActive()) {
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
            recordSpotFillSnapshot(cell, "buy", buyId, post, HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
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
        HtxSpotFeeRateProvider.FeeRates feeRates = feeRateProvider.rates(cell.getSymbol());
        SpotGridStrategy.GridQuote quote = gridStrategy.quote(askRaw, mmProps.getSpreadPct(),
                feeRates.taker(), feeRates.maker(), MarketMakerMath.PRICE_SCALE);
        BigDecimal newBuy = quote.buyPrice();
        BigDecimal newSell = quote.sellPrice();
        if (!gridStrategy.coversFees(newBuy, newSell, feeRates.taker(), feeRates.maker())) {
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
        recordSpotFillSnapshot(cell, "buy", buyId, post, HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
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
        HtxSpotFeeRateProvider.FeeRates feeRates = feeRateProvider.rates(cell.getSymbol());
        SpotGridStrategy.GridQuote quote = gridStrategy.quote(newBidRaw, mmProps.getSpreadPct(),
                feeRates.maker(), feeRates.maker(), MarketMakerMath.PRICE_SCALE);
        BigDecimal newBuy = quote.buyPrice();
        BigDecimal newSell = quote.sellPrice();
        if (!gridStrategy.coversFees(newBuy, newSell, feeRates.maker(), feeRates.maker())) {
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
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "buy-limit-maker",
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
        HtxSpotFeeRateProvider.FeeRates feeRates = feeRateProvider.rates(cell.getSymbol());
        BigDecimal sellPrice;
        if (mmProps.isSellFollowEnabled()) {
            // follow：非网格仍取 max(含费保本地板, bestAsk)；网格窗口跟随打开时，库存风险由合约空头对冲，卖腿贴当前网格。
            TopOfBook top = currentTopOfBook(cell.getSymbol());
            BigDecimal ask = (top != null) ? top.ask() : actualBuyPrice;
            sellPrice = desiredSellPrice(cell, ask, filledCashAmount, filledBase, buyFeeBase,
                    actualBuyPrice, feeRates);
        } else {
            sellPrice = gridStrategy.quote(actualBuyPrice, mmProps.getSpreadPct(),
                    feeRates.maker(), feeRates.maker(), MarketMakerMath.PRICE_SCALE).sellPrice();
            if (!gridStrategy.coversFees(actualBuyPrice, sellPrice, feeRates.maker(), feeRates.maker())) {
                log.error("MM actual-fill no-loss gate blocked pairId={} actualBuy={} sell={}",
                        cell.getPairId(), actualBuyPrice, sellPrice);
                return;
            }
        }
        BigDecimal floorSellPrice = sellPrice;
        sellPrice = uniqueSellPrice(cell, floorSellPrice);
        if (sellPrice.compareTo(floorSellPrice) != 0) {
            log.info("MM sell price de-duplicated pairId={} symbol={} floor={} adjusted={}",
                    cell.getPairId(), cell.getSymbol(), floorSellPrice, sellPrice);
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
        BigDecimal sellNotional = sellable.multiply(sellPrice);
        BigDecimal minimumSellNotional = mmProps.getMinSellOrderQuoteNotional();
        if (sellNotional.compareTo(minimumSellNotional) < 0) {
            recorder.markMmDust(pairId, symbol, sellable, sellNotional, minimumSellNotional);
            log.warn("MM sell residual moved to dust pairId={} symbol={} sellable={} price={} notional={} minimum={}",
                    pairId, symbol, sellable, sellPrice, sellNotional, minimumSellNotional);
            return;
        }
        String sellClientId = clientId + "-SELL";
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), symbol, "sell-limit-maker",
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
            BigDecimal sellReference = cell.getSellPrice();
            if (mmProps.isSellFollowEnabled() && mmProps.isGridModeEnabled()
                    && mmProps.isGridSellFollowWindowEnabled()) {
                TopOfBook top = currentTopOfBook(cell.getSymbol());
                if (top != null) {
                    sellReference = top.ask();
                }
            }
            BigDecimal targetSellPrice = desiredSellPrice(cell, sellReference);
            BigDecimal floorSellPrice = targetSellPrice;
            targetSellPrice = uniqueSellPrice(cell, floorSellPrice);
            if (targetSellPrice.compareTo(floorSellPrice) != 0) {
                log.info("MM remaining sell price de-duplicated pairId={} symbol={} floor={} adjusted={}",
                        cell.getPairId(), cell.getSymbol(), floorSellPrice, targetSellPrice);
            }
            if (!HtxStatus.MM_SELL_RETRYABLE.equals(cell.getState())) {
                recorder.markMmSellPending(cell.getPairId(), nz(cell.getBuyFilledAmount()),
                        nz(cell.getTotalFeeBase()), cell.getBuyPrice(), targetSellPrice);
            }
            submitRemainingSellLeg(cell, targetSellPrice);
            return;
        }
        HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(sellId);
        if (resp == null || !resp.ok() || resp.getData() == null) {
            if (isBaseRecordInvalid(resp)) {
                recorder.markMmReconcileRequired(cell.getPairId(), "HTX_ORDER_NOT_FOUND",
                        "sell order not found on exchange; manual reconciliation required orderId=" + sellId);
                log.error("MM sell order missing on exchange pairId={} sellOrderId={} symbol={} errCode={} errMsg={}",
                        cell.getPairId(), sellId, cell.getSymbol(),
                        resp == null ? "null" : resp.getErrCode(),
                        resp == null ? "null" : resp.getErrMsg());
            }
            return;
        }
        HtxOrder o = resp.getData();
        recordSpotFillSnapshot(cell, "sell", sellId, o, HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
        String state = o.getState();
        BigDecimal filled = nz(o.getFilledAmount());
        if ("filled".equalsIgnoreCase(state) || isTerminal(state)) {
            settleTerminalSell(cell, o);
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
        BigDecimal floorSellPrice = desiredSellPrice(cell, top.ask());
        BigDecimal desiredSell = uniqueSellPrice(cell, floorSellPrice);
        if (MarketMakerMath.shouldRepriceSell(desiredSell, cell.getSellPrice(), mmProps.getSellRepriceThresholdPct())) {
            if (desiredSell.compareTo(floorSellPrice) != 0) {
                log.info("MM reprice sell price de-duplicated pairId={} symbol={} floor={} adjusted={}",
                        cell.getPairId(), cell.getSymbol(), floorSellPrice, desiredSell);
            }
            repriceSell(cell, desiredSell);
        }
    }

    /** 卖腿跟价重挂（maker）：撤旧卖单→复查竞态（全成交则完成；未终态则 defer 杜绝双卖）→新价重挂限价卖。 */
    private void repriceSell(MarketMakerCell cell, BigDecimal desiredSell) {
        HtxOrder post = cancelAndQuery(cell.getSellOrderId());
        recordSpotFillSnapshot(cell, "sell", cell.getSellOrderId(), post,
                HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
        BigDecimal filled = (post != null) ? nz(post.getFilledAmount()) : BigDecimal.ZERO;
        BigDecimal amount = nz(post != null ? post.getAmount() : null);
        if (post != null && filled.signum() > 0 && amount.signum() > 0 && filled.compareTo(amount) >= 0) {
            settleTerminalSell(cell, post);
            return;
        }
        if (post == null || !isTerminal(post.getState())) return;   // 未确认终态 → defer，杜绝双卖

        SellProgress progress = sellProgress(cell, post);
        BigDecimal sellable = progress.remaining();
        if (sellable.signum() <= 0) return;

        String clientId = cell.getClientId() + "-SELL-R" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-limit-maker",
                sellable.toPlainString(), desiredSell.toPlainString(), clientId);
        if (resp == null || !resp.ok()) {
            recorder.markMmSellRetryable(cell.getPairId(), progress.cumulativeFilled(),
                    progress.cumulativeNetQuote(), progress.cumulativeFeeQuote(), post.getState());
            log.warn("repriceSell place failed pairId={} keep RESTING_SELL resp={}", cell.getPairId(), resp);
            return;                                                 // 旧单已撤、库存在，下 tick 兜底
        }
        Long newSellId = parseOrderId(resp);
        if (newSellId == null) return;
        recorder.recordMmSellProgress(cell.getPairId(), progress.cumulativeFilled(),
                progress.cumulativeNetQuote(), progress.cumulativeFeeQuote());
        recorder.markMmSellReprice(cell.getPairId(), newSellId, desiredSell, progress.cumulativeFilled());
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
        lossExitPairIds.add(cell.getPairId()); // 割肉/linger 退出：本 round-trip 记为亏损（连亏计数用）
        HtxOrder post = cancelAndQuery(cell.getSellOrderId());
        recordSpotFillSnapshot(cell, "sell", cell.getSellOrderId(), post,
                HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);
        BigDecimal filled = (post != null) ? nz(post.getFilledAmount()) : BigDecimal.ZERO;
        BigDecimal amount = nz(post != null ? post.getAmount() : null);
        if (post != null && filled.signum() > 0 && amount.signum() > 0 && filled.compareTo(amount) >= 0) {
            settleTerminalSell(cell, post);
            return;
        }
        if (post == null || !isTerminal(post.getState())) return;   // 未确认终态 → defer，杜绝双卖

        SellProgress progress = sellProgress(cell, post);
        BigDecimal sellable = progress.remaining();
        if (sellable.signum() <= 0) {
            recorder.recordMmSellProgress(cell.getPairId(), progress.cumulativeFilled(),
                    progress.cumulativeNetQuote(), progress.cumulativeFeeQuote());
            recorder.markMmDone(cell.getPairId(), progress.cumulativeFilled());
            recordCycleOutcome(cell.getPairId());
            return;
        }

        String clientId = cell.getClientId() + "-SELL-SL" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-market",
                sellable.toPlainString(), null, clientId);
        if (resp == null || !resp.ok()) {
            recorder.markMmSellRetryable(cell.getPairId(), progress.cumulativeFilled(),
                    progress.cumulativeNetQuote(), progress.cumulativeFeeQuote(), post.getState());
            log.error("takerFlatten place failed pairId={} resultCode={} keep RESTING_SELL resp={}",
                    cell.getPairId(), resultCode, resp);
            return;                                                 // 旧单已撤、库存在，下 tick 兜底
        }
        Long marketSellId = parseOrderId(resp);
        if (marketSellId == null) return;
        recorder.recordMmSellProgress(cell.getPairId(), progress.cumulativeFilled(),
                progress.cumulativeNetQuote(), progress.cumulativeFeeQuote());
        recorder.markMmSellReprice(cell.getPairId(), marketSellId, cell.getSellPrice(),
                progress.cumulativeFilled());
        recorder.updatePairStatus(cell.getPairId(), HtxStatus.MM_RESTING_SELL, resultCode,
                reason + " submitted orderId=" + marketSellId + " sellable=" + sellable.toPlainString());
    }

    /** 重试态只提交尚未出售的库存，绝不按原始买入量重复挂卖。 */
    private void submitRemainingSellLeg(MarketMakerCell cell, BigDecimal sellPrice) {
        BigDecimal totalSellable = totalSellable(cell);
        BigDecimal remaining = totalSellable.subtract(nz(cell.getSellFilledAmount()))
                .max(BigDecimal.ZERO)
                .setScale(MarketMakerMath.quantityScale(cell.getSymbol()), RoundingMode.DOWN);
        if (remaining.signum() <= 0) {
            recorder.markMmDone(cell.getPairId(), nz(cell.getSellFilledAmount()));
            recordCycleOutcome(cell.getPairId());
            return;
        }
        BigDecimal minimumSellNotional = mmProps.getMinSellOrderQuoteNotional();
        BigDecimal sellNotional = remaining.multiply(sellPrice);
        if (sellNotional.compareTo(minimumSellNotional) < 0) {
            recorder.markMmDust(cell.getPairId(), cell.getSymbol(), remaining, sellNotional, minimumSellNotional);
            return;
        }
        String suffix = HtxStatus.MM_SELL_RETRYABLE.equals(cell.getState())
                ? "-SELL-REC-" + System.currentTimeMillis() : "-SELL";
        HtxRestResponse<String> response = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-limit-maker",
                remaining.toPlainString(), sellPrice.toPlainString(), cell.getClientId() + suffix);
        Long newSellId = parseOrderId(response);
        if (newSellId == null) {
            log.error("MM remaining sell rejected; keep inventory pairId={} remaining={} price={}",
                    cell.getPairId(), remaining, sellPrice);
            return;
        }
        if (HtxStatus.MM_SELL_RETRYABLE.equals(cell.getState())) {
            recorder.markMmSellReprice(cell.getPairId(), newSellId, sellPrice,
                    nz(cell.getSellFilledAmount()));
        } else {
            recorder.markMmSellResting(cell.getPairId(), newSellId);
        }
    }

    private void settleTerminalSell(MarketMakerCell cell, HtxOrder order) {
        SellProgress progress = sellProgress(cell, order);
        if (progress.remaining().signum() <= 0) {
            recorder.recordMmSellProgress(cell.getPairId(), progress.cumulativeFilled(),
                    progress.cumulativeNetQuote(), progress.cumulativeFeeQuote());
            recorder.markMmDone(cell.getPairId(), progress.cumulativeFilled());
            recordCycleOutcome(cell.getPairId());
            log.info("MM done pairId={} sellId={} sellFilled={}", cell.getPairId(),
                    cell.getSellOrderId(), progress.cumulativeFilled());
            return;
        }
        recorder.markMmSellRetryable(cell.getPairId(), progress.cumulativeFilled(),
                progress.cumulativeNetQuote(), progress.cumulativeFeeQuote(), order.getState());
        log.warn("MM sell terminal but inventory remains pairId={} sellId={} state={} remaining={}",
                cell.getPairId(), cell.getSellOrderId(), order.getState(), progress.remaining());
    }

    private SellProgress sellProgress(MarketMakerCell cell, HtxOrder order) {
        BigDecimal currentFilled = nz(order.getFilledAmount());
        BigDecimal currentCash = nz(order.getFilledCashAmount());
        if (currentCash.signum() <= 0 && currentFilled.signum() > 0) {
            BigDecimal price = order.getPrice() != null && order.getPrice().signum() > 0
                    ? order.getPrice() : cell.getSellPrice();
            currentCash = currentFilled.multiply(nz(price));
        }
        BigDecimal currentFee = nz(order.getFilledFees());
        BigDecimal cumulativeFilled = cumulativeSellFilled(cell, currentFilled);
        BigDecimal cumulativeNetQuote = nz(cell.getNetQuoteCost()).add(currentCash).subtract(currentFee);
        BigDecimal cumulativeFeeQuote = nz(cell.getTotalFeeQuote()).add(currentFee);
        BigDecimal remaining = totalSellable(cell).subtract(cumulativeFilled)
                .max(BigDecimal.ZERO)
                .setScale(MarketMakerMath.quantityScale(cell.getSymbol()), RoundingMode.DOWN);
        return new SellProgress(cumulativeFilled, cumulativeNetQuote, cumulativeFeeQuote, remaining);
    }

    private BigDecimal totalSellable(MarketMakerCell cell) {
        return HtxFeeMath.sellableBase(nz(cell.getBuyFilledAmount()), nz(cell.getTotalFeeBase()))
                .setScale(MarketMakerMath.quantityScale(cell.getSymbol()), RoundingMode.DOWN);
    }

    private record SellProgress(BigDecimal cumulativeFilled, BigDecimal cumulativeNetQuote,
                                BigDecimal cumulativeFeeQuote, BigDecimal remaining) {}

    private BigDecimal protectedSellPrice(MarketMakerCell cell, BigDecimal bestAsk) {
        HtxSpotFeeRateProvider.FeeRates feeRates = feeRateProvider.rates(cell.getSymbol());
        BigDecimal filledBase = nz(cell.getBuyFilledAmount());
        BigDecimal quoteCost = filledBase.signum() > 0
                ? filledBase.multiply(cell.getBuyPrice())
                : cell.getQty().multiply(cell.getBuyPrice());
        BigDecimal grossBase = filledBase.signum() > 0 ? filledBase : cell.getQty();
        return gridStrategy.sellPriceAfterFill(quoteCost, grossBase, nz(cell.getTotalFeeBase()),
                cell.getBuyPrice(), bestAsk, feeRates.maker(), feeRates.maker(), MarketMakerMath.PRICE_SCALE);
    }

    private BigDecimal desiredSellPrice(MarketMakerCell cell, BigDecimal bestAsk) {
        HtxSpotFeeRateProvider.FeeRates feeRates = feeRateProvider.rates(cell.getSymbol());
        BigDecimal filledBase = nz(cell.getBuyFilledAmount());
        BigDecimal quoteCost = filledBase.signum() > 0
                ? filledBase.multiply(cell.getBuyPrice())
                : cell.getQty().multiply(cell.getBuyPrice());
        BigDecimal grossBase = filledBase.signum() > 0 ? filledBase : cell.getQty();
        return desiredSellPrice(cell, bestAsk, quoteCost, grossBase, nz(cell.getTotalFeeBase()),
                cell.getBuyPrice(), feeRates);
    }

    private BigDecimal desiredSellPrice(MarketMakerCell cell, BigDecimal bestAsk,
                                        BigDecimal filledQuote, BigDecimal filledBase,
                                        BigDecimal buyFeeBase, BigDecimal fallbackBuyPrice,
                                        HtxSpotFeeRateProvider.FeeRates feeRates) {
        BigDecimal gridSell = gridWindowSellPrice(cell.getSymbol(), bestAsk);
        if (gridSell != null) {
            return gridSell;
        }
        return gridStrategy.sellPriceAfterFill(filledQuote, filledBase, buyFeeBase,
                fallbackBuyPrice, bestAsk, feeRates.maker(), feeRates.maker(), MarketMakerMath.PRICE_SCALE);
    }

    private BigDecimal gridWindowSellPrice(String symbol, BigDecimal bestAsk) {
        if (!mmProps.isGridModeEnabled() || !mmProps.isGridSellFollowWindowEnabled()
                || bestAsk == null || bestAsk.signum() <= 0) {
            return null;
        }
        HtxGridWindowEntity window;
        try {
            window = gridWindowStore.find(symbol);
        } catch (RuntimeException ex) {
            log.warn("MM grid sell target read failed; use protected sell price symbol={}", symbol, ex);
            return null;
        }
        if (window == null || window.getAnchorPrice() == null || window.getStepPct() == null
                || window.getLevels() == null || window.getLevels() <= 0) {
            return bestAsk.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        }
        BigDecimal ask = bestAsk.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        List<BigDecimal> levels = MarketMakerMath.gridBuyLevels(window.getAnchorPrice(), window.getStepPct(),
                window.getLevels(), mmProps.getGridFloorPrice(), MarketMakerMath.PRICE_SCALE);
        BigDecimal lower = window.getLowerPrice();
        BigDecimal upper = window.getUpperPrice();
        if (lower != null && upper != null
                && ask.compareTo(lower) >= 0 && ask.compareTo(upper) <= 0) {
            BigDecimal target = null;
            for (BigDecimal level : levels) {
                if (level.compareTo(ask) >= 0 && (target == null || level.compareTo(target) < 0)) {
                    target = level;
                }
            }
            if (target != null) {
                return target.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
            }
        }
        return ask;
    }

    private BigDecimal uniqueSellPrice(MarketMakerCell cell, BigDecimal floorPrice) {
        BigDecimal gridPrice = uniqueGridWindowSellPrice(cell, floorPrice);
        if (gridPrice != null) {
            return gridPrice;
        }
        return uniqueSellPrice(cell.getSymbol(), cell.getPairId(), floorPrice,
                gridSellPriceCeiling(cell.getSymbol(), floorPrice));
    }

    private BigDecimal uniqueGridWindowSellPrice(MarketMakerCell cell, BigDecimal floorPrice) {
        if (!mmProps.isGridModeEnabled() || !mmProps.isGridSellFollowWindowEnabled()
                || cell == null || floorPrice == null || floorPrice.signum() <= 0) {
            return null;
        }
        HtxGridWindowEntity window;
        try {
            window = gridWindowStore.find(cell.getSymbol());
        } catch (RuntimeException ex) {
            log.warn("MM grid sell level read failed; use bounded de-dup pairId={} symbol={}",
                    cell.getPairId(), cell.getSymbol(), ex);
            return null;
        }
        if (window == null || window.getAnchorPrice() == null || window.getStepPct() == null
                || window.getLevels() == null || window.getLevels() <= 0
                || window.getLowerPrice() == null || window.getUpperPrice() == null) {
            return null;
        }
        BigDecimal floor = floorPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        if (floor.compareTo(window.getLowerPrice()) < 0 || floor.compareTo(window.getUpperPrice()) > 0) {
            return null;
        }
        List<BigDecimal> activeSellPrices = activeSellPricesWithinCeiling(
                cell.getSymbol(), cell.getPairId(), window.getUpperPrice());
        List<BigDecimal> gridSellLevels = MarketMakerMath.gridBuyLevels(window.getAnchorPrice(), window.getStepPct(),
                        window.getLevels(), mmProps.getGridFloorPrice(), MarketMakerMath.PRICE_SCALE)
                .stream()
                .map(price -> price.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING))
                .filter(price -> price.compareTo(floor) >= 0)
                .filter(price -> price.compareTo(window.getLowerPrice()) >= 0
                        && price.compareTo(window.getUpperPrice()) <= 0)
                .sorted()
                .collect(Collectors.toList());
        for (BigDecimal level : gridSellLevels) {
            if (activeSellPrices.stream().noneMatch(price -> price.compareTo(level) == 0)) {
                return level;
            }
        }
        List<BigDecimal> packedSellLevels = packedWindowSellLevels(floor, window.getUpperPrice(),
                activeSellPrices.size() + 1);
        for (BigDecimal level : packedSellLevels) {
            if (activeSellPrices.stream().noneMatch(price -> price.compareTo(level) == 0)) {
                log.info("MM grid sell uses packed overflow price pairId={} symbol={} floor={} adjusted={} activeSellCount={}",
                        cell.getPairId(), cell.getSymbol(), floor, level, activeSellPrices.size());
                return level;
            }
        }
        BigDecimal currentSell = cell.getSellPrice();
        if (currentSell != null && currentSell.signum() > 0) {
            BigDecimal current = currentSell.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
            if (current.compareTo(window.getLowerPrice()) >= 0 && current.compareTo(window.getUpperPrice()) <= 0) {
                log.info("MM grid sell levels occupied; keep current in-window price pairId={} symbol={} floor={} current={} activeSellCount={}",
                        cell.getPairId(), cell.getSymbol(), floor, current, activeSellPrices.size());
                return current;
            }
        }
        return null;
    }

    private List<BigDecimal> packedWindowSellLevels(BigDecimal floorPrice, BigDecimal ceilingPrice, int desiredSlots) {
        BigDecimal floor = floorPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        BigDecimal ceiling = ceilingPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        if (ceiling.compareTo(floor) < 0) {
            return Collections.emptyList();
        }
        int slots = Math.max(2, Math.min(Math.max(1, desiredSlots), mmProps.getMaxConcurrentCellsPerSymbol()));
        BigDecimal tick = BigDecimal.ONE.movePointLeft(MarketMakerMath.PRICE_SCALE);
        BigDecimal span = ceiling.subtract(floor);
        BigDecimal step = span.signum() <= 0 ? tick
                : span.divide(BigDecimal.valueOf(slots - 1L), MarketMakerMath.PRICE_SCALE + 4, RoundingMode.HALF_UP)
                .max(tick);
        List<BigDecimal> levels = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            BigDecimal rawLevel = floor.add(step.multiply(BigDecimal.valueOf(i)))
                    .setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
            BigDecimal level = rawLevel.compareTo(ceiling) > 0 ? ceiling : rawLevel;
            if (levels.stream().noneMatch(existing -> existing.compareTo(level) == 0)) {
                levels.add(level);
            }
        }
        if (levels.stream().noneMatch(existing -> existing.compareTo(ceiling) == 0)) {
            levels.add(ceiling);
        }
        return levels;
    }

    private List<BigDecimal> activeSellPricesWithinCeiling(String symbol, long pairId, BigDecimal ceilingPrice) {
        List<HtxLockedPairEntity> openCells;
        try {
            openCells = recorder.findOpenMmCells(symbol);
        } catch (RuntimeException ex) {
            log.warn("MM sell price de-dup read failed; use protected grid price pairId={} symbol={}",
                    pairId, symbol, ex);
            return Collections.emptyList();
        }
        if (openCells == null) {
            openCells = Collections.emptyList();
        }
        BigDecimal ceiling = ceilingPrice == null ? null
                : ceilingPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        return openCells.stream()
                .filter(openCell -> openCell.getId() == null || openCell.getId() != pairId)
                .filter(openCell -> HtxStatus.MM_RESTING_SELL.equals(openCell.getStatus())
                        || HtxStatus.MM_SELL_RETRYABLE.equals(openCell.getStatus()))
                .map(HtxLockedPairEntity::getSellPrice)
                .filter(price -> price != null && price.signum() > 0)
                .map(price -> price.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING))
                .filter(price -> ceiling == null || price.compareTo(ceiling) <= 0)
                .collect(Collectors.toList());
    }

    private BigDecimal gridSellPriceCeiling(String symbol, BigDecimal floorPrice) {
        if (!mmProps.isGridModeEnabled() || !mmProps.isGridSellFollowWindowEnabled()
                || floorPrice == null || floorPrice.signum() <= 0) {
            return null;
        }
        try {
            HtxGridWindowEntity window = gridWindowStore.find(symbol);
            if (window == null || window.getLowerPrice() == null || window.getUpperPrice() == null) {
                return null;
            }
            BigDecimal floor = floorPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
            if (floor.compareTo(window.getLowerPrice()) >= 0
                    && floor.compareTo(window.getUpperPrice()) <= 0) {
                return window.getUpperPrice().setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
            }
        } catch (RuntimeException ex) {
            log.warn("MM grid sell ceiling read failed; use unbounded de-dup symbol={}", symbol, ex);
        }
        return null;
    }

    private BigDecimal uniqueSellPrice(String symbol, long pairId, BigDecimal floorPrice, BigDecimal ceilingPrice) {
        BigDecimal candidate = floorPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        BigDecimal ceiling = ceilingPrice == null || ceilingPrice.compareTo(candidate) < 0
                ? null : ceilingPrice.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
        List<HtxLockedPairEntity> openCells;
        try {
            openCells = recorder.findOpenMmCells(symbol);
        } catch (RuntimeException ex) {
            log.warn("MM sell price de-dup read failed; use protected price pairId={} symbol={} price={}",
                    pairId, symbol, candidate, ex);
            return candidate;
        }
        if (openCells == null) {
            openCells = Collections.emptyList();
        }
        List<BigDecimal> activeSellPrices = openCells.stream()
                .filter(cell -> cell.getId() == null || cell.getId() != pairId)
                .filter(cell -> HtxStatus.MM_RESTING_SELL.equals(cell.getStatus())
                        || HtxStatus.MM_SELL_RETRYABLE.equals(cell.getStatus()))
                .map(HtxLockedPairEntity::getSellPrice)
                .filter(price -> price != null && price.signum() > 0)
                .filter(price -> ceiling == null || price.compareTo(ceiling) <= 0)
                .collect(Collectors.toList());
        if (MarketMakerMath.farEnough(candidate, activeSellPrices, mmProps.getMinGapPct())) {
            return candidate;
        }

        BigDecimal tick = BigDecimal.ONE.movePointLeft(MarketMakerMath.PRICE_SCALE);
        BigDecimal step = candidate.multiply(mmProps.getMinGapPct())
                .setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING)
                .max(tick);
        int maxAttempts = activeSellPrices.size() + mmProps.getMaxConcurrentCellsPerSymbol()
                + mmProps.getGridLevels() + 8;
        BigDecimal adjusted = candidate;
        for (int i = 0; i < maxAttempts; i++) {
            adjusted = adjusted.add(step).setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.CEILING);
            if (ceiling != null && adjusted.compareTo(ceiling) > 0) {
                log.info("MM bounded sell price de-dup exhausted pairId={} symbol={} floor={} ceiling={} activeSellCount={}",
                        pairId, symbol, candidate, ceiling, activeSellPrices.size());
                return ceiling;
            }
            if (MarketMakerMath.farEnough(adjusted, activeSellPrices, mmProps.getMinGapPct())) {
                return adjusted;
            }
        }
        log.warn("MM sell price de-dup exhausted pairId={} symbol={} floor={} adjusted={} activeSellCount={}",
                pairId, symbol, candidate, adjusted, activeSellPrices.size());
        return adjusted;
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
        recordSpotFillSnapshot(MarketMakerCell.fromEntity(entity), "buy", entity.getBuyOrderId(), order,
                HtxSpotLedgerRecorder.SOURCE_STARTUP_RECOVERY);
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
        MarketMakerRiskCoordinator.SpotBalanceSnapshot balances = spotBalances(null);
        if (!balances.available()) {
            return false;
        }
        BigDecimal required = nz(requiredUsdt).add(nz(mmProps.getMinQuoteReserve()));
        if (balances.availableQuote().compareTo(required) < 0) {
            blockNewBuysForInsufficientBalance(balances.availableQuote(), required);
            return false;
        }
        return true;
    }

    private MarketMakerRiskCoordinator.SpotBalanceSnapshot spotBalances(String symbol) {
        HtxRestResponse<HtxAccount> resp = htxApiService.getAccountService()
                .getAccountBalance(tradeProps.getApi().getSpotAccountId());
        if (resp == null || !resp.ok() || resp.getData() == null || resp.getData().getList() == null) {
            log.warn("MM balance preflight unavailable; fail-closed errCode={} errMsg={}",
                    resp == null ? "null" : resp.getErrCode(),
                    resp == null ? "null" : resp.getErrMsg());
            return MarketMakerRiskCoordinator.SpotBalanceSnapshot.unavailable();
        }
        String base = symbol == null ? "" : HtxSymbolUtil.base(symbol);
        BigDecimal availableQuote = BigDecimal.ZERO;
        BigDecimal baseInventory = BigDecimal.ZERO;
        for (HtxBalance balance : resp.getData().getList()) {
            if (balance == null || balance.getCurrency() == null || balance.getBalance() == null) {
                continue;
            }
            if ("usdt".equalsIgnoreCase(balance.getCurrency())
                    && "trade".equalsIgnoreCase(balance.getType())) {
                availableQuote = availableQuote.add(balance.getBalance());
            }
            if (!base.isEmpty() && base.equalsIgnoreCase(balance.getCurrency())
                    && ("trade".equalsIgnoreCase(balance.getType()) || "frozen".equalsIgnoreCase(balance.getType()))) {
                baseInventory = baseInventory.add(balance.getBalance());
            }
        }
        return new MarketMakerRiskCoordinator.SpotBalanceSnapshot(true, availableQuote, baseInventory);
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

    private static boolean isBaseRecordInvalid(HtxRestResponse<?> resp) {
        if (resp == null) {
            return false;
        }
        return "base-record-invalid".equalsIgnoreCase(resp.getErrCode());
    }

    private void recordSpotFillSnapshot(MarketMakerCell cell, String side, Long htxOrderId,
                                        HtxOrder order, String source) {
        if (order == null) {
            return;
        }
        try {
            HtxSpotLedgerRecorder.LedgerAppendResult result = spotLedgerRecorder.recordOrderSnapshot(
                    cell.getPairId(), null, cell.getSymbol(), side, htxOrderId,
                    order.getClientOrderId(), order, source);
            if (result.unmatchedSellBase().signum() > 0) {
                log.error("MM ledger has unmatched sell inventory pairId={} symbol={} unmatched={}",
                        cell.getPairId(), cell.getSymbol(), result.unmatchedSellBase());
            }
        } catch (RuntimeException ex) {
            log.error("MM ledger snapshot failed pairId={} side={} htxOrderId={}; state machine continues",
                    cell.getPairId(), side, htxOrderId, ex);
        }
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

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? BigDecimal.ZERO : v;
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }
}
