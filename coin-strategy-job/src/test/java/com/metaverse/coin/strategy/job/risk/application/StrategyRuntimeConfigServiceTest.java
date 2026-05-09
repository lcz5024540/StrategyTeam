package com.metaverse.coin.strategy.job.risk.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRuntimeConfigMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRuntimeConfigEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateStrategyRuntimeConfigRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyRuntimeConfigServiceTest {

    @Test
    void shouldApplyPersistedDbOverrideOnInit() {
        SmartWalletProperties properties = new SmartWalletProperties();
        StrategyRuntimeConfigMapper mapper = mock(StrategyRuntimeConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(entity(
                "smart-wallet.execution-wallet.max-order-usdc",
                "42",
                "DECIMAL",
                "execution"
        )));

        StrategyRuntimeConfigService service = new StrategyRuntimeConfigService(properties, mapper, new ObjectMapper());
        service.init();

        assertEquals(new BigDecimal("42"), properties.getExecutionWallet().getMaxOrderUsdc());
    }

    @Test
    void shouldRejectUnknownConfigKey() {
        SmartWalletProperties properties = new SmartWalletProperties();
        StrategyRuntimeConfigMapper mapper = mock(StrategyRuntimeConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        StrategyRuntimeConfigService service = new StrategyRuntimeConfigService(properties, mapper, new ObjectMapper());
        service.init();

        UpdateStrategyRuntimeConfigRequest request = new UpdateStrategyRuntimeConfigRequest();
        UpdateStrategyRuntimeConfigRequest.Item item = new UpdateStrategyRuntimeConfigRequest.Item();
        item.setKey("smart-wallet.execution-wallet.private-key");
        item.setValue("secret");
        request.setItems(List.of(item));

        assertThrows(IllegalArgumentException.class, () -> service.update(StrategyRuntimeConfigService.POLYMARKET_COPY, request));
    }

    @Test
    void shouldPersistAndApplyRuntimeConfigUpdate() {
        SmartWalletProperties properties = new SmartWalletProperties();
        StrategyRuntimeConfigMapper mapper = mock(StrategyRuntimeConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        StrategyRuntimeConfigService service = new StrategyRuntimeConfigService(properties, mapper, new ObjectMapper());
        service.init();

        UpdateStrategyRuntimeConfigRequest request = new UpdateStrategyRuntimeConfigRequest();
        request.setUpdatedBy("test");
        UpdateStrategyRuntimeConfigRequest.Item item = new UpdateStrategyRuntimeConfigRequest.Item();
        item.setKey("smart-wallet.signal-risk.min-trigger-size-usdc");
        item.setValue("12.50");
        request.setItems(List.of(item));

        service.update(StrategyRuntimeConfigService.POLYMARKET_COPY, request);

        assertEquals(new BigDecimal("12.5"), properties.getSignalRisk().getMinTriggerSizeUsdc());
        ArgumentCaptor<StrategyRuntimeConfigEntity> captor = ArgumentCaptor.forClass(StrategyRuntimeConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("POLYMARKET_COPY", captor.getValue().getStrategyCode());
        assertEquals("smart-wallet.signal-risk.min-trigger-size-usdc", captor.getValue().getConfigKey());
        assertEquals("12.5", captor.getValue().getConfigValue());
    }

    @Test
    void shouldApplyWalletProfileAndIntelligenceRuntimeConfigUpdate() {
        SmartWalletProperties properties = new SmartWalletProperties();
        StrategyRuntimeConfigMapper mapper = mock(StrategyRuntimeConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        StrategyRuntimeConfigService service = new StrategyRuntimeConfigService(properties, mapper, new ObjectMapper());
        service.init();

        UpdateStrategyRuntimeConfigRequest request = new UpdateStrategyRuntimeConfigRequest();
        UpdateStrategyRuntimeConfigRequest.Item minWinRate = new UpdateStrategyRuntimeConfigRequest.Item();
        minWinRate.setKey("smart-wallet.min-win-rate");
        minWinRate.setValue("0.66");
        UpdateStrategyRuntimeConfigRequest.Item behaviorSimilarity = new UpdateStrategyRuntimeConfigRequest.Item();
        behaviorSimilarity.setKey("smart-wallet.intelligence.behavior-similarity-threshold");
        behaviorSimilarity.setValue("0.8");
        request.setItems(List.of(minWinRate, behaviorSimilarity));

        service.update(StrategyRuntimeConfigService.POLYMARKET_COPY, request);

        assertEquals(new BigDecimal("0.66"), properties.getMinWinRate());
        assertEquals(0.8d, properties.getIntelligence().getBehaviorSimilarityThreshold());
    }

    private StrategyRuntimeConfigEntity entity(String key, String value, String type, String group) {
        return new StrategyRuntimeConfigEntity()
                .setId(1L)
                .setStrategyCode("POLYMARKET_COPY")
                .setConfigKey(key)
                .setConfigValue(value)
                .setValueType(type)
                .setGroupName(group)
                .setEditable(true);
    }
}
