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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Mirrors filled trend positions to DB-registered follower HTX swap accounts. */
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
    private final HtxTrendRecorder recorder;
    private final HtxTradeAccountService accountService;
    private final HtxTrendMirrorAccount fixedAccount;
    private final Map<String, PendingMirrorOrder> pendingCloseOrders = new ConcurrentHashMap<>();

    @Autowired
    public HtxTrendMirrorExecutor(HtxTrendProperties properties, HtxContractProperties contractProperties,
                                  HtxTrendRecorder recorder, HtxTradeAccountService accountService) {
        this.properties = properties;
        this.contractProperties = contractProperties;
        this.recorder = recorder;
        this.accountService = accountService;
        this.fixedAccount = null;
    }

    HtxTrendMirrorExecutor(HtxTrendProperties properties, HtxContractProperties contractProperties,
                           HtxUsdtSwapService mirrorSwap) {
        this(properties, contractProperties, mirrorSwap, null);
    }

    HtxTrendMirrorExecutor(HtxTrendProperties properties, HtxContractProperties contractProperties,
                           HtxUsdtSwapService mirrorSwap, HtxTrendRecorder recorder) {
        this.properties = properties;
        this.contractProperties = contractProperties;
        this.recorder = recorder;
        this.accountService = null;
        this.fixedAccount = legacyAccount(properties, contractProperties, mirrorSwap);
    }

    public SyncResult syncEntry(HtxTrendPositionEntity position) {
        List<HtxTrendMirrorAccount> accounts = mirrorAccounts();
        if (accounts.isEmpty()) {
            return SyncResult.skipped("mirror disabled");
        }
        if (position == null || position.getContractVolume() == null || position.getContractVolume() <= 0L) {
            return SyncResult.failed("mirror entry skipped: invalid primary position");
        }
        List<AccountSyncResult> results = new ArrayList<>();
        for (HtxTrendMirrorAccount account : accounts) {
            try {
                results.add(new AccountSyncResult(account, syncEntry(position, account)));
            } catch (RuntimeException ex) {
                results.add(new AccountSyncResult(account, SyncResult.failed("mirror entry exception account="
                        + account.accountLabel() + " error=" + ex.getClass().getSimpleName())));
            }
        }
        return combineEntry(results);
    }

    public SyncResult syncClose(HtxTrendPositionEntity position) {
        List<HtxTrendMirrorAccount> accounts = mirrorAccounts();
        if (accounts.isEmpty()) {
            return SyncResult.ok("mirror disabled");
        }
        if (position == null || position.getContractVolume() == null || position.getContractVolume() <= 0L) {
            return SyncResult.ok("mirror close skipped: invalid primary position");
        }
        List<AccountSyncResult> results = new ArrayList<>();
        for (HtxTrendMirrorAccount account : accounts) {
            try {
                results.add(new AccountSyncResult(account, syncClose(position, account)));
            } catch (RuntimeException ex) {
                results.add(new AccountSyncResult(account, SyncResult.failed("mirror close exception account="
                        + account.accountLabel() + " error=" + ex.getClass().getSimpleName())));
            }
        }
        return combineClose(results);
    }

    public boolean blockPrimaryCloseUntilSynced() {
        return mirrorAccounts().stream().anyMatch(HtxTrendMirrorAccount::blockPrimaryCloseUntilSynced);
    }

    private SyncResult syncEntry(HtxTrendPositionEntity position, HtxTrendMirrorAccount account) {
        String contractCode = position.getContractCode();
        String positionSide = positionSide(position.getSide());
        PositionSnapshot snapshot = positionSnapshot(account, contractCode, positionSide);
        if (!snapshot.ok()) {
            return SyncResult.failed(snapshot.message());
        }
        if (snapshot.oppositeVolume().signum() > 0) {
            return SyncResult.failed("mirror entry blocked: opposite position exists account=" + account.accountLabel()
                    + " contract=" + contractCode + " oppositeVolume=" + snapshot.oppositeVolume());
        }
        long targetVolume = position.getContractVolume();
        long currentVolume = snapshot.sameVolume().setScale(0, RoundingMode.DOWN).longValue();
        if (currentVolume >= targetVolume) {
            return SyncResult.ok("mirror entry already synced account=" + account.accountLabel()
                    + " contract=" + contractCode + " volume=" + currentVolume);
        }
        String openOrderGate = openOrderGate(account, contractCode);
        if (openOrderGate != null) {
            return SyncResult.failed(openOrderGate);
        }
        String leverageWarning = switchLeverage(account, contractCode, positionSide);
        if (leverageWarning != null && account.blockEntryOnLeverageSyncFailure()) {
            return SyncResult.failed(leverageWarning);
        }
        long deltaVolume = targetVolume - currentVolume;
        String marginGate = marginGate(account, contractCode, position.getContractSize(), deltaVolume,
                position.getEntryAvgPrice());
        if (marginGate != null) {
            return SyncResult.failed(marginGate);
        }
        BigDecimal takerFeeRate = account.feeRateResolver().takerRate(contractCode);
        HtxSwapOrderRequest request = orderRequest(contractCode, position.getSide(), false, deltaVolume);
        createMirrorOrderIntent(position, account, HtxTrendRecorder.LEG_ENTRY, request, deltaVolume, takerFeeRate,
                estimateFee(position.getEntryAvgPrice(), position.getContractSize(), deltaVolume, takerFeeRate),
                mirrorEntryReason(position, leverageWarning));
        HtxRestResponse<HtxSwapOrderResult> response = account.swap().placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        if (response == null || !response.ok() || accepted == null || accepted.getOrderId() == null) {
            markMirrorOrderRejected(position, HtxTrendRecorder.LEG_ENTRY, request.getClientOrderId(),
                    "mirror entry rejected: " + error(response));
            return SyncResult.failed("mirror entry rejected account=" + account.accountLabel()
                    + " contract=" + contractCode + " error=" + error(response));
        }
        markMirrorOrderAccepted(position, HtxTrendRecorder.LEG_ENTRY, request.getClientOrderId(), accepted,
                "mirror entry accepted by exchange");
        log.info("[HTX-TREND-MIRROR] entry sent account={} contract={} side={} orderId={} volume={} feeRate={}",
                account.accountLabel(), contractCode, position.getSide(), accepted.getOrderId(), deltaVolume, takerFeeRate);
        return SyncResult.ok("mirror entry sent account=" + account.accountLabel() + " orderId=" + accepted.getOrderId()
                + " volume=" + deltaVolume + " feeRate=" + takerFeeRate);
    }

    private SyncResult syncClose(HtxTrendPositionEntity position, HtxTrendMirrorAccount account) {
        String contractCode = position.getContractCode();
        String positionSide = positionSide(position.getSide());
        PositionSnapshot snapshot = positionSnapshot(account, contractCode, positionSide);
        if (!snapshot.ok()) {
            return SyncResult.failed(snapshot.message());
        }
        String pendingKey = pendingKey(account.accountLabel(), contractCode, positionSide);
        if (snapshot.sameVolume().signum() <= 0) {
            pendingCloseOrders.remove(pendingKey);
            return SyncResult.ok("mirror already flat account=" + account.accountLabel() + " contract=" + contractCode);
        }
        PendingMirrorOrder pending = pendingCloseOrders.get(pendingKey);
        if (pending != null && pending.isFresh()) {
            return SyncResult.pending("mirror close pending account=" + account.accountLabel()
                    + " contract=" + contractCode + " orderId=" + pending.orderId());
        }
        if (pending != null) {
            pendingCloseOrders.remove(pendingKey);
        }
        String openOrderGate = openOrderGate(account, contractCode);
        if (openOrderGate != null) {
            return SyncResult.failed(openOrderGate);
        }
        long closeVolume = snapshot.sameVolume().min(BigDecimal.valueOf(position.getContractVolume()))
                .setScale(0, RoundingMode.DOWN).longValue();
        if (closeVolume <= 0L) {
            return SyncResult.failed("mirror close skipped: position volume below one contract account="
                    + account.accountLabel() + " contract=" + contractCode + " volume=" + snapshot.sameVolume());
        }
        BigDecimal takerFeeRate = account.feeRateResolver().takerRate(contractCode);
        HtxSwapOrderRequest request = orderRequest(contractCode, position.getSide(), true, closeVolume);
        createMirrorOrderIntent(position, account, HtxTrendRecorder.LEG_CLOSE, request, closeVolume, takerFeeRate,
                estimateFee(position.getEntryAvgPrice(), position.getContractSize(), closeVolume, takerFeeRate),
                "mirror exit signal: " + safe(position.getExitReason()));
        HtxRestResponse<HtxSwapOrderResult> response = account.swap().placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        if (response == null || !response.ok() || accepted == null || accepted.getOrderId() == null) {
            markMirrorOrderRejected(position, HtxTrendRecorder.LEG_CLOSE, request.getClientOrderId(),
                    "mirror close rejected: " + error(response));
            return SyncResult.failed("mirror close rejected account=" + account.accountLabel()
                    + " contract=" + contractCode + " error=" + error(response));
        }
        markMirrorOrderAccepted(position, HtxTrendRecorder.LEG_CLOSE, request.getClientOrderId(), accepted,
                "mirror close accepted by exchange");
        pendingCloseOrders.put(pendingKey, new PendingMirrorOrder(accepted.getOrderId(), System.currentTimeMillis()));
        log.info("[HTX-TREND-MIRROR] close sent account={} contract={} side={} orderId={} volume={} feeRate={}",
                account.accountLabel(), contractCode, position.getSide(), accepted.getOrderId(), closeVolume, takerFeeRate);
        return SyncResult.pending("mirror close sent account=" + account.accountLabel() + " orderId=" + accepted.getOrderId()
                + " volume=" + closeVolume + " feeRate=" + takerFeeRate);
    }

    private SyncResult combineEntry(List<AccountSyncResult> results) {
        if (results.isEmpty()) {
            return SyncResult.skipped("mirror disabled");
        }
        boolean allOk = results.stream().allMatch(item -> item.result().ok());
        String message = joinMessages(results);
        return allOk ? SyncResult.ok(message) : SyncResult.failed(message);
    }

    private SyncResult combineClose(List<AccountSyncResult> results) {
        if (results.isEmpty()) {
            return SyncResult.ok("mirror disabled");
        }
        boolean blockingProblem = results.stream()
                .anyMatch(item -> !item.result().ok() && item.account().blockPrimaryCloseUntilSynced());
        String message = joinMessages(results);
        return blockingProblem ? SyncResult.pending(message) : SyncResult.ok(message);
    }

    private List<HtxTrendMirrorAccount> mirrorAccounts() {
        if (fixedAccount != null) {
            return List.of(fixedAccount);
        }
        if (accountService == null) {
            return List.of();
        }
        return accountService.trendMirrorAccounts(properties, contractProperties);
    }

    private static HtxTrendMirrorAccount legacyAccount(HtxTrendProperties properties,
                                                       HtxContractProperties contractProperties,
                                                       HtxUsdtSwapService mirrorSwap) {
        if (mirrorSwap == null) {
            return null;
        }
        HtxTrendProperties.Mirror mirror = properties == null ? null : properties.getMirror();
        String accountLabel = mirror == null || !StringUtils.hasText(mirror.getAccountLabel())
                ? "mirror" : mirror.getAccountLabel().trim();
        return new HtxTrendMirrorAccount(accountLabel, mirrorSwap,
                new HtxSwapFeeRateResolver(mirrorSwap, properties::getEstimatedTakerFeeRate,
                        properties::getFeeRateCacheMs, accountLabel),
                mirror == null || mirror.isSyncLeverage(),
                mirror != null && mirror.isBlockEntryOnLeverageSyncFailure(),
                mirror == null || mirror.isBlockPrimaryCloseUntilSynced());
    }

    private PositionSnapshot positionSnapshot(HtxTrendMirrorAccount account, String contractCode, String positionSide) {
        HtxRestResponse<List<HtxSwapPosition>> response = account.swap().positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return PositionSnapshot.failed("mirror position query failed account=" + account.accountLabel()
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

    private String openOrderGate(HtxTrendMirrorAccount account, String contractCode) {
        HtxRestResponse<HtxSwapOpenOrders> response = account.swap().openOrders(contractCode);
        HtxSwapOpenOrders orders = response == null ? null : response.payload();
        if (response == null || !response.ok() || orders == null || orders.getOrders() == null) {
            return "mirror open orders query failed account=" + account.accountLabel()
                    + " contract=" + contractCode + " error=" + error(response);
        }
        if (!orders.getOrders().isEmpty()) {
            return "mirror has open orders account=" + account.accountLabel() + " contract=" + contractCode
                    + " count=" + orders.getOrders().size();
        }
        return null;
    }

    private String switchLeverage(HtxTrendMirrorAccount account, String contractCode, String positionSide) {
        if (!account.syncLeverage()) {
            return null;
        }
        HtxRestResponse<?> response = account.swap().switchLeverage(contractCode, positionSide, properties.getLeverage());
        if (response == null || !response.ok() || response.getData() == null) {
            String message = "mirror leverage switch failed account=" + account.accountLabel() + " contract=" + contractCode
                    + " positionSide=" + positionSide + " error=" + error(response);
            if (!account.blockEntryOnLeverageSyncFailure()) {
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

    private String marginGate(HtxTrendMirrorAccount account, String contractCode, BigDecimal contractSize,
                              long volume, BigDecimal referencePrice) {
        if (!positive(contractSize) || volume <= 0L) {
            return "mirror margin skipped: invalid size account=" + account.accountLabel() + " contract=" + contractCode;
        }
        BigDecimal price = positive(referencePrice) ? referencePrice : BigDecimal.ZERO;
        if (!positive(price)) {
            return null;
        }
        HtxRestResponse<List<HtxSwapAccount>> response = account.swap().accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "mirror margin unavailable account=" + account.accountLabel() + " contract=" + contractCode
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
            return "mirror margin insufficient account=" + account.accountLabel() + " contract=" + contractCode
                    + " available=" + margin + " required=" + required;
        }
        return null;
    }

    private void createMirrorOrderIntent(HtxTrendPositionEntity position, HtxTrendMirrorAccount account, String leg,
                                         HtxSwapOrderRequest request, long volume, BigDecimal takerFeeRate,
                                         BigDecimal estimatedFee, String reason) {
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
        entity.setAccountLabel(account.accountLabel());
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

    @Deprecated
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

    private static String pendingKey(String accountLabel, String contractCode, String positionSide) {
        return accountLabel + "|" + contractCode + "|" + positionSide;
    }

    private static String joinMessages(List<AccountSyncResult> results) {
        return String.join("; ", results.stream().map(item -> item.result().message()).toList());
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

    private record AccountSyncResult(HtxTrendMirrorAccount account, SyncResult result) {
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
