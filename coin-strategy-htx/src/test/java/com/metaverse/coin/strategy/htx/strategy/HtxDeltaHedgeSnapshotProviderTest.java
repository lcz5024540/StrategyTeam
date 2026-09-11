package com.metaverse.coin.strategy.htx.strategy;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxDeltaHedgeSnapshotProviderTest {

    private HtxUsdtSwapService swap;
    private HtxMarketDataService spotMarketData;
    private HtxSpotLedgerRecorder spotLedgerRecorder;
    private HtxTradeRecorder tradeRecorder;
    private HtxDeltaHedgeSnapshotProvider provider;

    @BeforeEach
    void setUp() {
        swap = mock(HtxUsdtSwapService.class);
        spotMarketData = mock(HtxMarketDataService.class);
        spotLedgerRecorder = mock(HtxSpotLedgerRecorder.class);
        tradeRecorder = mock(HtxTradeRecorder.class);
        provider = new HtxDeltaHedgeSnapshotProvider(swap, spotMarketData, spotLedgerRecorder, tradeRecorder);
    }

    @Test
    void healthySnapshotMapsShortContractsAndLedgerInventoryToPlannerInput() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(position("short", null, "123"))));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        givenHealthyFundingAndBasis();
        when(spotLedgerRecorder.openInventory("xautusdt")).thenReturn(new BigDecimal("1.200"));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(null));

        assertTrue(snapshot.available());
        assertNull(snapshot.reason());
        DeltaHedgeManager.HedgeInput input = snapshot.input();
        assertEquals("xautusdt", input.symbol());
        assertEquals("XAUT-USDT", input.contractCode());
        assertEquals(0, new BigDecimal("1.200").compareTo(input.spotBaseInventory()));
        assertEquals(123L, input.currentShortContracts());
        assertEquals(0, new BigDecimal("0.001").compareTo(input.contractSize()));
        assertTrue(input.fundingAllowed());
        assertTrue(input.basisAllowed());
        assertTrue(input.marginSafe());
    }


    @Test
    void emptyLedgerFallsBackToLegacyOpenSellInventory() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        givenHealthyFundingAndBasis();
        when(spotLedgerRecorder.openInventory("xautusdt")).thenReturn(BigDecimal.ZERO);
        when(tradeRecorder.findOpenMmCells("xautusdt")).thenReturn(List.of(
                legacyCell(HtxStatus.MM_RESTING_SELL, "0.0100", "0.0001", "0.0020"),
                legacyCell(HtxStatus.MM_SELL_RETRYABLE, "0.0050", "0", "0.0010"),
                legacyCell(HtxStatus.MM_RESTING_BUY, "1.0000", "0", "0")));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(null));

        assertTrue(snapshot.available());
        assertEquals(0, new BigDecimal("0.0119").compareTo(snapshot.input().spotBaseInventory()));
    }

    @Test
    void ledgerInventoryTakesPrecedenceOverLegacyOpenSellInventory() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        givenHealthyFundingAndBasis();
        when(spotLedgerRecorder.openInventory("xautusdt")).thenReturn(new BigDecimal("0.0200"));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(null));

        assertTrue(snapshot.available());
        assertEquals(0, new BigDecimal("0.0200").compareTo(snapshot.input().spotBaseInventory()));
        verify(tradeRecorder, never()).findOpenMmCells(anyString());
    }

    @Test
    void missingContractSizeFailsClosedAndDoesNotQueryPrivateState() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract(null))));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("contract size unavailable", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
        verify(swap, never()).positionInfo(anyString());
        verify(swap, never()).accountInfo(anyString());
    }

    @Test
    void failedPositionQueryFailsClosedWithoutOpeningShort() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(error("timeout"));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("position query failed", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
        verify(swap, never()).accountInfo(anyString());
    }

    @Test
    void unsafeMarginDoesNotIncreaseRiskAndKeepsExistingShortUntouched() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(position("short", null, "50"))));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "3"))));
        givenHealthyFundingAndBasis();

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("contract margin gate blocked", snapshot.reason());
        assertEquals(DeltaHedgeManager.Action.NONE, decision.action());
        assertNull(decision.orderRequest());
    }

    @Test
    void unexpectedLongPositionFailsClosedWithoutTakingOwnership() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(position("long", null, "7"))));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("unexpected non-short contract position", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
        verify(swap, never()).accountInfo(anyString());
    }

    @Test
    void ledgerInventoryFailureFailsClosedWithoutOpeningNewShort() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        givenHealthyFundingAndBasis();
        when(spotLedgerRecorder.openInventory("xautusdt")).thenThrow(new RuntimeException("db down"));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(null));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("spot inventory unavailable", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
    }

    @Test
    void fundingRateAboveThresholdFailsClosedWithoutOpeningNewShort() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding("0.0050")));
        givenHealthyBasis();

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("funding gate blocked", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
    }

    @Test
    void wideSpotSwapBasisFailsClosedWithoutOpeningNewShort() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding("0.0001")));
        when(spotMarketData.depth("xautusdt", 1)).thenReturn(ok(depth("4200", "4202")));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth("4240", "4242")));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("basis gate blocked", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
    }

    @Test
    void missingSpotDepthFailsClosedWhenBasisGateIsEnabled() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding("0.0001")));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("spot depth unavailable", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
    }

    @Test
    void disabledBasisGateAllowsOpenShortFromInventoryWhenSpotDepthIsUnavailable() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding("0.0001")));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(
                request(new BigDecimal("0.046"), new BigDecimal("0.0010"), BigDecimal.ZERO));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertTrue(snapshot.available());
        assertTrue(snapshot.input().basisAllowed());
        assertEquals(DeltaHedgeManager.Action.OPEN_SHORT, decision.action());
        assertEquals("23", decision.orderRequest().getVolume());
    }

    @Test
    void closeLiquidationDistanceKeepsExistingShortUntouched() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(position("short", null, "50", "4210"))));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding("0.0001")));
        givenHealthyBasis();

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("liquidation distance gate blocked", snapshot.reason());
        assertEquals(DeltaHedgeManager.Action.NONE, decision.action());
        assertNull(decision.orderRequest());
    }

    private static HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest request(BigDecimal inventory) {
        return request(inventory, new BigDecimal("0.0010"), new BigDecimal("0.0020"));
    }

    private static HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest request(
            BigDecimal inventory, BigDecimal maxAbsFundingRate, BigDecimal maxBasisRate) {
        return new HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest(
                true, "xautusdt", null, inventory, new BigDecimal("0.5"),
                new BigDecimal("2"), true, true, new BigDecimal("10"),
                maxAbsFundingRate, maxBasisRate, new BigDecimal("0.1000"),
                "CS-MM-HEDGE-XAUTUSDT", 7L);
    }

    private void givenHealthyFundingAndBasis() {
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding("0.0001")));
        givenHealthyBasis();
    }

    private void givenHealthyBasis() {
        when(spotMarketData.depth("xautusdt", 1)).thenReturn(ok(depth("4200", "4202")));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth("4201", "4203")));
    }


    private static HtxLockedPairEntity legacyCell(String status, String buyFilled, String feeBase, String sellFilled) {
        HtxLockedPairEntity cell = new HtxLockedPairEntity();
        cell.setStatus(status);
        cell.setReqQuantity(new BigDecimal(buyFilled));
        cell.setBuyFilledAmount(new BigDecimal(buyFilled));
        cell.setTotalFeeBase(new BigDecimal(feeBase));
        cell.setSellFilledAmount(new BigDecimal(sellFilled));
        return cell;
    }

    private static HtxSwapContractInfo contract(String contractSize) {
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setContractStatus(1);
        contract.setBusinessType("swap");
        contract.setSupportMarginMode("isolated");
        if (contractSize != null) {
            contract.setContractSize(new BigDecimal(contractSize));
        }
        return contract;
    }

    private static HtxSwapPosition position(String side, String direction, String volume) {
        return position(side, direction, volume, "5000");
    }

    private static HtxSwapPosition position(String side, String direction, String volume,
                                            String liquidationPrice) {
        HtxSwapPosition position = new HtxSwapPosition();
        position.setContractCode("XAUT-USDT");
        position.setPositionSide(side);
        position.setDirection(direction);
        position.setVolume(new BigDecimal(volume));
        if (liquidationPrice != null) {
            position.setLiquidationPrice(new BigDecimal(liquidationPrice));
        }
        return position;
    }

    private static HtxSwapFundingRate funding(String rate) {
        HtxSwapFundingRate funding = new HtxSwapFundingRate();
        funding.setContractCode("XAUT-USDT");
        funding.setFundingRate(new BigDecimal(rate));
        return funding;
    }

    private static HtxDepth depth(String bid, String ask) {
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), BigDecimal.ONE)));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), BigDecimal.ONE)));
        return depth;
    }

    private static HtxSwapAccount account(String state, String marginAvailable) {
        HtxSwapAccount account = new HtxSwapAccount();
        account.setContractCode("XAUT-USDT");
        account.setState(state);
        account.setMarginAvailable(new BigDecimal(marginAvailable));
        return account;
    }

    private static <T> HtxRestResponse<T> ok(T data) {
        HtxRestResponse<T> response = new HtxRestResponse<>();
        response.setStatus("ok");
        response.setData(data);
        return response;
    }

    private static <T> HtxRestResponse<T> error(String message) {
        HtxRestResponse<T> response = new HtxRestResponse<>();
        response.setStatus("error");
        response.setErrMsg(message);
        return response;
    }
}
