package com.metaverse.coin.strategy.core.arbitrage.multi;
import org.junit.jupiter.api.Test; import java.math.BigDecimal; import java.time.*; import static org.junit.jupiter.api.Assertions.*;
class CrossExchangeOpportunityStabilityGateTest {
 @Test void approvesOnlyAfterWindow(){Instant now=Instant.now();var gate=new CrossExchangeOpportunityStabilityGate(Duration.ofMinutes(3));var o=opportunity(now);assertFalse(gate.stable(o,now));assertFalse(gate.stable(o,now.plusSeconds(179)));assertTrue(gate.stable(o,now.plusSeconds(180)));}
 private CrossExchangeOpportunity opportunity(Instant n){var l=new NormalizedFundingRateSnapshot(Exchange.BINANCE,"BTCUSDT",new BigDecimal("-0.001"),8,n.plusSeconds(3600),BigDecimal.TEN,BigDecimal.TEN,n);var s=new NormalizedFundingRateSnapshot(Exchange.OKX,"BTCUSDT",new BigDecimal("-0.0001"),8,n.plusSeconds(3600),BigDecimal.TEN,BigDecimal.TEN,n);return new CrossExchangeOpportunity("x","BTCUSDT",l,s,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.TEN,CrossExchangeOpportunity.Status.QUALIFIED,null,n);}
}
