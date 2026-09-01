package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxGridWindowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class HtxGridWindowStoreIntegrationTest {

    @Autowired HtxGridWindowMapper mapper;

    @Test void persistedWindowIsRecoveredByFreshStoreInstance() {
        String symbol = "t" + Long.toString(System.nanoTime(), 36);
        MovingGridEngine engine = new MovingGridEngine();
        HtxGridWindowStore firstProcess = new HtxGridWindowStore(mapper);
        HtxGridWindowEntity created = firstProcess.loadOrCreate(symbol, new BigDecimal("100"),
                new BigDecimal("0.01"), 5, engine, LocalDateTime.now());
        engine.observe(created, new BigDecimal("90"), BigDecimal.ZERO,
                1, 0, 20, LocalDateTime.now().plusSeconds(1));
        firstProcess.save(created);

        HtxGridWindowEntity recovered = new HtxGridWindowStore(mapper).find(symbol);

        assertNotNull(recovered);
        assertEquals(created.getEpoch(), recovered.getEpoch());
        assertEquals(0, created.getAnchorPrice().compareTo(recovered.getAnchorPrice()));
        assertEquals(MovingGridEngine.MIGRATING_DOWN, recovered.getState());
    }
}
