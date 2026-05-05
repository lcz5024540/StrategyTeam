package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletCashLedgerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartWalletTradeOrderServiceTest {

    @Mock
    private SmartWalletTradeOrderMapper tradeOrderMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletFillMapper fillMapper;
    @Mock
    private SmartWalletCashLedgerMapper cashLedgerMapper;
    @Mock
    private SmartWalletMarketInfoMapper marketInfoMapper;
    @Mock
    private SmartWalletMarketLinkService marketLinkService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private SmartWalletTradeOrderService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletTradeOrderService(
                tradeOrderMapper,
                positionMapper,
                orderMapper,
                fillMapper,
                cashLedgerMapper,
                marketInfoMapper,
                marketLinkService,
                new SmartWalletTimeFormatter(),
                jdbcTemplate
        );
        lenient().when(tradeOrderMapper.insert(any(SmartWalletTradeOrderEntity.class))).thenReturn(1);
        lenient().when(tradeOrderMapper.updateById(any(SmartWalletTradeOrderEntity.class))).thenReturn(1);
        lenient().when(tradeOrderMapper.selectOne(any())).thenReturn(null);
        lenient().when(marketInfoMapper.selectById(7L)).thenReturn(marketInfo());
        lenient().when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void shouldCreateOpenTradeOrderFromBuyFill() {
        when(positionMapper.selectById(1L)).thenReturn(position("OPEN")
                .setQuantity(new BigDecimal("10"))
                .setCostBasisUsdc(new BigDecimal("4.00"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(new BigDecimal("1.00"))
                .setLastMarkPrice(new BigDecimal("0.50")));
        when(orderMapper.selectList(any())).thenReturn(List.of(entryOrder()));
        when(fillMapper.selectList(any())).thenReturn(List.of(fill(11L, "BUY", "10", "4.00", "0.02", "0")));

        service.upsertFromPosition(1L);

        SmartWalletTradeOrderEntity trade = insertedTrade();
        assertEquals(new BigDecimal("10.00000000"), trade.getEntryQuantity());
        assertEquals(new BigDecimal("0.40000000"), trade.getEntryAvgPrice());
        assertEquals(new BigDecimal("4.00000000"), trade.getEntryCostUsdc());
        assertEquals(new BigDecimal("10.00000000"), trade.getRemainingQuantity());
        assertEquals(new BigDecimal("4.00000000"), trade.getRemainingCostBasisUsdc());
        assertEquals(new BigDecimal("1.00000000"), trade.getTotalPnlUsdc());
        assertEquals("OPEN", trade.getStatus());
        assertNull(trade.getSettlementPrice());
    }

    @Test
    void shouldAggregatePartialExitAndRemainingPosition() {
        when(positionMapper.selectById(2L)).thenReturn(position("OPEN")
                .setQuantity(new BigDecimal("4"))
                .setCostBasisUsdc(new BigDecimal("1.60"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00"))
                .setRealizedPnlUsdc(new BigDecimal("1.20"))
                .setUnrealizedPnlUsdc(new BigDecimal("0.40"))
                .setLastMarkPrice(new BigDecimal("0.50")));
        when(orderMapper.selectList(any())).thenReturn(List.of(entryOrder(), exitOrder()));
        when(fillMapper.selectList(any())).thenReturn(List.of(
                fill(21L, "BUY", "10", "4.00", "0.02", "0"),
                fill(22L, "SELL", "6", "3.60", "0.01", "1.20")
        ));

        service.upsertFromPosition(2L);

        SmartWalletTradeOrderEntity trade = insertedTrade();
        assertEquals(new BigDecimal("10.00000000"), trade.getEntryQuantity());
        assertEquals(new BigDecimal("6.00000000"), trade.getExitQuantity());
        assertEquals(new BigDecimal("0.60000000"), trade.getExitAvgPrice());
        assertEquals(new BigDecimal("3.60000000"), trade.getExitProceedsUsdc());
        assertEquals(new BigDecimal("1.20000000"), trade.getExitRealizedPnlUsdc());
        assertEquals(new BigDecimal("4.00000000"), trade.getRemainingQuantity());
        assertEquals(new BigDecimal("1.60000000"), trade.getRemainingCostBasisUsdc());
        assertEquals(new BigDecimal("1.60000000"), trade.getTotalPnlUsdc());
        assertEquals("PARTIALLY_EXITED", trade.getStatus());
    }

    @Test
    void shouldRecordSettlementWithoutRequiringVirtualSellOrder() {
        Date closedAt = new Date();
        when(positionMapper.selectById(3L)).thenReturn(position("RESOLVED_UNCLAIMED")
                .setQuantity(BigDecimal.ZERO)
                .setCostBasisUsdc(BigDecimal.ZERO)
                .setOriginalCostBasisUsdc(new BigDecimal("4.00"))
                .setRealizedPnlUsdc(new BigDecimal("6.00"))
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setLastMarkPrice(BigDecimal.ONE)
                .setClosedAt(closedAt));
        when(orderMapper.selectList(any())).thenReturn(List.of(entryOrder()));
        when(fillMapper.selectList(any())).thenReturn(List.of(fill(31L, "BUY", "10", "4.00", "0.02", "0")));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of(new SmartWalletCashLedgerEntity()
                .setEntryType("SETTLEMENT_PROCEEDS")
                .setAmount(new BigDecimal("10"))));

        service.upsertFromPosition(3L);

        SmartWalletTradeOrderEntity trade = insertedTrade();
        assertEquals(BigDecimal.ZERO.setScale(8), trade.getExitQuantity());
        assertEquals(new BigDecimal("1.00000000"), trade.getSettlementPrice());
        assertEquals(new BigDecimal("10.00000000"), trade.getSettlementProceedsUsdc());
        assertEquals(new BigDecimal("6.00000000"), trade.getSettlementPnlUsdc());
        assertEquals(new BigDecimal("6.00000000"), trade.getTotalPnlUsdc());
        assertEquals("RESOLVED_UNCLAIMED", trade.getStatus());
        assertEquals(closedAt, trade.getSettledAt());
    }

    @Test
    void shouldFallbackToFilledOrdersWhenFillRowsAreMissing() {
        when(positionMapper.selectById(4L)).thenReturn(position("CLOSED")
                .setQuantity(BigDecimal.ZERO)
                .setCostBasisUsdc(BigDecimal.ZERO)
                .setOriginalCostBasisUsdc(new BigDecimal("5.00"))
                .setRealizedPnlUsdc(new BigDecimal("1.00"))
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setLastMarkPrice(BigDecimal.ZERO));
        when(orderMapper.selectList(any())).thenReturn(List.of(
                entryOrder().setSize(new BigDecimal("10")).setNotionalUsdc(new BigDecimal("5.00")),
                exitOrder().setSize(new BigDecimal("10")).setNotionalUsdc(new BigDecimal("6.00")).setRealizedPnlUsdc(new BigDecimal("1.00"))
        ));
        when(fillMapper.selectList(any())).thenReturn(List.of());

        service.upsertFromPosition(4L);

        SmartWalletTradeOrderEntity trade = insertedTrade();
        assertEquals(new BigDecimal("10.00000000"), trade.getEntryQuantity());
        assertEquals(new BigDecimal("5.00000000"), trade.getEntryCostUsdc());
        assertEquals(new BigDecimal("10.00000000"), trade.getExitQuantity());
        assertEquals(new BigDecimal("6.00000000"), trade.getExitProceedsUsdc());
        assertEquals(new BigDecimal("1.00000000"), trade.getExitRealizedPnlUsdc());
        assertEquals("EXITED", trade.getStatus());
    }

    private SmartWalletTradeOrderEntity insertedTrade() {
        ArgumentCaptor<SmartWalletTradeOrderEntity> captor = ArgumentCaptor.forClass(SmartWalletTradeOrderEntity.class);
        verify(tradeOrderMapper).insert(captor.capture());
        return captor.getValue();
    }

    private SmartWalletPositionEntity position(String status) {
        return new SmartWalletPositionEntity()
                .setId(1L)
                .setMarketInfoId(7L)
                .setExecutionAddress("paper-book")
                .setOpenedAt(new Date())
                .setStatus(status);
    }

    private SmartWalletOrderEntity entryOrder() {
        return new SmartWalletOrderEntity()
                .setId(101L)
                .setPositionId(1L)
                .setMarketInfoId(7L)
                .setWalletAddress("0xwallet")
                .setFollowTaskId(99L)
                .setSide("BUY")
                .setOrderType("MARKET")
                .setStatus("FILLED")
                .setSize(new BigDecimal("10"))
                .setNotionalUsdc(new BigDecimal("4.00"))
                .setFeeUsdc(new BigDecimal("0.02"))
                .setPlacedAt(new Date())
                .setFilledAt(new Date());
    }

    private SmartWalletOrderEntity exitOrder() {
        return new SmartWalletOrderEntity()
                .setId(102L)
                .setPositionId(1L)
                .setMarketInfoId(7L)
                .setWalletAddress("0xwallet")
                .setFollowTaskId(99L)
                .setSide("SELL")
                .setOrderType("EXIT")
                .setStatus("FILLED")
                .setSize(new BigDecimal("6"))
                .setNotionalUsdc(new BigDecimal("3.60"))
                .setRealizedPnlUsdc(new BigDecimal("1.20"))
                .setFeeUsdc(new BigDecimal("0.01"))
                .setPlacedAt(new Date())
                .setFilledAt(new Date());
    }

    private SmartWalletFillEntity fill(Long id, String side, String size, String notional, String fee, String realizedPnl) {
        return new SmartWalletFillEntity()
                .setId(id)
                .setPositionId(1L)
                .setMarketInfoId(7L)
                .setSide(side)
                .setSize(new BigDecimal(size))
                .setNotionalUsdc(new BigDecimal(notional))
                .setFeeUsdc(new BigDecimal(fee))
                .setRealizedPnlUsdc(new BigDecimal(realizedPnl))
                .setFilledAt(new Date());
    }

    private SmartWalletMarketInfoEntity marketInfo() {
        return new SmartWalletMarketInfoEntity()
                .setId(7L)
                .setMarketId("condition-1")
                .setQuestion("Will lifecycle order work?")
                .setOutcome("YES")
                .setMarketLifecycle("ACTIVE")
                .setAcceptingOrders(true)
                .setClosed(false);
    }
}
