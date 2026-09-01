package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.persistence.HtxSpotLedgerRecorder;
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
    private HtxSpotLedgerRecorder spotLedgerRecorder;
    private HtxDeltaHedgeSnapshotProvider provider;

    @BeforeEach
    void setUp() {
        swap = mock(HtxUsdtSwapService.class);
        spotLedgerRecorder = mock(HtxSpotLedgerRecorder.class);
        provider = new HtxDeltaHedgeSnapshotProvider(swap, spotLedgerRecorder);
    }

    @Test
    void healthySnapshotMapsShortContractsAndLedgerInventoryToPlannerInput() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(position("short", null, "123"))));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "100"))));
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
    void unsafeMarginDoesNotIncreaseRiskButAllowsReduceOnlyShortReduction() {
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract("0.001"))));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(position("short", null, "50"))));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account("normal", "3"))));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(new BigDecimal("1")));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("contract margin gate blocked", snapshot.reason());
        assertEquals(DeltaHedgeManager.Action.CLOSE_SHORT, decision.action());
        assertTrue(decision.orderRequest().getReduceOnly());
        assertEquals("50", decision.orderRequest().getVolume());
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
        when(spotLedgerRecorder.openInventory("xautusdt")).thenThrow(new RuntimeException("db down"));

        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = provider.snapshot(request(null));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager().plan(snapshot.input());

        assertFalse(snapshot.available());
        assertEquals("spot inventory unavailable", snapshot.reason());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
    }

    private static HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest request(BigDecimal inventory) {
        return new HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest(
                true, "xautusdt", null, inventory, new BigDecimal("0.5"),
                new BigDecimal("2"), true, true, new BigDecimal("10"),
                "CS-MM-HEDGE-XAUTUSDT", 7L);
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
        HtxSwapPosition position = new HtxSwapPosition();
        position.setContractCode("XAUT-USDT");
        position.setPositionSide(side);
        position.setDirection(direction);
        position.setVolume(new BigDecimal(volume));
        return position;
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
