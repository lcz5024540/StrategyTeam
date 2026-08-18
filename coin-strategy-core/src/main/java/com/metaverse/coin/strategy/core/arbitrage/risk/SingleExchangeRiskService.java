package com.metaverse.coin.strategy.core.arbitrage.risk;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesCommissionRate;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceCommissionResponse;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo.BinanceBalance;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeDepthMonitor;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeDepthSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Risk Service
 *
 * <p>单交易所现货-合约套利的风控服务，负责两类检查:
 * <ul>
 *   <li>交易账户风控: 检查现货 USDT、合约可用余额、所需保证金和保证金健康度。</li>
 *   <li>盘口流动性风控: 基于现货/合约 depth 估算计划数量的 VWAP 和组合滑点。</li>
 * </ul>
 *
 * Handles pre-trade risk checks and account health monitoring.
 */
@Service
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeRiskService {

    public static final String PHASE_OPEN = "OPEN";
    public static final String PHASE_CLOSE = "CLOSE";

    private final BinanceApiService binanceApiService;
    private final SingleExchangeDepthMonitor depthMonitor;
    private final SingleExchangeSpotFuturesProperties properties;
    @Autowired(required = false)
    private BinanceQueryDataProvider queryDataProvider;

    /**
     * Check if a new trade is allowed.
     *
     * <p>兼容旧调用方的简化入口: 用同一个价格构造现货腿和合约腿计划，
     * 然后复用完整的 {@link #evaluateTradeRisk(SingleExchangeSpotFuturesOrderPlan)} 风控逻辑。
     */
    public boolean checkTradeRisk(String symbol, BigDecimal quantity, BigDecimal price) {
        SingleExchangeSpotFuturesOrderPlan plan = SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(symbol)
                .quantity(quantity)
                .spotPrice(price)
                .futuresPrice(price)
                .spotNotional(quantity.multiply(price))
                .futuresNotional(quantity.multiply(price))
                .leverage(1)
                .build();
        return checkTradeRisk(plan);
    }

    public boolean checkTradeRisk(SingleExchangeSpotFuturesOrderPlan plan) {
        return evaluateTradeRisk(plan).isPassed();
    }

    /**
     * 开仓前账户级硬风控。
     *
     * <p>这里不判断行情是否值得做，只判断“这笔交易能不能安全下出去”:
     * 单笔名义价值是否超限、现货余额是否足够、合约保证金是否足够、合约账户健康度是否达标。
     */
    public SingleExchangeTradeRiskAssessment evaluateTradeRisk(SingleExchangeSpotFuturesOrderPlan plan) {
        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Risk Alert: invalid trade plan");
            return SingleExchangeTradeRiskAssessment.builder()
                    .passed(false)
                    .rejectionReason("invalid trade plan")
                    .snapshotData(SingleExchangeRiskSnapshotData.builder().build())
                    .build();
        }

        BigDecimal tradeValue = plan.getSpotNotional() != null
                ? plan.getSpotNotional()
                : plan.getQuantity().multiply(plan.getSpotPrice());
        // 统一加载账户快照，后续即使某个检查失败，也能把当时的余额/保证金状态写入风险快照。
        RiskMetrics metrics = loadRiskMetrics(plan);
        SingleExchangeRiskSnapshotData snapshotData = toSnapshotData(metrics);

        // 单笔仓位名义价值上限，防止配置错误或价格异常导致超预期下大单。
        if (tradeValue.compareTo(properties.getMaxPositionValueUsdt()) > 0) {
            log.warn("Risk Alert: Trade value {} exceeds max allowed {}", tradeValue, properties.getMaxPositionValueUsdt());
            return buildTradeRiskAssessment(false,
                    "trade value " + tradeValue + " exceeds max allowed " + properties.getMaxPositionValueUsdt(),
                    snapshotData);
        }

        // 现货腿需要先买入标的资产，因此现货账户 USDT 必须覆盖现货名义金额。
        if (!metrics.spotAccountLoaded()) {
            return buildTradeRiskAssessment(false, "failed to fetch spot account info", snapshotData);
        }
        if (metrics.spotUsdtBalance().compareTo(tradeValue) < 0) {
            log.warn("Risk Alert: Insufficient Spot USDT Balance. Required: {}, Available: {}", tradeValue, metrics.spotUsdtBalance());
            return buildTradeRiskAssessment(false,
                    "insufficient spot USDT balance: required=" + tradeValue + ", available=" + metrics.spotUsdtBalance(),
                    snapshotData);
        }

        // 合约腿开空需要占用初始保证金，并额外乘以安全缓冲，避免刚开仓就贴近风险边界。
        if (!metrics.futuresAccountLoaded()) {
            return buildTradeRiskAssessment(false, "failed to fetch futures account info", snapshotData);
        }
        if (metrics.futuresAvailableBalance().compareTo(metrics.bufferedRequiredMargin()) < 0) {
            log.warn("Risk Alert: Insufficient Futures Balance. Required buffered margin: {}, Available: {}",
                    metrics.bufferedRequiredMargin(), metrics.futuresAvailableBalance());
            return buildTradeRiskAssessment(false,
                    "insufficient futures balance: requiredBufferedMargin=" + metrics.bufferedRequiredMargin()
                            + ", available=" + metrics.futuresAvailableBalance(),
                    snapshotData);
        }

        // 保证金率过低表示合约账户整体风险偏高，即使当前这笔保证金够，也不允许继续加风险敞口。
        if (metrics.marginRatio() == null || metrics.marginRatio().compareTo(properties.getMinMarginRatio()) < 0) {
            log.warn("Risk Alert: Futures Account Health is poor. marginRatio={}", metrics.marginRatio());
            return buildTradeRiskAssessment(false,
                    "margin ratio " + metrics.marginRatio() + " is below minimum " + properties.getMinMarginRatio(),
                    snapshotData);
        }

        return buildTradeRiskAssessment(true, null, snapshotData);
    }

    /**
     * 捕获当前账户风险快照。
     *
     * <p>策略在流动性拒绝、交易风控拒绝、平仓前检查等场景会调用它，
     * 用于把当时账户余额/保证金状态持久化，方便事后排查。
     */
    public SingleExchangeRiskSnapshotData captureRiskSnapshotData(SingleExchangeSpotFuturesOrderPlan plan) {
        return toSnapshotData(loadRiskMetrics(plan));
    }

    /**
     * 盘口流动性和滑点检查。
     *
     * <p>开仓阶段会阻断交易; 平仓阶段由策略侧决定通常只告警不阻断退出。
     * 检查逻辑是: 获取 depth 快照 -> 按交易方向选择盘口侧 -> 估算 VWAP -> 判断深度是否足够 -> 判断滑点是否超阈值。
     */
    public SingleExchangeLiquidityAssessment evaluateLiquidityRisk(SingleExchangeSpotFuturesOrderPlan plan, String phase) {
        BigDecimal configuredSlippageRate = defaultValue(properties.getEstimatedSlippageRate());
        SingleExchangeLiquidityAssessment.SingleExchangeLiquidityAssessmentBuilder builder = SingleExchangeLiquidityAssessment.builder()
                .phase(phase)
                .estimatedSlippageRate(configuredSlippageRate)
                .spotTopLevelEnough(true)
                .futuresTopLevelEnough(true);

        // 配置关闭流动性检查时直接放行，但仍保留配置滑点作为后续收益估算的保守输入。
        if (!Boolean.TRUE.equals(properties.getLiquidityCheckEnabled())) {
            return builder.passed(true).build();
        }

        // 没有合法数量就无法估算订单簿吃单路径，直接拒绝。
        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return builder
                    .passed(false)
                    .rejectionReason("invalid trade plan")
                    .spotTopLevelEnough(false)
                    .futuresTopLevelEnough(false)
                    .build();
        }

        // depthMonitor 内部负责重试/缓存/HTTP 回补; 这里拿到 fallback 表示深度数据仍不可可靠使用。
        String tradeSymbol = plan.getSymbol() != null ? plan.getSymbol() : plan.getSpotSymbol();
        SingleExchangeDepthSnapshot snapshot = depthMonitor.getDepthSnapshot(tradeSymbol);
        if (snapshot == null || snapshot.isFallbackUsed()) {
            return builder
                    .passed(false)
                    .fallbackUsed(true)
                    .rejectionReason("depth data unavailable after retries")
                    .build();
        }

        BinanceOrderBook spotOrderBook = snapshot.getSpotOrderBook();
        BinanceFuturesOrderBook futuresOrderBook = snapshot.getFuturesOrderBook();
        BigDecimal quantity = plan.getQuantity();

        // 按阶段选择盘口方向并估算 VWAP:
        // 开仓 = 买现货吃 asks + 开合约空吃 bids; 平仓 = 卖现货吃 bids + 买回合约空吃 asks。
        VwapEstimate spotEstimate = estimateVwap(resolveSpotLevels(phase, spotOrderBook), quantity);
        VwapEstimate futuresEstimate = estimateVwap(resolveFuturesLevels(phase, futuresOrderBook), quantity);
        builder.spotExpectedPrice(spotEstimate.vwap())
                .futuresExpectedPrice(futuresEstimate.vwap());

        // 如果某一侧订单簿累计数量不足以覆盖计划数量，滑点估算不完整，直接拒绝/告警。
        builder.spotTopLevelEnough(spotEstimate.sufficient())
                .futuresTopLevelEnough(futuresEstimate.sufficient());
        if (!spotEstimate.sufficient() || !futuresEstimate.sufficient()) {
            return builder
                    .passed(false)
                    .rejectionReason(buildDepthRejectionReason(
                            phase,
                            quantity,
                            spotEstimate.availableQty(),
                            futuresEstimate.availableQty()
                    ))
                    .build();
        }

        // 用一档买卖价中点作为“无冲击价格”，VWAP 与 mid 的偏离视为吃单滑点。
        BigDecimal spotMid = calculateMidPrice(
                extractLevelPrice(spotOrderBook.getBids(), 0),
                extractLevelPrice(spotOrderBook.getAsks(), 0)
        );
        BigDecimal futuresMid = calculateMidPrice(
                extractLevelPrice(futuresOrderBook.getBids(), 0),
                extractLevelPrice(futuresOrderBook.getAsks(), 0)
        );
        if (!isPositive(spotMid) || !isPositive(futuresMid) || !isPositive(spotEstimate.vwap()) || !isPositive(futuresEstimate.vwap())) {
            return builder
                    .passed(false)
                    .rejectionReason("mid price or expected execution price is unavailable")
                    .build();
        }

        // 组合滑点 = 现货腿滑点 + 合约腿滑点。套利要求两条腿同时成交，所以这里按总冲击评估。
        BigDecimal spotLegSlippageRate = spotEstimate.vwap().subtract(spotMid).abs()
                .divide(spotMid, 8, RoundingMode.HALF_UP);
        BigDecimal futuresLegSlippageRate = futuresEstimate.vwap().subtract(futuresMid).abs()
                .divide(futuresMid, 8, RoundingMode.HALF_UP);
        BigDecimal estimatedSlippageRate = spotLegSlippageRate.add(futuresLegSlippageRate);
        builder.estimatedSlippageRate(estimatedSlippageRate);

        if (estimatedSlippageRate.compareTo(properties.getEstimatedSlippageRate()) > 0) {
            return builder
                    .passed(false)
                    .rejectionReason("estimated slippage " + estimatedSlippageRate + " exceeds threshold " + properties.getEstimatedSlippageRate())
                    .build();
        }

        return builder.passed(true).build();
    }

    /**
     * Check account health (e.g., Margin Ratio).
     * Margin Ratio = Margin Balance / Maintenance Margin
     *
     * <p>该方法用于独立健康检查或持仓期间风险判断，不绑定某一笔交易计划。
     */
    public boolean checkAccountHealth() {
        BigDecimal marginRatio = getMarginRatio();
        if (marginRatio == null) {
            return false;
        }
        if (marginRatio.compareTo(properties.getMinMarginRatio()) < 0) {
            log.warn("Risk Alert: Low Margin Ratio: {}", marginRatio);
            return false;
        }
        return true;
    }

    public BigDecimal getMarginRatio() {
        try {
            // 优先通过可替换的数据提供者获取合约账户，便于 WebSocket/HTTP 或测试替身复用同一套风控逻辑。
            BinanceFuturesAccount futuresAccount = getQueryDataProvider().getFuturesAccount();
            BigDecimal marginRatio = calculateMarginRatio(futuresAccount);
            log.info("Current Futures Margin Ratio: {}", marginRatio);
            return marginRatio;
        } catch (Exception e) {
            log.error("Failed to check Futures account health", e);
            return null; // Fail safe
        }
    }

    private List<List<String>> resolveSpotLevels(String phase, BinanceOrderBook orderBook) {
        if (orderBook == null) {
            return null;
        }
        // 现货开仓是买入，吃卖盘 asks; 平仓是卖出，吃买盘 bids。
        return PHASE_CLOSE.equalsIgnoreCase(phase) ? orderBook.getBids() : orderBook.getAsks();
    }

    private List<List<String>> resolveFuturesLevels(String phase, BinanceFuturesOrderBook orderBook) {
        if (orderBook == null) {
            return null;
        }
        // 合约开仓是开空，吃买盘 bids; 平仓是买回空单，吃卖盘 asks。
        return PHASE_CLOSE.equalsIgnoreCase(phase) ? orderBook.getAsks() : orderBook.getBids();
    }

    /**
     * 按订单簿档位估算指定数量的成交均价。
     *
     * <p>逐档吃单直到满足计划数量，返回 VWAP、盘口累计可用数量以及是否足量。
     */
    private VwapEstimate estimateVwap(List<List<String>> levels, BigDecimal quantity) {
        if (levels == null || levels.isEmpty() || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return new VwapEstimate(null, BigDecimal.ZERO, false);
        }

        BigDecimal remainingQty = quantity;
        BigDecimal filledQty = BigDecimal.ZERO;
        BigDecimal totalQuote = BigDecimal.ZERO;
        BigDecimal availableQty = BigDecimal.ZERO;

        for (List<String> level : levels) {
            if (level == null || level.size() < 2) {
                continue;
            }
            BigDecimal levelPrice = parseDecimal(level.get(0));
            BigDecimal levelQty = parseDecimal(level.get(1));
            if (!isPositive(levelPrice) || !isPositive(levelQty)) {
                continue;
            }

            // availableQty 统计该侧盘口总可用量; filledQty/totalQuote 只统计计划数量实际会吃到的部分。
            availableQty = availableQty.add(levelQty);
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal executedQty = remainingQty.min(levelQty);
            totalQuote = totalQuote.add(executedQty.multiply(levelPrice));
            filledQty = filledQty.add(executedQty);
            remainingQty = remainingQty.subtract(executedQty);
        }

        if (filledQty.compareTo(BigDecimal.ZERO) <= 0) {
            return new VwapEstimate(null, availableQty, false);
        }

        return new VwapEstimate(
                totalQuote.divide(filledQty, 8, RoundingMode.HALF_UP),
                availableQty,
                remainingQty.compareTo(BigDecimal.ZERO) <= 0
        );
    }

    private BigDecimal extractLevelPrice(List<List<String>> levels, int index) {
        if (levels == null || levels.size() <= index || levels.get(index) == null || levels.get(index).size() < 1) {
            return null;
        }
        return parseDecimal(levels.get(index).get(0));
    }

    private BigDecimal calculateMidPrice(BigDecimal bidPrice, BigDecimal askPrice) {
        if (!isPositive(bidPrice) || !isPositive(askPrice)) {
            return null;
        }
        // mid price 只用于估算滑点基准，不作为真实成交价。
        return bidPrice.add(askPrice).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private String buildDepthRejectionReason(String phase,
                                             BigDecimal quantity,
                                             BigDecimal spotAvailableQty,
                                             BigDecimal futuresAvailableQty) {
        return "order-book depth is insufficient for " + phase
                + ": required=" + quantity
                + ", spotAvailable=" + defaultValue(spotAvailableQty)
                + ", futuresAvailable=" + defaultValue(futuresAvailableQty);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析账户真实双边往返手续费率（P0-1 保本闸真实费率来源）。
     *
     * <p>往返手续费 = (现货 taker + 合约 taker) × 2（开+平各一次、两腿 taker 成交）。现货档取
     * {@code standardCommission.taker}，合约档取 {@code takerCommissionRate}；两腿按 symbol 查账户实际佣金档，
     * 而非用成交回执（保本闸在开仓前判定，此时尚无成交）。
     *
     * <p>fail-open：任一档查询失败或缺失即返回 {@code null}，由调用方回落到静态兜底费率
     * （不因暂时取不到真实费率而误拒开仓；真实费率高于静态时才会收紧闸门）。
     *
     * @param symbol 交易对（现货与合约同名，如 BTCUSDT）
     * @return 真实往返手续费率；无法解析时返回 {@code null}
     */
    public BigDecimal resolveRoundTripFeeRate(String symbol) {
        try {
            BinanceCommissionResponse spotCommission = binanceApiService.getSpotService().getCommissionRate(symbol);
            BinanceFuturesCommissionRate futuresCommission = binanceApiService.getFuturesService().getCommissionRate(symbol);
            if (spotCommission == null || spotCommission.getStandardCommission() == null || futuresCommission == null) {
                log.warn("Round-trip fee rate unavailable for {}: missing spot/futures commission data", symbol);
                return null;
            }
            BigDecimal spotTaker = spotCommission.getStandardCommission().getTaker();
            BigDecimal futuresTaker = futuresCommission.getTakerCommissionRate();
            if (spotTaker == null || futuresTaker == null) {
                log.warn("Round-trip fee rate unavailable for {}: missing taker rate (spot={}, futures={})",
                        symbol, spotTaker, futuresTaker);
                return null;
            }
            // 开+平各两腿 taker 成交，故往返费 = 单边两腿 taker 之和 × 2。
            return spotTaker.add(futuresTaker).multiply(BigDecimal.valueOf(2));
        } catch (Exception e) {
            log.warn("Failed to resolve account real round-trip fee rate for {}, fallback to static floor", symbol, e);
            return null;
        }
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private SingleExchangeTradeRiskAssessment buildTradeRiskAssessment(boolean passed,
                                                                       String rejectionReason,
                                                                       SingleExchangeRiskSnapshotData snapshotData) {
        return SingleExchangeTradeRiskAssessment.builder()
                .passed(passed)
                .rejectionReason(rejectionReason)
                .snapshotData(snapshotData)
                .build();
    }

    private SingleExchangeRiskSnapshotData toSnapshotData(RiskMetrics metrics) {
        // 对外持久化只暴露风控快照字段，隐藏内部加载成功标记等运行细节。
        return SingleExchangeRiskSnapshotData.builder()
                .spotUsdtBalance(metrics.spotUsdtBalance())
                .futuresAvailableBalance(metrics.futuresAvailableBalance())
                .requiredMargin(metrics.requiredMargin())
                .bufferedRequiredMargin(metrics.bufferedRequiredMargin())
                .marginRatio(metrics.marginRatio())
                .build();
    }

    private RiskMetrics loadRiskMetrics(SingleExchangeSpotFuturesOrderPlan plan) {
        // requiredMargin 来自下单计划; bufferedRequiredMargin 在配置缓冲上再保守一层，给价格波动和手续费留余量。
        BigDecimal requiredMargin = plan == null ? BigDecimal.ZERO : defaultValue(plan.getRequiredFuturesMargin());
        BigDecimal bufferedRequiredMargin = requiredMargin.multiply(properties.getRequiredMarginBufferRate());
        BigDecimal spotUsdtBalance = BigDecimal.ZERO;
        BigDecimal futuresAvailableBalance = BigDecimal.ZERO;
        BigDecimal marginRatio = null;
        boolean spotAccountLoaded = false;
        boolean futuresAccountLoaded = false;

        try {
            // 查询现货账户 USDT free 余额，用于判断现货买入腿是否有足够现金。
            BinanceAccountInfo spotAccount = binanceApiService.account();
            Optional<BinanceBalance> usdtBalance = Optional.ofNullable(spotAccount.getBalances())
                    .orElse(List.of())
                    .stream()
                    .filter(b -> "USDT".equals(b.getAsset()))
                    .findFirst();
            spotUsdtBalance = usdtBalance.map(BinanceBalance::getFree).orElse(BigDecimal.ZERO);
            spotAccountLoaded = true;
        } catch (Exception e) {
            log.error("Failed to fetch Spot account info during risk check", e);
        }

        try {
            // 查询 U 本位合约账户可用 USDT 和保证金健康度，用于判断合约空单腿是否可安全开仓。
            BinanceFuturesAccount futuresAccount = getQueryDataProvider().getFuturesAccount();
            futuresAvailableBalance = Optional.ofNullable(futuresAccount.getAssets())
                    .orElse(List.of())
                    .stream()
                    .filter(asset -> "USDT".equals(asset.getAsset()))
                    .findFirst()
                    .map(BinanceFuturesAccount.BinanceFuturesAsset::getAvailableBalance)
                    .orElse(BigDecimal.ZERO);
            marginRatio = calculateMarginRatio(futuresAccount);
            futuresAccountLoaded = true;
        } catch (Exception e) {
            log.error("Failed to fetch Futures account info during risk check", e);
        }

        return new RiskMetrics(
                defaultValue(spotUsdtBalance),
                defaultValue(futuresAvailableBalance),
                requiredMargin,
                bufferedRequiredMargin,
                marginRatio,
                spotAccountLoaded,
                futuresAccountLoaded
        );
    }

    private BigDecimal calculateMarginRatio(BinanceFuturesAccount futuresAccount) {
        if (futuresAccount == null) {
            return null;
        }
        BigDecimal marginBalance = defaultValue(futuresAccount.getTotalMarginBalance());
        BigDecimal maintMargin = defaultValue(futuresAccount.getTotalMaintMargin());
        if (maintMargin.compareTo(BigDecimal.ZERO) == 0) {
            // 没有维持保证金通常表示没有风险仓位，返回一个极高健康值，避免被误判为不健康。
            return new BigDecimal("999");
        }
        return marginBalance.divide(maintMargin, 2, RoundingMode.HALF_DOWN);
    }

    private BinanceQueryDataProvider getQueryDataProvider() {
        if (queryDataProvider != null) {
            return queryDataProvider;
        }
        // 默认回退到 HTTP 查询实现; 测试或运行时可注入替代 provider，降低对具体数据源的耦合。
        return new com.metaverse.coin.strategy.core.arbitrage.market.HttpBinanceQueryDataProvider(binanceApiService);
    }

    private record VwapEstimate(BigDecimal vwap, BigDecimal availableQty, boolean sufficient) {
    }

    private record RiskMetrics(BigDecimal spotUsdtBalance,
                               BigDecimal futuresAvailableBalance,
                               BigDecimal requiredMargin,
                               BigDecimal bufferedRequiredMargin,
                               BigDecimal marginRatio,
                               boolean spotAccountLoaded,
                               boolean futuresAccountLoaded) {
    }
}
