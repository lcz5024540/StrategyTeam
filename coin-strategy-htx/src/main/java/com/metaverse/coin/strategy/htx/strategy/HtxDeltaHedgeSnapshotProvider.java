package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.persistence.HtxSpotLedgerRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Read-only HTX contract snapshot adapter for aggregate moving-grid delta hedge planning.
 * It never submits orders; missing contract/position/account/inventory data is converted to
 * a fail-closed planner input that cannot increase short exposure.
 */
@Slf4j
@Service
@ConditionalOnBean({HtxUsdtSwapApiService.class, HtxApiService.class})
public class HtxDeltaHedgeSnapshotProvider {

    private static final String SHORT = "short";
    private static final String SELL = "sell";
    private static final String NORMAL = "normal";
    private static final String SWAP = "swap";
    private static final String ALL = "all";
    private static final String ISOLATED = "isolated";
    private static final int SPOT_DEPTH_LEVELS = 1;
    private static final int SWAP_DEPTH_STEP = 6;
    private static final int RATE_SCALE = 12;

    private final HtxUsdtSwapService swap;
    private final HtxMarketDataService spotMarketData;
    private final HtxSpotLedgerRecorder spotLedgerRecorder;
    private final HtxTradeRecorder tradeRecorder;

    @Autowired
    public HtxDeltaHedgeSnapshotProvider(HtxUsdtSwapApiService api,
                                         HtxApiService spotApi,
                                         HtxSpotLedgerRecorder spotLedgerRecorder,
                                         HtxTradeRecorder tradeRecorder) {
        this(api.getSwapService(), spotApi.getMarketDataService(), spotLedgerRecorder, tradeRecorder);
    }

    HtxDeltaHedgeSnapshotProvider(HtxUsdtSwapService swap,
                                  HtxMarketDataService spotMarketData,
                                  HtxSpotLedgerRecorder spotLedgerRecorder) {
        this(swap, spotMarketData, spotLedgerRecorder, null);
    }

    HtxDeltaHedgeSnapshotProvider(HtxUsdtSwapService swap,
                                  HtxMarketDataService spotMarketData,
                                  HtxSpotLedgerRecorder spotLedgerRecorder,
                                  HtxTradeRecorder tradeRecorder) {
        this.swap = swap;
        this.spotMarketData = spotMarketData;
        this.spotLedgerRecorder = spotLedgerRecorder;
        this.tradeRecorder = tradeRecorder;
    }

    public HedgeSnapshot snapshot(HedgeSourceRequest request) {
        if (request == null) {
            return degraded(emptyInput(), "hedge request missing");
        }
        String symbol = normalizeSymbol(request.symbol());
        String contractCode = normalizeContractCode(request.contractCode(), symbol);
        if (!request.enabled()) {
            return available(new DeltaHedgeManager.HedgeInput(false, symbol, contractCode, BigDecimal.ZERO,
                    0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    false, false, false, request.clientOrderIdPrefix(), request.epoch()));
        }
        if (isBlank(symbol) || isBlank(contractCode)) {
            return degraded(input(request, symbol, contractCode, BigDecimal.ZERO, 0L,
                    BigDecimal.ZERO, false), "hedge symbol or contract code missing");
        }

        HtxSwapContractInfo contract = loadContract(contractCode);
        BigDecimal contractSize = positive(contract == null ? null : contract.getContractSize());
        if (contractSize.signum() <= 0) {
            return degraded(input(request, symbol, contractCode, BigDecimal.ZERO, 0L,
                    BigDecimal.ZERO, false), "contract size unavailable");
        }

        PositionSnapshot position = loadPosition(contractCode);
        if (!position.available()) {
            return degraded(input(request, symbol, contractCode, BigDecimal.ZERO, 0L,
                    contractSize, false), position.reason());
        }
        if (position.hasUnexpectedLong()) {
            return degraded(input(request, symbol, contractCode, BigDecimal.ZERO, 0L,
                    contractSize, false), "unexpected non-short contract position");
        }

        if (!request.fundingAllowed()) {
            return degraded(input(request, symbol, contractCode, BigDecimal.ZERO,
                    position.shortContracts(), contractSize, false), "funding gate blocked");
        }
        if (!request.basisAllowed()) {
            return degraded(input(request, symbol, contractCode, BigDecimal.ZERO,
                    position.shortContracts(), contractSize, false), "basis gate blocked");
        }

        AccountSnapshot account = loadAccount(contractCode, request.minimumMarginAvailable());
        FundingSnapshot funding = loadFunding(contractCode, request.maxAbsFundingRate());
        BasisSnapshot basis = loadBasis(symbol, contractCode, request.maxBasisRate());
        LiquidationSnapshot liquidation = evaluateLiquidation(position, basis.swapMid(),
                request.minimumLiquidationDistanceRate());
        InventorySnapshot inventory = loadSpotInventory(symbol, request.spotBaseInventory());
        boolean dataAllowed = account.marginSafe() && funding.allowed() && basis.allowed()
                && liquidation.allowed() && inventory.available();
        DeltaHedgeManager.HedgeInput input = input(request, symbol, contractCode, inventory.baseInventory(),
                position.shortContracts(), contractSize, dataAllowed);
        if (!account.available()) {
            return degraded(input, account.reason());
        }
        if (!account.marginSafe()) {
            return degraded(input, "contract margin gate blocked");
        }
        if (!inventory.available()) {
            return degraded(input, inventory.reason());
        }
        if (!funding.available()) {
            return degraded(input, funding.reason());
        }
        if (!funding.allowed()) {
            return degraded(input, "funding gate blocked");
        }
        if (!basis.available()) {
            return degraded(input, basis.reason());
        }
        if (!basis.allowed()) {
            return degraded(input, "basis gate blocked");
        }
        if (!liquidation.allowed()) {
            return degraded(input, liquidation.reason());
        }
        return available(input);
    }

    private HtxSwapContractInfo loadContract(String contractCode) {
        HtxRestResponse<List<HtxSwapContractInfo>> response = swap.contractInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return null;
        }
        return response.payload().stream()
                .filter(item -> contractCode.equalsIgnoreCase(item.getContractCode()))
                .filter(HtxDeltaHedgeSnapshotProvider::contractAvailable)
                .findFirst().orElse(null);
    }

    private PositionSnapshot loadPosition(String contractCode) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return PositionSnapshot.degraded("position query failed");
        }
        long shortContracts = 0L;
        boolean hasUnexpectedLong = false;
        boolean shortLiquidationMissing = false;
        BigDecimal nearestShortLiquidationPrice = BigDecimal.ZERO;
        for (HtxSwapPosition position : response.payload()) {
            if (position == null || !contractCode.equalsIgnoreCase(position.getContractCode())
                    || positive(position.getVolume()).signum() <= 0) {
                continue;
            }
            if (isShort(position)) {
                long contracts = longContracts(position.getVolume());
                if (contracts < 0L) {
                    return PositionSnapshot.degraded("short position volume is not an integer contract count");
                }
                shortContracts += contracts;
                BigDecimal liquidationPrice = positive(position.getLiquidationPrice());
                if (liquidationPrice.signum() <= 0) {
                    shortLiquidationMissing = true;
                } else if (nearestShortLiquidationPrice.signum() <= 0
                        || liquidationPrice.compareTo(nearestShortLiquidationPrice) < 0) {
                    nearestShortLiquidationPrice = liquidationPrice;
                }
            } else {
                hasUnexpectedLong = true;
            }
        }
        return new PositionSnapshot(true, shortContracts, nearestShortLiquidationPrice,
                shortLiquidationMissing, hasUnexpectedLong, null);
    }

    private AccountSnapshot loadAccount(String contractCode, BigDecimal minimumMarginAvailable) {
        HtxRestResponse<List<HtxSwapAccount>> response = swap.accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return AccountSnapshot.degraded("account query failed");
        }
        HtxSwapAccount account = response.payload().stream()
                .filter(item -> item != null && contractCode.equalsIgnoreCase(item.getContractCode()))
                .findFirst().orElse(null);
        if (account == null || !NORMAL.equalsIgnoreCase(account.getState())) {
            return AccountSnapshot.degraded("contract account is missing or not normal");
        }
        BigDecimal available = positive(account.getMarginAvailable());
        BigDecimal minimum = positive(minimumMarginAvailable);
        if (available.signum() <= 0 || available.compareTo(minimum) < 0) {
            return new AccountSnapshot(true, false, "contract margin unavailable");
        }
        return new AccountSnapshot(true, true, null);
    }

    private FundingSnapshot loadFunding(String contractCode, BigDecimal maxAbsFundingRate) {
        BigDecimal maxAbsRate = positive(maxAbsFundingRate);
        if (maxAbsRate.signum() <= 0) {
            return new FundingSnapshot(true, true, null);
        }
        HtxRestResponse<HtxSwapFundingRate> response = swap.fundingRate(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return FundingSnapshot.degraded("funding query failed");
        }
        BigDecimal rate = response.payload().getFundingRate();
        if (rate == null) {
            rate = response.payload().getEstimatedRate();
        }
        if (rate == null) {
            return FundingSnapshot.degraded("funding rate unavailable");
        }
        return new FundingSnapshot(true, rate.abs().compareTo(maxAbsRate) <= 0, null);
    }

    private BasisSnapshot loadBasis(String symbol, String contractCode, BigDecimal maxBasisRate) {
        BigDecimal maxRate = positive(maxBasisRate);
        if (maxRate.signum() <= 0) {
            return new BasisSnapshot(true, true, BigDecimal.ZERO, null);
        }
        BigDecimal spotMid = spotMid(symbol);
        if (spotMid.signum() <= 0) {
            return BasisSnapshot.degraded("spot depth unavailable");
        }
        BigDecimal swapMid = swapMid(contractCode);
        if (swapMid.signum() <= 0) {
            return BasisSnapshot.degraded("swap depth unavailable");
        }
        BigDecimal basisRate = swapMid.subtract(spotMid).abs()
                .divide(spotMid, RATE_SCALE, RoundingMode.HALF_UP);
        return new BasisSnapshot(true, basisRate.compareTo(maxRate) <= 0, swapMid, null);
    }

    private LiquidationSnapshot evaluateLiquidation(PositionSnapshot position, BigDecimal referencePrice,
                                                    BigDecimal minimumDistanceRate) {
        if (position.shortContracts() <= 0L) {
            return LiquidationSnapshot.safe();
        }
        if (position.shortLiquidationMissing() || positive(position.liquidationPrice()).signum() <= 0) {
            return LiquidationSnapshot.degraded("liquidation price unavailable");
        }
        if (referencePrice == null || referencePrice.signum() <= 0) {
            return LiquidationSnapshot.degraded("liquidation reference price unavailable");
        }
        BigDecimal minimumRate = positive(minimumDistanceRate);
        if (minimumRate.signum() <= 0) {
            return LiquidationSnapshot.degraded("liquidation distance threshold missing");
        }
        BigDecimal distanceRate = position.liquidationPrice().subtract(referencePrice)
                .divide(referencePrice, RATE_SCALE, RoundingMode.HALF_UP);
        if (distanceRate.compareTo(minimumRate) < 0) {
            return LiquidationSnapshot.degraded("liquidation distance gate blocked");
        }
        return LiquidationSnapshot.safe();
    }

    private InventorySnapshot loadSpotInventory(String symbol, BigDecimal explicitInventory) {
        if (explicitInventory != null) {
            return new InventorySnapshot(true, positive(explicitInventory), null);
        }
        try {
            BigDecimal ledgerInventory = positive(spotLedgerRecorder.openInventory(symbol));
            if (ledgerInventory.signum() > 0) {
                return new InventorySnapshot(true, ledgerInventory, null);
            }
            BigDecimal legacyInventory = legacyOpenSellInventory(symbol);
            if (legacyInventory.signum() > 0) {
                log.info("MM hedge spot inventory uses legacy open sell fallback symbol={} ledgerInventory={} legacyInventory={}",
                        symbol, ledgerInventory, legacyInventory);
                return new InventorySnapshot(true, legacyInventory, null);
            }
            return new InventorySnapshot(true, BigDecimal.ZERO, null);
        } catch (RuntimeException ex) {
            log.warn("MM hedge spot inventory unavailable symbol={} reason={}", symbol, ex.getMessage());
            return new InventorySnapshot(false, BigDecimal.ZERO, "spot inventory unavailable");
        }
    }

    private BigDecimal legacyOpenSellInventory(String symbol) {
        if (tradeRecorder == null || isBlank(symbol)) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        List<HtxLockedPairEntity> cells = tradeRecorder.findOpenMmCells(symbol);
        if (cells == null || cells.isEmpty()) {
            return BigDecimal.ZERO;
        }
        for (HtxLockedPairEntity cell : cells) {
            if (cell == null || !isLegacyOpenSell(cell.getStatus())) {
                continue;
            }
            BigDecimal bought = positive(cell.getBuyFilledAmount());
            if (bought.signum() <= 0) {
                bought = positive(cell.getReqQuantity());
            }
            BigDecimal remaining = bought
                    .subtract(positive(cell.getTotalFeeBase()))
                    .subtract(positive(cell.getSellFilledAmount()));
            if (remaining.signum() > 0) {
                total = total.add(remaining);
            }
        }
        return total;
    }

    private static boolean isLegacyOpenSell(String status) {
        return HtxStatus.MM_RESTING_SELL.equals(status) || HtxStatus.MM_SELL_RETRYABLE.equals(status);
    }

    private static DeltaHedgeManager.HedgeInput input(HedgeSourceRequest request, String symbol,
                                                      String contractCode, BigDecimal spotBaseInventory,
                                                      long currentShortContracts, BigDecimal contractSize,
                                                      boolean dataAllowed) {
        return new DeltaHedgeManager.HedgeInput(true, symbol, contractCode, positive(spotBaseInventory),
                Math.max(0L, currentShortContracts), positive(contractSize), positive(request.hedgeRatio()),
                positive(request.maxShortBase()), request.fundingAllowed() && dataAllowed,
                request.basisAllowed() && dataAllowed, dataAllowed,
                request.clientOrderIdPrefix(), request.epoch());
    }

    private static DeltaHedgeManager.HedgeInput emptyInput() {
        return new DeltaHedgeManager.HedgeInput(false, null, null, BigDecimal.ZERO,
                0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, false, null, 0L);
    }

    private static HedgeSnapshot available(DeltaHedgeManager.HedgeInput input) {
        return new HedgeSnapshot(true, input, null);
    }

    private static HedgeSnapshot degraded(DeltaHedgeManager.HedgeInput input, String reason) {
        return new HedgeSnapshot(false, input, reason);
    }

    private static boolean contractAvailable(HtxSwapContractInfo contract) {
        return contract != null && Integer.valueOf(1).equals(contract.getContractStatus())
                && SWAP.equalsIgnoreCase(contract.getBusinessType())
                && (ALL.equalsIgnoreCase(contract.getSupportMarginMode())
                || ISOLATED.equalsIgnoreCase(contract.getSupportMarginMode()));
    }

    private static boolean isShort(HtxSwapPosition position) {
        return SHORT.equalsIgnoreCase(position.getPositionSide())
                || SELL.equalsIgnoreCase(position.getDirection());
    }

    private BigDecimal spotMid(String symbol) {
        if (spotMarketData == null) {
            return BigDecimal.ZERO;
        }
        HtxRestResponse<HtxDepth> response = spotMarketData.depth(symbol, SPOT_DEPTH_LEVELS);
        return midPrice(response);
    }

    private BigDecimal swapMid(String contractCode) {
        HtxRestResponse<HtxDepth> response = swap.depth(contractCode, SWAP_DEPTH_STEP);
        return midPrice(response);
    }

    private static BigDecimal midPrice(HtxRestResponse<HtxDepth> response) {
        if (response == null || !response.ok() || response.payload() == null) {
            return BigDecimal.ZERO;
        }
        HtxDepth depth = response.payload();
        BigDecimal bid = firstPrice(depth.getBids());
        BigDecimal ask = firstPrice(depth.getAsks());
        if (bid.signum() <= 0 || ask.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return bid.add(ask).divide(BigDecimal.valueOf(2), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal firstPrice(List<HtxPriceLevel> levels) {
        if (levels == null || levels.isEmpty() || levels.get(0) == null) {
            return BigDecimal.ZERO;
        }
        return positive(levels.get(0).getPrice());
    }

    private static long longContracts(BigDecimal volume) {
        try {
            return volume.longValueExact();
        } catch (ArithmeticException ex) {
            return -1L;
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private static String normalizeSymbol(String symbol) {
        return isBlank(symbol) ? null : symbol.toLowerCase(Locale.ROOT);
    }

    private static String normalizeContractCode(String contractCode, String symbol) {
        if (!isBlank(contractCode)) {
            return contractCode.toUpperCase(Locale.ROOT);
        }
        if (isBlank(symbol)) {
            return null;
        }
        String base = HtxSymbolUtil.base(symbol);
        String quote = HtxSymbolUtil.quote(symbol);
        if (isBlank(base) || isBlank(quote)) {
            return symbol.toUpperCase(Locale.ROOT);
        }
        return base.toUpperCase(Locale.ROOT) + "-" + quote.toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record HedgeSourceRequest(boolean enabled, String symbol, String contractCode,
                                     BigDecimal spotBaseInventory, BigDecimal hedgeRatio,
                                     BigDecimal maxShortBase, boolean fundingAllowed,
                                     boolean basisAllowed, BigDecimal minimumMarginAvailable,
                                     BigDecimal maxAbsFundingRate, BigDecimal maxBasisRate,
                                     BigDecimal minimumLiquidationDistanceRate,
                                     String clientOrderIdPrefix, long epoch) {
    }

    public record HedgeSnapshot(boolean available, DeltaHedgeManager.HedgeInput input, String reason) {
    }

    private record PositionSnapshot(boolean available, long shortContracts, BigDecimal liquidationPrice,
                                    boolean shortLiquidationMissing,
                                    boolean hasUnexpectedLong, String reason) {
        private static PositionSnapshot degraded(String reason) {
            return new PositionSnapshot(false, 0L, BigDecimal.ZERO, false, false, reason);
        }
    }

    private record AccountSnapshot(boolean available, boolean marginSafe, String reason) {
        private static AccountSnapshot degraded(String reason) {
            return new AccountSnapshot(false, false, reason);
        }
    }

    private record InventorySnapshot(boolean available, BigDecimal baseInventory, String reason) {
    }

    private record FundingSnapshot(boolean available, boolean allowed, String reason) {
        private static FundingSnapshot degraded(String reason) {
            return new FundingSnapshot(false, false, reason);
        }
    }

    private record BasisSnapshot(boolean available, boolean allowed, BigDecimal swapMid, String reason) {
        private static BasisSnapshot degraded(String reason) {
            return new BasisSnapshot(false, false, BigDecimal.ZERO, reason);
        }
    }

    private record LiquidationSnapshot(boolean allowed, String reason) {
        private static LiquidationSnapshot safe() {
            return new LiquidationSnapshot(true, null);
        }

        private static LiquidationSnapshot degraded(String reason) {
            return new LiquidationSnapshot(false, reason);
        }
    }
}
