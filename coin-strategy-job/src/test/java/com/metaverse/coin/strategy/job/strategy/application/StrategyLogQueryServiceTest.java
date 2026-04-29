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
    void appSourceIncludesStartupLogWhenDeploymentWritesConsoleOutputThere() throws IOException {
        write("job-startup.log", """
                2026-04-29 10:49:58.357 INFO  [http-nio-127.0.0.1-8081-exec-3] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSignalRiskService - [POLY_FOLLOW] action=完成跟单风控评估 result=拒绝 followTaskId=20050 marketId=m1
                """);

        List<StrategyLogEntryView> rows = service().query("app", "ALL", "POLYMARKET_COPY", null, 20);

        assertEquals(1, rows.size());
        assertEquals("POLYMARKET_COPY", rows.get(0).strategy());
        assertTrue(rows.get(0).message().contains("[POLY_FOLLOW]"));
    }

    @Test
    void flowReaderFallsBackToLegacyPolymarketFollowLogs() throws IOException {
        write("job-startup.log", """
                2026-04-29 10:49:58.357 INFO  [http-nio-127.0.0.1-8081-exec-3] com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSignalRiskService - [POLY_FOLLOW] action=完成跟单风控评估 result=拒绝 followTaskId=20050 wallet=0xabc marketId=m1 outcome=YES firstRejectGate=G1_WATCHLIST_UNQUALIFIED
                """);

        FileStrategyFlowLogReader reader = new FileStrategyFlowLogReader(service());
        List<StrategyLogFlowView> flows = reader.queryFlows("app", "POLYMARKET_COPY", null, null, null, null, null, 20);

        assertEquals(1, flows.size());
        assertEquals("task:20050", flows.get(0).flowId());
        assertEquals("S5_RISK_EVALUATED", flows.get(0).flowStage());
        assertEquals("BLOCKED", flows.get(0).status());
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
    void parsesServerPipeLogFormat() throws IOException {
        write("binance-java-connector.log", """
                2026-04-29 01:15:35.684[1777396535684] | ERROR | main       | o.s.boot.SpringApplication           - Application run failed
                2026-04-29 01:15:36.001[1777396536001] | WARN  | worker     | com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService - [POLY_FLOW] flowStage=S4_MARKET_CHECK action="检查Polymarket市场状态" result="拒绝" wallet=0xabc marketId=m1 outcome=YES firstRejectGate=MARKET_RESOLVED blockerCategories=MARKET reasonSummary="market resolved"
                """);

        List<StrategyLogEntryView> rows = service().query("app", "ALL", "POLYMARKET_COPY", "MARKET_RESOLVED", 20);

        assertEquals(1, rows.size());
        assertEquals("WARN", rows.get(0).level());
        assertEquals("worker", rows.get(0).thread());
        assertEquals("S4_MARKET_CHECK", rows.get(0).flowStage());
        assertEquals("MARKET_RESOLVED", rows.get(0).firstRejectGate());
    }

    @Test
    void flowReaderSearchesFlowLinesBeforeApplyingLimit() throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("""
                2026-04-29 09:09:46.348[1777424986348] | INFO  | scheduling-1 | c.m.c.s.j.s.s.SmartWalletFollowTaskService - [POLY_FLOW] flowStage=S1_EVENT_CAPTURED action=记录Polymarket钱包交易事件 result=已入库 eventId=49307 followTaskId=- wallet=0xabc marketId=m1 outcome=UP reasonSummary=交易事件已入库
                2026-04-29 09:09:46.422[1777424986422] | INFO  | scheduling-1 | c.m.c.s.j.s.s.SmartWalletFollowTaskService - [POLY_FLOW] flowStage=S4_MARKET_CHECK action=市场状态检查 result=拒绝 eventId=49307 followTaskId=- wallet=0xabc marketId=m1 outcome=UP firstRejectGate=G6_MARKET_NOT_ACTIVE blockerCategories=MARKET_STATE reasonSummary="[MARKET_RESOLVED] Market is already resolved."
                """);
        for (int i = 0; i < 600; i++) {
            content.append("2026-04-29 09:38:").append(String.format("%02d", i % 60))
                    .append(".000[1777426700000] | INFO  | scheduling-1 | c.m.c.s.j.s.s.SmartWalletFollowTaskService - [POLY_FOLLOW] action=扫描待执行跟单任务 result=查到批次 pendingCount=0 limit=20\n");
        }
        write("binance-java-connector.log", content.toString());

        FileStrategyFlowLogReader reader = new FileStrategyFlowLogReader(service());
        List<StrategyLogFlowView> flows = reader.queryFlows("app", "POLYMARKET_COPY", null, null, null, null, null, 20);

        assertEquals(1, flows.size());
        assertEquals("event:49307", flows.get(0).flowId());
        assertEquals("G6_MARKET_NOT_ACTIVE", flows.get(0).firstRejectGate());
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
