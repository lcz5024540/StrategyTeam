package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Mirrors filled trend positions to a secondary HTX swap account. */
@Slf4j
@Component
@ConditionalOnBean(HtxTrendProperties.class)
public class HtxTrendMirrorExecutor {

    private static final String LONG = "LONG";
    private static final String POSITION_SIDE_LONG = "long";
    private static final String POSITION_SIDE_SHORT = "short";
    private static final String SIDE_BUY = "buy";
    private static final String SIDE_SELL = "sell";
    private static final String ORDER_TYPE_MARKET = "market";
    private static final long PENDING_MIRROR_ORDER_TTL_MS = 120_000L;

    private final HtxTrendProperties properties;
    private final HtxContractProperties contractProperties;
    private final HtxUsdtSwapService mirrorSwap;
    private final HtxTrendRecorder recorder;
    private final HtxSwapFeeRateResolver feeRateResolver;
    private final Map<String, PendingMirrorOrder> pendingCloseOrders = new ConcurrentHashMap<>();

    @Autowired
    public HtxTrendMirrorExecutor(HtxTrendProperties properties, HtxContractProperties contractProperties,
                                  HtxTrendRecorder recorder) {
        this(properties, contractProperties, createMirrorSwap(properties, contractProperties), recorder);
    }

    HtxTrendMirrorExecutor(HtxTrendProperties properties, HtxContractProperties contractProperties,
                           HtxUsdtSwapService mirrorSwap) {
        this(properties, contractProperties, mirrorSwap, null);
    }

    HtxTrendMirrorExecutor(HtxTrendProperties properties, HtxContractProperties contractProperties,
                           HtxUsdtSwapService mirrorSwap, HtxTrendRecorder recorder) {
        this.properties = properties;
        this.contractProperties = contractProperties;
        this.mirrorSwap = mirrorSwap;
        this.recorder = recorder;
        this.feeRateResolver = new HtxSwapFeeRateResolver(mirrorSwap,
                properties::getEstimatedTakerFeeRate, properties::getFeeRateCacheMs, accountLabel());
    }

    public SyncResult syncEntry(HtxTrendPositionEntity position) {
        if (!enabled()) {
            return SyncResult.skipped("mirror disabled");
        }
        if (position == null || position.getContractVolume() == null || position.getContractVolume() <= 0L) {
            return SyncResult.failed("mirror entry skipped: invalid primary position");
        }
        String contractCode = position.getContractCode();
        String positionSide = positionSide(position.getSide());
        PositionSnapshot snapshot = positionSnapshot(contractCode, positionSide);
        if (!snapshot.ok()) {
            return SyncResult.failed(snapshot.message());
        }
        if (snapshot.oppositeVolume().signum() > 0) {
            return SyncResult.failed("mirror entry blocked: opposite position exists account=" + accountLabel()
                    + " contract=" + contractCode + " oppositeVolume=" + snapshot.oppositeVolume());
        }
        long targetVolume = position.getContractVolume();
        long currentVolume = snapshot.sameVolume().setScale(0, RoundingMode.DOWN).longValue();
        if (currentVolume >= targetVolume) {
            return SyncResult.ok("mirror entry already synced account=" + accountLabel()
                    + " contract=" + contractCode + " volume=" + currentVolume);
        }
        String openOrderGate = openOrderGate(contractCode);
        if (openOrderGate != null) {
            return SyncResult.failed(openOrderGate);
        }
        String leverageWarning = switchLeverage(contractCode, positionSide);
        if (leverageWarning != null && mirrorProperties().isBlockEntryOnLeverageSyncFailure()) {
            return SyncResult.failed(leverageWarning);
        }
        long deltaVolume = targetVolume - currentVolume;
        String marginGate = marginGate(contractCode, position.getContractSize(), deltaVolume,
                position.getEntryAvgPrice());
        if (marginGate != null) {
            return SyncResult.failed(marginGate);
        }
        BigDecimal takerFeeRate = feeRateResolver.takerRate(contractCode);
        HtxSwapOrderRequest request = orderRequest(contractCode, position.getSide(), false, deltaVolume);
        createMirrorOrderIntent(position, HtxTrendRecorder.LEG_ENTRY, request, deltaVolume, takerFeeRate,
                estimateFee(position.getEntryAvgPrice(), position.getContractSize(), deltaVolume, takerFeeRate),
                mirrorEntryReason(position, leverageWarning));
        HtxRestResponse<HtxSwapOrderResult> response = mirrorSwap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        if (response == null || !response.ok() || accepted == null || accepted.getOrderId() == null) {
            markMirrorOrderRejected(position, HtxTrendRecorder.LEG_ENTRY, request.getClientOrderId(),
                    "mirror entry rejected: " + error(response));
            return SyncResult.failed("mirror entry rejected account=" + accountLabel()
                    + " contract=" + contractCode + " error=" + error(response));
        }
        markMirrorOrderAccepted(position, HtxTrendRecorder.LEG_ENTRY, request.getClientOrderId(), accepted,
                "mirror entry accepted by exchange");
        log.info("[HTX-TREND-MIRROR] entry sent account={} contract={} side={} orderId={} volume={} feeRate={}",
                accountLabel(), contractCode, position.getSide(), accepted.getOrderId(), deltaVolume, takerFeeRate);
        return SyncResult.ok("mirror entry sent account=" + accountLabel() + " orderId=" + accepted.getOrderId()
                + " volume=" + deltaVolume + " feeRate=" + takerFeeRate);
    }

    public SyncResult syncClose(HtxTrendPositionEntity position) {
        if (!enabled()) {
            return SyncResult.ok("mirror disabled");
        }
        if (position == null || position.getContractVolume() == null || position.getContractVolume() <= 0L) {
            return SyncResult.ok("mirror close skipped: invalid primary position");
        }
        String contractCode = position.getContractCode();
        String positionSide = positionSide(position.getSide());
        PositionSnapshot snapshot = positionSnapshot(contractCode, positionSide);
        if (!snapshot.ok()) {
            return SyncResult.failed(snapshot.message());
        }
        String pendingKey = pendingKey(contractCode, positionSide);
        if (snapshot.sameVolume().signum() <= 0) {
            pendingCloseOrders.remove(pendingKey);
            return SyncResult.ok("mirror already flat account=" + accountLabel() + " contract=" + contractCode);
        }
        PendingMirrorOrder pending = pendingCloseOrders.get(pendingKey);
        if (pending != null && pending.isFresh()) {
            return SyncResult.pending("mirror close pending account=" + accountLabel()
                    + " contract=" + contractCode + " orderId=" + pending.orderId());
        }
        if (pending != null) {
            pendingCloseOrders.remove(pendingKey);
        }
        String openOrderGate = openOrderGate(contractCode);
        if (openOrderGate != null) {
            return SyncResult.failed(openOrderGate);
        }
        long closeVolume = snapshot.sameVolume().min(BigDecimal.valueOf(position.getContractVolume()))
                .setScale(0, RoundingMode.DOWN).longValue();
        if (closeVolume <= 0L) {
            return SyncResult.failed("mirror close skipped: position volume below one contract account=" + accountLabel()
                    + " contract=" + contractCode + " volume=" + snapshot.sameVolume());
        }
        BigDecimal takerFeeRate = feeRateResolver.takerRate(contractCode);
        HtxSwapOrderRequest request = orderRequest(contractCode, position.getSide(), true, closeVolume);
        createMirrorOrderIntent(position, HtxTrendRecorder.LEG_CLOSE, request, closeVolume, takerFeeRate,
                estimateFee(position.getEntryAvgPrice(), position.getContractSize(), closeVolume, takerFeeRate),
                "mirror exit signal: " + safe(position.getExitReason()));
        HtxRestResponse<HtxSwapOrderResult> response = mirrorSwap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        if (response == null || !response.ok() || accepted == null || accepted.getOrderId() == null) {
            markMirrorOrderRejected(position, HtxTrendRecorder.LEG_CLOSE, request.getClientOrderId(),
                    "mirror close rejected: " + error(response));
            return SyncResult.failed("mirror close rejected account=" + accountLabel()
                    + " contract=" + contractCode + " error=" + error(response));
        }
        markMirrorOrderAccepted(position, HtxTrendRecorder.LEG_CLOSE, request.getClientOrderId(), accepted,
                "mirror close accepted by exchange");
        pendingCloseOrders.put(pendingKey, new PendingMirrorOrder(accepted.getOrderId(), System.currentTimeMillis()));
        log.info("[HTX-TREND-MIRROR] close sent account={} contract={} side={} orderId={} volume={} feeRate={}",
                accountLabel(), contractCode, position.getSide(), accepted.getOrderId(), closeVolume, takerFeeRate);
        return SyncResult.pending("mirror close sent account=" + accountLabel() + " orderId=" + accepted.getOrderId()
                + " volume=" + closeVolume + " feeRate=" + takerFeeRate);
    }

    public boolean blockPrimaryCloseUntilSynced() {
        HtxTrendProperties.Mirror mirror = mirrorProperties();
        return mirror != null && mirror.isBlockPrimaryCloseUntilSynced();
    }

    private boolean enabled() {
        HtxTrendProperties.Mirror mirror = mirrorProperties();
        return mirror != null && mirror.isEnabled() && mirrorSwap != null;
    }

    private HtxTrendProperties.Mirror mirrorProperties() {
        return properties == null ? null : properties.getMirror();
    }

    private String accountLabel() {
        HtxTrendProperties.Mirror mirror = mirrorProperties();
        if (mirror == null || !StringUtils.hasText(mirror.getAccountLabel())) {
            return "mirror";
        }
        return mirror.getAccountLabel().trim();
    }

    private PositionSnapshot positionSnapshot(String contractCode, String positionSide) {
        HtxRestResponse<List<HtxSwapPosition>> response = mirrorSwap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return PositionSnapshot.failed("mirror position query failed account=" + accountLabel()
                    + " contract=" + contractCode + " error=" + error(response));
        }
        BigDecimal same = BigDecimal.ZERO;
        BigDecimal opposite = BigDecimal.ZERO;
        for (HtxSwapPosition position : response.payload()) {
            if (position == null || position.getVolume() == null || position.getVolume().signum() <= 0) {
                continue;
            }
            if (positionSide.equalsIgnoreCase(position.getPositionSide())) {
                same = same.add(position.getVolume());
            } else {
                opposite = opposite.add(position.getVolume());
            }
        }
        return PositionSnapshot.ok(same, opposite);
    }

    private String openOrderGate(String contractCode) {
        HtxRestResponse<HtxSwapOpenOrders> response = mirrorSwap.openOrders(contractCode);
        HtxSwapOpenOrders orders = response == null ? null : response.payload();
        if (response == null || !response.ok() || orders == null || orders.getOrders() == null) {
            return "mirror open orders query failed account=" + accountLabel()
                    + " contract=" + contractCode + " error=" + error(response);
        }
        if (!orders.getOrders().isEmpty()) {
            return "mirror has open orders account=" + accountLabel() + " contract=" + contractCode
                    + " count=" + orders.getOrders().size();
        }
        return null;
    }

    private String switchLeverage(String contractCode, String positionSide) {
        HtxTrendProperties.Mirror mirror = mirrorProperties();
        if (mirror == null || !mirror.isSyncLeverage()) {
            return null;
        }
        HtxRestResponse<?> response = mirrorSwap.switchLeverage(contractCode, positionSide, properties.getLeverage());
        if (response == null || !response.ok() || response.getData() == null) {
            String message = "mirror leverage switch failed account=" + accountLabel() + " contract=" + contractCode
                    + " positionSide=" + positionSide + " error=" + error(response);
            if (!mirror.isBlockEntryOnLeverageSyncFailure()) {
                log.warn("[HTX-TREND-MIRROR] leverage sync failed but entry will continue: {}", message);
            }
            return message;
        }
        return null;
    }

    private String mirrorEntryReason(HtxTrendPositionEntity position, String leverageWarning) {
        String reason = "mirror entry signal: " + safe(position.getEntrySignal());
        if (!StringUtils.hasText(leverageWarning)) {
            return reason;
        }
        return reason + "; " + leverageWarning;
    }

    private String marginGate(String contractCode, BigDecimal contractSize, long volume, BigDecimal referencePrice) {
        if (!positive(contractSize) || volume <= 0L) {
            return "mirror margin skipped: invalid size account=" + accountLabel() + " contract=" + contractCode;
        }
        BigDecimal price = positive(referencePrice) ? referencePrice : BigDecimal.ZERO;
        if (!positive(price)) {
            return null;
        }
        HtxRestResponse<List<HtxSwapAccount>> response = mirrorSwap.accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "mirror margin unavailable account=" + accountLabel() + " contract=" + contractCode
                    + " error=" + error(response);
        }
        BigDecimal margin = response.payload().stream()
                .filter(item -> item != null && item.getMarginAvailable() != null)
                .map(HtxSwapAccount::getMarginAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal notional = BigDecimal.valueOf(volume).multiply(contractSize).multiply(price);
        BigDecimal required = notional.divide(BigDecimal.valueOf(Math.max(1, properties.getLeverage())),
                18, RoundingMode.UP).multiply(contractProperties.getMarginSafetyRatio());
        if (required.compareTo(properties.getMinContractMarginAvailable()) < 0) {
            required = properties.getMinContractMarginAvailable();
        }
        if (margin.compareTo(required) < 0) {
            return "mirror margin insufficient account=" + accountLabel() + " contract=" + contractCode
                    + " available=" + margin + " required=" + required;
        }
        return null;
    }

    private void createMirrorOrderIntent(HtxTrendPositionEntity position, String leg, HtxSwapOrderRequest request,
                                         long volume, BigDecimal takerFeeRate, BigDecimal estimatedFee, String reason) {
        if (recorder == null) {
            return;
        }
        HtxTrendOrderEntity entity = new HtxTrendOrderEntity();
        entity.setPositionId(position.getId());
        entity.setPositionKey(position.getPositionKey());
        entity.setContractCode(request.getContractCode());
        entity.setLeg(leg);
        entity.setTrendSide(position.getSide());
        entity.setOrderSide(request.getSide());
        entity.setPositionSide(request.getPositionSide());
        entity.setReduceOnly(Boolean.TRUE.equals(request.getReduceOnly()));
        entity.setClientOrderId(request.getClientOrderId());
        entity.setRequestVolume(volume);
        entity.setFilledContracts(BigDecimal.ZERO);
        entity.setFee(BigDecimal.ZERO);
        entity.setStatus(HtxTrendRecorder.ORDER_INTENT);
        entity.setReason(clip(reason));
        entity.setMessage("prepared mirror " + leg.toLowerCase(Locale.ROOT) + " order");
        entity.setAccountLabel(accountLabel());
        entity.setFeeRateUsed(takerFeeRate);
        entity.setEstimatedFee(estimatedFee);
        if (HtxTrendRecorder.LEG_CLOSE.equals(leg)) {
            entity.setEstimatedCloseFee(estimatedFee);
        } else {
            entity.setEstimatedOpenFee(estimatedFee);
        }
        entity.setSizingMultiplier(position.getSizingMultiplier());
        entity.setSizingReason(clip(position.getSizingReason()));
        entity.setEntryGateReason(clip(position.getEntryGateReason(), 1024));
        try {
            recorder.createOrderIntent(entity);
        } catch (HtxPersistenceException ex) {
            throw ex;
        }
    }

    private void markMirrorOrderAccepted(HtxTrendPositionEntity position, String leg, String clientOrderId,
                                         HtxSwapOrderResult accepted, String message) {
        if (recorder == null) {
            return;
        }
        recorder.markOrderAccepted(position.getId(), leg, clientOrderId, accepted.getOrderId(),
                accepted.getClientOrderId(), message);
    }

    private void markMirrorOrderRejected(HtxTrendPositionEntity position, String leg, String clientOrderId,
                                         String message) {
        if (recorder == null) {
            return;
        }
        recorder.markOrderRejected(position.getId(), leg, clientOrderId, message);
    }

    private static BigDecimal estimateFee(BigDecimal price, BigDecimal contractSize, long volume, BigDecimal feeRate) {
        if (!positive(price) || !positive(contractSize) || volume <= 0L || feeRate == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(contractSize).multiply(BigDecimal.valueOf(volume)).multiply(feeRate);
    }

    private HtxSwapOrderRequest orderRequest(String contractCode, String trendSide, boolean reduceOnly, long volume) {
        boolean longSide = LONG.equals(trendSide);
        String side = reduceOnly ? (longSide ? SIDE_SELL : SIDE_BUY) : (longSide ? SIDE_BUY : SIDE_SELL);
        String positionSide = longSide ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        return HtxUsdtSwapService.orderRequest(contractCode, numericClientOrderId(), volume,
                side, positionSide, reduceOnly, null, ORDER_TYPE_MARKET);
    }

    private static HtxUsdtSwapService createMirrorSwap(HtxTrendProperties properties,
                                                       HtxContractProperties contractProperties) {
        HtxTrendProperties.Mirror mirror = properties == null ? null : properties.getMirror();
        if (mirror == null || !mirror.isEnabled()) {
            return null;
        }
        if (!StringUtils.hasText(mirror.getAccessKey()) || !StringUtils.hasText(mirror.getSecretKey())) {
            log.warn("[HTX-TREND-MIRROR] mirror enabled but credentials are blank account={}",
                    StringUtils.hasText(mirror.getAccountLabel()) ? mirror.getAccountLabel() : "mirror");
            return null;
        }
        String baseUrl = StringUtils.hasText(mirror.getBaseUrl()) ? mirror.getBaseUrl() : contractProperties.getBaseUrl();
        return new HtxUsdtSwapApiService(mirror.getAccessKey(), mirror.getSecretKey(), baseUrl,
                contractProperties.getUidQueryLimit(), contractProperties.getUidTradeLimit(),
                contractProperties.getUidRateWindowMs()).getSwapService();
    }

    private static String positionSide(String trendSide) {
        return LONG.equals(trendSide) ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
    }

    private static String pendingKey(String contractCode, String positionSide) {
        return contractCode + "|" + positionSide;
    }

    private static long numericClientOrderId() {
        long millis = Instant.now().toEpochMilli();
        long suffix = ThreadLocalRandom.current().nextLong(100, 999);
        return millis * 1_000L + suffix;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String clip(String value) {
        return clip(value, 512);
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String error(HtxRestResponse<?> response) {
        if (response == null) {
            return "null response";
        }
        if (StringUtils.hasText(response.getErrMsg())) {
            return response.getErrMsg();
        }
        if (StringUtils.hasText(response.getMessage())) {
            return response.getMessage();
        }
        if (StringUtils.hasText(response.getErrCode())) {
            return response.getErrCode();
        }
        return "status=" + response.getStatus() + ", code=" + response.getCode();
    }

    public record SyncResult(boolean ok, boolean pending, String message) {
        static SyncResult ok(String message) {
            return new SyncResult(true, false, message);
        }

        static SyncResult pending(String message) {
            return new SyncResult(false, true, message);
        }

        static SyncResult skipped(String message) {
            return new SyncResult(true, false, message);
        }

        static SyncResult failed(String message) {
            return new SyncResult(false, false, message);
        }
    }

    private record PendingMirrorOrder(Long orderId, long createdAtMs) {
        boolean isFresh() {
            return System.currentTimeMillis() - createdAtMs <= PENDING_MIRROR_ORDER_TTL_MS;
        }
    }

    private record PositionSnapshot(boolean ok, BigDecimal sameVolume, BigDecimal oppositeVolume, String message) {
        static PositionSnapshot ok(BigDecimal sameVolume, BigDecimal oppositeVolume) {
            return new PositionSnapshot(true, sameVolume, oppositeVolume, null);
        }

        static PositionSnapshot failed(String message) {
            return new PositionSnapshot(false, BigDecimal.ZERO, BigDecimal.ZERO, message);
        }
    }
}
