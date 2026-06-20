package com.metaverse.coin.strategy.web.service.smartwallet;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPerformanceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SmartWalletReportReadServiceTest {

    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletFillMapper fillMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletCashLedgerMapper cashLedgerMapper;
    @Mock
    private SmartWalletMarketInfoMapper marketInfoMapper;
    @Mock
    private SmartWalletTradeOrderMapper tradeOrderMapper;

    private SmartWalletReportReadService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletReportReadService(orderMapper, fillMapper, positionMapper,
                cashLedgerMapper, marketInfoMapper, tradeOrderMapper);
        lenient().when(fillMapper.sumVolumeByMode(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(positionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(orderMapper.selectList(any())).thenReturn(List.of());
        lenient().when(cashLedgerMapper.sumSignedAmountByMode(any(), any(), isNull(), isNull()))
                .thenReturn(BigDecimal.ZERO);
    }

    @Test
    void performanceShouldClassifyExpiredOpenTradeOrdersAsPendingSettlement() {
        lenient().when(tradeOrderMapper.selectList(any())).thenReturn(List.of(
                tradeOrder("OPEN", "-2.50", System.currentTimeMillis() - 60_000L),
                tradeOrder("OPEN", "1.25", System.currentTimeMillis() + 60_000L),
                tradeOrder("SETTLED", "4.00", System.currentTimeMillis() - 60_000L),
                tradeOrder("FAILED", "-9.00", System.currentTimeMillis() - 60_000L)
        ));

        SmartWalletPerformanceView view = service.performance("PAPER");

        assertEquals(1, view.getPendingPositions());
        assertEquals(new BigDecimal("-2.50"), view.getPendingSettlementPnlUsdc());
        assertEquals(1, view.getOpenPositions());
        assertEquals(new BigDecimal("1.25"), view.getUnrealizedPnlUsdc());
        assertEquals(1, view.getClosedPositions());
        assertEquals(1, view.getWinningPositions());
        assertEquals(0, view.getLosingPositions());
        assertEquals(new BigDecimal("4.00"), view.getRealizedPnlUsdc());
    }

    private SmartWalletTradeOrderEntity tradeOrder(String status, String pnl, long marketEndAt) {
        return new SmartWalletTradeOrderEntity()
                .setExecutionAddress("paper-book")
                .setEntryQuantity(BigDecimal.ONE)
                .setTotalPnlUsdc(new BigDecimal(pnl))
                .setStatus(status)
                .setMarketInfo(new SmartWalletMarketInfoEntity().setMarketEndAt(marketEndAt));
    }
}
