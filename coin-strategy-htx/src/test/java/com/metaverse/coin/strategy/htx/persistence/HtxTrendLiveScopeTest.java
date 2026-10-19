package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class HtxTrendLiveScopeTest {
    @Test
    void paperCannotChangeLivePerformanceOrCooldown() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:live_scope;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE htx_trend_position (account_label VARCHAR, contract_code VARCHAR, status VARCHAR, "
                    + "side VARCHAR, primary_model VARCHAR, market_regime VARCHAR, final_net_profit DECIMAL, "
                    + "realized_profit DECIMAL, entry_fee DECIMAL, close_fee DECIMAL, updated_at TIMESTAMP, source_position_id BIGINT)");
            s.execute("INSERT INTO htx_trend_position VALUES ('primary','BTC-USDT','DONE','LONG','MODEL','UP',8,10,1,1,'2026-10-01',NULL)");
        }
        Configuration config = new Configuration(new Environment("fixture", new JdbcTransactionFactory(), ds));
        config.addMapper(HtxTrendPositionMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper = session.getMapper(HtxTrendPositionMapper.class);
            var since = LocalDateTime.of(2026, 9, 1, 0, 0);
            var before = mapper.trendPerformanceSince(since, 100);
            var cooldown = mapper.latestDoneAt("BTC-USDT");
            session.getConnection().createStatement().execute("INSERT INTO htx_trend_position VALUES "
                    + "('paper','BTC-USDT','DONE','LONG','MODEL','UP',999,999,0,0,'2026-10-19',NULL),"
                    + "('paper','BTC-USDT','ACTIVE','LONG','MODEL','UP',999,999,0,0,'2026-10-19',NULL)");
            session.clearCache();
            assertEquals(before.getNetProfit(), mapper.trendPerformanceSince(since, 100).getNetProfit());
            assertEquals(before.getClosedCount(), mapper.trendPerformanceForBucket(since, 100, "LONG", "MODEL", "UP", null).getClosedCount());
            assertEquals(999, mapper.trendPerformanceForBucket(since, 100, "LONG", "MODEL", "UP", "paper").getNetProfit().intValue());
            assertEquals(cooldown, mapper.latestDoneAt("BTC-USDT"));
            assertEquals(0L, mapper.countOpenLike());
        }
    }
}
