package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletMarketRepairServiceTest {

    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletFillMapper fillMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private SmartWalletSettlementRetryMapper settlementRetryMapper;
    @Mock
    private SmartWalletClaimRetryMapper claimRetryMapper;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;

    @Test
    void shouldRepairLegacyMarketIdsAcrossPositionChain() {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(57L)
                .setMarketInfoId(1L);
        SmartWalletMarketInfoEntity oldMarketInfo = new SmartWalletMarketInfoEntity()
                .setId(1L)
                .setMarketId("market-legacy")
                .setQuestion("76ers vs. Celtics: O/U 217.5")
                .setOutcome("OVER");
        SmartWalletMarketInfoEntity canonicalMarketInfo = new SmartWalletMarketInfoEntity()
                .setId(2L)
                .setMarketId("0xcondition")
                .setQuestion("76ers vs. Celtics: O/U 217.5")
                .setOutcome("OVER");
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(70L)
                .setPositionId(57L)
                .setFollowTaskId(6676L)
                .setMarketInfoId(1L);
        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setId(70L)
                .setPositionId(57L)
                .setFollowTaskId(6676L)
                .setMarketInfoId(1L);
        SmartWalletFollowTaskEntity followTask = new SmartWalletFollowTaskEntity()
                .setId(6676L)
                .setSourceEventId(70L)
                .setMarketInfoId(1L);
        SmartWalletTradeEventEntity tradeEvent = new SmartWalletTradeEventEntity()
                .setId(70L)
                .setMarketId("market-legacy");
        SmartWalletTradeEffectLabelEntity effectLabel = new SmartWalletTradeEffectLabelEntity()
                .setId(11L)
                .setSourceEventId(70L)
                .setMarketId("market-legacy");
        SmartWalletClaimRetryEntity claimRetry = new SmartWalletClaimRetryEntity()
                .setId(9L)
                .setPositionId(57L)
                .setMarketId("market-legacy");
        SmartWalletSettlementRetryEntity settlementRetry = new SmartWalletSettlementRetryEntity()
                .setId(8L)
                .setMarketId("market-legacy")
                .setQuestion("76ers vs. Celtics: O/U 217.5");

        when(positionMapper.selectBatchIds(List.of(57L))).thenReturn(List.of(position));
        when(marketInfoService.getById(1L)).thenReturn(oldMarketInfo);
        when(marketMetadataService.resolveCanonicalConditionId(
                "market-legacy",
                "76ers vs. Celtics: O/U 217.5",
                List.of("OVER")
        )).thenReturn("0xcondition");
        when(marketInfoService.getOrCreate(
                "0xcondition",
                "OVER",
                "76ers vs. Celtics: O/U 217.5",
                null,
                null,
                null
        )).thenReturn(canonicalMarketInfo);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));
        when(fillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fill));
        when(followTaskMapper.selectBatchIds(List.of(6676L))).thenReturn(List.of(followTask));
        when(tradeEventMapper.selectBatchIds(List.of(70L))).thenReturn(List.of(tradeEvent));
        when(tradeEffectLabelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(effectLabel));
        when(claimRetryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(claimRetry));
        when(settlementRetryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(settlementRetry));

        SmartWalletMarketRepairService service = new SmartWalletMarketRepairService(
                positionMapper,
                orderMapper,
                fillMapper,
                followTaskMapper,
                tradeEventMapper,
                tradeEffectLabelMapper,
                settlementRetryMapper,
                claimRetryMapper,
                marketInfoService,
                marketMetadataService
        );

        SmartWalletMarketIdRepairRequest request = new SmartWalletMarketIdRepairRequest();
        request.setPositionIds(List.of(57L));
        SmartWalletMarketIdRepairSummary summary = service.repairPositionMarketIds(request);

        assertEquals(1, summary.getRepairedPositions());
        assertEquals(List.of(57L), summary.getRepairedPositionIds());
        assertEquals(List.of("0xcondition"), summary.getRepairedMarketIds());
        assertEquals(Long.valueOf(2L), position.getMarketInfoId());
        assertEquals(Long.valueOf(2L), order.getMarketInfoId());
        assertEquals(Long.valueOf(2L), fill.getMarketInfoId());
        assertEquals(Long.valueOf(2L), followTask.getMarketInfoId());
        assertEquals("0xcondition", tradeEvent.getMarketId());
        assertEquals("0xcondition", effectLabel.getMarketId());
        assertEquals("0xcondition", claimRetry.getMarketId());
        assertEquals("0xcondition", settlementRetry.getMarketId());
        verify(marketInfoService).clearCache();
    }
}
