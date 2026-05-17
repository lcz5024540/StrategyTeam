package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class LoggingPolymarketExecutionGateway implements PolymarketExecutionGateway {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketOrderRequestFactory orderRequestFactory;
    private final PolymarketClobClientRouter clobClientRouter;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletProperties properties;
    /**
     * 第十二批: MAKER_ONLY 超时撤单 tracker, 可选注入. enable 开关在 tracker 内自查;
     * sink 缺失或异常都不阻塞 gateway 主流程.
     */
    @Autowired(required = false)
    private MakerOnlyOrderTimeoutTracker makerOnlyOrderTimeoutTracker;
    /**
     * F-P1-3: GTC 全局超时撤单 tracker, 可选注入. enable 开关在 tracker 内自查;
     * tracker 缺失或异常都不阻塞 gateway 主流程 (与 MakerOnly 同模式).
     */
    @Autowired(required = false)
    private GtcOrderTimeoutTracker gtcOrderTimeoutTracker;

    @Override
    public SmartWalletExecutionResult execute(SmartWalletSignal signal, ExecutionMode mode) {
        String marketId = signal.getMarket() == null ? null : signal.getMarket().getMarketId();
        String question = signal.getMarket() == null ? null : signal.getMarket().getQuestion();
        String outcome = signal.getFollowOutcome();
        log.info("{} action=执行Polymarket跟单订单 result=开始 signalId={} mode={} marketId={} outcome={} requestedStakeUsdc={}",
                LOG_PREFIX, signal.getSignalId(), mode, marketId, outcome, signal.getRecommendedStakeUsdc());
        if (mode == ExecutionMode.LIVE) {
            ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
            log.info("{} action=加载LIVE执行钱包 result=完成 signalId={} address={} funderAddress={} orderType={} minOrderUsdc={} maxOrderUsdc={}",
                    LOG_PREFIX, signal.getSignalId(), wallet.getAddress(), wallet.getFunderAddress(), wallet.getOrderType(),
                    wallet.getMinOrderUsdc(), wallet.getMaxOrderUsdc());
            PolymarketOrderRequestFactory.PreparedPolymarketOrder order = orderRequestFactory.prepare(signal, wallet);
            // W2-T4.3: 日志包含 priceImpactMode / orderStrategy / override 三元组, 方便运维基于 trace_id 反查路由原因
            log.info("{} action=准备LIVE CLOB下单参数 result=完成 signalId={} marketId={} outcome={} tokenId={} side={} limitPrice={} size={} stakeUsdc={} tickSize={} negRisk={} orderStrategy={} priceImpactMode={} priceImpactOverride={}",
                    LOG_PREFIX, signal.getSignalId(), order.getMarketId(), order.getOutcome(), order.getTokenId(),
                    order.getSide(), order.getLimitPrice(), order.getSize(), order.getStakeUsdc(), order.getTickSize(),
                    order.isNegRisk(), order.getOrderStrategy(), order.getPriceImpactExecutionMode(),
                    order.isPriceImpactOverride());
            Map<String, Object> response = clobClientRouter.placeOrder(wallet, order);
            @SuppressWarnings("unchecked")
            Map<String, Object> orderResponse = response.get("response") instanceof Map<?, ?>
                    ? (Map<String, Object>) response.get("response")
                    : Map.of();
            String orderId = stringify(firstNonNull(response.get("orderID"), response.get("orderId"), response.get("id")));
            boolean success = truthy(firstNonNull(response.get("success"), response.get("ok"))) || orderId != null;
            String rawStatus = stringify(firstNonNull(
                    response.get("status"),
                    orderResponse.get("status"),
                    orderResponse.get("orderStatus"),
                    orderResponse.get("state")
            ));
            String normalizedStatus = normalizeLiveStatus(rawStatus, orderId, success);
            java.math.BigDecimal liveNotional = order.getStakeUsdc();
            java.math.BigDecimal liveFee = estimateFee(liveNotional, properties.getExecutionWallet().getEstimatedFeeRate());
            Integer liveIndexSet = resolveLiveIndexSet(order.getMarketId(), order.getTokenId(), order.getQuestion(), order.getOutcome());
            log.info("{} action=提交LIVE CLOB下单 result={} signalId={} marketId={} outcome={} tokenId={} orderId={} rawStatus={} normalizedStatus={} notionalUsdc={} feeUsdc={} message={}",
                    LOG_PREFIX, success ? "成功" : "失败", signal.getSignalId(), order.getMarketId(), order.getOutcome(),
                    order.getTokenId(), orderId, rawStatus, normalizedStatus, liveNotional, liveFee,
                    stringify(firstNonNull(response.get("message"), rawStatus)));
            // 第十二批 W2-T4: 成功 LIVE 下单且为 MAKER_ONLY 时注册超时撤单
            if (success && orderId != null && makerOnlyOrderTimeoutTracker != null) {
                try {
                    makerOnlyOrderTimeoutTracker.register(orderId, wallet.getAddress(),
                            order.getPriceImpactExecutionMode());
                } catch (RuntimeException trackerErr) {
                    log.warn("{} makerOnly tracker register failed orderId={} mode={} err={}",
                            LOG_PREFIX, orderId, order.getPriceImpactExecutionMode(),
                            trackerErr.toString());
                }
            }
            // F-P1-3: 所有成功 LIVE GTC 单加全局超时兜底（MAKER_ONLY 已有专用 tracker，此处仍注册保持
            // "未成交 60s 取消" 的兜底语义；MakerOnly tracker 先撤掉时 GtcOrderTimeoutTracker 的 scan
            // 调 cancelOrder 收到 404 / already-cancelled 也会被吞并标 CANCELLED，副作用可忽略）。
            if (success && orderId != null && gtcOrderTimeoutTracker != null
                    && "GTC".equalsIgnoreCase(wallet.getOrderType())) {
                try {
                    gtcOrderTimeoutTracker.register(
                            null, // smart_wallet_order.id 由 OrderPersistenceService 后续落库，此处尚不可得
                            orderId,
                            wallet.getAddress(),
                            order.getOrderStrategy(),
                            order.getMarketId(),
                            order.getOutcome(),
                            null);
                } catch (RuntimeException trackerErr) {
                    log.warn("{} gtc tracker register failed externalOrderId={} strategy={} err={}",
                            LOG_PREFIX, orderId, order.getOrderStrategy(), trackerErr.toString());
                }
            }
            return SmartWalletExecutionResult.builder()
                    .signalId(signal.getSignalId())
                    .mode(mode)
                    .success(success)
                    .message(stringify(firstNonNull(response.get("message"), rawStatus, "LIVE order submitted to Polymarket CLOB.")))
                    .externalOrderId(orderId)
                    .executionAddress(wallet.getAddress())
                    .marketId(order.getMarketId())
                    .tokenId(order.getTokenId())
                    .indexSet(liveIndexSet)
                    .outcome(order.getOutcome())
                    .side(order.getSide())
                    .orderType(wallet.getOrderType())
                    .status(normalizedStatus)
                    .price(order.getLimitPrice())
                    .size(order.getSize())
                    .notionalUsdc(liveNotional)
                    .feeUsdc(liveFee)
                    .tradingFeeUsdc(liveFee)
                    .build();
        }
        java.math.BigDecimal paperNotional = signal.getRecommendedStakeUsdc();
        java.math.BigDecimal paperPrice    = signal.getMarket() == null ? null : signal.getMarket().getPrice();
        java.math.BigDecimal paperFee = estimateFee(paperNotional, properties.getExecutionWallet().getEstimatedFeeRate());
        PaperTokenInfo paperTokenInfo = resolvePaperTokenInfo(marketId, question, outcome);
        log.info("{} action=创建PAPER模拟跟单订单 result=成功 signalId={} marketId={} outcome={} tokenId={} indexSet={} price={} notionalUsdc={} feeUsdc={} externalOrderId={}",
                LOG_PREFIX, signal.getSignalId(), marketId, outcome, paperTokenInfo.tokenId(), paperTokenInfo.indexSet(),
                paperPrice, paperNotional, paperFee, "paper-" + signal.getSignalId());
        return SmartWalletExecutionResult.builder()
                .signalId(signal.getSignalId())
                .mode(ExecutionMode.PAPER)
                .success(true)
                .message("Paper order created from smart-wallet signal.")
                .externalOrderId("paper-" + signal.getSignalId())
                .marketId(marketId)
                .tokenId(paperTokenInfo.tokenId())
                .indexSet(paperTokenInfo.indexSet())
                .outcome(outcome)
                .side("BUY")
                .orderType("GTC")
                .status("FILLED")
                .price(paperPrice)
                .notionalUsdc(paperNotional)
                .feeUsdc(paperFee)
                .tradingFeeUsdc(paperFee)
                .build();
    }

    private Object firstNonNull(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** Taker fee = notional * feeRate (single-leg, entry only). Returns ZERO when inputs are null. */
    private java.math.BigDecimal estimateFee(java.math.BigDecimal notional, java.math.BigDecimal feeRate) {
        if (notional == null || feeRate == null) {
            return java.math.BigDecimal.ZERO;
        }
        return notional.multiply(feeRate).setScale(8, java.math.RoundingMode.HALF_UP);
    }

    private String normalizeLiveStatus(String rawStatus, String orderId, boolean success) {
        if (rawStatus != null) {
            String normalized = rawStatus.trim().toUpperCase();
            if ("OPEN".equals(normalized)
                    || "PLACED".equals(normalized)
                    || "PENDING".equals(normalized)
                    || "UNMATCHED".equals(normalized)) {
                return "LIVE";
            }
            if ("PARTIAL".equals(normalized)) {
                return "PARTIALLY_FILLED";
            }
            return normalized;
        }
        if (success && orderId != null) {
            return "LIVE";
        }
        return "FAILED";
    }

    /** Best-effort resolve indexSet for a live order from cached/fetched metadata. */
    private Integer resolveLiveIndexSet(String marketId, String tokenId, String questionHint, String outcome) {
        if (!org.springframework.util.StringUtils.hasText(marketId)
                || !org.springframework.util.StringUtils.hasText(tokenId)) {
            return null;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId, false, questionHint,
                    outcome == null ? java.util.List.of() : java.util.List.of(outcome));
            return metadata == null ? null : metadata.resolveIndexSet(tokenId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Resolved paper token info: tokenId + indexSet.
     */
    private record PaperTokenInfo(String tokenId, Integer indexSet) {}

    private PaperTokenInfo resolvePaperTokenInfo(String marketId, String questionHint, String outcome) {
        if (!org.springframework.util.StringUtils.hasText(marketId)
                || !org.springframework.util.StringUtils.hasText(outcome)) {
            return new PaperTokenInfo(null, null);
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId,
                    false,
                    questionHint,
                    java.util.List.of(outcome)
            );
            if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
                return new PaperTokenInfo(null, null);
            }
            String tokenId = metadata.getOutcomeTokenIds().entrySet().stream()
                    .filter(entry -> org.springframework.util.StringUtils.hasText(entry.getKey())
                            && org.springframework.util.StringUtils.hasText(entry.getValue()))
                    .filter(entry -> outcome.trim().equalsIgnoreCase(entry.getKey().trim()))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            Integer indexSet = tokenId != null ? metadata.resolveIndexSet(tokenId) : null;
            return new PaperTokenInfo(tokenId, indexSet);
        } catch (RuntimeException ignored) {
            return new PaperTokenInfo(null, null);
        }
    }
}
