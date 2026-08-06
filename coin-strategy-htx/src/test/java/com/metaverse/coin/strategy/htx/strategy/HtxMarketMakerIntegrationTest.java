package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxExposureEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxLockedPairMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 做市与现有对账器共存的集成回归。无 AK/SK → HtxConfig 条件不满足，交易/调度 bean 不装配，仅验证持久层与豁免。
 * 见 spec 2026-08-06-htx-market-maker-design.md §7（对账器豁免）/§8（急停：调度器 bean 不加载）。
 */
@SpringBootTest
class HtxMarketMakerIntegrationTest {

    @Autowired HtxLockedPairMapper pairMapper;
    @Autowired HtxTradeRecorder recorder;
    @Autowired ApplicationContext ctx;

    private HtxLockedPairEntity mmRestingSell() {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setClientOrderId("mm-it-" + System.nanoTime());
        e.setSymbol("btcusdt");
        e.setReqQuantity(new BigDecimal("0.0003"));
        e.setBuyPrice(new BigDecimal("59700.00"));
        e.setSellPrice(new BigDecimal("60300.00"));
        e.setStatus(HtxStatus.MM_RESTING_SELL);
        e.setStrategy(HtxStatus.STRATEGY_MARKET_MAKER);
        e.setDryRun(false);
        return e;
    }

    @Test
    void mm_resting_sell_is_exempt_from_reconciler_and_visible_as_open_cell() {
        HtxLockedPairEntity cell = mmRestingSell();
        assertEquals(1, pairMapper.insert(cell));

        // 对账器只扫 htx_exposure 里的 OPEN 裸露；做市 MM_RESTING_SELL 在 htx_locked_pair，不进对账器视野。
        List<HtxExposureEntity> open = recorder.findOpenExposures();
        assertTrue(open.stream().noneMatch(x -> cell.getId().equals(x.getPairId())),
                "MM_RESTING_SELL must NOT surface as an OPEN exposure to the reconciler");

        // 但它对做市自身的恢复/编排可见。
        List<HtxLockedPairEntity> cells = recorder.findAllOpenMmCells();
        assertTrue(cells.stream().anyMatch(c -> cell.getId().equals(c.getId())),
                "MM_RESTING_SELL must be visible to the market-maker as an open cell");
    }

    @Test
    void scheduler_bean_not_loaded_when_disabled_and_no_credentials() {
        // 默认 enabled=false 且无凭证 → 调度器 bean 不装配，上下文正常加载。
        assertEquals(0, ctx.getBeanNamesForType(HtxMarketMakerScheduler.class).length,
                "HtxMarketMakerScheduler must not load when disabled / no credentials");
    }
}
