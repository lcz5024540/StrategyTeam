package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRunMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRunEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletClaimServiceTest {

    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletClaimRunMapper claimRunMapper;
    @Mock
    private SmartWalletClaimRetryMapper claimRetryMapper;
    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private SmartWalletOnChainClaimGateway onChainClaimGateway;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private SmartWalletClaimService service;

    @BeforeEach
    void setUp() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setEnabled(true);
        properties.getClaim().setEnabled(true);
        service = new SmartWalletClaimService(
                properties,
                positionMapper,
                claimRunMapper,
                claimRetryMapper,
                accountingService,
                onChainClaimGateway,
                marketInfoService,
                new ObjectMapper(),
                jdbcTemplate
        );
    }

    @Test
    void shouldSkipPersistingScheduledRunWhenNoClaimablePositionsExist() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.scheduledProcessClaims();

        verify(claimRunMapper, never()).insert(any());
        verify(claimRunMapper, never()).updateById(any());
    }

    @Test
    void shouldPersistScheduledRunWhenClaimablePositionsExist() {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(101L)
                .setStatus("RESOLVED_UNCLAIMED")
                .setExecutionAddress("paper-book");
        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(claimRetryMapper.selectList(any())).thenReturn(List.of());

        service.scheduledProcessClaims();

        ArgumentCaptor<SmartWalletClaimRunEntity> runCaptor = ArgumentCaptor.forClass(SmartWalletClaimRunEntity.class);
        verify(claimRunMapper).insert(runCaptor.capture());
        verify(claimRunMapper).updateById(any(SmartWalletClaimRunEntity.class));
        assertEquals("SCHEDULED", runCaptor.getValue().getTriggerType());
        assertEquals(1, runCaptor.getValue().getPositionLimit());
    }
}
