package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.persistence.HtxSpotLedgerRecorder;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Read-only HTX contract snapshot adapter for aggregate moving-grid delta hedge planning.
 * It never submits orders; missing contract/position/account/inventory data is converted to
 * a fail-closed planner input that cannot increase short exposure.
 */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
public class HtxDeltaHedgeSnapshotProvider {

    private static final String SHORT = "short";
    private static final String SELL = "sell";
    private static final String NORMAL = "normal";
    private static final String SWAP = "swap";
    private static final String ALL = "all";
    private static final String ISOLATED = "isolated";

    private final HtxUsdtSwapService swap;
    private final HtxSpotLedgerRecorder spotLedgerRecorder;

    @Autowired
    public HtxDeltaHedgeSnapshotProvider(HtxUsdtSwapApiService api,
                                         HtxSpotLedgerRecorder spotLedgerRecorder) {
        this(api.getSwapService(), spotLedgerRecorder);
    }

    HtxDeltaHedgeSnapshotProvider(HtxUsdtSwapService swap,
                                  HtxSpotLedgerRecorder spotLedgerRecorder) {
        this.swap = swap;
        this.spotLedgerRecorder = spotLedgerRecorder;
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

        AccountSnapshot account = loadAccount(contractCode, request.minimumMarginAvailable());
        InventorySnapshot inventory = loadSpotInventory(symbol, request.spotBaseInventory());
        boolean dataAllowed = account.marginSafe() && inventory.available()
                && request.fundingAllowed() && request.basisAllowed();
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
        if (!request.fundingAllowed()) {
            return degraded(input, "funding gate blocked");
        }
        if (!request.basisAllowed()) {
            return degraded(input, "basis gate blocked");
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
            } else {
                hasUnexpectedLong = true;
            }
        }
        return new PositionSnapshot(true, shortContracts, hasUnexpectedLong, null);
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

    private InventorySnapshot loadSpotInventory(String symbol, BigDecimal explicitInventory) {
        if (explicitInventory != null) {
            return new InventorySnapshot(true, positive(explicitInventory), null);
        }
        try {
            return new InventorySnapshot(true, positive(spotLedgerRecorder.openInventory(symbol)), null);
        } catch (RuntimeException ex) {
            log.warn("MM hedge spot inventory unavailable symbol={} reason={}", symbol, ex.getMessage());
            return new InventorySnapshot(false, BigDecimal.ZERO, "spot inventory unavailable");
        }
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
                                     String clientOrderIdPrefix, long epoch) {
    }

    public record HedgeSnapshot(boolean available, DeltaHedgeManager.HedgeInput input, String reason) {
    }

    private record PositionSnapshot(boolean available, long shortContracts,
                                    boolean hasUnexpectedLong, String reason) {
        private static PositionSnapshot degraded(String reason) {
            return new PositionSnapshot(false, 0L, false, reason);
        }
    }

    private record AccountSnapshot(boolean available, boolean marginSafe, String reason) {
        private static AccountSnapshot degraded(String reason) {
            return new AccountSnapshot(false, false, reason);
        }
    }

    private record InventorySnapshot(boolean available, BigDecimal baseInventory, String reason) {
    }
}
