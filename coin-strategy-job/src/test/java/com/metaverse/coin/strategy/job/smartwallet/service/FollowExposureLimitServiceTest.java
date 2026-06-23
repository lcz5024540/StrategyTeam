package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowExposureLimitServiceTest {

    @Mock
    private SmartWalletTradeOrderMapper tradeOrderMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;

    private FollowExposureLimitService newService(SmartWalletProperties properties) {
        return new FollowExposureLimitService(tradeOrderMapper, positionMapper, marketInfoService, properties);
    }

    /**
     * 回归守护：原 Layer 3「总 TVL 利用率上限」已移除。
     * 无论历史未平仓暴露多大，都不应再因总暴露而拒单——防止暴露上限死锁复现。
     */
    @Test
    void totalExposureNoLongerBlocksAfterLayer3Removed() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getExposureLimit().setMaxPerMarketUsdc(new BigDecimal("200"));
        properties.getExposureLimit().setMaxPerFollowWalletUsdc(new BigDecimal("300"));

        when(tradeOrderMapper.selectList(any()))
                .thenReturn(List.of())                    // Layer 1：单市场暴露 = 0
                .thenReturn(List.of())                    // Layer 2：单地址暴露 = 0
                .thenReturn(List.of(order("100000.00"))); // 总暴露极大（仅观测，不拦截）
        when(positionMapper.selectList(any())).thenReturn(List.of());

        FollowExposureLimitService.ExposureCheckResult result = newService(properties).check(
                "condition-1", "YES", "0xleader", new BigDecimal("20.00"));

        assertTrue(result.isAllowed());
        assertFalse(result.isClipped());
        assertEquals("OK", result.getReason());
        assertEquals(0, new BigDecimal("100000.00").compareTo(result.getTotalExposure()));
    }

    /** Layer 1：单市场暴露达上限仍应拒单。 */
    @Test
    void perMarketLimitStillBlocks() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getExposureLimit().setMaxPerMarketUsdc(new BigDecimal("200"));

        when(tradeOrderMapper.selectList(any()))
                .thenReturn(List.of(order("250.00"))); // Layer 1：单市场暴露 250 ≥ 200

        FollowExposureLimitService.ExposureCheckResult result = newService(properties).check(
                "condition-1", "YES", "0xleader", new BigDecimal("20.00"));

        assertFalse(result.isAllowed());
        assertEquals("MARKET_EXPOSURE_LIMIT", result.getReason());
    }

    /** Layer 2：单跟单地址暴露达上限仍应拒单。 */
    @Test
    void perFollowWalletLimitStillBlocks() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getExposureLimit().setMaxPerMarketUsdc(new BigDecimal("200"));
        properties.getExposureLimit().setMaxPerFollowWalletUsdc(new BigDecimal("300"));

        when(tradeOrderMapper.selectList(any()))
                .thenReturn(List.of())                 // Layer 1：单市场暴露 = 0
                .thenReturn(List.of(order("350.00"))); // Layer 2：单地址暴露 350 ≥ 300

        FollowExposureLimitService.ExposureCheckResult result = newService(properties).check(
                "condition-1", "YES", "0xleader", new BigDecimal("20.00"));

        assertFalse(result.isAllowed());
        assertEquals("WALLET_EXPOSURE_LIMIT", result.getReason());
    }

    private SmartWalletTradeOrderEntity order(String remainingCostBasisUsdc) {
        return new SmartWalletTradeOrderEntity()
                .setRemainingCostBasisUsdc(new BigDecimal(remainingCostBasisUsdc));
    }
}
