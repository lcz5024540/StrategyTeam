package com.metaverse.coin.strategy.job.btcminute;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.BtcMinutePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.BtcMinutePositionEntity;
import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcMinuteMarketSpec;
import com.metaverse.coin.strategy.job.btcminute.model.BtcMinuteSignal;
import com.metaverse.coin.strategy.job.btcminute.service.BtcFairProbabilityService;
import com.metaverse.coin.strategy.job.btcminute.service.BtcMinuteMarketScanService;
import com.metaverse.coin.strategy.job.btcminute.service.BtcMinuteRiskService;
import com.metaverse.coin.strategy.job.btcminute.service.BtcPriceFeedService;
import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyMode;
import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyExitResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import com.metaverse.coin.strategy.job.engine.model.TradingSignal;
import com.metaverse.coin.strategy.job.smartwallet.service.ExecutionWalletContextLoader;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobClientRouter;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketOrderRequestFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * BTC 5 分钟涨跌策略主入口。
 *
 * <p>实现 {@link PolymarketStrategy} 接口，由 StrategyRegistry 自动发现注册。</p>
 *
 * <p>策略流程：</p>
 * <ol>
 *   <li>扫描 Polymarket BTC 短期市场</li>
 *   <li>为每个市场计算公允概率</li>
 *   <li>与市场价格比较，识别 Edge</li>
 *   <li>风控检查（Edge、持仓数、日亏损等）</li>
 *   <li>下单（Paper 记录 / Live CLOB 限价单）</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BtcMinuteStrategyJob implements PolymarketStrategy {

    private static final String LOG_PREFIX = "[BTC_MINUTE]";
    private static final String STRATEGY_ID = "BTC_MINUTE";

    private final BtcMinuteStrategyProperties properties;
    private final BtcPriceFeedService priceFeedService;
    private final BtcMinuteMarketScanService marketScanService;
    private final BtcFairProbabilityService fairProbabilityService;
    private final BtcMinuteRiskService riskService;
    private final BtcMinutePositionMapper positionMapper;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketClobClientRouter clobClientRouter;
    private final PolymarketOrderRequestFactory orderRequestFactory;

    // ── PolymarketStrategy 接口实现 ──────────────────────────────────

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getDisplayName() {
        return "BTC 5分钟涨跌策略";
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public String getWalletId() {
        return properties.getWalletId();
    }

    @Override
    public StrategyMode getMode() {
        try {
            return StrategyMode.valueOf(properties.getMode().toUpperCase());
        } catch (Exception e) {
            return StrategyMode.OFF;
        }
    }

    // ── 信号扫描（定时调度） ──────────────────────────────────────────

    @Scheduled(fixedDelayString = "${btc-minute.scan-interval-ms:30000}")
    public void scheduledCycle() {
        if (!isEnabled() || !getMode().isActive()) return;
        try {
            runCycle();
        } catch (Exception e) {
            log.error("{} 定时信号扫描异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    @Override
    public StrategyCycleResult runCycle() {
        Instant start = Instant.now();
        List<TradingSignal> signals = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int ordersPlaced = 0;
        int signalsRejected = 0;

        try {
            // 1. 扫描可交易的 BTC 短期市场
            List<BtcMinuteMarketSpec> markets = marketScanService.scanEligibleMarkets();
            if (markets.isEmpty()) {
                log.debug("{} 无符合条件的 BTC 市场", LOG_PREFIX);
                return buildCycleResult(start, 0, 0, 0, signals, errors);
            }

            // 2. 逐个市场计算公允概率和 Edge
            for (BtcMinuteMarketSpec spec : markets) {
                try {
                    BtcMinuteSignal signal = evaluateMarket(spec);
                    if (signal == null) continue;

                    // 3. 风控检查
                    String rejectReason = riskService.evaluate(signal);
                    if (rejectReason != null) {
                        log.info("{} 信号被风控拒绝: market={} reason={}", LOG_PREFIX, spec.getConditionId(), rejectReason);
                        signalsRejected++;
                        continue;
                    }

                    // 4. 计算仓位
                    BigDecimal stake = riskService.calculateStake(signal);
                    signal.setSuggestedStakeUsdc(stake);

                    // 转换为统一 TradingSignal
                    TradingSignal tradingSignal = toTradingSignal(signal);
                    signals.add(tradingSignal);

                    // 5. 执行下单
                    boolean placed = executeOrder(signal);
                    if (placed) ordersPlaced++;

                } catch (Exception e) {
                    String errMsg = String.format("评估市场 %s 异常: %s", spec.getConditionId(), e.getMessage());
                    errors.add(errMsg);
                    log.error("{} {}", LOG_PREFIX, errMsg, e);
                }
            }

            log.info("{} 扫描完成: markets={} signals={} rejected={} orders={}",
                    LOG_PREFIX, markets.size(), signals.size(), signalsRejected, ordersPlaced);

        } catch (Exception e) {
            errors.add("runCycle 异常: " + e.getMessage());
            log.error("{} runCycle 异常: {}", LOG_PREFIX, e.getMessage(), e);
        }

        return buildCycleResult(start, signals.size(), ordersPlaced, signalsRejected, signals, errors);
    }

    // ── 退出扫描 ──────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000) // 每分钟检查
    public void scheduledExitScan() {
        if (!isEnabled() || !getMode().isActive()) return;
        try {
            runExitScan();
        } catch (Exception e) {
            log.error("{} 退出扫描异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    @Override
    public StrategyExitResult runExitScan() {
        Instant now = Instant.now();
        List<StrategyExitResult.ExitDetail> exits = new ArrayList<>();

        try {
            List<BtcMinutePositionEntity> openPositions = positionMapper.selectList(
                    new LambdaQueryWrapper<BtcMinutePositionEntity>()
                            .eq(BtcMinutePositionEntity::getStatus, "OPEN")
                            .eq(BtcMinutePositionEntity::getStrategyId, STRATEGY_ID));

            for (BtcMinutePositionEntity pos : openPositions) {
                // 到期退出：市场结算时间已过
                if (pos.getSettlementTime() != null && pos.getSettlementTime().before(new Date())) {
                    closePosition(pos, "SETTLEMENT");
                    exits.add(StrategyExitResult.ExitDetail.builder()
                            .marketId(pos.getMarketId())
                            .outcome(pos.getOutcome())
                            .exitReason("SETTLEMENT")
                            .build());
                    continue;
                }

                // 订单超时：持仓时间超过配置的超时时间
                if (pos.getCreatedAt() != null) {
                    long ageSeconds = (System.currentTimeMillis() - pos.getCreatedAt().getTime()) / 1000;
                    if (ageSeconds > properties.getOrderTimeoutSeconds()) {
                        closePosition(pos, "TIMEOUT");
                        exits.add(StrategyExitResult.ExitDetail.builder()
                                .marketId(pos.getMarketId())
                                .outcome(pos.getOutcome())
                                .exitReason("TIMEOUT")
                                .build());
                    }
                }
            }

            return StrategyExitResult.builder()
                    .strategyId(STRATEGY_ID)
                    .scannedAt(now)
                    .positionsScanned(openPositions.size())
                    .exitsTriggered(exits.size())
                    .exitDetails(exits)
                    .build();

        } catch (Exception e) {
            log.error("{} 退出扫描异常: {}", LOG_PREFIX, e.getMessage(), e);
            return StrategyExitResult.builder()
                    .strategyId(STRATEGY_ID).scannedAt(now)
                    .positionsScanned(0).exitsTriggered(0)
                    .build();
        }
    }

    // ── 健康检查 ──────────────────────────────────────────────────────

    @Override
    public StrategyHealthSnapshot health() {
        long openCount = positionMapper.selectCount(
                new LambdaQueryWrapper<BtcMinutePositionEntity>()
                        .eq(BtcMinutePositionEntity::getStatus, "OPEN")
                        .eq(BtcMinutePositionEntity::getStrategyId, STRATEGY_ID));

        BigDecimal totalExposure = BigDecimal.ZERO;
        List<BtcMinutePositionEntity> opens = positionMapper.selectList(
                new LambdaQueryWrapper<BtcMinutePositionEntity>()
                        .eq(BtcMinutePositionEntity::getStatus, "OPEN")
                        .eq(BtcMinutePositionEntity::getStrategyId, STRATEGY_ID));
        for (BtcMinutePositionEntity p : opens) {
            if (p.getStakeUsdc() != null) totalExposure = totalExposure.add(p.getStakeUsdc());
        }

        return StrategyHealthSnapshot.builder()
                .strategyId(STRATEGY_ID)
                .checkedAt(Instant.now())
                .healthy(priceFeedService.isConnected() || priceFeedService.getLatestPrice() != null)
                .openPositions((int) openCount)
                .totalExposureUsdc(totalExposure)
                .notes(priceFeedService.isConnected() ? "WebSocket connected" : "REST fallback")
                .build();
    }

    // ── 内部方法 ──────────────────────────────────────────────────────

    private BtcMinuteSignal evaluateMarket(BtcMinuteMarketSpec spec) {
        BtcFairProbabilityService.FairProbResult result = fairProbabilityService.calculate(spec);
        if (!result.isReady()) return null;

        BigDecimal fairProb = result.getFairProbability();
        BigDecimal marketPrice = spec.getYesPrice();

        // 判断交易方向：如果公允概率 > 市场价格 → 买 YES，反之 → 买 NO
        String outcome;
        BigDecimal edge;
        BigDecimal targetMarketPrice;

        if (fairProb.compareTo(marketPrice) > 0) {
            outcome = "YES";
            edge = fairProb.subtract(marketPrice);
            targetMarketPrice = marketPrice;
        } else {
            outcome = "NO";
            edge = marketPrice.subtract(fairProb);
            targetMarketPrice = spec.getNoPrice() != null ? spec.getNoPrice()
                    : BigDecimal.ONE.subtract(marketPrice);
        }

        if (edge.compareTo(properties.getMinEdgeThreshold()) < 0) {
            return null; // Edge 不足，不产生信号
        }

        return BtcMinuteSignal.builder()
                .marketId(spec.getConditionId())
                .marketTitle(spec.getTitle())
                .outcome(outcome)
                .btcPrice(result.getBtcPrice())
                .fairProbability(fairProb)
                .marketPrice(targetMarketPrice)
                .edge(edge)
                .confidence(fairProb)
                .settlementTime(spec.getSettlementTime())
                .minutesToSettlement(spec.getWindowMinutes())
                .signalTime(Instant.now())
                .rsi(result.getRsi())
                .emaDiff(result.getEmaDiff())
                .volatility(result.getVolatility())
                .build();
    }

    private boolean executeOrder(BtcMinuteSignal signal) {
        StrategyMode mode = getMode();

        if (mode == StrategyMode.SHADOW) {
            log.info("{} [SHADOW] 信号: market={} outcome={} edge={} fairProb={} marketPrice={}",
                    LOG_PREFIX, signal.getMarketId(), signal.getOutcome(),
                    signal.getEdge(), signal.getFairProbability(), signal.getMarketPrice());
            return false;
        }

        if (mode == StrategyMode.PAPER || mode == StrategyMode.LIVE) {
            // 记录仓位
            BtcMinutePositionEntity position = new BtcMinutePositionEntity()
                    .setStrategyId(STRATEGY_ID)
                    .setWalletId(properties.getWalletId())
                    .setMarketId(signal.getMarketId())
                    .setMarketTitle(signal.getMarketTitle())
                    .setOutcome(signal.getOutcome())
                    .setSide("BUY")
                    .setBtcEntryPrice(signal.getBtcPrice())
                    .setEntryPrice(signal.getMarketPrice())
                    .setCurrentPrice(signal.getMarketPrice())
                    .setStakeUsdc(signal.getSuggestedStakeUsdc())
                    .setFairProbability(signal.getFairProbability())
                    .setEdge(signal.getEdge())
                    .setRsiAtEntry(signal.getRsi())
                    .setVolatilityAtEntry(signal.getVolatility())
                    .setStatus("OPEN")
                    .setExecutionMode(mode == StrategyMode.LIVE ? "LIVE" : "PAPER")
                    .setSettlementTime(signal.getSettlementTime() != null ? Date.from(signal.getSettlementTime()) : null)
                    .setSignalReason(String.format("edge=%.4f fairProb=%.4f rsi=%.1f",
                            signal.getEdge(), signal.getFairProbability(),
                            signal.getRsi() != null ? signal.getRsi() : BigDecimal.ZERO))
                    .setCreatedAt(new Date())
                    .setUpdatedAt(new Date());

            // 计算 quantity = stake / price
            if (signal.getMarketPrice() != null && signal.getMarketPrice().compareTo(BigDecimal.ZERO) > 0) {
                position.setQuantity(signal.getSuggestedStakeUsdc()
                        .divide(signal.getMarketPrice(), 4, RoundingMode.HALF_UP));
            }

            positionMapper.insert(position);
            log.info("{} [{}] 建仓: market={} outcome={} stake={} edge={}",
                    LOG_PREFIX, mode, signal.getMarketId(), signal.getOutcome(),
                    signal.getSuggestedStakeUsdc(), signal.getEdge());

            // LIVE 模式：通过 PolymarketClobClientRouter 下真实 CLOB V2 订单
            if (mode == StrategyMode.LIVE) {
                try {
                    ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
                    PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = orderRequestFactory.prepareLocalDraft(
                            "btc-minute-" + position.getId(),
                            signal.getMarketId(),
                            signal.getMarketTitle(),
                            signal.getOutcome(),
                            signal.getSuggestedStakeUsdc(),
                            signal.getMarketPrice(),
                            null, // tokenId — will be resolved by factory via market metadata
                            new BigDecimal("0.01"), // default tick size
                            false, // negRisk — BTC short-term markets are standard
                            wallet
                    );
                    Map<String, Object> result = clobClientRouter.placeOrder(wallet, prepared);
                    String orderId = result.get("orderID") != null ? result.get("orderID").toString() : null;
                    position.setExternalOrderId(orderId);
                    positionMapper.updateById(position);
                    log.info("{} [LIVE] CLOB订单已提交: market={} outcome={} orderId={}",
                            LOG_PREFIX, signal.getMarketId(), signal.getOutcome(), orderId);
                } catch (Exception e) {
                    log.error("{} [LIVE] CLOB订单提交失败: market={} outcome={} error={}",
                            LOG_PREFIX, signal.getMarketId(), signal.getOutcome(), e.getMessage(), e);
                    position.setStatus("FAILED");
                    position.setSignalReason(position.getSignalReason() + " | LIVE_ERROR: " + e.getMessage());
                    positionMapper.updateById(position);
                }
            }

            return true;
        }

        return false;
    }

    private void closePosition(BtcMinutePositionEntity position, String reason) {
        position.setStatus("CLOSED");
        position.setUpdatedAt(new Date());
        // TODO: 计算 realizedPnlUsdc（需要查询结算结果或当前市价）
        positionMapper.updateById(position);
        log.info("{} 仓位关闭: market={} outcome={} reason={}", LOG_PREFIX,
                position.getMarketId(), position.getOutcome(), reason);
    }

    private TradingSignal toTradingSignal(BtcMinuteSignal signal) {
        return TradingSignal.builder()
                .strategyId(STRATEGY_ID)
                .marketId(signal.getMarketId())
                .outcome(signal.getOutcome())
                .side("BUY")
                .targetPrice(signal.getMarketPrice())
                .stakeUsdc(signal.getSuggestedStakeUsdc())
                .confidence(signal.getConfidence())
                .signalReason(String.format("BTC=%s edge=%.4f fairProb=%.4f rsi=%.1f",
                        signal.getBtcPrice(), signal.getEdge(), signal.getFairProbability(),
                        signal.getRsi() != null ? signal.getRsi() : BigDecimal.ZERO))
                .signalTime(signal.getSignalTime())
                .metadata(Map.of(
                        "btcPrice", signal.getBtcPrice(),
                        "volatility", signal.getVolatility() != null ? signal.getVolatility() : BigDecimal.ZERO,
                        "minutesToSettlement", signal.getMinutesToSettlement()))
                .build();
    }

    private StrategyCycleResult buildCycleResult(Instant start, int signalsFound, int ordersPlaced,
                                                  int signalsRejected, List<TradingSignal> signals,
                                                  List<String> errors) {
        return StrategyCycleResult.builder()
                .strategyId(STRATEGY_ID)
                .startedAt(start)
                .duration(Duration.between(start, Instant.now()))
                .signalsFound(signalsFound)
                .ordersPlaced(ordersPlaced)
                .signalsRejected(signalsRejected)
                .errors(errors.size())
                .signals(signals)
                .errorMessages(errors)
                .fatalError(false)
                .build();
    }
}
