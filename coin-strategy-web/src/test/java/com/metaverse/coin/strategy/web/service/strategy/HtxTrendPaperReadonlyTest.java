package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.web.controller.strategy.HtxTrendDashboardController;
import com.metaverse.coin.strategy.web.mapper.HtxTrendDashboardMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HtxTrendPaperReadonlyTest {
    @Test
    void omittedHttpModeRoutesToLiveAndInvalidModeFailsBeforeQuery() throws Exception {
        var service = org.mockito.Mockito.mock(HtxTrendDashboardQueryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HtxTrendDashboardController(service)).build();
        mvc.perform(get("/strategy-platform/arbitrage-dashboard/htx-trend")).andExpect(status().isOk());
        org.mockito.Mockito.verify(service).dashboard("LIVE");
        var mapper = org.mockito.Mockito.mock(HtxTrendDashboardMapper.class);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> new HtxTrendDashboardQueryService(mapper).dashboard("ALL"));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void paperLedgerFailureIsUnavailableRatherThanHealthyZero() {
        var mapper = org.mockito.Mockito.mock(HtxTrendDashboardMapper.class);
        org.mockito.Mockito.when(mapper.ledgerAccountSummaries("PAPER"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable"));
        assertEquals("HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE",
                new HtxTrendDashboardQueryService(mapper).dashboard("PAPER").source());
    }

    @Test
    void mixedFixtureIsIsolatedThroughReadOnlyApi() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:paper;MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1");
        try (var connection = ds.getConnection()) {
            RunScript.execute(connection, new InputStreamReader(getClass().getResourceAsStream(
                    "/paper-readonly-fixture.sql"), StandardCharsets.UTF_8));
        }
        Configuration config = new Configuration(new Environment("fixture", new JdbcTransactionFactory(), ds));
        config.addMapper(HtxTrendDashboardMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper = session.getMapper(HtxTrendDashboardMapper.class);
            assertEquals(2L, ((Number) mapper.summary(null).get("closedPositions")).longValue());
            assertEquals(new BigDecimal("16.00000000"), mapper.summary("LIVE").get("netRealizedProfit"));
            assertEquals(4, mapper.recentOrders("LIVE").size());
            assertEquals(4, mapper.recentLedgerEvents("LIVE").size());
            assertEquals(3, mapper.recentPositions("PAPER").size());
            assertEquals(6, mapper.recentOrders("PAPER").size());
            assertEquals(6, mapper.recentLedgerEvents("PAPER").size());
            assertEquals(new BigDecimal("6.00000000"), mapper.ledgerSummary("PAPER").get("ledgerTotalFees"));
            assertEquals(new BigDecimal("54.00000000"), mapper.ledgerSummary("PAPER").get("ledgerNetRealizedProfit"));
            // Every financial projection executes the real SQL against mixed rows.
            for (String mode : new String[]{"LIVE", "PAPER"}) {
                mapper.activePositions(mode); mapper.performanceSummary(mode); mapper.accountSummaries(mode);
                mapper.ledgerAccountSummaries(mode); mapper.dailyPnl(mode); mapper.sidePnl(mode);
                mapper.todaySidePnl(mode); mapper.entryModelPnl(mode); mapper.marketRegimePnl(mode);
                mapper.symbolPnl(mode); mapper.exitReasonPnl(mode);
            }
            var service = new HtxTrendDashboardQueryService(mapper);
            var mvc = MockMvcBuilders.standaloneSetup(new HtxTrendDashboardController(service)).build();
            String path = "/strategy-platform/arbitrage-dashboard/htx-trend";
            mvc.perform(get(path).param("mode", "PAPER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.closedPositions").value(3))
                    .andExpect(jsonPath("$.recentOrders.length()").value(6))
                    .andExpect(jsonPath("$.recentLedgerEvents.length()").value(6))
                    .andExpect(jsonPath("$.recentPositions[0].accountLabel").value("paper"))
                    .andExpect(jsonPath("$.recentPositions[0].finalPnlSource").value("PAPER_SIMULATED"))
                    .andExpect(jsonPath("$.recentPositions[0].finalNetProfit").value(18))
                    .andExpect(jsonPath("$.recentOrders[0].clientOrderId").value(org.hamcrest.Matchers.startsWith("paper-")));
            mvc.perform(get(path).param("mode", "ALL")).andExpect(status().isBadRequest());
            mvc.perform(post(path).param("mode", "PAPER")).andExpect(status().isMethodNotAllowed());
            mvc.perform(delete(path).param("mode", "PAPER")).andExpect(status().isMethodNotAllowed());
            // Remove PAPER and prove default LIVE counts/PnL are unchanged.
            Map<String, Object> before = mapper.summary(null);
            var ledgerBefore = mapper.ledgerSummary(null);
            var ordersBefore = mapper.recentOrders(null);
            var accountsBefore = mapper.accountSummaries(null);
            session.getConnection().createStatement().executeUpdate(
                    "DELETE FROM htx_trade_strategy.htx_trend_position WHERE account_label = 'paper'");
            session.getConnection().createStatement().executeUpdate(
                    "DELETE FROM htx_trade_strategy.htx_trend_order WHERE account_label = 'paper'");
            session.getConnection().createStatement().executeUpdate(
                    "DELETE FROM htx_trade_strategy.coin_strategy_ledger_event WHERE account_label = 'paper'");
            session.clearCache();
            assertEquals(before, mapper.summary(null));
            assertEquals(ledgerBefore, mapper.ledgerSummary(null));
            assertEquals(ordersBefore, mapper.recentOrders(null));
            assertEquals(accountsBefore, mapper.accountSummaries(null));
        }
    }
}
