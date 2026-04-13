package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRunMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRetryEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryCandidate;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletSettlementSyncServiceTest {

    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private SmartWalletSettlementRunMapper settlementRunMapper;
    @Mock
    private SmartWalletSettlementRetryMapper settlementRetryMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private SmartWalletSettlementSyncService service;

    @BeforeEach
    void setUp() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setEnabled(true);
        properties.getSettlementSync().setEnabled(true);
        service = new SmartWalletSettlementSyncService(
                properties,
                accountingService,
                settlementRunMapper,
                settlementRetryMapper,
                new ObjectMapper(),
                jdbcTemplate
        );
    }

    @Test
    void shouldKeepOraclePendingRetriesPendingAfterRun() {
        SmartWalletSettlementRetryEntity existingRetry = new SmartWalletSettlementRetryEntity()
                .setId(1L)
                .setMarketId("market-1")
                .setQuestion("Will retry stay pending?")
                .setFailureCount(0)
                .setStatus("PENDING")
                .setNextRetryAt(new Date(System.currentTimeMillis() - 1_000L));
        SettlementSyncSummary summary = SettlementSyncSummary.builder()
                .scannedPositions(1)
                .distinctMarkets(1)
                .fetchedMarkets(1)
                .resolvedPositions(0)
                .unresolvedMarkets(1)
                .oraclePendingMarkets(1)
                .skippedFutureMarkets(0)
                .failedMarkets(0)
                .retryCandidates(List.of(SettlementRetryCandidate.builder()
                        .marketId("market-1")
                        .question("Will retry stay pending?")
                        .retryType("ORACLE_PENDING")
                        .retryMessage("Still waiting for oracle resolution.")
                        .build()))
                .processedMarketIds(List.of("market-1"))
                .build();

        when(accountingService.syncSettlements(anyInt(), anyInt(), anyBoolean())).thenReturn(summary);
        when(settlementRetryMapper.selectOne(any())).thenReturn(existingRetry);
        when(settlementRetryMapper.selectList(any())).thenReturn(List.of(existingRetry));

        service.runNow(10, 10, false);

        ArgumentCaptor<SmartWalletSettlementRetryEntity> retryCaptor = ArgumentCaptor.forClass(SmartWalletSettlementRetryEntity.class);
        verify(settlementRetryMapper).updateById(retryCaptor.capture());
        assertEquals("PENDING", retryCaptor.getValue().getStatus());
        assertEquals(0, retryCaptor.getValue().getFailureCount());
        assertEquals("ORACLE_PENDING", retryCaptor.getValue().getRetryType());
        verify(settlementRetryMapper, never()).insert(any());
    }
}
