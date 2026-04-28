package com.metaverse.coin.strategy.job.strategy.application;

import com.metaverse.coin.strategy.job.strategy.model.StrategyLogEntryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyLogFlowView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyLogQueryServiceTest {

    @TempDir
    private Path logDirectory;

    @Test
    void parsesLogbackLinesNewestFirst() throws IOException {
        write("binance-java-connector.log", """
                2026-04-28 10:00:00.001 INFO  [main] com.metaverse.coin.strategy.job.CoinStrategyJobApplication - started
                2026-04-28 10:00:01.002 WARN  [worker] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService - Polymarket delayed
                """);

        List<StrategyLogEntryView> rows = service().query("app", "ALL", "ALL", null, 20);

        assertEquals(2, rows.size());
        assertEquals("WARN", rows.get(0).level());
        assertEquals("POLYMARKET_COPY", rows.get(0).strategy());
        assertEquals("INFO", rows.get(1).level());
    }

    @Test
    void filtersByLevelStrategyAndSearch() throws IOException {
        write("binance-java-connector.log", """
                2026-04-28 10:00:00.001 INFO  [main] com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageRebalanceService - Funding rate check ok
                2026-04-28 10:00:01.002 WARN  [worker] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService - Polymarket delayed
                2026-04-28 10:00:02.003 ERROR [worker] com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService - CLOB order failed
                """);

        List<StrategyLogEntryView> rows = service().query("app", "ERROR", "POLYMARKET_COPY", "clob", 20);

        assertEquals(1, rows.size());
        assertEquals("ERROR", rows.get(0).level());
        assertTrue(rows.get(0).message().contains("CLOB order failed"));
    }

    @Test
    void mergesStackTraceLinesIntoPreviousEntry() throws IOException {
        write("binance-java-connector.log", """
                2026-04-28 10:00:02.003 ERROR [worker] com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService - CLOB order failed
                java.lang.IllegalStateException: boom
                \tat com.metaverse.coin.strategy.Job.run(Job.java:10)
                """);

        List<StrategyLogEntryView> rows = service().query("app", "ALL", "ALL", null, 20);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).message().contains("IllegalStateException"));
        assertTrue(rows.get(0).message().contains("Job.java:10"));
    }

    @Test
    void returnsEmptyRowsWhenLogFileDoesNotExist() {
        assertTrue(service().query("error", "ALL", "ALL", null, 20).isEmpty());
    }

    @Test
    void rejectsUnsupportedSources() {
        assertThrows(IllegalArgumentException.class, () ->
                service().query("../binance-java-connector.log", "ALL", "ALL", null, 20));
    }

    @Test
    void redactsSensitiveValues() throws IOException {
        write("binance-java-connector.log", """
                2026-04-28 10:00:00.001 INFO  [main] com.metaverse.coin.strategy.job.config.SecretDecryptor - apiKey=abc123 privateKey=ENC(secret) Authorization Bearer abc.def token:xyz
                """);

        String message = service().query("app", "ALL", "ALL", null, 20).get(0).message();

        assertTrue(message.contains("apiKey=***"));
        assertTrue(message.contains("privateKey=***"));
        assertTrue(message.contains("Bearer ***"));
        assertTrue(message.contains("token=***"));
    }

    @Test
    void parsesPolymarketFlowFieldsFromRawLogs() throws IOException {
        write("binance-java-connector.log", """
                2026-04-28 10:00:00.001 INFO  [worker] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService - [POLY_FLOW] flowStage=S5_RISK_EVALUATED action="评估跟单风控" result="拒绝" eventId=101 followTaskId=202 wallet=0xabc marketId=m1 outcome=YES mode=PAPER stakeUsdc=1.00 entryLane=SINGLE_WALLET_PAPER_CANARY matchedStrategies=SINGLE_WALLET_PAPER_CANARY gateHighWaterMark=11 firstRejectGate=G12_ENTRY_STRATEGY_REJECTED blockerCategories=CONSENSUS_SCORE reasonSummary="Consensus score below threshold"
                """);

        StrategyLogEntryView row = service().query("app", "ALL", "POLYMARKET_COPY", "G12_ENTRY", 20).get(0);

        assertEquals("S5_RISK_EVALUATED", row.flowStage());
        assertEquals("101", row.eventId());
        assertEquals("202", row.followTaskId());
        assertEquals("0xabc", row.wallet());
        assertEquals("G12_ENTRY_STRATEGY_REJECTED", row.firstRejectGate());
        assertEquals("CONSENSUS_SCORE", row.blockerCategories());
    }

    @Test
    void groupsPolymarketFlowTimelineByEventId() throws IOException {
        write("binance-java-connector.log", """
                2026-04-28 10:00:00.001 INFO  [worker] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService - [POLY_FLOW] flowStage=S1_EVENT_CAPTURED action="记录Polymarket钱包交易事件" result=已入库 eventId=101 wallet=0xabc marketId=m1 outcome=YES reasonSummary="captured"
                2026-04-28 10:00:01.001 INFO  [worker] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService - [POLY_FLOW] flowStage=S6_TASK_CREATED action="创建跟单任务" result=PENDING eventId=101 followTaskId=202 wallet=0xabc marketId=m1 outcome=YES mode=PAPER stakeUsdc=1.00 reasonSummary="created"
                """);

        FileStrategyFlowLogReader reader = new FileStrategyFlowLogReader(service());
        List<StrategyLogFlowView> flows = reader.queryFlows("app", "POLYMARKET_COPY", null, null, null, null, null, 20);

        assertEquals(1, flows.size());
        assertEquals("event:101", flows.get(0).flowId());
        assertEquals("PENDING", flows.get(0).status());
        assertEquals(2, flows.get(0).entries().size());
        assertEquals("202", flows.get(0).followTaskId());
    }

    private StrategyLogQueryService service() {
        return new StrategyLogQueryService(logDirectory, 1_000, 128 * 1024);
    }

    private void write(String fileName, String content) throws IOException {
        Files.writeString(logDirectory.resolve(fileName), content);
    }
}
